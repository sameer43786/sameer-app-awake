package com.sameerali.appawake;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Centralizes every persisted setting used by the application.
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
    public static final String KEY_LAST_STATUS_EPOCH_MS = "last_status_epoch_ms";
    public static final String KEY_LAST_ERROR = "last_error";

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
}
