package dev.dorfandiy.vanadiumupdater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
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
        "Filter lists and settings used by Vanadium."),
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

    fun installedVersion(ctx: Context, pkg: String): String? = try {
        ctx.packageManager.getPackageInfo(pkg, 0).versionName ?: "?"
    } catch (_: Exception) { null }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("vanadium", Context.MODE_PRIVATE)
    fun storedCommit(ctx: Context, c: Comp): String? = sp(ctx).getString("commit_${c.id}", null)
    fun storeCommit(ctx: Context, c: Comp, commit: String) = sp(ctx).edit().putString("commit_${c.id}", commit).apply()
    var Context.notifyUpdates: Boolean
        get() = sp(this).getBoolean("notify", false)
        set(v) = sp(this).edit().putBoolean("notify", v).apply()
}
