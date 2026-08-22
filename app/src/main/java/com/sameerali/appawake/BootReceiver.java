package com.sameerali.appawake;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Restores stale timeout state and restarts monitoring after reboot or an in-place update.
 *
 * <p>By: Sameer Ali | Contact: sameer43786@gmail.com</p>
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        // Safety first: never carry an old timeout lease through reboot/update without
        // a live Smart Guard heartbeat.
        TimeoutLeaseGuard.restoreIfStale(context);

        if (!AppPreferences.monitoringEnabled(context)
                || AppPreferences.selectedPackages(context).isEmpty()
                || !PermissionUtils.hasUsageAccess(context)) {
            return;
        }

        Intent serviceIntent = new Intent(context, AppMonitorService.class)
                .setAction(AppMonitorService.ACTION_START);
        try {
            context.startForegroundService(serviceIntent);
        } catch (RuntimeException error) {
            AppPreferences.get(context).edit()
                    .putString(AppPreferences.KEY_LAST_ERROR,
                            "Automatic restart failed: " + error.getClass().getSimpleName())
                    .apply();
        }
    }
}
