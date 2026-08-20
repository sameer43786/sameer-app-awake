package com.sameerali.appawake;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Advanced-Protection-safe display guard.
 *
 * <p>This version intentionally does not depend on Android Accessibility. A profile-local
 * foreground service combines UsageEvents/UsageStats foreground detection with a strong
 * SCREEN_BRIGHT_WAKE_LOCK. A dedicated Protected Chrome launcher arms the lock before Chrome
 * opens, eliminating the switch-detection race that can otherwise occur in Private Space.</p>
 *
 * <p>The lock is released when Chrome/another selected app leaves the foreground. A physical
 * power-button press is never overridden.</p>
 *
 * <p>By: Sameer Ali | Contact: sameer43786@gmail.com</p>
 */
public final class AppMonitorService extends Service {

    public static final String ACTION_START = "com.sameerali.appawake.action.START";
    public static final String ACTION_STOP = "com.sameerali.appawake.action.STOP";
    public static final String ACTION_REFRESH = "com.sameerali.appawake.action.REFRESH";
    public static final String ACTION_ARM_PACKAGE = "com.sameerali.appawake.action.ARM_PACKAGE";
    public static final String ACTION_STATUS = "com.sameerali.appawake.action.STATUS";

    public static final String EXTRA_ARM_PACKAGE = "arm_package";
    public static final String EXTRA_FOREGROUND_PACKAGE = "foreground_package";
    public static final String EXTRA_PROTECTED_PACKAGE = "protected_package";
    public static final String EXTRA_SERVICE_RUNNING = "service_running";
    public static final String EXTRA_PROTECTION_ACTIVE = "protection_active";
    public static final String EXTRA_DETECTION_SOURCE = "detection_source";

    private static final String CHANNEL_ID = "app_aware_screen_monitoring";
    private static final int NOTIFICATION_ID = 3107;

    private static final long POLL_MS = 250L;
    private static final long INITIAL_WINDOW_MS = TimeUnit.MINUTES.toMillis(30);
    private static final long OVERLAP_MS = 2_000L;
    private static final long STATS_LOOKBACK_MS = TimeUnit.MINUTES.toMillis(10);
    private static final long FALLBACK_FRESHNESS_MS = 5_000L;

    // The display lock is periodically renewed so a single lock is never held forever.
    private static final long WAKE_MAX_MS = TimeUnit.MINUTES.toMillis(30);
    private static final long WAKE_RENEW_MS = TimeUnit.MINUTES.toMillis(25);

    // Protected Chrome is armed before Chrome is launched. These short grace windows bridge
    // UsageStats/UsageEvents delivery latency without keeping the screen awake after Chrome exits.
    private static final long ARM_START_GRACE_MS = 20_000L;
    private static final long DETECTOR_GAP_GRACE_MS = 5_000L;
    private static final long LEAVE_CONFIRM_MS = 1_250L;

    private final ForegroundAppTracker tracker = new ForegroundAppTracker();

    private UsageStatsManager usageStatsManager;
    private PowerManager powerManager;
    private NotificationManager notificationManager;
    private PowerManager.WakeLock displayWakeLock;
    private PowerManager.WakeLock cpuWakeLock;
    private ScheduledExecutorService scheduler;

    private volatile long previousQueryEndMs;
    private volatile long wakeAcquiredElapsedMs;
    private volatile boolean lastInteractive;
    private volatile boolean stopRequested;
    private volatile boolean foregroundStarted;

    private volatile String armedPackage = "";
    private volatile boolean armedConfirmed;
    private volatile long armDeadlineElapsedMs;
    private volatile long lastArmedSeenElapsedMs;
    private volatile long differentPackageSinceElapsedMs;

    private volatile String publishedForeground = "";
    private volatile String publishedProtected = "";
    private volatile String publishedSource = "";
    private volatile boolean publishedActive;

    @Override
    public void onCreate() {
        super.onCreate();
        usageStatsManager = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();
        createWakeLocks();
        lastInteractive = powerManager != null && powerManager.isInteractive();

        AppPreferences.get(this).edit()
                .putBoolean(AppPreferences.KEY_SERVICE_RUNNING, true)
                .putBoolean(AppPreferences.KEY_PROTECTION_ACTIVE, false)
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

        if (ACTION_ARM_PACKAGE.equals(action) && intent != null) {
            String requestedPackage = intent.getStringExtra(EXTRA_ARM_PACKAGE);
            if (requestedPackage != null && !requestedPackage.trim().isEmpty()) {
                AppPreferences.setPackageSelected(this, requestedPackage, true);
                AppPreferences.setMonitoringEnabled(this, true);
            }
        }

        if (!AppPreferences.monitoringEnabled(this)) {
            requestStop(false, null);
            return START_NOT_STICKY;
        }

        startForegroundCompat(buildNotification("Monitoring selected apps", false));

        if (!prerequisitesReady()) {
            requestStop(true, "Usage Access or selected apps are missing in this Android profile");
            return START_NOT_STICKY;
        }

        if (ACTION_ARM_PACKAGE.equals(action) && intent != null) {
            String requestedPackage = intent.getStringExtra(EXTRA_ARM_PACKAGE);
            if (requestedPackage != null && !requestedPackage.trim().isEmpty()) {
                armPackage(requestedPackage.trim());
            }
        }

        startPolling();

        if (ACTION_REFRESH.equals(action)) {
            publishedForeground = "__refresh__";
            publishedProtected = "__refresh__";
            publishedSource = "__refresh__";
            pollSafely();
        }

        return START_STICKY;
    }

    private boolean prerequisitesReady() {
        return usageStatsManager != null
                && powerManager != null
                && displayWakeLock != null
                && PermissionUtils.hasUsageAccess(this)
                && !AppPreferences.selectedPackages(this).isEmpty();
    }

    private void startPolling() {
        if (scheduler != null && !scheduler.isShutdown()) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SameerAppAwake-Monitor");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::pollSafely, 0L, POLL_MS, TimeUnit.MILLISECONDS);
    }

    private void pollSafely() {
        if (stopRequested) {
            return;
        }
        try {
            poll();
        } catch (SecurityException error) {
            requestStop(true, "Usage Access was revoked in this Android profile");
        } catch (RuntimeException error) {
            releaseProtection();
            AppPreferences.get(this).edit()
                    .putBoolean(AppPreferences.KEY_PROTECTION_ACTIVE, false)
                    .putString(AppPreferences.KEY_LAST_ERROR,
                            "Monitoring error: " + error.getClass().getSimpleName())
                    .apply();
        }
    }

    private void poll() {
        if (!AppPreferences.monitoringEnabled(this) || !prerequisitesReady()) {
            requestStop(true, "Monitoring prerequisites are no longer available");
            return;
        }

        boolean interactive = powerManager.isInteractive();
        if (!interactive) {
            // Respect a real screen-off/power-button action. Do not wake the device back up.
            releaseProtection();
            clearArmedPackage();
            tracker.clear();
            previousQueryEndMs = 0L;
            lastInteractive = false;
            publishStatus("", "", false, "screen-off");
            return;
        }

        if (!lastInteractive) {
            tracker.clear();
            previousQueryEndMs = 0L;
            lastInteractive = true;
        }

        long nowEpoch = System.currentTimeMillis();
        long nowElapsed = android.os.SystemClock.elapsedRealtime();
        queryLifecycleEvents(nowEpoch);

        UsageCandidate candidate = queryMostRecentlyVisible(nowEpoch);
        String detectionSource = "events";
        if (candidate != null
                && candidate.timestampMs > tracker.latestTimestampMs()
                && nowEpoch - candidate.timestampMs <= FALLBACK_FRESHNESS_MS) {
            tracker.recordVisible(candidate.packageName, candidate.timestampMs);
            detectionSource = "last-visible";
        }

        String currentPackage = tracker.currentPackage();
        Set<String> selected = AppPreferences.selectedPackages(this);

        String protectedPackage = "";
        String source = detectionSource;

        if (!armedPackage.isEmpty()) {
            if (armedPackage.equals(currentPackage)) {
                armedConfirmed = true;
                lastArmedSeenElapsedMs = nowElapsed;
                differentPackageSinceElapsedMs = 0L;
                protectedPackage = armedPackage;
                source = "protected-launch-confirmed";
            } else if (!armedConfirmed && nowElapsed <= armDeadlineElapsedMs) {
                // The user explicitly tapped Protected Chrome. Hold the display lock while
                // Android transitions from this launcher activity into Chrome.
                protectedPackage = armedPackage;
                source = "protected-launch-grace";
            } else if (armedConfirmed) {
                if (currentPackage.isEmpty()) {
                    if (nowElapsed - lastArmedSeenElapsedMs <= DETECTOR_GAP_GRACE_MS) {
                        protectedPackage = armedPackage;
                        source = "detector-gap-grace";
                    } else {
                        clearArmedPackage();
                    }
                } else if (selected.contains(currentPackage)) {
                    // Another selected app became active. Protect it and end the manual Chrome latch.
                    protectedPackage = currentPackage;
                    source = detectionSource;
                    clearArmedPackage();
                } else {
                    if (differentPackageSinceElapsedMs == 0L) {
                        differentPackageSinceElapsedMs = nowElapsed;
                    }
                    if (nowElapsed - differentPackageSinceElapsedMs < LEAVE_CONFIRM_MS) {
                        protectedPackage = armedPackage;
                        source = "leave-confirmation";
                    } else {
                        clearArmedPackage();
                    }
                }
            } else {
                clearArmedPackage();
            }
        }

        if (protectedPackage.isEmpty()
                && !currentPackage.isEmpty()
                && selected.contains(currentPackage)) {
            protectedPackage = currentPackage;
            source = detectionSource;
        }

        boolean active;
        if (!protectedPackage.isEmpty()) {
            active = acquireOrRenewProtection();
        } else {
            releaseProtection();
            active = false;
        }

        publishStatus(currentPackage, active ? protectedPackage : "", active, source);
    }

    private void armPackage(String packageName) {
        armedPackage = packageName;
        armedConfirmed = false;
        long elapsed = android.os.SystemClock.elapsedRealtime();
        armDeadlineElapsedMs = elapsed + ARM_START_GRACE_MS;
        lastArmedSeenElapsedMs = elapsed;
        differentPackageSinceElapsedMs = 0L;

        boolean active = acquireOrRenewProtection();
        publishStatus(packageName, active ? packageName : "", active,
                active ? "protected-launch-armed" : "protected-launch-lock-failed");
    }

    private void clearArmedPackage() {
        armedPackage = "";
        armedConfirmed = false;
        armDeadlineElapsedMs = 0L;
        lastArmedSeenElapsedMs = 0L;
        differentPackageSinceElapsedMs = 0L;
    }

    private void queryLifecycleEvents(long now) {
        long begin = previousQueryEndMs == 0L
                ? Math.max(0L, now - INITIAL_WINDOW_MS)
                : Math.max(0L, previousQueryEndMs - OVERLAP_MS);

        UsageEvents events = usageStatsManager.queryEvents(begin, now + 1L);
        if (events != null) {
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                String pkg = event.getPackageName();
                long ts = event.getTimeStamp();
                int type = event.getEventType();

                if (type == UsageEvents.Event.ACTIVITY_RESUMED
                        || type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    tracker.recordResumed(pkg, ts);
                } else if (type == UsageEvents.Event.ACTIVITY_PAUSED
                        || type == UsageEvents.Event.ACTIVITY_STOPPED
                        || type == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                    tracker.recordPaused(pkg, ts);
                }
            }
        }
        previousQueryEndMs = now;
    }

    private UsageCandidate queryMostRecentlyVisible(long now) {
        List<UsageStats> stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                Math.max(0L, now - STATS_LOOKBACK_MS),
                now + 1L
        );
        if (stats == null || stats.isEmpty()) {
            return null;
        }

        UsageCandidate best = null;
        for (UsageStats usage : stats) {
            if (usage == null || usage.getPackageName() == null) {
                continue;
            }
            long visible = usage.getLastTimeVisible();
            if (visible <= 0L) {
                continue;
            }
            if (best == null || visible > best.timestampMs) {
                best = new UsageCandidate(usage.getPackageName(), visible);
            }
        }
        return best;
    }

    private static final class UsageCandidate {
        final String packageName;
        final long timestampMs;

        UsageCandidate(String packageName, long timestampMs) {
            this.packageName = packageName;
            this.timestampMs = timestampMs;
        }
    }

    @SuppressWarnings("deprecation")
    private void createWakeLocks() {
        if (powerManager == null) {
            return;
        }

        int displayLevel;
        if (powerManager.isWakeLockLevelSupported(PowerManager.SCREEN_BRIGHT_WAKE_LOCK)) {
            displayLevel = PowerManager.SCREEN_BRIGHT_WAKE_LOCK;
        } else if (powerManager.isWakeLockLevelSupported(PowerManager.SCREEN_DIM_WAKE_LOCK)) {
            displayLevel = PowerManager.SCREEN_DIM_WAKE_LOCK;
        } else {
            return;
        }

        displayWakeLock = powerManager.newWakeLock(
                displayLevel,
                "sameer-app-awake:SelectedAppBrightDisplayProtection"
        );
        displayWakeLock.setReferenceCounted(false);

        cpuWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "sameer-app-awake:SelectedAppMonitorCpu"
        );
        cpuWakeLock.setReferenceCounted(false);
    }

    @SuppressWarnings("WakelockTimeout")
    private synchronized boolean acquireOrRenewProtection() {
        if (displayWakeLock == null) {
            return false;
        }

        long elapsed = android.os.SystemClock.elapsedRealtime();
        if (displayWakeLock.isHeld() && elapsed - wakeAcquiredElapsedMs >= WAKE_RENEW_MS) {
            displayWakeLock.release();
            if (cpuWakeLock != null && cpuWakeLock.isHeld()) {
                cpuWakeLock.release();
            }
        }

        if (!displayWakeLock.isHeld()) {
            displayWakeLock.acquire(WAKE_MAX_MS);
            wakeAcquiredElapsedMs = elapsed;
        }

        if (cpuWakeLock != null && !cpuWakeLock.isHeld()) {
            cpuWakeLock.acquire(WAKE_MAX_MS);
        }

        return displayWakeLock.isHeld();
    }

    private synchronized void releaseProtection() {
        if (displayWakeLock != null && displayWakeLock.isHeld()) {
            displayWakeLock.release();
        }
        if (cpuWakeLock != null && cpuWakeLock.isHeld()) {
            cpuWakeLock.release();
        }
        wakeAcquiredElapsedMs = 0L;
    }

    private void publishStatus(String foreground, String protectedPackage,
                               boolean active, String source) {
        String safeForeground = foreground == null ? "" : foreground;
        String safeProtected = protectedPackage == null ? "" : protectedPackage;
        String safeSource = source == null ? "" : source;

        if (safeForeground.equals(publishedForeground)
                && safeProtected.equals(publishedProtected)
                && safeSource.equals(publishedSource)
                && active == publishedActive) {
            return;
        }

        publishedForeground = safeForeground;
        publishedProtected = safeProtected;
        publishedSource = safeSource;
        publishedActive = active;

        AppPreferences.get(this).edit()
                .putString(AppPreferences.KEY_LAST_FOREGROUND_PACKAGE, safeForeground)
                .putString(AppPreferences.KEY_PROTECTED_PACKAGE, safeProtected)
                .putBoolean(AppPreferences.KEY_PROTECTION_ACTIVE, active)
                .putString(AppPreferences.KEY_DETECTION_SOURCE, safeSource)
                .putLong(AppPreferences.KEY_LAST_STATUS_EPOCH_MS, System.currentTimeMillis())
                .apply();

        if (foregroundStarted && notificationManager != null) {
            String text = active
                    ? "Bright display lock held for " + PermissionUtils.appLabel(this, safeProtected)
                    : "Monitoring " + AppPreferences.selectedPackages(this).size() + " selected app(s)";
            notificationManager.notify(NOTIFICATION_ID, buildNotification(text, active));
        }

        Intent status = new Intent(ACTION_STATUS)
                .setPackage(getPackageName())
                .putExtra(EXTRA_FOREGROUND_PACKAGE, safeForeground)
                .putExtra(EXTRA_PROTECTED_PACKAGE, safeProtected)
                .putExtra(EXTRA_SERVICE_RUNNING, true)
                .putExtra(EXTRA_PROTECTION_ACTIVE, active)
                .putExtra(EXTRA_DETECTION_SOURCE, safeSource);
        sendBroadcast(status);
    }

    private void createNotificationChannel() {
        if (notificationManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String message, boolean active) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
                this, 100, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent protectedChromeIntent = new Intent(this, ProtectedChromeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent protectedChromePending = PendingIntent.getActivity(
                this, 102, protectedChromeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, AppMonitorService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this, 101, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(active ? "Display protection active" : "Sameer App Awake is active")
                .setContentText(message)
                .setSubText("By: Sameer Ali")
                .setContentIntent(openPending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_view,
                        "Protected Chrome",
                        protectedChromePending
                ).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause,
                        "Stop monitoring",
                        stopPending
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
        releaseProtection();
        clearArmedPackage();
        tracker.clear();
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }

        AppPreferences.get(this).edit()
                .putBoolean(AppPreferences.KEY_SERVICE_RUNNING, false)
                .putBoolean(AppPreferences.KEY_PROTECTION_ACTIVE, false)
                .putString(AppPreferences.KEY_PROTECTED_PACKAGE, "")
                .putString(AppPreferences.KEY_LAST_ERROR, reason == null ? "" : reason)
                .apply();

        sendBroadcast(new Intent(ACTION_STATUS)
                .setPackage(getPackageName())
                .putExtra(EXTRA_SERVICE_RUNNING, false)
                .putExtra(EXTRA_FOREGROUND_PACKAGE, publishedForeground)
                .putExtra(EXTRA_PROTECTED_PACKAGE, "")
                .putExtra(EXTRA_PROTECTION_ACTIVE, false));

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        releaseProtection();
        clearArmedPackage();
        tracker.clear();
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        AppPreferences.get(this).edit()
                .putBoolean(AppPreferences.KEY_SERVICE_RUNNING, false)
                .putBoolean(AppPreferences.KEY_PROTECTION_ACTIVE, false)
                .putString(AppPreferences.KEY_PROTECTED_PACKAGE, "")
                .apply();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
