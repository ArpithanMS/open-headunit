package com.andrerinas.openheadunit.ride.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import com.andrerinas.openheadunit.ride.RideEnginePreferences
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Restarts [AutoRideMonitorService] after a reboot if the user has opted into passive detection -
 * without this, an always-on service that never survives a reboot would quietly stop being
 * "always-on" the first time the head unit loses power. Deliberately its own receiver rather than
 * folded into [com.andrerinas.openheadunit.app.BootCompleteReceiver]: that one's boot-start logic
 * is specific to AapService, and this module stays extractable on its own (see
 * [com.andrerinas.openheadunit.ride.RideComponent]'s KDoc).
 *
 * Shares the same boot-action set as BootCompleteReceiver for the same reason - many of these
 * head units never send a standard BOOT_COMPLETED and only fire an OEM-specific action instead.
 */
class AutoRideBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return

        val isLocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            !(context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked
        if (isLocked) {
            // RideEnginePreferences needs credential-encrypted storage - same constraint
            // App.initUnlockedOnce() already documents. USER_UNLOCKED is itself in BOOT_ACTIONS,
            // so this receiver gets a second chance once the device is actually unlocked.
            return
        }

        if (!RideEnginePreferences.isAutoDetectionEnabled(context)) return

        AppLog.i("AutoRideBootReceiver: auto-detection enabled, starting AutoRideMonitorService (trigger=${intent.action})")
        AutoRideMonitorService.start(context)
    }

    private companion object {
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "com.mediatek.intent.action.QUICKBOOT_POWERON",
            "com.mediatek.intent.action.BOOT_IPO",
            "com.fyt.boot.ACCON",
            "com.glsx.boot.ACCON",
            "android.intent.action.ACTION_MT_COMMAND_SLEEP_OUT",
            "com.cayboy.action.ACC_ON",
            "com.carboy.action.ACC_ON",
        )
    }
}
