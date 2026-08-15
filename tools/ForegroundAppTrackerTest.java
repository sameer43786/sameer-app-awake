package com.sameerali.appawake;

/**
 * Dependency-free JVM checks for foreground-event ordering.
 *
 * <p>Run through tools/run_core_tests.sh from the project root.</p>
 */
public final class ForegroundAppTrackerTest {

    private ForegroundAppTrackerTest() {
    }

    public static void main(String[] args) {
        ForegroundAppTracker tracker = new ForegroundAppTracker();
        assertEquals("", tracker.currentPackage(), "Initial state must be empty");

        assertTrue(tracker.recordResumed("com.example.reader", 1_000L),
                "First foreground package must change state");
        assertEquals("com.example.reader", tracker.currentPackage(),
                "Reader must become current");

        assertFalse(tracker.recordResumed("com.example.old", 900L),
                "An older overlapping query event must be ignored");
        assertEquals("com.example.reader", tracker.currentPackage(),
                "Older events must not replace newer state");

        assertFalse(tracker.recordResumed("com.example.reader", 1_100L),
                "A newer duplicate for the same package is not a package change");
        assertEquals(1_100L, tracker.latestTimestampMs(),
                "A same-package event must still advance the ordering timestamp");

        assertTrue(tracker.recordResumed("com.example.maps", 1_200L),
                "A newer package must replace the current package");
        assertEquals("com.example.maps", tracker.currentPackage(),
                "Maps must become current");

        assertFalse(tracker.recordResumed(null, 1_300L),
                "Null package events must be ignored");
        assertFalse(tracker.recordResumed("   ", 1_400L),
                "Blank package events must be ignored");

        System.out.println("PASS: ForegroundAppTracker ordering and validation checks");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean value, String message) {
        assertTrue(!value, message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
