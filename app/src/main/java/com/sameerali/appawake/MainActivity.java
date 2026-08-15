package com.sameerali.appawake;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main configuration screen for Sameer App Awake.
 *
 * <p>By: Sameer Ali | Contact: sameer43786@gmail.com</p>
 */
@SuppressLint({"SetTextI18n", "UnspecifiedRegisterReceiverFlag"})
public final class MainActivity extends Activity {

    private static final int REQUEST_NOTIFICATIONS = 2401;

    private final ExecutorService appLoader = Executors.newSingleThreadExecutor();

    private AppListAdapter appListAdapter;
    private Switch monitoringSwitch;
    private TextView statusBadge;
    private TextView statusTitle;
    private TextView statusDetail;
    private TextView foregroundText;
    private TextView selectionTitle;
    private TextView emptyView;
    private Button usageAccessButton;
    private EditText searchInput;

    private boolean updatingSwitch;
    private boolean pendingEnableAfterUsageAccess;
    private boolean receiverRegistered;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshStatusUi();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureSystemBars();
        setContentView(buildInterface());
        loadInstalledApplications();
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

    private View buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.app_background));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets systemBars = insets.getInsets(WindowInsets.Type.systemBars());
                left = systemBars.left;
                top = systemBars.top;
                right = systemBars.right;
                bottom = systemBars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, top, right, bottom);
            return insets;
        });

        root.addView(buildHeader());
        root.addView(buildStatusCard());
        root.addView(buildMonitoringCard());
        root.addView(buildUsageAccessCard());
        root.addView(buildApplicationToolbar());
        root.addView(buildSearchInput());

        FrameLayout listContainer = new FrameLayout(this);
        LinearLayout.LayoutParams listContainerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
        listContainerParams.setMargins(dp(16), dp(6), dp(16), 0);
        listContainer.setLayoutParams(listContainerParams);
        listContainer.setBackground(roundedDrawable(
                getColor(R.color.surface),
                dp(18),
                getColor(R.color.border),
                dp(1)
        ));
        listContainer.setClipToOutline(true);

        ListView appList = new ListView(this);
        appList.setClipToOutline(true);
        appList.setDividerHeight(dp(1));
        appList.setDivider(new android.graphics.drawable.ColorDrawable(getColor(R.color.border)));
        appList.setFastScrollEnabled(true);
        appList.setVerticalScrollBarEnabled(true);
        appListAdapter = new AppListAdapter(this, this::onApplicationSelectionChanged);
        appList.setAdapter(appListAdapter);
        appList.setOnItemClickListener((parent, view, position, id) -> appListAdapter.toggle(position));
        listContainer.addView(appList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        emptyView = new TextView(this);
        emptyView.setText("Loading installed apps…");
        emptyView.setTextColor(getColor(R.color.text_secondary));
        emptyView.setTextSize(15);
        emptyView.setGravity(Gravity.CENTER);
        listContainer.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        appList.setEmptyView(emptyView);
        root.addView(listContainer);

        TextView footer = new TextView(this);
        footer.setText("Private by design: app names only, no screen content and no internet access.");
        footer.setTextColor(getColor(R.color.text_secondary));
        footer.setTextSize(11);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(16), dp(8), dp(16), dp(10));
        root.addView(footer);

        return root;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(18), dp(12), dp(18), dp(8));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher_foreground);
        logo.setPadding(dp(2), dp(2), dp(2), dp(2));
        logo.setBackground(roundedDrawable(
                getColor(R.color.brand_primary),
                dp(15),
                Color.TRANSPARENT,
                0
        ));
        topRow.addView(logo, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout titleColumn = new LinearLayout(this);
        titleColumn.setOrientation(LinearLayout.VERTICAL);
        titleColumn.setPadding(dp(13), 0, dp(8), 0);
        topRow.addView(titleColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText("Sameer App Awake");
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleColumn.addView(title);

        TextView version = new TextView(this);
        version.setText("Version " + versionName());
        version.setTextColor(getColor(R.color.text_secondary));
        version.setTextSize(12);
        titleColumn.addView(version);

        Button infoButton = compactButton("Info", false);
        infoButton.setOnClickListener(view -> showDiagnostics());
        topRow.addView(infoButton, new LinearLayout.LayoutParams(dp(64), dp(40)));
        header.addView(topRow);

        TextView branding = new TextView(this);
        branding.setText("By: Sameer Ali | Contact: sameer43786@gmail.com");
        branding.setTextColor(getColor(R.color.brand_primary));
        branding.setTextSize(11);
        branding.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        branding.setPadding(dp(2), dp(7), 0, 0);
        header.addView(branding);
        return header;
    }

    private View buildStatusCard() {
        LinearLayout card = verticalCard(dp(16), dp(6), dp(16), dp(6));
        card.setPadding(dp(16), dp(13), dp(16), dp(13));
        card.setBackground(roundedDrawable(
                getColor(R.color.surface_muted),
                dp(18),
                getColor(R.color.border),
                dp(1)
        ));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        statusBadge = new TextView(this);
        statusBadge.setGravity(Gravity.CENTER);
        statusBadge.setTextSize(11);
        statusBadge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusBadge.setPadding(dp(10), dp(5), dp(10), dp(5));
        row.addView(statusBadge);

        statusTitle = new TextView(this);
        statusTitle.setTextColor(getColor(R.color.text_primary));
        statusTitle.setTextSize(17);
        statusTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams statusTitleParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        statusTitleParams.setMargins(dp(10), 0, 0, 0);
        row.addView(statusTitle, statusTitleParams);
        card.addView(row);

        statusDetail = new TextView(this);
        statusDetail.setTextColor(getColor(R.color.text_secondary));
        statusDetail.setTextSize(13);
        statusDetail.setPadding(0, dp(7), 0, 0);
        card.addView(statusDetail);

        foregroundText = new TextView(this);
        foregroundText.setTextColor(getColor(R.color.text_secondary));
        foregroundText.setTextSize(12);
        foregroundText.setPadding(0, dp(4), 0, 0);
        card.addView(foregroundText);
        return card;
    }

    private View buildMonitoringCard() {
        LinearLayout card = horizontalCard(dp(16), dp(5), dp(16), dp(5));
        card.setPadding(dp(16), dp(9), dp(10), dp(9));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        card.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView label = new TextView(this);
        label.setText("Protect selected apps");
        label.setTextColor(getColor(R.color.text_primary));
        label.setTextSize(16);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        labels.addView(label);

        TextView explanation = new TextView(this);
        explanation.setText("The screen stays on only while a selected app is active.");
        explanation.setTextColor(getColor(R.color.text_secondary));
        explanation.setTextSize(12);
        labels.addView(explanation);

        monitoringSwitch = new Switch(this);
        monitoringSwitch.setContentDescription("Enable app-aware display protection");
        monitoringSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (updatingSwitch) {
                return;
            }
            if (checked) {
                beginEnableMonitoring();
            } else {
                disableMonitoring();
            }
        });
        card.addView(monitoringSwitch, new LinearLayout.LayoutParams(dp(64), dp(48)));
        return card;
    }

    private View buildUsageAccessCard() {
        LinearLayout card = horizontalCard(dp(16), dp(5), dp(16), dp(5));
        card.setPadding(dp(16), dp(10), dp(10), dp(10));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        card.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView label = new TextView(this);
        label.setText("Usage Access");
        label.setTextColor(getColor(R.color.text_primary));
        label.setTextSize(15);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        labels.addView(label);

        TextView explanation = new TextView(this);
        explanation.setText("Required to identify the active app. No content is read.");
        explanation.setTextColor(getColor(R.color.text_secondary));
        explanation.setTextSize(11);
        labels.addView(explanation);

        usageAccessButton = compactButton("Grant", true);
        usageAccessButton.setOnClickListener(view -> {
            pendingEnableAfterUsageAccess = false;
            PermissionUtils.openUsageAccessSettings(this);
        });
        card.addView(usageAccessButton, new LinearLayout.LayoutParams(dp(96), dp(42)));
        return card;
    }

    private View buildApplicationToolbar() {
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(18), dp(11), dp(18), dp(2));

        selectionTitle = new TextView(this);
        selectionTitle.setTextColor(getColor(R.color.text_primary));
        selectionTitle.setTextSize(16);
        selectionTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        toolbar.addView(selectionTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button clearButton = compactButton("Clear", false);
        clearButton.setOnClickListener(view -> confirmClearSelection());
        toolbar.addView(clearButton, new LinearLayout.LayoutParams(dp(76), dp(38)));
        return toolbar;
    }

    private View buildSearchInput() {
        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint("Search installed apps or package names");
        searchInput.setTextColor(getColor(R.color.text_primary));
        searchInput.setHintTextColor(getColor(R.color.text_secondary));
        searchInput.setTextSize(14);
        searchInput.setPadding(dp(15), 0, dp(15), 0);
        searchInput.setBackground(roundedDrawable(
                getColor(R.color.surface),
                dp(14),
                getColor(R.color.border),
                dp(1)
        ));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        );
        params.setMargins(dp(16), dp(6), dp(16), dp(4));
        searchInput.setLayoutParams(params);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // No pre-change action is needed.
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (appListAdapter != null) {
                    appListAdapter.filter(s == null ? "" : s.toString());
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                // Filtering already occurs in onTextChanged.
            }
        });
        return searchInput;
    }

    private LinearLayout verticalCard(int left, int top, int right, int bottom) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedDrawable(
                getColor(R.color.surface),
                dp(18),
                getColor(R.color.border),
                dp(1)
        ));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(left, top, right, bottom);
        card.setLayoutParams(params);
        return card;
    }

    private LinearLayout horizontalCard(int left, int top, int right, int bottom) {
        LinearLayout card = verticalCard(left, top, right, bottom);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        return card;
    }

    private Button compactButton(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? getColor(R.color.white) : getColor(R.color.brand_primary));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(roundedDrawable(
                primary ? getColor(R.color.brand_primary) : getColor(R.color.surface),
                dp(12),
                getColor(R.color.brand_primary),
                dp(1)
        ));
        return button;
    }

    private GradientDrawable roundedDrawable(int fillColor, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) {
            drawable.setStroke(strokeWidth, strokeColor);
        }
        return drawable;
    }

    private void beginEnableMonitoring() {
        Set<String> selected = AppPreferences.selectedPackages(this);
        if (selected.isEmpty()) {
            toast("Select at least one app first.");
            setMonitoringSwitch(false);
            return;
        }
        if (!PermissionUtils.hasUsageAccess(this)) {
            pendingEnableAfterUsageAccess = true;
            setMonitoringSwitch(false);
            toast("Grant Usage Access, then return here.");
            PermissionUtils.openUsageAccessSettings(this);
            return;
        }
        requestNotificationPermissionThenStart();
    }

    private void requestNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !PermissionUtils.hasNotificationPermission(this)) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS
            );
            return;
        }
        enableMonitoringNow();
    }

    private void enableMonitoringNow() {
        if (!PermissionUtils.hasUsageAccess(this)
                || AppPreferences.selectedPackages(this).isEmpty()) {
            setMonitoringSwitch(false);
            refreshStatusUi();
            return;
        }
        AppPreferences.setMonitoringEnabled(this, true);
        Intent serviceIntent = new Intent(this, AppMonitorService.class)
                .setAction(AppMonitorService.ACTION_START);
        try {
            startForegroundService(serviceIntent);
            setMonitoringSwitch(true);
            refreshStatusUi();
        } catch (RuntimeException error) {
            AppPreferences.setMonitoringEnabled(this, false);
            AppPreferences.get(this).edit()
                    .putString(AppPreferences.KEY_LAST_ERROR,
                            "Start failed: " + error.getClass().getSimpleName())
                    .apply();
            setMonitoringSwitch(false);
            toast("Android did not allow monitoring to start. Open Info for diagnostics.");
        }
    }

    private void disableMonitoring() {
        pendingEnableAfterUsageAccess = false;
        AppPreferences.setMonitoringEnabled(this, false);
        stopService(new Intent(this, AppMonitorService.class));
        AppPreferences.get(this).edit()
                .putBoolean(AppPreferences.KEY_SERVICE_RUNNING, false)
                .putString(AppPreferences.KEY_PROTECTED_PACKAGE, "")
                .apply();
        setMonitoringSwitch(false);
        refreshStatusUi();
    }

    private void onApplicationSelectionChanged(String packageName, boolean selected) {
        int selectedCount = AppPreferences.selectedPackages(this).size();
        if (selectedCount == 0 && AppPreferences.monitoringEnabled(this)) {
            disableMonitoring();
            toast("Monitoring stopped because no apps remain selected.");
        } else if (AppPreferences.serviceRunning(this)) {
            startService(new Intent(this, AppMonitorService.class)
                    .setAction(AppMonitorService.ACTION_REFRESH));
        }
        refreshSelectionTitle();
        refreshStatusUi();
    }

    private void confirmClearSelection() {
        if (AppPreferences.selectedPackages(this).isEmpty()) {
            toast("No apps are selected.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Clear selected apps?")
                .setMessage("This also stops monitoring.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (dialog, which) -> {
                    AppPreferences.clearSelectedPackages(this);
                    disableMonitoring();
                    appListAdapter.refreshSelections();
                    refreshSelectionTitle();
                })
                .show();
    }

    @SuppressWarnings("deprecation")
    private void loadInstalledApplications() {
        appLoader.execute(() -> {
            PackageManager packageManager = getPackageManager();
            Intent launcherIntent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolvedApps = packageManager.queryIntentActivities(launcherIntent, 0);
            Map<String, AppEntry> uniqueApps = new LinkedHashMap<>();

            for (ResolveInfo resolved : resolvedApps) {
                if (resolved.activityInfo == null || resolved.activityInfo.applicationInfo == null) {
                    continue;
                }
                String packageName = resolved.activityInfo.packageName;
                if (getPackageName().equals(packageName) || uniqueApps.containsKey(packageName)) {
                    continue;
                }
                CharSequence loadedLabel = resolved.loadLabel(packageManager);
                String label = loadedLabel == null ? packageName : loadedLabel.toString();
                uniqueApps.put(packageName, new AppEntry(
                        label,
                        packageName,
                        resolved.loadIcon(packageManager)
                ));
            }

            List<AppEntry> apps = new ArrayList<>(uniqueApps.values());
            Collator collator = Collator.getInstance(Locale.getDefault());
            apps.sort((first, second) -> collator.compare(first.label, second.label));
            runOnUiThread(() -> {
                appListAdapter.setApps(apps);
                appListAdapter.filter(searchInput.getText().toString());
                emptyView.setText(apps.isEmpty()
                        ? "No launchable apps were found."
                        : "No apps match this search.");
                refreshSelectionTitle();
            });
        });
    }

    private void refreshUsageAccessUi() {
        boolean granted = PermissionUtils.hasUsageAccess(this);
        usageAccessButton.setText(granted ? "Granted ✓" : "Grant");
        usageAccessButton.setTextColor(granted
                ? getColor(R.color.success)
                : getColor(R.color.white));
        usageAccessButton.setBackground(roundedDrawable(
                granted ? getColor(R.color.surface) : getColor(R.color.brand_primary),
                dp(12),
                granted ? getColor(R.color.success) : getColor(R.color.brand_primary),
                dp(1)
        ));
    }

    private void refreshSelectionTitle() {
        int count = AppPreferences.selectedPackages(this).size();
        selectionTitle.setText("Protected apps (" + count + ")");
    }

    private void refreshStatusUi() {
        boolean usageGranted = PermissionUtils.hasUsageAccess(this);
        boolean enabled = AppPreferences.monitoringEnabled(this);
        boolean running = AppPreferences.serviceRunning(this);
        String foregroundPackage = AppPreferences.get(this)
                .getString(AppPreferences.KEY_LAST_FOREGROUND_PACKAGE, "");
        String protectedPackage = AppPreferences.get(this)
                .getString(AppPreferences.KEY_PROTECTED_PACKAGE, "");
        int selectedCount = AppPreferences.selectedPackages(this).size();

        if (!usageGranted) {
            setBadge("SETUP", getColor(R.color.warning));
            statusTitle.setText("Usage Access is required");
            statusDetail.setText("Grant access once so Android can report which app is active.");
        } else if (enabled && running && protectedPackage != null && !protectedPackage.isEmpty()) {
            setBadge("AWAKE", getColor(R.color.success));
            statusTitle.setText("Screen protection is active");
            statusDetail.setText("Keeping the display awake for "
                    + PermissionUtils.appLabel(this, protectedPackage) + ".");
        } else if (enabled && running) {
            setBadge("ON", getColor(R.color.brand_primary));
            statusTitle.setText("Monitoring is active");
            statusDetail.setText("Watching " + selectedCount
                    + " selected app(s). Open one to keep the screen awake.");
        } else if (enabled) {
            setBadge("STARTING", getColor(R.color.brand_primary));
            statusTitle.setText("Monitoring is starting");
            statusDetail.setText("Android is preparing the foreground service.");
        } else {
            setBadge("OFF", getColor(R.color.text_secondary));
            statusTitle.setText("Monitoring is off");
            statusDetail.setText("Select apps, then enable Protect selected apps.");
        }

        if (running && foregroundPackage != null && !foregroundPackage.isEmpty()) {
            foregroundText.setText("Detected foreground app: "
                    + PermissionUtils.appLabel(this, foregroundPackage));
        } else {
            foregroundText.setText("Detection runs locally and does not inspect app content.");
        }

        setMonitoringSwitch(enabled);
        refreshSelectionTitle();
        refreshUsageAccessUi();
    }

    private void setBadge(String text, int color) {
        statusBadge.setText("●  " + text);
        statusBadge.setTextColor(color);
        statusBadge.setBackground(roundedDrawable(
                getColor(R.color.surface),
                dp(20),
                color,
                dp(1)
        ));
    }

    private void setMonitoringSwitch(boolean checked) {
        updatingSwitch = true;
        monitoringSwitch.setChecked(checked);
        updatingSwitch = false;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(AppMonitorService.ACTION_STATUS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(statusReceiver, filter);
            }
            receiverRegistered = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatusUi();

        if (pendingEnableAfterUsageAccess && PermissionUtils.hasUsageAccess(this)) {
            pendingEnableAfterUsageAccess = false;
            requestNotificationPermissionThenStart();
            return;
        }

        // Recover a service that Android stopped while the user's enabled preference remained set.
        if (AppPreferences.monitoringEnabled(this)
                && PermissionUtils.hasUsageAccess(this)
                && !AppPreferences.selectedPackages(this).isEmpty()) {
            enableMonitoringNow();
        }
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        appLoader.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            if (!PermissionUtils.hasNotificationPermission(this)) {
                toast("Monitoring will run, but Android may hide its notification.");
            }
            enableMonitoringNow();
        }
    }

    private void showDiagnostics() {
        String foregroundPackage = AppPreferences.get(this)
                .getString(AppPreferences.KEY_LAST_FOREGROUND_PACKAGE, "");
        String protectedPackage = AppPreferences.get(this)
                .getString(AppPreferences.KEY_PROTECTED_PACKAGE, "");
        String lastError = AppPreferences.get(this)
                .getString(AppPreferences.KEY_LAST_ERROR, "");
        String diagnostics = "Sameer App Awake diagnostics\n"
                + "Version: " + versionName() + "\n"
                + "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "Usage Access: " + yesNo(PermissionUtils.hasUsageAccess(this)) + "\n"
                + "Notification permission: " + yesNo(PermissionUtils.hasNotificationPermission(this)) + "\n"
                + "Monitoring enabled: " + yesNo(AppPreferences.monitoringEnabled(this)) + "\n"
                + "Service running: " + yesNo(AppPreferences.serviceRunning(this)) + "\n"
                + "Selected apps: " + AppPreferences.selectedPackages(this).size() + "\n"
                + "Foreground package: " + emptyAsNone(foregroundPackage) + "\n"
                + "Protected package: " + emptyAsNone(protectedPackage) + "\n"
                + "Last error: " + emptyAsNone(lastError) + "\n\n"
                + "By: Sameer Ali | Contact: sameer43786@gmail.com";

        new AlertDialog.Builder(this)
                .setTitle("App information and diagnostics")
                .setMessage(diagnostics)
                .setNegativeButton("Close", null)
                .setNeutralButton("Notification settings", (dialog, which) -> openNotificationSettings())
                .setPositiveButton("Copy", (dialog, which) -> copyDiagnostics(diagnostics))
                .show();
    }

    private void copyDiagnostics(String diagnostics) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Sameer App Awake diagnostics", diagnostics));
            toast("Diagnostics copied.");
        }
    }

    private void openNotificationSettings() {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        startActivity(intent);
    }

    @SuppressWarnings("deprecation")
    private String versionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "1.0.0" : info.versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            return "1.0.0";
        }
    }

    private String yesNo(boolean value) {
        return value ? "Yes" : "No";
    }

    private String emptyAsNone(String value) {
        return value == null || value.trim().isEmpty() ? "None" : value;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
