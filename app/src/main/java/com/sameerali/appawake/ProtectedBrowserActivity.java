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
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Protected browser window owned by Sameer App Awake.
 *
 * <p>Android guarantees FLAG_KEEP_SCREEN_ON for the visible Activity that owns the
 * window. This Activity therefore hosts the webpage itself. When it is visible the
 * normal inactivity timeout is suppressed. When it leaves the foreground, normal
 * Android timeout behavior resumes.</p>
 *
 * <p>By: Sameer Ali | Contact: sameer43786@gmail.com</p>
 */
public final class ProtectedBrowserActivity extends Activity {

    private static final String PREFS = "protected_browser_preferences";
    private static final String KEY_LAST_URL = "last_url";
    private static final String DEFAULT_URL = "https://www.google.com/?hl=en";

    private static final Map<String, String> ENGLISH_HEADERS = new HashMap<>();

    static {
        ENGLISH_HEADERS.put("Accept-Language", "en-US,en;q=0.9");
    }

    private WebView webView;
    private EditText addressBar;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        forceEnglishLocale();
        super.onCreate(savedInstanceState);
        configureSystemBars();

        // Official Android mechanism. The flag belongs to this visible browser window.
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
    }

    private void forceEnglishLocale() {
        Locale english = Locale.US;
        Locale.setDefault(english);
        Configuration configuration = new Configuration(getResources().getConfiguration());
        configuration.setLocale(english);
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.app_background));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // Android 15+ enforces edge-to-edge for modern targets. Respect the real status
        // and navigation bar insets so the URL field never sits underneath clock/icons.
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

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(dp(10), dp(8), dp(10), dp(5));

        Button back = compactButton("Back");
        back.setOnClickListener(v -> {
            if (webView != null && webView.canGoBack()) {
                webView.goBack();
            } else {
                finish();
            }
        });
        titleRow.addView(back, new LinearLayout.LayoutParams(dp(70), dp(44)));

        TextView title = new TextView(this);
        title.setText("Protected Browser");
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0,
                dp(44),
                1f
        );
        titleParams.setMargins(dp(10), 0, dp(8), 0);
        titleRow.addView(title, titleParams);

        Button reload = compactButton("Reload");
        reload.setOnClickListener(v -> {
            if (webView != null) {
                webView.reload();
            }
        });
        titleRow.addView(reload, new LinearLayout.LayoutParams(dp(76), dp(44)));

        Button close = compactButton("Close");
        close.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(72), dp(44));
        closeParams.setMargins(dp(6), 0, 0, 0);
        titleRow.addView(close, closeParams);

        root.addView(titleRow);

        // Address bar gets its own row. This is intentionally separate from browser
        // controls so the entire URL remains editable on a phone-sized display.
        LinearLayout addressRow = new LinearLayout(this);
        addressRow.setOrientation(LinearLayout.HORIZONTAL);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        addressRow.setPadding(dp(10), dp(3), dp(10), dp(5));

        addressBar = new EditText(this);
        addressBar.setSingleLine(true);
        addressBar.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressBar.setHint("Enter website address");
        addressBar.setTextColor(getColor(R.color.text_primary));
        addressBar.setHintTextColor(getColor(R.color.text_secondary));
        addressBar.setTextSize(14);
        addressBar.setSelectAllOnFocus(true);
        addressBar.setPadding(dp(14), 0, dp(14), 0);
        addressBar.setBackground(rounded(
                getColor(R.color.surface),
                dp(12),
                getColor(R.color.border),
                dp(1)
        ));
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
        addressRow.addView(addressBar, new LinearLayout.LayoutParams(0, dp(50), 1f));

        Button go = compactButton("Go");
        go.setOnClickListener(v -> navigate(addressBar.getText().toString()));
        LinearLayout.LayoutParams goParams = new LinearLayout.LayoutParams(dp(64), dp(50));
        goParams.setMargins(dp(8), 0, 0, 0);
        addressRow.addView(go, goParams);

        root.addView(addressRow);

        statusText = new TextView(this);
        statusText.setText("Screen protection active while this browser is visible");
        statusText.setTextColor(getColor(R.color.success));
        statusText.setTextSize(11);
        statusText.setPadding(dp(12), dp(2), dp(12), dp(7));
        root.addView(statusText);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        setContentView(root);
    }

    private Button compactButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(getColor(R.color.brand_primary));
        button.setPadding(dp(7), 0, dp(7), 0);
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
                    String english = ensureEnglishForKnownGooglePages(original);
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
                statusText.setText("Screen protection active • normal timeout resumes when you leave");
            }
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
            addressBar.requestFocus();
            return;
        }

        if (!value.contains("://")) {
            value = "https://" + value;
        }

        if (!isWebUrl(value)) {
            Toast.makeText(this, "Enter a valid HTTP or HTTPS website address.", Toast.LENGTH_SHORT).show();
            return;
        }

        value = ensureEnglishForKnownGooglePages(value);
        addressBar.setText(value);
        addressBar.clearFocus();
        hideKeyboard();
        statusText.setText("Loading… screen protection is already active");
        webView.loadUrl(value, ENGLISH_HEADERS);
    }

    private String ensureEnglishForKnownGooglePages(String value) {
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

        // Google user-facing pages support the hl parameter. Add it only when absent
        // so authentication/navigation parameters are not otherwise modified.
        if (uri.getQueryParameter("hl") == null) {
            return uri.buildUpon().appendQueryParameter("hl", "en").build().toString();
        }

        return value;
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
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        CookieManager.getInstance().flush();
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
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
