package com.sameerali.appawake;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.util.Set;

/**
 * High-reliability, profile-local detector and keep-awake actuator.
 *
 * <p>This service consumes only accessibility window/package transition metadata. It does not
 * retrieve window content. When a selected app is foreground, it adds a tiny non-interactive
 * TYPE_ACCESSIBILITY_OVERLAY carrying FLAG_KEEP_SCREEN_ON. This uses Android's window-level
 * screen-on mechanism instead of relying only on the deprecated screen wake-lock level. A screen
 * wake lock is retained as a redundant secondary actuator.</p>
 *
 * <p>By: Sameer Ali | Contact: sameer43786@gmail.com</p>
 */
public final class SameerAccessibilityGuardService extends AccessibilityService {

    private static final long WATCHDOG_MS = 750L;
    private static final String SYSTEM_UI = "com.android.systemui";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private PowerManager powerManager;
    private PowerManager.WakeLock backupWakeLock;
    private View guardView;
    private boolean guardAttached;
    private boolean receiverRegistered;
    private String currentPackage = "";
    private String inputMethodPackage = "";

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            evaluateProtection("accessibility-watchdog");
            handler.postDelayed(this, WATCHDOG_MS);
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                currentPackage = "";
                releaseProtection("screen-off");
            } else if (Intent.ACTION_SCREEN_ON.equals(action)
                    || Intent.ACTION_USER_PRESENT.equals(action)
                    || Intent.ACTION_USER_UNLOCKED.equals(action)) {
                inputMethodPackage = readDefaultInputMethodPackage();
                evaluateProtection("profile-or-screen-active");
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        createBackupWakeLock();
        inputMethodPackage = readDefaultInputMethodPackage();
        registerScreenReceiver();

        AppPreferences.get(this).edit()
                .putBoolean(AppPreferences.KEY_ACCESSIBILITY_CONNECTED, true)
                .putString(AppPreferences.KEY_LAST_ERROR, "")
                .apply();

        handler.removeCallbacks(watchdog);
        handler.post(watchdog);
        publish("", false, "accessibility-connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) {
            return;
        }

        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
                && type != AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            return;
        }

        String pkg = event.getPackageName().toString();
        if (pkg.trim().isEmpty()) {
            return;
        }

        // Keyboard and System UI can temporarily become the event source while Chrome remains the
        // underlying foreground app. Ignore those transient sources so typing or quick controls do
        // not accidentally release the guard.
        if (pkg.equals(inputMethodPackage) || SYSTEM_UI.equals(pkg)) {
            evaluateProtection("accessibility-transient-window");
            return;
        }

        currentPackage = pkg;
        evaluateProtection("accessibility-window");
    }

    @Override
    public void onInterrupt() {
        releaseProtection("accessibility-interrupted");
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(watchdog);
        releaseProtection("accessibility-destroyed");
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver);
            receiverRegistered = false;
        }
        AppPreferences.get(this).edit()
                .putBoolean(AppPreferences.KEY_ACCESSIBILITY_CONNECTED, false)
                .apply();
        super.onDestroy();
    }

    private void evaluateProtection(String source) {
        if (powerManager != null && !powerManager.isInteractive()) {
            releaseProtection("screen-off");
            return;
        }

        if (!AppPreferences.monitoringEnabled(this)) {
            releaseProtection("monitoring-off");
            return;
        }

        Set<String> selected = AppPreferences.selectedPackages(this);
        boolean selectedForeground = !currentPackage.isEmpty() && selected.contains(currentPackage);
        if (selectedForeground) {
            ensureProtection(source);
        } else {
            releaseProtection(source);
        }
    }

    @SuppressWarnings("deprecation")
    private void createBackupWakeLock() {
        if (powerManager == null
                || !powerManager.isWakeLockLevelSupported(PowerManager.SCREEN_DIM_WAKE_LOCK)) {
            return;
        }
        backupWakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK,
                "sameer-app-awake:AccessibilityGuardBackup"
        );
        backupWakeLock.setReferenceCounted(false);
    }

    private void ensureProtection(String source) {
        ensureKeepScreenOnOverlay();
        ensureBackupWakeLock();
        boolean active = guardAttached || (backupWakeLock != null && backupWakeLock.isHeld());
        publish(currentPackage, active, active ? source : "guard-failed");
    }

    @SuppressWarnings("WakelockTimeout")
    private void ensureBackupWakeLock() {
        if (backupWakeLock != null && !backupWakeLock.isHeld()) {
            try {
                backupWakeLock.acquire(10 * 60 * 1000L);
            } catch (RuntimeException error) {
                AppPreferences.get(this).edit()
                        .putString(AppPreferences.KEY_LAST_ERROR,
                                "Backup wake lock failed: " + error.getClass().getSimpleName())
                        .apply();
            }
        }
    }

    private void ensureKeepScreenOnOverlay() {
        if (guardAttached || windowManager == null) {
            return;
        }

        if (guardView == null) {
            guardView = new View(this);
            guardView.setBackgroundColor(Color.TRANSPARENT);
            guardView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                2,
                2,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 0;
        // Keep the overlay technically visible to WindowManager while making it effectively
        // imperceptible. TYPE_ACCESSIBILITY_OVERLAY is a trusted Android window type.
        params.alpha = 0.01f;
        params.setTitle("Sameer App Awake keep-screen-on guard");

        try {
            windowManager.addView(guardView, params);
            guardAttached = true;
        } catch (RuntimeException error) {
            guardAttached = false;
            AppPreferences.get(this).edit()
                    .putString(AppPreferences.KEY_LAST_ERROR,
                            "Keep-screen-on overlay failed: " + error.getClass().getSimpleName())
                    .apply();
        }
    }

    private void releaseProtection(String source) {
        if (guardAttached && guardView != null && windowManager != null) {
            try {
                windowManager.removeViewImmediate(guardView);
            } catch (RuntimeException ignored) {
                // Window was already removed by Android/profile shutdown.
            }
            guardAttached = false;
        }
        if (backupWakeLock != null && backupWakeLock.isHeld()) {
            try {
                backupWakeLock.release();
            } catch (RuntimeException ignored) {
                // Wake lock was already released by the platform.
            }
        }
        publish(currentPackage, false, source);
    }

    private void publish(String foreground, boolean active, String source) {
        String safeForeground = foreground == null ? "" : foreground;
        AppPreferences.get(this).edit()
                .putString(AppPreferences.KEY_LAST_FOREGROUND_PACKAGE, safeForeground)
                .putString(AppPreferences.KEY_PROTECTED_PACKAGE, active ? safeForeground : "")
                .putBoolean(AppPreferences.KEY_PROTECTION_ACTIVE, active)
                .putBoolean(AppPreferences.KEY_GUARD_WINDOW_ATTACHED, guardAttached)
                .putString(AppPreferences.KEY_DETECTION_SOURCE, source == null ? "" : source)
                .putLong(AppPreferences.KEY_LAST_STATUS_EPOCH_MS, System.currentTimeMillis())
                .apply();

        sendBroadcast(new Intent(AppMonitorService.ACTION_STATUS)
                .setPackage(getPackageName())
                .putExtra(AppMonitorService.EXTRA_FOREGROUND_PACKAGE, safeForeground)
                .putExtra(AppMonitorService.EXTRA_PROTECTED_PACKAGE, active ? safeForeground : "")
                .putExtra(AppMonitorService.EXTRA_PROTECTION_ACTIVE, active)
                .putExtra(AppMonitorService.EXTRA_DETECTION_SOURCE, source));
    }

    private String readDefaultInputMethodPackage() {
        try {
            String value = Settings.Secure.getString(
                    getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (value == null || value.trim().isEmpty()) {
                return "";
            }
            int slash = value.indexOf('/');
            return slash > 0 ? value.substring(0, slash) : value;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private void registerScreenReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
        receiverRegistered = true;
    }
}
