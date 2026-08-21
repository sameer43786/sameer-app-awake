package com.sameerali.appawake;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

/**
 * Reliable external-app screen-timeout controller for Smart Guard v2.
 *
 * <p>For an external app such as Google Maps, Android does not let Sameer App Awake
 * place FLAG_KEEP_SCREEN_ON on that other app's window. With explicit user approval,
 * this guard temporarily raises Settings.System.SCREEN_OFF_TIMEOUT while a selected
 * external app is in the foreground, and restores the user's original timeout as soon
 * as the selected app leaves.</p>
 *
 * <p>A watchdog limits the risk of leaving a long timeout behind if Android kills the
 * monitoring process unexpectedly. The foreground service refreshes a heartbeat while
 * protection is active; the watchdog restores the original timeout if the heartbeat
 * becomes stale.</p>
 *
 * <p>By: Sameer Ali | Contact: sameer43786@gmail.com</p>
 */
public final class TimeoutLeaseGuard {

    private static final int REQUEST_WATCHDOG = 4417;
    private static final int EXTENDED_TIMEOUT_MS = 86_400_000; // 24 hours
    private static final long HEARTBEAT_WRITE_INTERVAL_MS = 10_000L;
    private static final long WATCHDOG_DELAY_MS = 120_000L;
    private static final long WATCHDOG_STALE_MS = 45_000L;

    private static volatile long lastHeartbeatWriteEpochMs;

    private TimeoutLeaseGuard() {
    }

    public static boolean canWrite(Context context) {
        return Settings.System.canWrite(context);
    }

    public static Intent permissionIntent(Context context) {
        return new Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:" + context.getPackageName())
        );
    }

    /**
     * Applies a long timeout only while Smart Guard owns the lease.
     * Returns false when the special WRITE_SETTINGS approval is unavailable.
     */
    public static synchronized boolean engage(Context context) {
        if (!canWrite(context)) {
            return false;
        }

        boolean alreadyActive = AppPreferences.get(context)
                .getBoolean(AppPreferences.KEY_TIMEOUT_OVERRIDE_ACTIVE, false);

        if (!alreadyActive) {
            int original = Settings.System.getInt(
                    context.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT,
                    60_000
            );
            AppPreferences.get(context).edit()
                    .putInt(AppPreferences.KEY_TIMEOUT_ORIGINAL_MS, Math.max(1_000, original))
                    .putBoolean(AppPreferences.KEY_TIMEOUT_OVERRIDE_ACTIVE, true)
                    .apply();
        }

        boolean written = Settings.System.putInt(
                context.getContentResolver(),
                Settings.System.SCREEN_OFF_TIMEOUT,
                EXTENDED_TIMEOUT_MS
        );

        heartbeat(context, true);
        scheduleWatchdog(context);
        return written;
    }

    public static synchronized void heartbeat(Context context) {
        heartbeat(context, false);
    }

    private static void heartbeat(Context context, boolean force) {
        if (!AppPreferences.get(context)
                .getBoolean(AppPreferences.KEY_TIMEOUT_OVERRIDE_ACTIVE, false)) {
            return;
        }

        long now = System.currentTimeMillis();
        if (!force && now - lastHeartbeatWriteEpochMs < HEARTBEAT_WRITE_INTERVAL_MS) {
            return;
        }
        lastHeartbeatWriteEpochMs = now;
        AppPreferences.get(context).edit()
                .putLong(AppPreferences.KEY_TIMEOUT_HEARTBEAT_EPOCH_MS, now)
                .apply();
    }

    /** Restores the user's original timeout and cancels the crash watchdog. */
    public static synchronized void restore(Context context) {
        boolean active = AppPreferences.get(context)
                .getBoolean(AppPreferences.KEY_TIMEOUT_OVERRIDE_ACTIVE, false);
        if (!active) {
            cancelWatchdog(context);
            return;
        }

        int original = AppPreferences.get(context)
                .getInt(AppPreferences.KEY_TIMEOUT_ORIGINAL_MS, 60_000);

        if (canWrite(context)) {
            Settings.System.putInt(
                    context.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT,
                    Math.max(1_000, original)
            );
        }

        AppPreferences.get(context).edit()
                .putBoolean(AppPreferences.KEY_TIMEOUT_OVERRIDE_ACTIVE, false)
                .remove(AppPreferences.KEY_TIMEOUT_HEARTBEAT_EPOCH_MS)
                .apply();
        lastHeartbeatWriteEpochMs = 0L;
        cancelWatchdog(context);
    }

    /** Called by the watchdog receiver or at boot/update recovery. */
    public static synchronized void restoreIfStale(Context context) {
        boolean active = AppPreferences.get(context)
                .getBoolean(AppPreferences.KEY_TIMEOUT_OVERRIDE_ACTIVE, false);
        if (!active) {
            return;
        }

        long heartbeat = AppPreferences.get(context)
                .getLong(AppPreferences.KEY_TIMEOUT_HEARTBEAT_EPOCH_MS, 0L);
        long age = heartbeat <= 0L ? Long.MAX_VALUE : System.currentTimeMillis() - heartbeat;

        if (age > WATCHDOG_STALE_MS) {
            restore(context);
        } else {
            scheduleWatchdog(context);
        }
    }

    public static int effectiveTimeoutMs(Context context) {
        return Settings.System.getInt(
                context.getContentResolver(),
                Settings.System.SCREEN_OFF_TIMEOUT,
                -1
        );
    }

    private static void scheduleWatchdog(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        long when = System.currentTimeMillis() + WATCHDOG_DELAY_MS;
        alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                when,
                watchdogPendingIntent(context)
        );
    }

    private static void cancelWatchdog(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.cancel(watchdogPendingIntent(context));
        }
    }

    private static PendingIntent watchdogPendingIntent(Context context) {
        Intent intent = new Intent(context, TimeoutWatchdogReceiver.class)
                .setAction("com.sameerali.appawake.action.TIMEOUT_WATCHDOG");
        return PendingIntent.getBroadcast(
                context,
                REQUEST_WATCHDOG,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }
}
