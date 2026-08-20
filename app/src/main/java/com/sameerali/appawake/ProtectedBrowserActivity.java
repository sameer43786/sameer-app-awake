package com.sameerali.appawake;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.LocaleList;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Full-screen protected browser owned by Sameer App Awake.
 *
 * <p>The WebView occupies the full usable display. Browser controls are an overlay
 * that auto-hides after navigation so chat/message fields get the same practical
 * screen area as a normal mobile browser. A small floating handle reveals the
 * controls whenever the user needs Back, Reload, URL entry, or Close.</p>
 *
 * <p>FLAG_KEEP_SCREEN_ON is applied to this visible Activity. Android's normal
 * inactivity timeout therefore remains suppressed while this browser is visible
 * and resumes as soon as the Activity leaves the foreground.</p>
 *
 * <p>By: Sameer Ali | Contact: sameer43786@gmail.com</p>
 */
public final class ProtectedBrowserActivity extends Activity {

    private static final String PREFS = "protected_browser_preferences";
    private static final String KEY_LAST_URL = "last_url";
    private static final String DEFAULT_URL = "https://www.google.com/?hl=en";
    private static final long TOOLBAR_AUTO_HIDE_MS = 3200L;

    private static final Map<String, String> ENGLISH_HEADERS = new HashMap<>();

    static {
        ENGLISH_HEADERS.put("Accept-Language", "en-US,en;q=0.9");
    }

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private WebView webView;
    private EditText addressBar;
    private LinearLayout toolbarPanel;
    private TextView revealHandle;

    private final Runnable hideToolbarRunnable = this::hideToolbar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        forceEnglishLocale();
        super.onCreate(savedInstanceState);
        configureSystemBars();

        // Keep message fields visible when the keyboard is open.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        // Supported Android mechanism for the visible protected browser window.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        buildUi();
        configureWebView();

        String initialUrl = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_LAST_URL, DEFAULT_URL);

        Intent intent = getIntent();
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            String candidate = intent.getData().toString();
            if (isWebUrl(candidate)) {
                initialUrl = candidate;
            }
        }

        navigate(initialUrl);
        showToolbar(false);
    }

    private void forceEnglishLocale() {
        Locale english = Locale.US;
        Locale.setDefault(english);

        Configuration configuration = new Configuration(getResources().getConfiguration());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            LocaleList locales = new LocaleList(english);
            LocaleList.setDefault(locales);
            configuration.setLocales(locales);
        } else {
            configuration.setLocale(english);
        }
        getResources().updateConfiguration(configuration, getResources().getDisplayMetrics());
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

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.app_background));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // Respect status/navigation bars without permanently reserving any extra
        // application toolbar space.
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
            view.setPadding(left, top, right, bottom);
            return insets;
        });

        // Web content is the base layer and always uses the complete usable screen.
        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        toolbarPanel = new LinearLayout(this);
        toolbarPanel.setOrientation(LinearLayout.VERTICAL);
        toolbarPanel.setPadding(dp(8), dp(6), dp(8), dp(8));
        toolbarPanel.setBackground(rounded(
                getColor(R.color.app_background),
                dp(0),
                getColor(R.color.border),
                dp(1)
        ));
        toolbarPanel.setElevation(dp(8));

        LinearLayout navigationRow = new LinearLayout(this);
        navigationRow.setOrientation(LinearLayout.HORIZONTAL);
        navigationRow.setGravity(Gravity.CENTER_VERTICAL);

        Button back = compactButton("Back");
        back.setOnClickListener(v -> {
            resetAutoHide();
            if (webView != null && webView.canGoBack()) {
                webView.goBack();
            } else {
                finish();
            }
        });
        navigationRow.addView(back, new LinearLayout.LayoutParams(0, dp(42), 1f));

        Button reload = compactButton("Reload");
        reload.setOnClickListener(v -> {
            resetAutoHide();
            if (webView != null) {
                webView.reload();
            }
        });
        LinearLayout.LayoutParams reloadParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        reloadParams.setMargins(dp(6), 0, 0, 0);
        navigationRow.addView(reload, reloadParams);

        Button hide = compactButton("Hide");
        hide.setOnClickListener(v -> hideToolbar());
        LinearLayout.LayoutParams hideParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        hideParams.setMargins(dp(6), 0, 0, 0);
        navigationRow.addView(hide, hideParams);

        Button close = compactButton("Close");
        close.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        closeParams.setMargins(dp(6), 0, 0, 0);
        navigationRow.addView(close, closeParams);

        toolbarPanel.addView(navigationRow);

        LinearLayout addressRow = new LinearLayout(this);
        addressRow.setOrientation(LinearLayout.HORIZONTAL);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        addressRow.setPadding(0, dp(6), 0, 0);

        addressBar = new EditText(this);
        addressBar.setSingleLine(true);
        addressBar.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressBar.setHint("Enter website address");
        addressBar.setTextColor(getColor(R.color.text_primary));
        addressBar.setHintTextColor(getColor(R.color.text_secondary));
        addressBar.setTextSize(14);
        addressBar.setSelectAllOnFocus(true);
        addressBar.setPadding(dp(13), 0, dp(13), 0);
        addressBar.setBackground(rounded(
                getColor(R.color.surface),
                dp(12),
                getColor(R.color.border),
                dp(1)
        ));
        addressBar.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                cancelAutoHide();
            } else {
                resetAutoHide();
            }
        });
        addressBar.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                navigate(addressBar.getText().toString());
                return true;
            }
            return false;
        });
        addressRow.addView(addressBar, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button go = compactButton("Go");
        go.setOnClickListener(v -> navigate(addressBar.getText().toString()));
        LinearLayout.LayoutParams goParams = new LinearLayout.LayoutParams(dp(60), dp(48));
        goParams.setMargins(dp(7), 0, 0, 0);
        addressRow.addView(go, goParams);

        toolbarPanel.addView(addressRow);

        FrameLayout.LayoutParams toolbarParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
        );
        root.addView(toolbarPanel, toolbarParams);

        // Tiny browser-style control handle. It is the only permanent browser UI
        // when controls are collapsed and occupies very little page area.
        revealHandle = new TextView(this);
        revealHandle.setText("⋮");
        revealHandle.setTextColor(getColor(R.color.text_primary));
        revealHandle.setTextSize(24);
        revealHandle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        revealHandle.setGravity(Gravity.CENTER);
        revealHandle.setContentDescription("Show browser controls");
        revealHandle.setBackground(rounded(
                getColor(R.color.surface),
                dp(16),
                getColor(R.color.brand_primary),
                dp(1)
        ));
        revealHandle.setElevation(dp(10));
        revealHandle.setAlpha(0.92f);
        revealHandle.setOnClickListener(v -> showToolbar(true));

        FrameLayout.LayoutParams handleParams = new FrameLayout.LayoutParams(
                dp(42),
                dp(42),
                Gravity.TOP | Gravity.END
        );
        handleParams.setMargins(0, dp(7), dp(7), 0);
        root.addView(revealHandle, handleParams);

        setContentView(root);
    }

    private Button compactButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(11);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(getColor(R.color.brand_primary));
        button.setPadding(dp(5), 0, dp(5), 0);
        button.setBackground(rounded(
                getColor(R.color.surface),
                dp(11),
                getColor(R.color.brand_primary),
                dp(1)
        ));
        return button;
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

    private void showToolbar(boolean focusAddressBar) {
        if (toolbarPanel == null || revealHandle == null) {
            return;
        }
        toolbarPanel.setVisibility(View.VISIBLE);
        revealHandle.setVisibility(View.GONE);
        cancelAutoHide();

        if (focusAddressBar && addressBar != null) {
            addressBar.requestFocus();
            addressBar.selectAll();
            InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (manager != null) {
                manager.showSoftInput(addressBar, InputMethodManager.SHOW_IMPLICIT);
            }
        } else {
            resetAutoHide();
        }
    }

    private void hideToolbar() {
        if (toolbarPanel == null || revealHandle == null) {
            return;
        }
        cancelAutoHide();
        if (addressBar != null) {
            addressBar.clearFocus();
        }
        hideKeyboard();
        toolbarPanel.setVisibility(View.GONE);
        revealHandle.setVisibility(View.VISIBLE);
    }

    private void resetAutoHide() {
        cancelAutoHide();
        if (toolbarPanel != null
                && toolbarPanel.getVisibility() == View.VISIBLE
                && (addressBar == null || !addressBar.hasFocus())) {
            uiHandler.postDelayed(hideToolbarRunnable, TOOLBAR_AUTO_HIDE_MS);
        }
    }

    private void cancelAutoHide() {
        uiHandler.removeCallbacks(hideToolbarRunnable);
    }

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(true);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();

                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    String original = uri.toString();
                    String english = forceEnglishForKnownGooglePages(original);
                    if (!english.equals(original)) {
                        view.loadUrl(english, ENGLISH_HEADERS);
                        return true;
                    }
                    return false;
                }

                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (RuntimeException ignored) {
                    Toast.makeText(ProtectedBrowserActivity.this,
                            "No installed app can open this link.", Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null) {
                    addressBar.setText(url);
                    getSharedPreferences(PREFS, MODE_PRIVATE)
                            .edit()
                            .putString(KEY_LAST_URL, url)
                            .apply();
                }
                CookieManager.getInstance().flush();
                uiHandler.postDelayed(ProtectedBrowserActivity.this::hideToolbar, 650L);
            }
        });

        // If controls are temporarily open and the user taps the webpage, collapse
        // them immediately so chat input and page controls regain maximum room.
        webView.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                    && toolbarPanel != null
                    && toolbarPanel.getVisibility() == View.VISIBLE
                    && (addressBar == null || !addressBar.hasFocus())) {
                hideToolbar();
            }
            return false;
        });

        DownloadListener listener = (url, userAgent, contentDisposition, mimetype, contentLength) -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (RuntimeException ignored) {
                Toast.makeText(this, "Unable to open the download link.", Toast.LENGTH_SHORT).show();
            }
        };
        webView.setDownloadListener(listener);
    }

    private void navigate(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            showToolbar(true);
            return;
        }

        if (!value.contains("://")) {
            value = "https://" + value;
        }

        if (!isWebUrl(value)) {
            Toast.makeText(this, "Enter a valid HTTP or HTTPS website address.", Toast.LENGTH_SHORT).show();
            showToolbar(true);
            return;
        }

        value = forceEnglishForKnownGooglePages(value);
        addressBar.setText(value);
        addressBar.clearFocus();
        hideKeyboard();
        webView.loadUrl(value, ENGLISH_HEADERS);
        uiHandler.postDelayed(this::hideToolbar, 500L);
    }

    private String forceEnglishForKnownGooglePages(String value) {
        if (value == null) {
            return "";
        }

        Uri uri = Uri.parse(value);
        String host = uri.getHost();
        if (host == null) {
            return value;
        }

        String lowerHost = host.toLowerCase(Locale.US);
        boolean googleUi = lowerHost.equals("accounts.google.com")
                || lowerHost.equals("google.com")
                || lowerHost.equals("www.google.com")
                || lowerHost.equals("myaccount.google.com");

        if (!googleUi) {
            return value;
        }

        Uri.Builder builder = uri.buildUpon().clearQuery();
        for (String name : uri.getQueryParameterNames()) {
            if ("hl".equalsIgnoreCase(name)) {
                continue;
            }
            for (String parameterValue : uri.getQueryParameters(name)) {
                builder.appendQueryParameter(name, parameterValue);
            }
        }
        builder.appendQueryParameter("hl", "en");
        return builder.build().toString();
    }

    private boolean isWebUrl(String value) {
        if (value == null) {
            return false;
        }
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused == null) {
            return;
        }
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onPause() {
        cancelAutoHide();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        CookieManager.getInstance().flush();
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cancelAutoHide();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        CookieManager.getInstance().flush();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (toolbarPanel != null && toolbarPanel.getVisibility() == View.VISIBLE) {
                hideToolbar();
                return true;
            }
            if (webView != null && webView.canGoBack()) {
                webView.goBack();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
