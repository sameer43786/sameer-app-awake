package com.sameerali.appawake;

import android.graphics.drawable.Drawable;

import java.util.Locale;

/** Immutable representation of one launchable application. */
public final class AppEntry {

    public final String label;
    public final String packageName;
    public final Drawable icon;

    public AppEntry(String label, String packageName, Drawable icon) {
        this.label = label;
        this.packageName = packageName;
        this.icon = icon;
    }

    public boolean matches(String query) {
        if (query == null || query.trim().isEmpty()) {
            return true;
        }
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        return label.toLowerCase(Locale.ROOT).contains(normalized)
                || packageName.toLowerCase(Locale.ROOT).contains(normalized);
    }
}
