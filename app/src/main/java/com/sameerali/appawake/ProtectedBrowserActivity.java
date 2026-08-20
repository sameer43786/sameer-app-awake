package com.sameerali.appawake;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * A foreground browser window owned by Sameer App Awake.
 *
 * <p>This is the deterministic solution for Private Space. Android only guarantees
 * FLAG_KEEP_SCREEN_ON for the Activity that owns the visible window. Therefore this
 * Activity hosts the requested webpage itself using the system Chromium WebView.
 * While this Activity is visible, Android's normal inactivity timeout is suppressed.
 * As soon as the user leaves, closes, or backgrounds this Activity, Android stops
 * honoring FLAG_KEEP_SCREEN_ON and the normal timeout resumes automatically.</p>
 *
 * <p>By: Sameer Ali | Contact: sameer43786@gmail.com</p>
 */
public final class ProtectedBrowserActivity extends Activity {

    private static final String PREFS = "protected_browser_preferences";
    private static final String KEY_LAST_URL = "last_url";
    private static final String DEFAULT_URL = "https://www.google.com/";

    private WebView webView;
    private EditText addressBar;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Official Android mechanism: keep the display on only while this Activity owns
        // a visible window. This is intentionally not implemented in a Service.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        buildUi();
        configureWebView();

        String initialUrl = getPreferences(MODE_PRIVATE).getString(KEY_LAST_URL, DEFAULT_URL);
        Intent intent = getIntent();
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            String candidate = intent.getData().toString();
            if (isWebUrl(candidate)) {
                initialUrl = candidate;
            }
        }
        navigate(initialUrl);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(10, 18, 33));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), dp(8), dp(8), dp(8));

        Button back = button("‹");
        back.setOnClickListener(v -> {
            if (webView != null && webView.canGoBack()) {
                webView.goBack();
            } else {
                finish();
            }
        });
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(46)));

        addressBar = new EditText(this);
        addressBar.setSingleLine(true);
        addressBar.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressBar.setHint("Enter HTTPS webpage");
        addressBar.setTextColor(Color.WHITE);
        addressBar.setHintTextColor(Color.rgb(160, 170, 190));
        addressBar.setTextSize(13);
        addressBar.setPadding(dp(12), 0, dp(12), 0);
        addressBar.setOnEditorActionListener((v, actionId, event) -> {
            navigate(addressBar.getText().toString());
            return true;
        });
        toolbar.addView(addressBar, new LinearLayout.LayoutParams(0, dp(46), 1f));

        Button go = button("Go");
        go.setOnClickListener(v -> navigate(addressBar.getText().toString()));
        LinearLayout.LayoutParams goParams = new LinearLayout.LayoutParams(dp(58), dp(46));
        goParams.setMargins(dp(6), 0, 0, 0);
        toolbar.addView(go, goParams);

        Button chrome = button("Chrome");
        chrome.setOnClickListener(v -> openCurrentInChrome());
        LinearLayout.LayoutParams chromeParams = new LinearLayout.LayoutParams(dp(82), dp(46));
        chromeParams.setMargins(dp(6), 0, 0, 0);
        toolbar.addView(chrome, chromeParams);

        root.addView(toolbar);

        statusText = new TextView(this);
        statusText.setText("Protected Browser • screen timeout suppressed while this window is visible");
        statusText.setTextColor(Color.rgb(115, 236, 199));
        statusText.setTextSize(11);
        statusText.setPadding(dp(12), dp(2), dp(12), dp(6));
        root.addView(statusText);

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        setContentView(root);
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(12);
        return button;
    }

    @SuppressLint("SetJavaScriptEnabled")
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
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (RuntimeException ignored) {
                    Toast.makeText(ProtectedBrowserActivity.this,
                            "No app can open this link.", Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null) {
                    addressBar.setText(url);
                    getPreferences(MODE_PRIVATE).edit().putString(KEY_LAST_URL, url).apply();
                }
                statusText.setText("Protected Browser • screen remains on until you leave or close this window");
            }
        });

        DownloadListener listener = (url, userAgent, contentDisposition, mimetype, contentLength) -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (RuntimeException ignored) {
                Toast.makeText(this, "Unable to open download link.", Toast.LENGTH_SHORT).show();
            }
        };
        webView.setDownloadListener(listener);
    }

    private void navigate(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return;
        }
        if (!value.contains("://")) {
            value = "https://" + value;
        }
        if (!isWebUrl(value)) {
            Toast.makeText(this, "Only HTTP/HTTPS webpages are supported.", Toast.LENGTH_SHORT).show();
            return;
        }
        addressBar.setText(value);
        statusText.setText("Loading… screen protection is already active");
        webView.loadUrl(value);
    }

    private boolean isWebUrl(String value) {
        if (value == null) {
            return false;
        }
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private void openCurrentInChrome() {
        String url = webView == null ? null : webView.getUrl();
        if (url == null || !isWebUrl(url)) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.setPackage("com.android.chrome");
            startActivity(intent);
        } catch (RuntimeException error) {
            Toast.makeText(this, "Chrome is not available in this profile.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reassert after returning from a system dialog or external app.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onPause() {
        // Clear explicitly for deterministic normal timeout as soon as the browser leaves foreground.
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
