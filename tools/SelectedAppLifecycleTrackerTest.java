package com.sameerali.appawake;

import android.app.usage.UsageEvents;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Dependency-light regression checks for selected-app lifecycle tracking.
 * By: Sameer Ali | Contact: sameer43786@gmail.com
 */
public final class SelectedAppLifecycleTrackerTest {

    private static final String MAPS = "com.google.android.apps.maps";
    private static final String SYSTEM_UI = "com.android.systemui";

    public static void main(String[] args) {
        SelectedAppLifecycleTracker tracker = new SelectedAppLifecycleTracker();
        Set<String> selected = new LinkedHashSet<>();
        selected.add(MAPS);
        tracker.syncSelected(selected);

        tracker.recordEvent(MAPS, "MapActivity", UsageEvents.Event.ACTIVITY_RESUMED, 100L);
        assertEquals(MAPS, tracker.activePackage(), "Maps becomes active after resume");

        // Unselected packages are deliberately ignored, even if they have newer timestamps.
        tracker.recordEvent(SYSTEM_UI, "ShadeActivity", UsageEvents.Event.ACTIVITY_RESUMED, 200L);
        assertEquals(MAPS, tracker.activePackage(), "System UI cannot displace selected Maps");

        // Multi-activity transition: activity A pauses while activity B is already resumed.
        tracker.recordEvent(MAPS, "NavigationActivity", UsageEvents.Event.ACTIVITY_RESUMED, 300L);
        tracker.recordEvent(MAPS, "MapActivity", UsageEvents.Event.ACTIVITY_PAUSED, 310L);
        assertEquals(MAPS, tracker.activePackage(), "Pausing one Maps activity keeps another resumed activity active");

        tracker.recordEvent(MAPS, "NavigationActivity", UsageEvents.Event.ACTIVITY_PAUSED, 400L);
        assertEquals("", tracker.activePackage(), "Maps becomes inactive after all resumed activities pause");

        tracker.recordEvent(MAPS, "MapActivity", UsageEvents.Event.ACTIVITY_RESUMED, 500L);
        tracker.recordEvent(MAPS, "MapActivity", UsageEvents.Event.ACTIVITY_STOPPED, 600L);
        assertEquals("", tracker.activePackage(), "Stopped Maps activity is inactive");

        System.out.println("PASS: SelectedAppLifecycleTracker regression checks");
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
