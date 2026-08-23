package com.sameerali.appawake;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * One-tap fail-safe launcher for Pixel Private Space.
 *
 * <p>The activity arms the App Awake foreground service and display wake lock before Chrome
 * is launched. This avoids depending on Accessibility, which Android Advanced Protection
 * intentionally restricts for unverified accessibility tools.</p>
 *
 * <p>By: Sameer Ali | Contact: sameer43786@gmail.com</p>
 */
public final class ProtectedChromeActivity extends Activity {

    private static final String CHROME_PACKAGE = "com.android.chrome";
    private static final long LAUNCH_DELAY_MS = 300L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent chromeIntent = getPackageManager().getLaunchIntentForPackage(CHROME_PACKAGE);
        if (chromeIntent == null) {
            Toast.makeText(this,
                    "Chrome is not installed in this Android profile.",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (!PermissionUtils.hasUsageAccess(this)) {
            Toast.makeText(this,
                    "Grant Usage Access to Sameer App Awake first, then tap Protected Chrome again.",
                    Toast.LENGTH_LONG).show();
            PermissionUtils.openUsageAccessSettings(this);
            finish();
            return;
        }

        AppPreferences.setPackageSelected(this, CHROME_PACKAGE, true);
        AppPreferences.setMonitoringEnabled(this, true);

        Intent armIntent = new Intent(this, AppMonitorService.class)
                .setAction(AppMonitorService.ACTION_ARM_PACKAGE)
                .putExtra(AppMonitorService.EXTRA_ARM_PACKAGE, CHROME_PACKAGE);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(armIntent);
            } else {
                startService(armIntent);
            }
        } catch (RuntimeException error) {
            AppPreferences.get(this).edit()
                    .putString(AppPreferences.KEY_LAST_ERROR,
                            "Protected Chrome start failed: " + error.getClass().getSimpleName())
                    .apply();
            Toast.makeText(this,
                    "Android did not allow the protection service to start.",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Give the foreground service a short head start so the bright display wake lock is
        // acquired before Chrome becomes the visible app.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                chromeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(chromeIntent);
            } catch (RuntimeException error) {
                Toast.makeText(this,
                        "Chrome could not be opened in this profile.",
                        Toast.LENGTH_LONG).show();
            } finally {
                finish();
            }
        }, LAUNCH_DELAY_MS);
    }
}
