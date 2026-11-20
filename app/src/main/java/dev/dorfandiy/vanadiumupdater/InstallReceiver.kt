package dev.dorfandiy.vanadiumupdater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else i.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                val comp = Comp.entries.firstOrNull { it.id == i.getStringExtra("comp") }
                val commit = i.getStringExtra("commit")
                if (comp != null && commit != null) Vanadium.storeCommit(ctx, comp, commit)
                Bus.message.value = "${comp?.title ?: "Package"} installed"
                Bus.tick.value++
            }
            else -> {
                Bus.message.value = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Installation failed"
                Bus.tick.value++
            }
        }
    }
}
