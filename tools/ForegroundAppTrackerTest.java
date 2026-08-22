package com.sameerali.appawake;

/**
 * Dependency-free JVM checks for foreground-event ordering and safe release behavior.
 *
 * <p>By: Sameer Ali | Contact: sameer43786@gmail.com</p>
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
                "Older foreground events must be ignored");

        assertFalse(tracker.recordPaused("com.example.unrelated", 1_050L),
                "Unrelated background events must not clear active state");
        assertEquals(1_000L, tracker.latestTimestampMs(),
                "Unrelated pause must not advance ordering state");

        assertFalse(tracker.recordResumed("com.example.reader", 1_100L),
                "Same-package resume is not a package change");
        assertEquals(1_100L, tracker.latestTimestampMs(),
                "Same-package resume must advance timestamp");

        assertTrue(tracker.recordPaused("com.example.reader", 1_200L),
                "Active package pause must be recorded");
        assertEquals("", tracker.currentPackage(),
                "Paused selected app must immediately stop being foreground");

        assertFalse(tracker.recordVisible("com.example.reader", 1_200L),
                "Equal-timestamp fallback must not revive a paused app");
        assertEquals("", tracker.currentPackage(),
                "Explicit pause must win at the same timestamp");

        assertTrue(tracker.recordVisible("com.example.maps", 1_300L),
                "Strictly newer visibility fallback may seed foreground state");
        assertEquals("com.example.maps", tracker.currentPackage(),
                "Maps must become current through fallback");

        assertTrue(tracker.recordResumed("com.example.chrome", 1_400L),
                "Newer lifecycle resume must replace fallback state");
        assertEquals("com.example.chrome", tracker.currentPackage(),
                "Chrome must become current");

        tracker.clear();
        assertEquals("", tracker.currentPackage(), "Clear must reset foreground state");
        assertEquals(Long.MIN_VALUE, tracker.latestTimestampMs(),
                "Clear must reset timestamp state");

        System.out.println("PASS: ForegroundAppTracker selective foreground/release checks");
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
