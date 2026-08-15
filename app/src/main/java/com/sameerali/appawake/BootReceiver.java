package com.sameerali.appawake;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts monitoring after a reboot or an in-place app update when the user left it enabled. */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
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
            // Preserve a concise local diagnostic for the in-app support screen.
            AppPreferences.get(context).edit()
                    .putString(AppPreferences.KEY_LAST_ERROR,
                            "Automatic restart failed: " + error.getClass().getSimpleName())
                    .apply();
        }
    }
}
