package com.sameerali.appawake;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageEventsQuery;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sameer App Awake Auto Lifecycle Guard.
 *
 * <p>The main-space guard no longer decides foreground state by tracking whichever package
 * most recently produced a system UsageEvent. That global approach can be disturbed by
 * transient System UI, permission, launcher, and overlay activities even while Google Maps
 * is still genuinely active.</p>
 *
 * <p>Instead, this service asks Android only for lifecycle events belonging to the apps the
 * user selected, then tracks resumed/paused state per activity class. Protection remains
 * active while any activity in a selected package is resumed. A short release debounce
 * prevents internal activity transitions inside Maps from momentarily dropping protection.</p>
 *
 * <p>Protection layers are intentionally independent: SCREEN_BRIGHT_WAKE_LOCK is primary,
 * the temporary system screen-timeout lease is used whenever WRITE_SETTINGS is already
 * available, and PARTIAL_WAKE_LOCK keeps the detector alive while protection is active.</p>
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
    private static final long OVERLAP_MS = 2_000L;
    private static final long INITIAL_SELECTED_EVENT_WINDOW_MS = TimeUnit.DAYS.toMillis(3);
    private static final long RELEASE_DEBOUNCE_MS = 1_800L;

    private static final long WAKE_MAX_MS = TimeUnit.MINUTES.toMillis(30);
    private static final long WAKE_RENEW_MS = TimeUnit.MINUTES.toMillis(25);

    private static final long ARM_START_GRACE_MS = 20_000L;
    private static final long ARM_DETECTOR_GAP_MS = 5_000L;

    private final SelectedAppLifecycleTracker selectedTracker = new SelectedAppLifecycleTracker();
    private final Set<String> trackedSelection = new HashSet<>();

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

    private volatile String lastSelectedActivePackage = "";
    private volatile long lastSelectedSeenElapsedMs;

    private volatile String armedPackage = "";
    private volatile boolean armedConfirmed;
    private volatile long armDeadlineElapsedMs;
    private volatile long lastArmedSeenElapsedMs;

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

        TimeoutLeaseGuard.restoreIfStale(this);

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
                AppPreferences.setPackageSelected(this, requestedPackage.trim(), true);
            }
        }

        Set<String> selected = AppPreferences.selectedPackages(this);
        if (selected.isEmpty() || !PermissionUtils.hasUsageAccess(this)) {
            requestStop(false, "Usage Access or selected apps are missing in this Android profile");
            return START_NOT_STICKY;
        }

        // A selected app is sufficient to arm protection. Stale OFF preferences from earlier
        // releases no longer prevent Maps protection from running.
        AppPreferences.setMonitoringEnabled(this, true);

        startForegroundCompat(buildNotification("Watching selected apps", false));

        if (!prerequisitesReady()) {
            requestStop(false, "Selected-app protection prerequisites are unavailable");
            return START_NOT_STICKY;
        }

        syncSelection(selected, true);

        if (ACTION_ARM_PACKAGE.equals(action) && intent != null) {
            String requestedPackage = intent.getStringExtra(EXTRA_ARM_PACKAGE);
            if (requestedPackage != null && !requestedPackage.trim().isEmpty()) {
                armPackage(requestedPackage.trim());
            }
        }

        startPolling();

        if (ACTION_REFRESH.equals(action)) {
            previousQueryEndMs = 0L;
            selectedTracker.clearLifecycleState();
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
                && (displayWakeLock != null || TimeoutLeaseGuard.canWrite(this))
                && PermissionUtils.hasUsageAccess(this)
                && !AppPreferences.selectedPackages(this).isEmpty();
    }

    private void syncSelection(Set<String> selected, boolean forceReplay) {
        Set<String> safe = selected == null ? new HashSet<>() : new HashSet<>(selected);
        if (!forceReplay && safe.equals(trackedSelection)) {
            return;
        }

        trackedSelection.clear();
        trackedSelection.addAll(safe);
        selectedTracker.syncSelected(safe);
        selectedTracker.clearLifecycleState();
        previousQueryEndMs = 0L;
        lastSelectedActivePackage = "";
        lastSelectedSeenElapsedMs = 0L;
    }

    private void startPolling() {
        if (scheduler != null && !scheduler.isShutdown()) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "SameerAppAwake-SelectedLifecycleMonitor");
            thread.setDaemon(true);
            return thread;
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
            requestStop(false, "Usage Access was revoked in this Android profile");
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
        Set<String> selected = AppPreferences.selectedPackages(this);
        if (selected.isEmpty() || !PermissionUtils.hasUsageAccess(this)) {
            requestStop(false, "Monitoring prerequisites are no longer available");
            return;
        }

        // Self-heal the persistent master state. Selection itself is now the source of truth.
        if (!AppPreferences.monitoringEnabled(this)) {
            AppPreferences.setMonitoringEnabled(this, true);
        }

        syncSelection(selected, false);

        boolean interactive = powerManager.isInteractive();
        if (!interactive) {
            releaseProtection();
            clearArmedPackage();
            selectedTracker.clearLifecycleState();
            previousQueryEndMs = 0L;
            lastSelectedActivePackage = "";
            lastSelectedSeenElapsedMs = 0L;
            lastInteractive = false;
            publishStatus("", "", false, "screen-off");
            return;
        }

        if (!lastInteractive) {
            selectedTracker.clearLifecycleState();
            previousQueryEndMs = 0L;
            lastSelectedActivePackage = "";
            lastSelectedSeenElapsedMs = 0L;
            lastInteractive = true;
        }

        long nowEpoch = System.currentTimeMillis();
        long nowElapsed = android.os.SystemClock.elapsedRealtime();
        querySelectedLifecycleEvents(nowEpoch, selected);

        String activeSelectedPackage = selectedTracker.activePackage();
        String protectedPackage = "";
        String source = "selected-lifecycle";

        if (!armedPackage.isEmpty()) {
            if (selectedTracker.isPackageActive(armedPackage)) {
                armedConfirmed = true;
                lastArmedSeenElapsedMs = nowElapsed;
                protectedPackage = armedPackage;
                source = "armed-selected-lifecycle";
            } else if (!armedConfirmed && nowElapsed <= armDeadlineElapsedMs) {
                protectedPackage = armedPackage;
                source = "armed-launch-grace";
            } else if (armedConfirmed
                    && nowElapsed - lastArmedSeenElapsedMs <= ARM_DETECTOR_GAP_MS) {
                protectedPackage = armedPackage;
                source = "armed-detector-gap";
            } else {
                clearArmedPackage();
            }
        }

        if (protectedPackage.isEmpty() && !activeSelectedPackage.isEmpty()) {
            protectedPackage = activeSelectedPackage;
            lastSelectedActivePackage = activeSelectedPackage;
            lastSelectedSeenElapsedMs = nowElapsed;
            source = "selected-activity-resumed";
        } else if (protectedPackage.isEmpty()
                && !lastSelectedActivePackage.isEmpty()
                && nowElapsed - lastSelectedSeenElapsedMs < RELEASE_DEBOUNCE_MS) {
            // Google Maps can pause one Activity while resuming another. Keep protection through
            // that short transition rather than dropping the screen guard between activities.
            protectedPackage = lastSelectedActivePackage;
            source = "selected-activity-transition-grace";
        } else if (protectedPackage.isEmpty()) {
            lastSelectedActivePackage = "";
            lastSelectedSeenElapsedMs = 0L;
        }

        boolean active = false;
        if (!protectedPackage.isEmpty()) {
            boolean timeoutLease = TimeoutLeaseGuard.engage(this);
            boolean wakeLock = acquireOrRenewProtection();
            active = timeoutLease || wakeLock;
            if (wakeLock) {
                source = source + "+bright-wakelock";
            }
            if (timeoutLease) {
                source = source + "+timeout-lease";
            }
        } else {
            releaseProtection();
        }

        publishStatus(activeSelectedPackage, active ? protectedPackage : "", active, source);
    }

    private void querySelectedLifecycleEvents(long now, Set<String> selected) {
        long begin = previousQueryEndMs == 0L
                ? Math.max(0L, now - INITIAL_SELECTED_EVENT_WINDOW_MS)
                : Math.max(0L, previousQueryEndMs - OVERLAP_MS);
        long end = now + 1L;

        UsageEvents events;
        if (Build.VERSION.SDK_INT >= 35) {
            UsageEventsQuery query = new UsageEventsQuery.Builder(begin, end)
                    .setPackageNames(selected.toArray(new String[0]))
                    .setEventTypes(
                            UsageEvents.Event.ACTIVITY_RESUMED,
                            UsageEvents.Event.ACTIVITY_PAUSED,
                            UsageEvents.Event.ACTIVITY_STOPPED
                    )
                    .build();
            events = usageStatsManager.queryEvents(query);
        } else {
            events = usageStatsManager.queryEvents(begin, end);
        }

        if (events != null) {
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                String packageName = event.getPackageName();
                if (packageName == null || !selected.contains(packageName)) {
                    continue;
                }
                int type = event.getEventType();
                if (type == UsageEvents.Event.ACTIVITY_RESUMED
                        || type == UsageEvents.Event.ACTIVITY_PAUSED
                        || type == UsageEvents.Event.ACTIVITY_STOPPED
                        || type == UsageEvents.Event.MOVE_TO_FOREGROUND
                        || type == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                    selectedTracker.recordEvent(
                            packageName,
                            event.getClassName(),
                            type,
                            event.getTimeStamp()
                    );
                }
            }
        }

        previousQueryEndMs = now;
    }

    private void armPackage(String packageName) {
        armedPackage = packageName;
        armedConfirmed = false;
        long elapsed = android.os.SystemClock.elapsedRealtime();
        armDeadlineElapsedMs = elapsed + ARM_START_GRACE_MS;
        lastArmedSeenElapsedMs = elapsed;

        boolean timeoutLease = TimeoutLeaseGuard.engage(this);
        boolean wakeLock = acquireOrRenewProtection();
        boolean active = timeoutLease || wakeLock;
        publishStatus(packageName, active ? packageName : "", active,
                active ? "armed-before-launch" : "armed-protection-failed");
    }

    private void clearArmedPackage() {
        armedPackage = "";
        armedConfirmed = false;
        armDeadlineElapsedMs = 0L;
        lastArmedSeenElapsedMs = 0L;
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
            displayLevel = 0;
        }

        if (displayLevel != 0) {
            displayWakeLock = powerManager.newWakeLock(
                    displayLevel,
                    "sameer-app-awake:SelectedLifecycleDisplayGuard"
            );
            displayWakeLock.setReferenceCounted(false);
        }

        cpuWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "sameer-app-awake:SelectedLifecycleMonitorCpu"
        );
        cpuWakeLock.setReferenceCounted(false);
    }

    @SuppressWarnings("WakelockTimeout")
    private synchronized boolean acquireOrRenewProtection() {
        long elapsed = android.os.SystemClock.elapsedRealtime();

        if (displayWakeLock != null
                && displayWakeLock.isHeld()
                && elapsed - wakeAcquiredElapsedMs >= WAKE_RENEW_MS) {
            displayWakeLock.release();
            if (cpuWakeLock != null && cpuWakeLock.isHeld()) {
                cpuWakeLock.release();
            }
        }

        if (displayWakeLock != null && !displayWakeLock.isHeld()) {
            displayWakeLock.acquire(WAKE_MAX_MS);
            wakeAcquiredElapsedMs = elapsed;
        }

        if (cpuWakeLock != null && !cpuWakeLock.isHeld()) {
            cpuWakeLock.acquire(WAKE_MAX_MS);
        }

        return displayWakeLock != null && displayWakeLock.isHeld();
    }

    private synchronized void releaseProtection() {
        TimeoutLeaseGuard.restore(this);
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
                    ? "Keeping the screen awake for " + PermissionUtils.appLabel(this, safeProtected)
                    : "Watching " + AppPreferences.selectedPackages(this).size() + " selected app(s)";
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
                this,
                100,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, AppMonitorService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this,
                101,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(active ? "Selected app protected" : "Sameer App Awake is ready")
                .setContentText(message)
                .setSubText("By: Sameer Ali")
                .setContentIntent(openPending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause,
                        "Stop",
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
        selectedTracker.clear();
        trackedSelection.clear();
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
        selectedTracker.clear();
        trackedSelection.clear();
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
