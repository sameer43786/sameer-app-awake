package com.sameerali.appawake;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Centralizes every persisted setting used by the application.
 *
 * <p>The same APK is installed independently in the main Android profile and in
 * Private Space. Android therefore gives each copy its own preferences. Smart Guard
 * uses that isolation so the main-space copy can protect an Android app while the
 * Private-Space copy can open the protected web session automatically.</p>
 *
 * <p>By: Sameer Ali | Contact: sameer43786@gmail.com</p>
 */
public final class AppPreferences {

    private AppPreferences() {
        // Utility class. Instances are unnecessary.
    }

    public static final String FILE_NAME = "app_awake_preferences";
    public static final String KEY_SELECTED_PACKAGES = "selected_packages";
    public static final String KEY_MONITORING_ENABLED = "monitoring_enabled";
    public static final String KEY_SERVICE_RUNNING = "service_running";
    public static final String KEY_LAST_FOREGROUND_PACKAGE = "last_foreground_package";
    public static final String KEY_PROTECTED_PACKAGE = "protected_package";
    public static final String KEY_PROTECTION_ACTIVE = "protection_active";
    public static final String KEY_LAST_STATUS_EPOCH_MS = "last_status_epoch_ms";
    public static final String KEY_LAST_ERROR = "last_error";
    public static final String KEY_DETECTION_SOURCE = "detection_source";
    public static final String KEY_ACCESSIBILITY_CONNECTED = "accessibility_connected";
    public static final String KEY_GUARD_WINDOW_ATTACHED = "guard_window_attached";

    // Smart Guard v2 profile-local operating mode.
    public static final String KEY_SMART_MODE = "smart_mode";
    public static final String MODE_APP = "app";
    public static final String MODE_WEB = "web";

    // Timeout-lease reliability state. These are profile-local SharedPreferences,
    // while Settings.System is changed only after the user grants WRITE_SETTINGS.
    public static final String KEY_TIMEOUT_OVERRIDE_ACTIVE = "timeout_override_active";
    public static final String KEY_TIMEOUT_ORIGINAL_MS = "timeout_original_ms";
    public static final String KEY_TIMEOUT_HEARTBEAT_EPOCH_MS = "timeout_heartbeat_epoch_ms";

    public static SharedPreferences get(Context context) {
        return context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    /** Returns a defensive copy because SharedPreferences string sets are mutable views. */
    public static Set<String> selectedPackages(Context context) {
        Set<String> stored = get(context).getStringSet(KEY_SELECTED_PACKAGES, Collections.emptySet());
        return stored == null ? new HashSet<>() : new HashSet<>(stored);
    }

    public static void setPackageSelected(Context context, String packageName, boolean selected) {
        Set<String> packages = selectedPackages(context);
        if (selected) {
            packages.add(packageName);
        } else {
            packages.remove(packageName);
        }
        get(context).edit().putStringSet(KEY_SELECTED_PACKAGES, packages).apply();
    }

    public static void clearSelectedPackages(Context context) {
        get(context).edit().putStringSet(KEY_SELECTED_PACKAGES, new HashSet<>()).apply();
    }

    public static boolean monitoringEnabled(Context context) {
        return get(context).getBoolean(KEY_MONITORING_ENABLED, false);
    }

    public static void setMonitoringEnabled(Context context, boolean enabled) {
        get(context).edit().putBoolean(KEY_MONITORING_ENABLED, enabled).apply();
    }

    public static boolean serviceRunning(Context context) {
        return get(context).getBoolean(KEY_SERVICE_RUNNING, false);
    }

    public static String smartMode(Context context) {
        return get(context).getString(KEY_SMART_MODE, "");
    }

    public static void setSmartMode(Context context, String mode) {
        get(context).edit().putString(KEY_SMART_MODE, mode == null ? "" : mode).apply();
    }

    public static void clearSmartMode(Context context) {
        get(context).edit().remove(KEY_SMART_MODE).apply();
    }
}
