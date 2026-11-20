package dev.dorfandiy.vanadiumupdater

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku
import java.io.File

enum class ShizukuStatus { NOT_RUNNING, NO_PERMISSION, READY }

object ShizukuHelper {
    private val _status = MutableStateFlow(ShizukuStatus.NOT_RUNNING)
    val status: StateFlow<ShizukuStatus> = _status
    private var shell: IShellService? = null
    private var bound = false

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }
    private val binderListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val deadListener = Shizuku.OnBinderDeadListener { shell = null; bound = false; refresh() }

    private val conn = object : android.content.ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            shell = if (b != null && b.pingBinder()) IShellService.Stub.asInterface(b) else null
        }
        override fun onServiceDisconnected(n: ComponentName?) { shell = null; bound = false }
    }

    fun init() {
        Shizuku.addRequestPermissionResultListener(permListener)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
        refresh()
    }

    fun refresh() {
        _status.value = try {
            when {
                !Shizuku.pingBinder() -> ShizukuStatus.NOT_RUNNING
                Shizuku.isPreV11() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuStatus.NO_PERMISSION
                else -> ShizukuStatus.READY
            }
        } catch (e: Throwable) { ShizukuStatus.NOT_RUNNING }
        if (_status.value == ShizukuStatus.READY) bind()
    }

    fun requestPermission() {
        try { if (Shizuku.pingBinder()) Shizuku.requestPermission(100) } catch (_: Throwable) {}
    }

    private fun bind() {
        if (bound) return
        bound = true
        val args = Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ShellService::class.java.name))
            .daemon(false).processNameSuffix("shell").debuggable(BuildConfig.DEBUG).version(1)
        try { Shizuku.bindUserService(args, conn) } catch (e: Throwable) { bound = false }
    }

    /** True when commands can be run right now. */
    val usable get() = _status.value == ShizukuStatus.READY && shell != null

    fun exec(cmd: String): String? = try { shell?.exec(cmd) } catch (e: Throwable) { "ERR: ${e.message}" }

    /** Installs [file] through `pm install`. Returns null on success or an error text. */
    fun install(file: File): String? {
        val s = shell ?: return "Shizuku service not connected"
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                val r = s.install(pfd, file.length())
                if (r == "OK") null else r
            }
        } catch (e: Throwable) { "ERR: ${e.message}" }
    }
}
