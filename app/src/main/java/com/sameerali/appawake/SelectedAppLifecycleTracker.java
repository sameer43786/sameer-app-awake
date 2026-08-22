package com.sameerali.appawake;

import android.app.usage.UsageEvents;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tracks lifecycle state only for apps the user explicitly selected.
 *
 * <p>This deliberately ignores unrelated foreground packages such as System UI,
 * permission panels, launchers, overlays, notification shade components, and transient
 * system activities. That prevents those unrelated packages from cancelling protection
 * while a selected app such as Google Maps still has a resumed activity.</p>
 *
 * <p>Activity state is maintained per class name. This matters for applications such as
 * Google Maps that move between multiple activities: pausing one activity does not mark
 * the whole package inactive when another activity in the same package is still resumed.</p>
 *
 * <p>By: Sameer Ali | Contact: sameer43786@gmail.com</p>
 */
public final class SelectedAppLifecycleTracker {

    private static final String UNKNOWN_ACTIVITY = "<unknown-activity>";

    private static final class ActivityState {
        long timestampMs = Long.MIN_VALUE;
        boolean resumed;
    }

    private static final class PackageState {
        final Map<String, ActivityState> activities = new HashMap<>();
        long latestResumeMs = Long.MIN_VALUE;
    }

    private final Map<String, PackageState> packages = new HashMap<>();
    private final Set<String> selectedPackages = new HashSet<>();

    public synchronized void syncSelected(Set<String> selected) {
        Set<String> safe = selected == null ? new HashSet<>() : new HashSet<>(selected);
        selectedPackages.clear();
        selectedPackages.addAll(safe);
        packages.keySet().removeIf(pkg -> !safe.contains(pkg));
        for (String pkg : safe) {
            if (pkg != null && !pkg.trim().isEmpty()) {
                packages.computeIfAbsent(pkg, ignored -> new PackageState());
            }
        }
    }

    public synchronized void clear() {
        packages.clear();
        selectedPackages.clear();
    }

    public synchronized void clearLifecycleState() {
        for (PackageState state : packages.values()) {
            state.activities.clear();
            state.latestResumeMs = Long.MIN_VALUE;
        }
    }

    public synchronized void recordEvent(
            String packageName,
            String className,
            int eventType,
            long timestampMs
    ) {
        if (packageName == null || packageName.trim().isEmpty()
                || !selectedPackages.contains(packageName)) {
            return;
        }

        PackageState packageState = packages.computeIfAbsent(
                packageName,
                ignored -> new PackageState()
        );

        String activityKey = className == null || className.trim().isEmpty()
                ? UNKNOWN_ACTIVITY
                : className;

        if (eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
            ActivityState state = packageState.activities.computeIfAbsent(
                    activityKey,
                    ignored -> new ActivityState()
            );
            if (timestampMs >= state.timestampMs) {
                state.timestampMs = timestampMs;
                state.resumed = true;
                packageState.latestResumeMs = Math.max(packageState.latestResumeMs, timestampMs);
            }
            return;
        }

        if (eventType == UsageEvents.Event.ACTIVITY_PAUSED
                || eventType == UsageEvents.Event.ACTIVITY_STOPPED) {
            ActivityState state = packageState.activities.computeIfAbsent(
                    activityKey,
                    ignored -> new ActivityState()
            );
            if (timestampMs >= state.timestampMs) {
                state.timestampMs = timestampMs;
                state.resumed = false;
            }
        }
    }

    public synchronized boolean isPackageActive(String packageName) {
        PackageState state = packages.get(packageName);
        if (state == null) {
            return false;
        }
        for (ActivityState activity : state.activities.values()) {
            if (activity.resumed) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the selected package that currently has a resumed activity and whose latest
     * resume is newest. Returns an empty string when no selected package is active.
     */
    public synchronized String activePackage() {
        String bestPackage = "";
        long bestResume = Long.MIN_VALUE;

        for (String packageName : selectedPackages) {
            PackageState state = packages.get(packageName);
            if (state == null) {
                continue;
            }

            boolean active = false;
            for (ActivityState activity : state.activities.values()) {
                if (activity.resumed) {
                    active = true;
                    break;
                }
            }

            if (active && state.latestResumeMs >= bestResume) {
                bestPackage = packageName;
                bestResume = state.latestResumeMs;
            }
        }

        return bestPackage;
    }
}
