package com.rentalx.playa.data.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rentalx.playa.data.settings.AppSettings
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Relaunches the root strategy after a reboot, and only when asked to.
 *
 * The firewall rule and the process both disappear with a restart, so without
 * this the listener would have to open the settings screen every morning. The
 * work is handed to [goAsync] because a broadcast receiver is not allowed to
 * block, and `su` can take a moment on a freshly booted device.
 */
class ZapretBootReceiver : BroadcastReceiver() {

    private val receiverScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> Unit },
    )

    private companion object {
        const val BOOT_BUDGET_MS = 8_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AppSettings.zapretAutoStart.value) return
        val pending = goAsync()
        // A receiver has roughly ten seconds. Two probe passes, three killalls,
        // an iptables call and a 600 ms settle do not fit in it reliably, so the
        // budget is enforced here and a failure is swallowed rather than allowed
        // to crash the process during boot.
        receiverScope.launch {
            try {
                withTimeoutOrNull(BOOT_BUDGET_MS) {
                    RootZapret.start(
                        context = context,
                        userPath = AppSettings.zapretPath.value,
                        strategy = RootZapret.Strategy.of(AppSettings.zapretStrategy.value),
                        customArgs = AppSettings.zapretArgs.value,
                        hostFile = RootZapret.hostFile(context),
                        logFile = RootZapret.logFile(context),
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
