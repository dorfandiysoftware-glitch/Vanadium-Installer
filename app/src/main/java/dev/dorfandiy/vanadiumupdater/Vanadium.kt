package dev.dorfandiy.vanadiumupdater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Components that can be installed without root. The WebView needs a system overlay, so it is only reported. */
enum class Comp(val id: String, val title: String, val pkg: String, val path: String, val info: String) {
    BROWSER("browser", "Vanadium Browser", "app.vanadium.browser", "prebuilt/arm64/VanadiumBrowser.apk",
        "The hardened Chromium browser from GrapheneOS."),
    CONFIG("config", "Vanadium Config", "app.vanadium.config", "prebuilt/VanadiumConfig.apk",
        "Filter lists and settings used by Vanadium (a helper package, it has no screen)."),
    WEBVIEW("webview", "Vanadium WebView", "app.vanadium.webview", "prebuilt/arm64-multilib/VanadiumWebView.apk",
        "The WebView engine. It installs as a normal app; Android decides whether it can be selected as the system WebView."),
}

class Remote(val commit: String, val date: String, val title: String)

object Bus {
    val tick = MutableStateFlow(0)
    val message = MutableStateFlow<String?>(null)
}

object Vanadium {
    private const val PROJECT = "grapheneos%2Fplatform_external_vanadium"
    private const val UA = "VanadiumUpdater/0.1"
    const val ACTION = "dev.dorfandiy.vanadiumupdater.INSTALL"

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
        }

    /** Latest commit that changed the component's APK, on branch 17 first and then the default branch. */
    fun latest(c: Comp): Remote? {
        val path = URLEncoder.encode(c.path, "UTF-8")
        for (ref in listOf("&ref_name=17", "")) {
            try {
                val con = open("https://gitlab.com/api/v4/projects/$PROJECT/repository/commits?path=$path&per_page=1$ref")
                if (con.responseCode != 200) continue
                val arr = JSONArray(con.inputStream.bufferedReader().readText())
                if (arr.length() == 0) continue
                val o = arr.getJSONObject(0)
                return Remote(o.getString("id"), o.optString("committed_date").take(10), o.optString("title"))
            } catch (_: Exception) { }
        }
        return null
    }

    fun download(ctx: Context, c: Comp, r: Remote, onProgress: (Float) -> Unit): File {
        val out = File(ctx.cacheDir, "${c.id}.apk")
        val con = open("https://gitlab.com/grapheneos/platform_external_vanadium/-/raw/${r.commit}/${c.path}")
        if (con.responseCode != 200) throw IllegalStateException("Download failed: HTTP ${con.responseCode}")
        val total = con.contentLengthLong
        var done = 0L
        con.inputStream.use { i ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n); done += n
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
        return out
    }

    fun install(ctx: Context, c: Comp, r: Remote, file: File) {
        val mode = ctx.installMode
        if (mode.startsWith("pkg:")) { handOff(ctx, c, r, file, mode.removePrefix("pkg:")); return }
        if (mode == "shizuku" && ShizukuHelper.usable) {
            val err = ShizukuHelper.install(file)
            if (err == null) {
                storeCommit(ctx, c, r.commit)
                Bus.message.value = "${c.title} installed (Shizuku)"
            } else Bus.message.value = err
            Bus.tick.value++
            return
        }
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(c.pkg)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            file.inputStream().use { i ->
                s.openWrite("pkg", 0, file.length()).use { o -> i.copyTo(o); s.fsync(o) }
            }
            val intent = Intent(ctx, InstallReceiver::class.java).setAction(ACTION)
                .putExtra("comp", c.id).putExtra("commit", r.commit)
            val pending = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            s.commit(pending.intentSender)
        }
    }

    /** Gives the APK to another installer app (InstallerX, etc.). The result is picked up when the package changes. */
    private fun handOff(ctx: Context, c: Comp, r: Remote, file: File, installer: String) {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .setPackage(installer).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        sp(ctx).edit().putString("pending_${c.id}", r.commit).putLong("pendingAt_${c.id}", System.currentTimeMillis()).apply()
        try { ctx.startActivity(i); Bus.message.value = "Opened in the chosen installer" }
        catch (e: Exception) { Bus.message.value = "Could not open that installer: ${e.message}" }
    }

    /** Records installs finished by an external installer. */
    fun syncPending(ctx: Context) {
        Comp.entries.forEach { c ->
            val commit = sp(ctx).getString("pending_${c.id}", null) ?: return@forEach
            val at = sp(ctx).getLong("pendingAt_${c.id}", 0)
            val t = try { ctx.packageManager.getPackageInfo(c.pkg, 0).lastUpdateTime } catch (_: Exception) { return@forEach }
            if (t >= at) { storeCommit(ctx, c, commit); sp(ctx).edit().remove("pending_${c.id}").apply() }
        }
    }

    /** Installed apps that can open an APK, as package to label pairs. */
    fun installerApps(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://x/a.apk"), "application/vnd.android.package-archive")
        return pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != ctx.packageName }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
    }

    fun installedVersion(ctx: Context, pkg: String): String? = try {
        ctx.packageManager.getPackageInfo(pkg, 0).versionName ?: "?"
    } catch (_: Exception) { null }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("vanadium", Context.MODE_PRIVATE)
    fun storedCommit(ctx: Context, c: Comp): String? = sp(ctx).getString("commit_${c.id}", null)
    fun storeCommit(ctx: Context, c: Comp, commit: String) = sp(ctx).edit().putString("commit_${c.id}", commit).apply()
    var Context.notifyUpdates: Boolean
        get() = sp(this).getBoolean("notify", false)
        set(v) = sp(this).edit().putBoolean("notify", v).apply()
    /** "android" (default), "shizuku", or "pkg:<package>" for another installer app. */
    var Context.installMode: String
        get() = sp(this).getString("installer", "android") ?: "android"
        set(v) = sp(this).edit().putString("installer", v).apply()
    var Context.checkHours: Int
        get() = sp(this).getInt("hours", 24)
        set(v) = sp(this).edit().putInt("hours", v).apply()
    var Context.expressive: Boolean
        get() = sp(this).getBoolean("expressive", true)
        set(v) = sp(this).edit().putBoolean("expressive", v).apply()
}
