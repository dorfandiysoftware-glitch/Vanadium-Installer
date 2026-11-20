package dev.dorfandiy.vanadiumupdater

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.NetworkType
import java.util.concurrent.TimeUnit

/** Once a day: tells the user when a newer Vanadium build is available. */
class CheckWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result {
        val c = applicationContext
        val outdated = Comp.entries.filter { comp ->
            val stored = Vanadium.storedCommit(c, comp)
            Vanadium.installedVersion(c, comp.pkg) != null && stored != null && Vanadium.latest(comp)?.commit.let { it != null && it != stored }
        }
        if (outdated.isNotEmpty()) {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("updates", "Updates", NotificationManager.IMPORTANCE_DEFAULT))
            val n = NotificationCompat.Builder(c, "updates").setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Vanadium update available")
                .setContentText(outdated.joinToString { it.title })
                .setAutoCancel(true).build()
            runCatching { nm.notify(1, n) }
        }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context, on: Boolean, hours: Int = 24) {
            val wm = WorkManager.getInstance(ctx)
            if (!on) { wm.cancelUniqueWork("check"); return }
            wm.enqueueUniquePeriodicWork("check", ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<CheckWorker>(hours.toLong(), TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}
