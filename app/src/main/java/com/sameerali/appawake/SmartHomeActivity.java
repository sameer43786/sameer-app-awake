package com.sameerali.appawake;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Set;

/**
 * Single launcher for Sameer App Awake Smart Guard v2.
 *
 * <p>Each Android profile receives its own copy of this app's data. The user therefore
 * configures the main-space copy once for external-app guarding and the Private-Space
 * copy once for a protected web session. After that first setup, the same launcher icon
 * automatically opens the correct experience. There are no permanent "two mode" buttons.</p>
 *
 * <p>By: Sameer Ali | Contact: sameer43786@gmail.com</p>
 */
public final class SmartHomeActivity extends Activity {

    public static final String EXTRA_CONFIGURE = "configure_mode";

    private boolean waitingForWriteSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureSystemBars();

        boolean forceConfigure = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_CONFIGURE, false);

        if (!forceConfigure) {
            migrateExistingConfiguration();
            if (routeConfiguredMode()) {
                return;
            }
        }

        setContentView(buildSetupUi());
    }

    private void migrateExistingConfiguration() {
        if (!AppPreferences.smartMode(this).isEmpty()) {
            return;
        }

        Set<String> selected = AppPreferences.selectedPackages(this);
        if (!selected.isEmpty()) {
            AppPreferences.setSmartMode(this, AppPreferences.MODE_APP);
            return;
        }

        String lastUrl = getSharedPreferences("protected_browser_preferences", MODE_PRIVATE)
                .getString("last_url", "");
        if (lastUrl != null
                && !lastUrl.trim().isEmpty()
                && !lastUrl.contains("google.com/?hl=en")) {
            AppPreferences.setSmartMode(this, AppPreferences.MODE_WEB);
        }
    }

    /** Returns true when navigation has been started and this Activity can finish. */
    private boolean routeConfiguredMode() {
        String mode = AppPreferences.smartMode(this);
        if (AppPreferences.MODE_WEB.equals(mode)) {
            startActivity(new Intent(this, ProtectedBrowserActivity.class));
            finish();
            return true;
        }

        if (AppPreferences.MODE_APP.equals(mode)) {
            if (!TimeoutLeaseGuard.canWrite(this)) {
                setContentView(buildWriteSettingsUi());
                return true;
            }
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return true;
        }

        return false;
    }

    private View buildSetupUi() {
        ScrollView scroll = baseScroll();
        LinearLayout root = baseRoot();
        scroll.addView(root);

        addHeader(root, "Smart Guard setup");

        TextView intro = text(
                "Configure this Android profile once. The same Sameer App Awake APK can then "
                        + "behave differently in Main Space and Private Space because Android keeps "
                        + "their app data separate.",
                14,
                getColor(R.color.text_secondary),
                false
        );
        intro.setPadding(0, 0, 0, dp(16));
        root.addView(intro);

        LinearLayout card = card();
        TextView question = text("What should this copy keep awake?", 18,
                getColor(R.color.text_primary), true);
        card.addView(question);

        RadioGroup modes = new RadioGroup(this);
        modes.setOrientation(RadioGroup.VERTICAL);
        modes.setPadding(0, dp(10), 0, dp(6));

        RadioButton appMode = radio(
                "An Android app",
                "Best for Google Maps and other apps. Smart Guard temporarily extends the "
                        + "system screen timeout only while the selected app is foreground, then "
                        + "restores your normal timeout automatically."
        );
        appMode.setId(View.generateViewId());
        modes.addView(appMode);

        RadioButton webMode = radio(
                "A website",
                "Best for Private Space. The protected web session runs inside Sameer App Awake "
                        + "and owns the visible window, so Android's supported keep-screen-on flag "
                        + "remains active until you leave or close it."
        );
        webMode.setId(View.generateViewId());
        modes.addView(webMode);
        card.addView(modes);

        Button continueButton = primaryButton("Continue");
        continueButton.setOnClickListener(v -> {
            int checked = modes.getCheckedRadioButtonId();
            if (checked == appMode.getId()) {
                AppPreferences.setSmartMode(this, AppPreferences.MODE_APP);
                if (!TimeoutLeaseGuard.canWrite(this)) {
                    setContentView(buildWriteSettingsUi());
                } else {
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                }
            } else if (checked == webMode.getId()) {
                AppPreferences.setSmartMode(this, AppPreferences.MODE_WEB);
                startActivity(new Intent(this, ProtectedBrowserActivity.class));
                finish();
            } else {
                android.widget.Toast.makeText(this,
                        "Choose what this profile should protect first.",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        card.addView(continueButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
        ));
        root.addView(card);

        LinearLayout note = card();
        note.addView(text("Why this redesign is more reliable", 16,
                getColor(R.color.text_primary), true));
        TextView noteBody = text(
                "Website mode no longer depends on detecting another browser after the fact. "
                        + "External-app mode no longer relies only on deprecated display wake-lock "
                        + "levels: with your approval, it temporarily controls Android's own screen "
                        + "timeout and uses the wake lock only as a backup. A watchdog restores your "
                        + "normal timeout if monitoring ever stops unexpectedly.",
                13,
                getColor(R.color.text_secondary),
                false
        );
        noteBody.setPadding(0, dp(8), 0, 0);
        note.addView(noteBody);
        root.addView(note);

        return scroll;
    }

    private View buildWriteSettingsUi() {
        ScrollView scroll = baseScroll();
        LinearLayout root = baseRoot();
        scroll.addView(root);
        addHeader(root, "One-time reliability permission");

        LinearLayout card = card();
        card.addView(text("Allow Sameer App Awake to modify system settings", 18,
                getColor(R.color.text_primary), true));
        TextView detail = text(
                "For external apps such as Google Maps, Android does not let one app place "
                        + "FLAG_KEEP_SCREEN_ON on another app's window. Smart Guard therefore uses "
                        + "Android's SCREEN_OFF_TIMEOUT setting while your selected app is active. "
                        + "Your original timeout is restored automatically when you leave that app.",
                13,
                getColor(R.color.text_secondary),
                false
        );
        detail.setPadding(0, dp(8), 0, dp(12));
        card.addView(detail);

        Button allow = primaryButton("Allow reliable screen control");
        allow.setOnClickListener(v -> {
            waitingForWriteSettings = true;
            try {
                startActivity(TimeoutLeaseGuard.permissionIntent(this));
            } catch (RuntimeException error) {
                android.widget.Toast.makeText(this,
                        "Android could not open the Modify system settings screen.",
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });
        card.addView(allow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
        ));
        root.addView(card);

        Button change = secondaryButton("Change protection type");
        change.setOnClickListener(v -> {
            AppPreferences.clearSmartMode(this);
            waitingForWriteSettings = false;
            setContentView(buildSetupUi());
        });
        LinearLayout.LayoutParams changeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        );
        changeParams.setMargins(0, dp(10), 0, 0);
        root.addView(change, changeParams);
        return scroll;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitingForWriteSettings && TimeoutLeaseGuard.canWrite(this)) {
            waitingForWriteSettings = false;
            startActivity(new Intent(this, MainActivity.class));
            finish();
        }
    }

    private void configureSystemBars() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(getColor(R.color.app_background));
        boolean darkMode = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int flags = getWindow().getDecorView().getSystemUiVisibility();
        if (!darkMode) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private ScrollView baseScroll() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.app_background));
        return scroll;
    }

    private LinearLayout baseRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(24));
        root.setBackgroundColor(getColor(R.color.app_background));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(dp(18) + left, dp(14) + top,
                    dp(18) + right, dp(24) + bottom);
            return insets;
        });
        return root;
    }

    private void addHeader(LinearLayout root, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher_foreground);
        logo.setPadding(dp(3), dp(3), dp(3), dp(3));
        logo.setBackground(rounded(getColor(R.color.brand_primary), dp(16),
                Color.TRANSPARENT, 0));
        row.addView(logo, new LinearLayout.LayoutParams(dp(58), dp(58)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(14), 0, 0, 0);
        row.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titles.addView(text("Sameer App Awake", 24,
                getColor(R.color.text_primary), true));
        titles.addView(text(subtitle, 13,
                getColor(R.color.text_secondary), false));
        root.addView(row);

        TextView branding = text("By: Sameer Ali | Contact: sameer43786@gmail.com", 11,
                getColor(R.color.brand_primary), true);
        branding.setPadding(dp(2), dp(9), 0, dp(14));
        root.addView(branding);
    }

    private RadioButton radio(String title, String detail) {
        RadioButton button = new RadioButton(this);
        button.setText(title + "\n" + detail);
        button.setTextColor(getColor(R.color.text_primary));
        button.setTextSize(14);
        button.setPadding(dp(4), dp(8), dp(4), dp(8));
        button.setLineSpacing(0, 1.08f);
        return button;
    }

    private Button primaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(getColor(R.color.white));
        button.setBackground(rounded(getColor(R.color.brand_primary), dp(12),
                Color.TRANSPARENT, 0));
        return button;
    }

    private Button secondaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(getColor(R.color.brand_primary));
        button.setBackground(rounded(getColor(R.color.surface), dp(12),
                getColor(R.color.brand_primary), dp(1)));
        return button;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        card.setBackground(rounded(getColor(R.color.surface), dp(18),
                getColor(R.color.border), dp(1)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(7), 0, dp(7));
        card.setLayoutParams(params);
        return card;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private GradientDrawable rounded(int fill, int radius, int stroke, int width) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        if (width > 0) {
            drawable.setStroke(width, stroke);
        }
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
