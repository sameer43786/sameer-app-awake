package com.sameerali.appawake;

import java.util.Objects;

/**
 * Maintains a conservative foreground-package state from activity lifecycle and
 * visibility timestamps. Older duplicate observations cannot overwrite newer state.
 *
 * <p>By: Sameer Ali | Contact: sameer43786@gmail.com</p>
 */
public final class ForegroundAppTracker {

    private String currentPackage = "";
    private long latestTimestampMs = Long.MIN_VALUE;
    private long latestPauseTimestampMs = Long.MIN_VALUE;

    /** Records an activity-resumed or move-to-foreground observation. */
    public synchronized boolean recordResumed(String packageName, long timestampMs) {
        if (!validPackage(packageName) || timestampMs < latestTimestampMs) {
            return false;
        }
        boolean changed = !Objects.equals(currentPackage, packageName);
        currentPackage = packageName;
        latestTimestampMs = timestampMs;
        latestPauseTimestampMs = Long.MIN_VALUE;
        return changed;
    }

    /** Records that the active package paused, stopped, or moved to background. */
    public synchronized boolean recordPaused(String packageName, long timestampMs) {
        if (!validPackage(packageName) || timestampMs < latestTimestampMs) {
            return false;
        }
        if (!Objects.equals(currentPackage, packageName)) {
            return false;
        }
        latestTimestampMs = timestampMs;
        latestPauseTimestampMs = timestampMs;
        return true;
    }

    /**
     * Records a last-visible fallback. It must be strictly newer than the current
     * lifecycle state so an explicit pause at the same timestamp cannot be undone.
     */
    public synchronized boolean recordVisible(String packageName, long timestampMs) {
        if (!validPackage(packageName) || timestampMs <= latestTimestampMs) {
            return false;
        }
        boolean changed = !Objects.equals(currentPackage, packageName);
        currentPackage = packageName;
        latestTimestampMs = timestampMs;
        latestPauseTimestampMs = Long.MIN_VALUE;
        return changed;
    }

    /** Returns the best-known foreground package, or empty after an explicit pause. */
    public synchronized String currentPackage() {
        if (latestPauseTimestampMs == latestTimestampMs) {
            return "";
        }
        return currentPackage;
    }

    public synchronized long latestTimestampMs() {
        return latestTimestampMs;
    }

    public synchronized void clear() {
        currentPackage = "";
        latestTimestampMs = Long.MIN_VALUE;
        latestPauseTimestampMs = Long.MIN_VALUE;
    }

    private static boolean validPackage(String packageName) {
        return packageName != null && !packageName.trim().isEmpty();
    }
}
