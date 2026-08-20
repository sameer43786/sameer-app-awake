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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Unified launcher for Sameer App Awake.
 *
 * <p>Normal-space app protection and the Private Space Protected Browser now live
 * behind one application icon. The two functions remain technically separate so
 * each uses the Android mechanism that is reliable for that use case.</p>
 *
 * <p>By: Sameer Ali | Contact: sameer43786@gmail.com</p>
 */
public final class HomeActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureSystemBars();
        setContentView(buildUi());
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

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.app_background));

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
            view.setPadding(dp(18) + left, dp(14) + top, dp(18) + right, dp(24) + bottom);
            return insets;
        });

        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher_foreground);
        logo.setPadding(dp(3), dp(3), dp(3), dp(3));
        logo.setBackground(rounded(getColor(R.color.brand_primary), dp(16), Color.TRANSPARENT, 0));
        header.addView(logo, new LinearLayout.LayoutParams(dp(58), dp(58)));

        LinearLayout titleColumn = new LinearLayout(this);
        titleColumn.setOrientation(LinearLayout.VERTICAL);
        titleColumn.setPadding(dp(14), 0, 0, 0);
        header.addView(titleColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = text("Sameer App Awake", 24, getColor(R.color.text_primary), true);
        titleColumn.addView(title);

        TextView version = text("Version " + versionName(), 12, getColor(R.color.text_secondary), false);
        titleColumn.addView(version);

        root.addView(header);

        TextView branding = text("By: Sameer Ali | Contact: sameer43786@gmail.com", 11,
                getColor(R.color.brand_primary), true);
        branding.setPadding(dp(2), dp(9), 0, dp(14));
        root.addView(branding);

        TextView intro = text(
                "One app, two protection modes. Use App Protection for Google Maps and other Android apps. "
                        + "Use Protected Browser for websites inside Private Space.",
                14,
                getColor(R.color.text_secondary),
                false
        );
        intro.setLineSpacing(0, 1.12f);
        intro.setPadding(dp(2), 0, dp(2), dp(10));
        root.addView(intro);

        root.addView(buildModeCard(
                "App Protection",
                "Normal Space and Private Space app monitoring. Keep the display awake only while a selected app, such as Google Maps, is active.",
                "Manage protected apps",
                () -> startActivity(new Intent(this, MainActivity.class))
        ));

        root.addView(buildModeCard(
                "Protected Browser",
                "For your Private Space web session. The browser belongs to Sameer App Awake, so Android's supported keep-screen-on flag stays active for as long as this browser window is visible.",
                "Open Protected Browser",
                () -> startActivity(new Intent(this, ProtectedBrowserActivity.class))
        ));

        LinearLayout note = card();
        TextView noteTitle = text("Private Space note", 15, getColor(R.color.text_primary), true);
        note.addView(noteTitle);
        TextView noteText = text(
                "Your Private Space password remains enabled. If the device is allowed to lock, Android stops the entire Private Space profile. "
                        + "Protected Browser prevents normal inactivity timeout while its window is visible; leaving it restores normal timeout.",
                12,
                getColor(R.color.text_secondary),
                false
        );
        noteText.setPadding(0, dp(7), 0, 0);
        note.addView(noteText);
        root.addView(note);

        return scroll;
    }

    private View buildModeCard(String title, String detail, String buttonLabel, Runnable action) {
        LinearLayout card = card();

        TextView heading = text(title, 18, getColor(R.color.text_primary), true);
        card.addView(heading);

        TextView body = text(detail, 13, getColor(R.color.text_secondary), false);
        body.setPadding(0, dp(7), 0, dp(12));
        body.setLineSpacing(0, 1.12f);
        card.addView(body);

        Button button = new Button(this);
        button.setText(buttonLabel);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(getColor(R.color.white));
        button.setBackground(rounded(getColor(R.color.brand_primary), dp(12), Color.TRANSPARENT, 0));
        button.setOnClickListener(v -> action.run());
        card.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        ));

        return card;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        card.setBackground(rounded(
                getColor(R.color.surface),
                dp(18),
                getColor(R.color.border),
                dp(1)
        ));
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

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "";
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
