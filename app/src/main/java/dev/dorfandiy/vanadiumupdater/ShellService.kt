package dev.dorfandiy.vanadiumupdater

import android.os.ParcelFileDescriptor
import kotlin.system.exitProcess

/** Runs inside the Shizuku server process with shell (adb) privileges. */
class ShellService : IShellService.Stub() {
    override fun destroy() { exitProcess(0) }

    override fun exec(command: String): String = try {
        val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        if (code == 0) out.trim() else "ERR($code): ${out.trim()}"
    } catch (e: Exception) { "ERR: ${e.message}" }

    /** Streams the APK into `pm install` so the shell never needs to read the app's private files. */
    override fun install(apk: ParcelFileDescriptor, size: Long): String = try {
        val p = ProcessBuilder("pm", "install", "-r", "-S", size.toString()).redirectErrorStream(true).start()
        ParcelFileDescriptor.AutoCloseInputStream(apk).use { i -> p.outputStream.use { o -> i.copyTo(o) } }
        val out = p.inputStream.bufferedReader().readText().trim()
        val code = p.waitFor()
        if (code == 0 && out.contains("Success")) "OK" else "ERR($code): $out"
    } catch (e: Exception) { "ERR: ${e.message}" }
}
