package com.sameerali.appawake;

import java.util.Objects;

/**
 * Maintains the latest resumed package while tolerating overlapping event queries.
 * This class contains no Android dependencies, which makes its ordering logic testable on a desktop JVM.
 */
public final class ForegroundAppTracker {

    private String currentPackage = "";
    private long latestTimestampMs = Long.MIN_VALUE;

    /**
     * Records an activity-resumed or move-to-foreground event.
     * Older duplicate events cannot overwrite a newer foreground state.
     */
    public synchronized boolean recordResumed(String packageName, long timestampMs) {
        if (packageName == null || packageName.trim().isEmpty() || timestampMs < latestTimestampMs) {
            return false;
        }
        boolean changed = !Objects.equals(currentPackage, packageName);
        currentPackage = packageName;
        latestTimestampMs = timestampMs;
        return changed;
    }

    public synchronized String currentPackage() {
        return currentPackage;
    }

    public synchronized long latestTimestampMs() {
        return latestTimestampMs;
    }
}
