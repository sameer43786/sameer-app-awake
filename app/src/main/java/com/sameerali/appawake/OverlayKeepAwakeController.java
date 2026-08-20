package com.sameerali.appawake;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * Owns a tiny, transparent, non-touchable application overlay whose window carries
 * FLAG_KEEP_SCREEN_ON. This is the primary display-timeout actuator for builds that
 * must remain compatible with Android Advanced Protection, which can block third-party
 * AccessibilityService activation.
 *
 * <p>The overlay has no UI, captures no input, reads no screen content, and is removed
 * immediately when the selected foreground package is no longer active.</p>
 *
 * <p>By: Sameer Ali | Contact: sameer43786@gmail.com</p>
 */
public final class OverlayKeepAwakeController {

    private final Context context;
    private final WindowManager windowManager;
    private View guardView;
    private boolean attached;

    public OverlayKeepAwakeController(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    /** True when Android has granted the special "Display over other apps" permission. */
    public boolean isPermissionGranted() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context);
    }

    /**
     * Adds the minimum possible overlay window and asks WindowManager to keep the display on.
     * Returns true only when the overlay is actually attached.
     */
    public synchronized boolean acquire() {
        if (attached) {
            return true;
        }
        if (windowManager == null || !isPermissionGranted()) {
            return false;
        }

        View view = new View(context);
        view.setAlpha(0.0f);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                1,
                1,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 0;
        params.setTitle("Sameer App Awake Keep-Screen-On Guard");

        try {
            windowManager.addView(view, params);
            guardView = view;
            attached = true;
            return true;
        } catch (RuntimeException error) {
            guardView = null;
            attached = false;
            return false;
        }
    }

    /** Removes the overlay immediately so Android's normal screen timeout resumes. */
    public synchronized void release() {
        if (!attached || guardView == null || windowManager == null) {
            guardView = null;
            attached = false;
            return;
        }
        try {
            windowManager.removeViewImmediate(guardView);
        } catch (RuntimeException ignored) {
            // The system may already have detached it during a profile/process transition.
        } finally {
            guardView = null;
            attached = false;
        }
    }

    public synchronized boolean isHeld() {
        return attached;
    }
}
