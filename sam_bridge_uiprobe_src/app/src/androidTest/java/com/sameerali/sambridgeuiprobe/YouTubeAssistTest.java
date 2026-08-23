package com.sameerali.sambridgeuiprobe;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RunWith(AndroidJUnit4.class)
public class YouTubeAssistTest {
    private static final String TAG = "SAMBridgeAssist";
    private static final String YT = "com.google.android.youtube";
    private static final String VOL_KEY = "sam_bridge_ad_assist_orig_vol";
    private static final String HEARTBEAT_KEY = "sam_bridge_ad_assist_heartbeat";
    private static final long POLL_MS = 400;
    private static final long MAX_RUNTIME_MS = 8L * 60L * 60L * 1000L;

    private UiDevice device;
    private Instrumentation instrumentation;
    private WindowManager windowManager;
    private TextView overlayView;
    private int originalVolume = -1;
    private boolean muted = false;
    private long lastShortSwipe = 0;
    private long lastHeartbeatCheck = 0;
    private boolean heartbeatOK = true;

    @Test
    public void runAssist() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        device = UiDevice.getInstance(instrumentation);
        windowManager = (WindowManager) instrumentation.getTargetContext().getSystemService(Context.WINDOW_SERVICE);
        Log.i(TAG, "READY helper=1.1.0 mode=UiAutomation overlay=1 heartbeat=1");
        long started = SystemClock.elapsedRealtime();
        try {
            while (SystemClock.elapsedRealtime() - started < MAX_RUNTIME_MS) {
                if (Thread.currentThread().isInterrupted()) break;
                if (!heartbeatAlive()) {
                    Log.i(TAG, "STOP heartbeat_lost");
                    break;
                }
                String pkg = safeCurrentPackage();
                if (!YT.equals(pkg)) {
                    hideOverlay();
                    restoreVolumeIfNeeded("left_youtube");
                    SystemClock.sleep(POLL_MS);
                    continue;
                }

                boolean ad = hasAdMarker();
                if (!ad) {
                    hideOverlay();
                    restoreVolumeIfNeeded("content_resumed");
                    SystemClock.sleep(POLL_MS);
                    continue;
                }

                UiObject2 action = findActionButton();
                if (action != null) {
                    String label = objectLabel(action);
                    if (safeClick(action)) {
                        hideOverlay();
                        Log.i(TAG, "ACTION click " + sanitize(label));
                        SystemClock.sleep(250);
                        continue;
                    }
                }

                if (isSponsoredShort() && SystemClock.elapsedRealtime() - lastShortSwipe > 1500) {
                    int w = device.getDisplayWidth();
                    int h = device.getDisplayHeight();
                    if (w > 0 && h > 0) {
                        hideOverlay();
                        device.swipe(w / 2, (h * 3) / 4, w / 2, h / 4, 14);
                        lastShortSwipe = SystemClock.elapsedRealtime();
                        Log.i(TAG, "ACTION swipe sponsored_short");
                        SystemClock.sleep(450);
                        continue;
                    }
                }

                muteIfNeeded();
                showOverlay();
                try { device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD); } catch (Throwable ignored) {}
                SystemClock.sleep(POLL_MS);
            }
        } finally {
            hideOverlay();
            restoreVolumeIfNeeded("helper_exit");
            try { device.executeShellCommand("settings delete global " + VOL_KEY); } catch (Throwable ignored) {}
            Log.i(TAG, "STOP helper_exit");
        }
    }

    private boolean heartbeatAlive() {
        long nowElapsed = SystemClock.elapsedRealtime();
        if (nowElapsed - lastHeartbeatCheck < 3000) return heartbeatOK;
        lastHeartbeatCheck = nowElapsed;
        try {
            String out = device.executeShellCommand("settings get global " + HEARTBEAT_KEY);
            long beat = Long.parseLong(out.trim());
            long now = System.currentTimeMillis() / 1000L;
            heartbeatOK = beat > 0 && Math.abs(now - beat) <= 15L;
        } catch (Throwable t) {
            heartbeatOK = false;
        }
        return heartbeatOK;
    }

    private void showOverlay() {
        if (overlayView != null || instrumentation == null || windowManager == null) return;
        try {
            instrumentation.runOnMainSync(() -> {
                if (overlayView != null) return;
                try {
                    Context ctx = instrumentation.getTargetContext();
                    TextView v = new TextView(ctx);
                    v.setBackgroundColor(Color.BLACK);
                    v.setTextColor(Color.WHITE);
                    v.setTextSize(17f);
                    v.setGravity(Gravity.CENTER);
                    v.setText("Advertisement suppressed\nSAM Ad Shield");
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                            PixelFormat.OPAQUE);
                    lp.gravity = Gravity.TOP | Gravity.START;
                    windowManager.addView(v, lp);
                    overlayView = v;
                    Log.i(TAG, "ACTION cover unskippable_ad");
                } catch (Throwable t) {
                    Log.w(TAG, "ERROR overlay " + sanitize(String.valueOf(t)));
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "ERROR overlay_dispatch " + sanitize(String.valueOf(t)));
        }
    }

    private void hideOverlay() {
        if (overlayView == null || instrumentation == null || windowManager == null) return;
        try {
            instrumentation.runOnMainSync(() -> {
                TextView v = overlayView;
                overlayView = null;
                if (v != null) {
                    try {
                        windowManager.removeViewImmediate(v);
                        Log.i(TAG, "ACTION uncover content");
                    } catch (Throwable t) {
                        Log.w(TAG, "ERROR overlay_remove " + sanitize(String.valueOf(t)));
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "ERROR overlay_remove_dispatch " + sanitize(String.valueOf(t)));
        }
    }

    private String safeCurrentPackage() {
        try {
            String p = device.getCurrentPackageName();
            return p == null ? "" : p;
        } catch (Throwable t) {
            return "";
        }
    }

    private boolean hasAdMarker() {
        if (hasAnyText("Sponsored", "Visit advertiser", "Skip ad", "Skip ads", "Advertisement",
                "Patrocinado", "Visitar anunciante", "Saltar anuncio", "Omitir anuncio", "Publicidad",
                "Gesponsert", "Werbung", "Anzeige überspringen", "Sponsorisé", "Publicité",
                "Sponsorizzato", "Annuncio", "広告", "スキップ", "赞助内容", "广告")) return true;
        String[] ids = {"ad_badge", "ad_attribution", "ad_progress_text", "ad_duration", "ad_info_view",
                "skip_ad_button", "skip_ad_button_text", "modern_skip_ad_button",
                "visit_advertiser_button", "ad_visit_advertiser_button", "ad_overlay"};
        for (String id : ids) {
            try { if (device.hasObject(By.res(YT, id))) return true; } catch (Throwable ignored) {}
        }
        return false;
    }

    private UiObject2 findActionButton() {
        String[] ids = {"skip_ad_button", "skip_ad_button_text", "modern_skip_ad_button", "ad_skip_button",
                "close_ad_button", "ad_overlay_close_button", "close_button", "dismiss_button"};
        for (String id : ids) {
            try {
                UiObject2 o = device.findObject(By.res(YT, id));
                if (usable(o)) return o;
            } catch (Throwable ignored) {}
        }
        String[] phrases = {"Skip ad", "Skip ads", "Skip", "Close ad", "Close", "Dismiss",
                "Saltar anuncio", "Omitir anuncio", "Saltar", "Cerrar anuncio", "Cerrar",
                "Anzeige überspringen", "Schließen", "Passer l'annonce", "Fermer",
                "Salta annuncio", "Chiudi", "スキップ", "閉じる", "跳过广告", "关闭"};
        for (String s : phrases) {
            UiObject2 o = findByTextOrDesc(s);
            if (usable(o)) return o;
        }
        return null;
    }

    private boolean isSponsoredShort() {
        if (!hasAnyText("Sponsored", "Patrocinado", "Gesponsert", "Sponsorisé", "Sponsorizzato")) return false;
        return hasAnyText("Shorts") || hasResourceFragment("reel") || hasResourceFragment("shorts");
    }

    private boolean hasResourceFragment(String fragment) {
        try {
            List<UiObject2> roots = device.findObjects(By.pkg(YT));
            for (UiObject2 o : roots) {
                String r = o.getResourceName();
                if (r != null && r.toLowerCase(Locale.ROOT).contains(fragment)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean hasAnyText(String... values) {
        for (String s : values) if (findByTextOrDesc(s) != null) return true;
        return false;
    }

    private UiObject2 findByTextOrDesc(String s) {
        try {
            UiObject2 o = device.findObject(By.textContains(s));
            if (o != null && belongsToYouTube(o)) return o;
        } catch (Throwable ignored) {}
        try {
            UiObject2 o = device.findObject(By.descContains(s));
            if (o != null && belongsToYouTube(o)) return o;
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean belongsToYouTube(UiObject2 o) {
        try {
            String p = o.getApplicationPackage();
            return p == null || p.isEmpty() || YT.equals(p);
        } catch (Throwable t) { return true; }
    }

    private boolean usable(UiObject2 o) { return o != null && belongsToYouTube(o); }

    private boolean safeClick(UiObject2 o) {
        try { o.click(); return true; }
        catch (Throwable ignored) {
            try {
                android.graphics.Rect b = o.getVisibleBounds();
                if (b != null && b.width() > 0 && b.height() > 0) {
                    device.click(b.centerX(), b.centerY());
                    return true;
                }
            } catch (Throwable ignored2) {}
            return false;
        }
    }

    private String objectLabel(UiObject2 o) {
        try { String t = o.getText(); if (t != null && !t.isEmpty()) return t; } catch (Throwable ignored) {}
        try { String d = o.getContentDescription(); if (d != null && !d.isEmpty()) return d; } catch (Throwable ignored) {}
        try { String r = o.getResourceName(); if (r != null) return r; } catch (Throwable ignored) {}
        return "button";
    }

    private void muteIfNeeded() {
        if (muted) return;
        try {
            String out = device.executeShellCommand("media volume --stream 3 --get");
            originalVolume = parseVolume(out);
            if (originalVolume >= 0) {
                device.executeShellCommand("settings put global " + VOL_KEY + " " + originalVolume);
                device.executeShellCommand("media volume --stream 3 --set 0");
                muted = true;
                Log.i(TAG, "ACTION mute original=" + originalVolume);
            }
        } catch (Throwable t) { Log.w(TAG, "ERROR mute " + sanitize(String.valueOf(t))); }
    }

    private void restoreVolumeIfNeeded(String reason) {
        if (!muted || originalVolume < 0) return;
        try {
            device.executeShellCommand("media volume --stream 3 --set " + originalVolume);
            device.executeShellCommand("settings delete global " + VOL_KEY);
            Log.i(TAG, "ACTION restore_volume value=" + originalVolume + " reason=" + reason);
        } catch (Throwable t) { Log.w(TAG, "ERROR restore " + sanitize(String.valueOf(t))); }
        finally { muted = false; originalVolume = -1; }
    }

    private static int parseVolume(String out) {
        if (out == null) return -1;
        Pattern[] ps = {Pattern.compile("(?i)volume is\\s+(\\d+)"),
                Pattern.compile("(?i)stream volume[^0-9]*(\\d+)"),
                Pattern.compile("(?i)index[^0-9]*(\\d+)")};
        for (Pattern p : ps) {
            Matcher m = p.matcher(out);
            if (m.find()) try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
        }
        return -1;
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.length() > 160 ? s.substring(0, 160) : s;
    }
}
