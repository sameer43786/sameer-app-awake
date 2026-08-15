package com.sameerali.appawake;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Continuously identifies the foreground package and holds a screen wake lock only for
 * applications explicitly selected by the user.
 *
 * <p>The service never reads screen text, keystrokes, messages, files, or network traffic.</p>
 * <p>By: Sameer Ali | Contact: sameer43786@gmail.com</p>
 */
public final class AppMonitorService extends Service {

    public static final String ACTION_START = "com.sameerali.appawake.action.START";
    public static final String ACTION_STOP = "com.sameerali.appawake.action.STOP";
    public static final String ACTION_REFRESH = "com.sameerali.appawake.action.REFRESH";
    public static final String ACTION_STATUS = "com.sameerali.appawake.action.STATUS";

    public static final String EXTRA_FOREGROUND_PACKAGE = "foreground_package";
    public static final String EXTRA_PROTECTED_PACKAGE = "protected_package";
    public static final String EXTRA_SERVICE_RUNNING = "service_running";

    private static final String NOTIFICATION_CHANNEL_ID = "app_aware_screen_monitoring";
    private static final int NOTIFICATION_ID = 3107;
    private static final long POLL_INTERVAL_MS = 800L;
    private static final long INITIAL_EVENT_WINDOW_MS = TimeUnit.MINUTES.toMillis(30);
    private static final long EVENT_QUERY_OVERLAP_MS = 2_000L;

    /*
     * A finite wake-lock timeout is a safety net. The service renews it before expiry only
     * while a selected app remains active. If the process stalls, Android releases it.
     */
    private static final long WAKE_LOCK_MAX_HOLD_MS = TimeUnit.MINUTES.toMillis(10);
    private static final long WAKE_LOCK_RENEW_AFTER_MS = TimeUnit.MINUTES.toMillis(8);

    private final ForegroundAppTracker foregroundTracker = new ForegroundAppTracker();

    private ScheduledExecutorService scheduler;
    private UsageStatsManager usageStatsManager;
    private PowerManager powerManager;
    private PowerManager.WakeLock screenWakeLock;
    private NotificationManager notificationManager;

    private volatile long previousQueryEndMs;
    private volatile long wakeLockAcquiredAtElapsedMs;
    private volatile String publishedForegroundPackage = "";
    private volatile String publishedProtectedPackage = "";
    private volatile boolean foregroundStarted;
    private volatile boolean stopRequested;
    private volatile boolean wakeLockUnavailableReported;

    @Override
    public void onCreate() {
        super.onCreate();
        usageStatsManager = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();
        createScreenWakeLock();
        AppPreferences.get(this).edit()
                .putBoolean(AppPreferences.KEY_SERVICE_RUNNING, true)
                .putString(AppPreferences.KEY_LAST_ERROR, "")
                .apply();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            requestStop(true, null);
            return START_NOT_STICKY;
        }

        if (!AppPreferences.monitoringEnabled(this)) {
            requestStop(false, null);
            return START_NOT_STICKY;
        }

        startForegroundCompat(buildNotification("Monitoring selected apps", false));

        if (!validatePrerequisites()) {
            requestStop(true, "Usage Access or selected apps are missing");
            return START_NOT_STICKY;
        }

        startPollingIfNeeded();
        if (ACTION_REFRESH.equals(action)) {
            // Force a notification and UI refresh even when the foreground package is unchanged.
            publishedForegroundPackage = "__refresh__";
            pollForegroundAppSafely();
        }
        return START_STICKY;
    }

    private boolean validatePrerequisites() {
        return PermissionUtils.hasUsageAccess(this)
                && !AppPreferences.selectedPackages(this).isEmpty();
    }

    private void startPollingIfNeeded() {
        if (scheduler != null && !scheduler.isShutdown()) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SameerAppAwake-Monitor");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(
                this::pollForegroundAppSafely,
                0L,
                POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
    }

    private void pollForegroundAppSafely() {
        if (stopRequested) {
            return;
        }
        try {
            pollForegroundApp();
        } catch (SecurityException error) {
            saveErrorAndStop("Usage Access was revoked");
        } catch (RuntimeException error) {
            // Release immediately on an unexpected monitoring failure, then leave a local diagnostic.
            releaseScreenWakeLock();
            AppPreferences.get(this).edit()
                    .putString(AppPreferences.KEY_LAST_ERROR,
                            "Monitoring error: " + error.getClass().getSimpleName())
                    .apply();
        }
    }

    private void pollForegroundApp() {
        if (!AppPreferences.monitoringEnabled(this) || !validatePrerequisites()) {
            saveErrorAndStop("Monitoring prerequisites are no longer available");
            return;
        }

        long nowMs = System.currentTimeMillis();
        long beginMs = previousQueryEndMs == 0L
                ? nowMs - INITIAL_EVENT_WINDOW_MS
                : Math.max(0L, previousQueryEndMs - EVENT_QUERY_OVERLAP_MS);

        if (usageStatsManager != null) {
            UsageEvents usageEvents = usageStatsManager.queryEvents(beginMs, nowMs + 1L);
            if (usageEvents != null) {
                UsageEvents.Event event = new UsageEvents.Event();
                while (usageEvents.hasNextEvent()) {
                    usageEvents.getNextEvent(event);
                    int type = event.getEventType();
                    if (type == UsageEvents.Event.ACTIVITY_RESUMED
                            || type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        foregroundTracker.recordResumed(event.getPackageName(), event.getTimeStamp());
                    }
                }
            }
        }
        previousQueryEndMs = nowMs;

        String currentPackage = foregroundTracker.currentPackage();
        Set<String> selectedPackages = AppPreferences.selectedPackages(this);
        boolean displayInteractive = powerManager != null && powerManager.isInteractive();
        boolean protectionRequested = displayInteractive && selectedPackages.contains(currentPackage);
        boolean protectionActive = false;

        if (protectionRequested) {
            protectionActive = acquireOrRenewScreenWakeLock();
            if (!protectionActive && !wakeLockUnavailableReported) {
                wakeLockUnavailableReported = true;
                AppPreferences.get(this).edit()
                        .putString(AppPreferences.KEY_LAST_ERROR,
                                "This device did not provide a compatible screen wake lock")
                        .apply();
            }
        } else {
            releaseScreenWakeLock();
        }

        publishStatus(currentPackage, protectionActive ? currentPackage : "");
    }

    @SuppressWarnings("deprecation")
    private void createScreenWakeLock() {
        if (powerManager == null) {
            return;
        }
        /*
         * SCREEN_DIM_WAKE_LOCK is used because this cross-application utility has no activity
         * window to which FLAG_KEEP_SCREEN_ON can be attached. It prevents sleep but permits
         * dimming, which reduces power use and OLED wear. It never wakes a manually locked phone.
         */
        if (!powerManager.isWakeLockLevelSupported(PowerManager.SCREEN_DIM_WAKE_LOCK)) {
            return;
        }
        screenWakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK,
                "sameer-app-awake:SelectedAppDisplay"
        );
        screenWakeLock.setReferenceCounted(false);
    }

    @SuppressWarnings("WakelockTimeout")
    private synchronized boolean acquireOrRenewScreenWakeLock() {
        if (screenWakeLock == null) {
            return false;
        }
        long elapsedMs = android.os.SystemClock.elapsedRealtime();
        boolean renewalDue = screenWakeLock.isHeld()
                && elapsedMs - wakeLockAcquiredAtElapsedMs >= WAKE_LOCK_RENEW_AFTER_MS;
        if (renewalDue) {
            screenWakeLock.release();
        }
        if (!screenWakeLock.isHeld()) {
            screenWakeLock.acquire(WAKE_LOCK_MAX_HOLD_MS);
            wakeLockAcquiredAtElapsedMs = elapsedMs;
        }
        return screenWakeLock.isHeld();
    }

    private synchronized void releaseScreenWakeLock() {
        if (screenWakeLock != null && screenWakeLock.isHeld()) {
            screenWakeLock.release();
        }
        wakeLockAcquiredAtElapsedMs = 0L;
    }

    private void publishStatus(String foregroundPackage, String protectedPackage) {
        String safeForeground = foregroundPackage == null ? "" : foregroundPackage;
        String safeProtected = protectedPackage == null ? "" : protectedPackage;
        boolean changed = !safeForeground.equals(publishedForegroundPackage)
                || !safeProtected.equals(publishedProtectedPackage);
        if (!changed) {
            return;
        }

        publishedForegroundPackage = safeForeground;
        publishedProtectedPackage = safeProtected;
        AppPreferences.get(this).edit()
                .putString(AppPreferences.KEY_LAST_FOREGROUND_PACKAGE, safeForeground)
                .putString(AppPreferences.KEY_PROTECTED_PACKAGE, safeProtected)
                .putLong(AppPreferences.KEY_LAST_STATUS_EPOCH_MS, System.currentTimeMillis())
                .apply();

        if (foregroundStarted && notificationManager != null) {
            String message = safeProtected.isEmpty()
                    ? "Monitoring " + AppPreferences.selectedPackages(this).size() + " selected app(s)"
                    : "Screen awake for " + PermissionUtils.appLabel(this, safeProtected);
            notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification(message, !safeProtected.isEmpty())
            );
        }

        Intent status = new Intent(ACTION_STATUS)
                .setPackage(getPackageName())
                .putExtra(EXTRA_FOREGROUND_PACKAGE, safeForeground)
                .putExtra(EXTRA_PROTECTED_PACKAGE, safeProtected)
                .putExtra(EXTRA_SERVICE_RUNNING, true);
        sendBroadcast(status);
    }

    private void createNotificationChannel() {
        if (notificationManager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String message, boolean activelyProtecting) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                100,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, AppMonitorService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                101,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID);
        return builder
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(activelyProtecting ? "Display protection active" : "Sameer App Awake is active")
                .setContentText(message)
                .setSubText("By: Sameer Ali")
                .setColor(getColor(R.color.brand_primary))
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause,
                        "Stop monitoring",
                        stopPendingIntent
                ).build())
                .build();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        foregroundStarted = true;
    }

    private void saveErrorAndStop(String message) {
        AppPreferences.get(this).edit()
                .putString(AppPreferences.KEY_LAST_ERROR, message)
                .apply();
        requestStop(true, message);
    }

    private void requestStop(boolean disableMonitoring, String reason) {
        if (stopRequested) {
            return;
        }
        stopRequested = true;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new android.os.Handler(Looper.getMainLooper()).post(
                    () -> finishStop(disableMonitoring, reason)
            );
        } else {
            finishStop(disableMonitoring, reason);
        }
    }

    private void finishStop(boolean disableMonitoring, String reason) {
        if (disableMonitoring) {
            AppPreferences.setMonitoringEnabled(this, false);
        }
        releaseScreenWakeLock();
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        AppPreferences.get(this).edit()
                .putBoolean(AppPreferences.KEY_SERVICE_RUNNING, false)
                .putString(AppPreferences.KEY_PROTECTED_PACKAGE, "")
                .putString(AppPreferences.KEY_LAST_ERROR,
                        reason == null ? "" : reason)
                .apply();
        sendBroadcast(new Intent(ACTION_STATUS)
                .setPackage(getPackageName())
                .putExtra(EXTRA_SERVICE_RUNNING, false)
                .putExtra(EXTRA_FOREGROUND_PACKAGE, publishedForegroundPackage)
                .putExtra(EXTRA_PROTECTED_PACKAGE, ""));
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        releaseScreenWakeLock();
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        AppPreferences.get(this).edit()
                .putBoolean(AppPreferences.KEY_SERVICE_RUNNING, false)
                .putString(AppPreferences.KEY_PROTECTED_PACKAGE, "")
                .apply();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
