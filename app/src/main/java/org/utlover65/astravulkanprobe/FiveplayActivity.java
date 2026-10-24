package org.utlover65.astravulkanprobe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Full-screen Chromium-backed Android WebView with a virtual GTA-style control overlay.
 * The game itself remains hosted by the configured HTTPS site; this APK does not bundle game assets.
 */
public final class FiveplayActivity extends Activity {
    private static final String TAG = "WebGamePort";
    private static final String START_URL = "https://fiveplay.lol/";
    private static final String FALLBACK_URL = "https://gta5-87g.pages.dev/?low=1&fps=30&res=1280x720&shadows=0";
    private static final long STARTUP_TIMEOUT_MS = 15_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Set<Integer>> keySources = new HashMap<>();
    private final Set<Integer> emittedKeys = new HashSet<>();
    private WebView webView;
    private TextView status;
    private TouchOverlay overlay;
    private boolean statusPinned;
    private boolean pageFinished;
    private boolean fallbackAttempted;
    private boolean crossOriginIsolationAllowlistConfigured;
    private int pageProgress;
    private final Runnable startupTimeout = () -> {
        if (webView != null && !pageFinished && !fallbackAttempted
                && isFiveplayUrl(webView.getUrl())) {
            loadFallback("startup timeout after 15 seconds");
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.setSafeBrowsingEnabled(true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int progress) {
                pageProgress = progress;
                if (!statusPinned && status != null) {
                    status.setText(progress < 100
                            ? "Fiveplay Web · Chromium " + webViewVersion() + " · loading " + progress + "%"
                            : "Fiveplay Web · tap the world to focus · INFO for compatibility");
                    status.setVisibility(View.VISIBLE);
                }
            }

            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                String line = message.message() + " (" + message.sourceId() + ":" + message.lineNumber() + ")";
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Log.e(TAG, line);
                else if (message.messageLevel() == ConsoleMessage.MessageLevel.WARNING) Log.w(TAG, line);
                else Log.d(TAG, line);
                return true;
            }

            @Override public void onPermissionRequest(PermissionRequest request) {
                // This game needs no microphone/camera capture. Do not grant web permissions implicitly.
                request.deny();
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                Toast.makeText(FiveplayActivity.this, "Downloads are disabled in this game wrapper", Toast.LENGTH_LONG).show());

        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // Android WebView needs an explicit response policy plus a narrowly scoped
                // AndroidX WebKit allowlist before SharedArrayBuffer can be exposed. Only
                // intercept top-level HTML navigations to the two configured game origins.
                if (request.isForMainFrame()
                        && "GET".equalsIgnoreCase(request.getMethod())
                        && isIsolationTargetUrl(request.getUrl().toString())) {
                    WebResourceResponse response = interceptMainDocumentForIsolation(request);
                    if (response != null) return response;
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri uri = request.getUrl();
                if (!"https".equalsIgnoreCase(uri.getScheme())) {
                    Toast.makeText(FiveplayActivity.this, "Blocked non-HTTPS navigation", Toast.LENGTH_SHORT).show();
                    return true;
                }
                String host = uri.getHost();
                if (isFiveplayHost(host) || isFallbackHost(host)) return false;
                // Keep unrelated pages out of the embedded game surface.
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                catch (Exception e) { Toast.makeText(FiveplayActivity.this, "External link blocked", Toast.LENGTH_SHORT).show(); }
                return true;
            }

            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageFinished = false;
                pageProgress = 0;
                if (status != null) {
                    status.setVisibility(View.VISIBLE);
                    status.setText(fallbackAttempted
                            ? "Connecting to the alternate GTA V host…"
                            : "Connecting to the game website…");
                }
            }

            @Override public void onPageFinished(WebView view, String url) {
                pageFinished = true;
                handler.removeCallbacks(startupTimeout);
                if (status != null && !statusPinned) {
                    status.setVisibility(View.VISIBLE);
                    status.setText("Fiveplay Web · INFO: tap to check compatibility");
                    status.setVisibility(View.VISIBLE);
                }
                Log.i(TAG, "Page finished: " + url + "; progress=" + pageProgress);
            }

            @Override public void onReceivedError(
                    WebView view, WebResourceRequest request, WebResourceError error) {
                Log.e(TAG, "Web error: url=" + request.getUrl()
                        + " mainFrame=" + request.isForMainFrame()
                        + " code=" + error.getErrorCode()
                        + " description=" + error.getDescription());

                // Fail over only for a failed top-level Fiveplay document. Subresource errors
                // should not replace the whole page, and TLS validation remains enabled.
                Uri failedUri = request.getUrl();
                if (request.isForMainFrame() && isFiveplayUrl(failedUri.toString())
                        && !fallbackAttempted) {
                    loadFallback("Fiveplay network error " + error.getErrorCode());
                    return;
                }

                if (request.isForMainFrame() && status != null) {
                    statusPinned = true;
                    status.setVisibility(View.VISIBLE);
                    status.setText("WebView error " + error.getErrorCode()
                            + ": " + error.getDescription()
                            + "\\nTap INFO for compatibility details.");
                }
            }

            @Override public void onReceivedHttpError(
                    WebView view, WebResourceRequest request,
                    android.webkit.WebResourceResponse response) {
                Log.e(TAG, "HTTP error: url=" + request.getUrl()
                        + " mainFrame=" + request.isForMainFrame()
                        + " status=" + response.getStatusCode()
                        + " reason=" + response.getReasonPhrase());
                if (request.isForMainFrame() && isFiveplayUrl(request.getUrl().toString())
                        && response.getStatusCode() >= 400 && !fallbackAttempted) {
                    loadFallback("Fiveplay HTTP " + response.getStatusCode());
                }
            }

            @Override
            public void onReceivedSslError(
                    WebView view,
                    android.webkit.SslErrorHandler handler,
                    android.net.http.SslError error) {
                String details = "SSL CONNECTION FAILED\\n\\n"
                        + "URL: " + error.getUrl() + "\\n"
                        + "Error code: " + error.getPrimaryError() + "\\n"
                        + "Certificate: " + error.getCertificate()
                        + "\\n\\nConnection cancelled securely.";
                Log.e(TAG, details);
                handler.cancel();
                if (isFiveplayUrl(error.getUrl()) && !fallbackAttempted) {
                    loadFallback("Fiveplay TLS/certificate error");
                    return;
                }
                runOnUiThread(() -> new android.app.AlertDialog.Builder(FiveplayActivity.this)
                        .setTitle("Fiveplay SSL diagnostic")
                        .setMessage(details)
                        .setPositiveButton("Close", null)
                        .show());
            }
        });

        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));

        overlay = new TouchOverlay();
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(11);
        status.setTypeface(Typeface.MONOSPACE);
        status.setPadding(dp(10), dp(7), dp(10), dp(7));
        status.setBackgroundColor(0xB8101720);
        status.setText("Connecting to the game website…");
        status.setOnClickListener(v -> showCompatibilityInfo());
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        statusParams.setMargins(dp(8), dp(8), dp(8), dp(8));
        root.addView(status, statusParams);

        setContentView(root);

        // Prefer HTTP/3 (QUIC) for Fiveplay before first load.
        // TLS certificate validation remains enabled.
        try {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(
                        androidx.webkit.WebViewFeature.MULTI_PROFILE)
                    && androidx.webkit.WebViewFeature.isFeatureSupported(
                        androidx.webkit.WebViewFeature.ADD_QUIC_HINTS_V1)) {
                androidx.webkit.Profile profile =
                        androidx.webkit.WebViewCompat.getProfile(webView);
                profile.addQuicHints(
                        java.util.Collections.singleton("https://fiveplay.lol"));
                Log.i(TAG, "QUIC preference enabled for fiveplay.lol");
            } else {
                Log.w(TAG, "This WebView provider does not support QUIC hints");
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not enable the Fiveplay QUIC hint", e);
        }

        configureCrossOriginIsolationAllowlist();
        webView.loadUrl(START_URL);
        handler.postDelayed(startupTimeout, STARTUP_TIMEOUT_MS);
    }

    private boolean isIsolationTargetUrl(String url) {
        return isFiveplayUrl(url) || isFallbackUrl(url);
    }

    private boolean isFallbackUrl(String url) {
        if (url == null) return false;
        try { return isFallbackHost(Uri.parse(url).getHost()); }
        catch (RuntimeException ignored) { return false; }
    }

    /**
     * Fetches just the top-level HTML document with normal TLS verification, then adds
     * Document-Isolation-Policy to the response. All game scripts, WASM, workers and
     * media continue to load normally from their original HTTPS URLs inside WebView.
     * If the response is a redirect, error, non-HTML document, or exceeds the size cap,
     * return null so WebView performs its ordinary network request instead.
     */
    private WebResourceResponse interceptMainDocumentForIsolation(WebResourceRequest request) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(request.getUrl().toString());
            if (!"https".equalsIgnoreCase(url.getProtocol())) return null;

            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(20000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept-Encoding", "identity");

            Map<String, String> requestHeaders = request.getRequestHeaders();
            for (Map.Entry<String, String> entry : requestHeaders.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key == null || value == null) continue;
                String lower = key.toLowerCase(Locale.ROOT);
                if (lower.equals("host") || lower.equals("connection")
                        || lower.equals("content-length") || lower.equals("accept-encoding")
                        || lower.equals("cookie") || lower.equals("cookie2")
                        || lower.equals("transfer-encoding") || lower.equals("upgrade")
                        || lower.equals("proxy-connection")) continue;
                try { connection.setRequestProperty(key, value); }
                catch (IllegalArgumentException ignored) { /* Skip restricted transport headers. */ }
            }
            String cookies = android.webkit.CookieManager.getInstance().getCookie(url.toString());
            if (cookies != null && !cookies.isEmpty()) connection.setRequestProperty("Cookie", cookies);

            int statusCode = connection.getResponseCode();
            if (statusCode < 200 || statusCode >= 300) return null;

            String contentType = connection.getContentType();
            if (contentType == null) return null;
            String lowerContentType = contentType.toLowerCase(Locale.ROOT);
            if (!lowerContentType.startsWith("text/html")
                    && !lowerContentType.startsWith("application/xhtml+xml")) return null;
            String contentEncoding = connection.getContentEncoding();
            if (contentEncoding != null && !contentEncoding.equalsIgnoreCase("identity")) return null;

            InputStream input = connection.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            int total = 0;
            final int maxDocumentBytes = 8 * 1024 * 1024;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maxDocumentBytes) {
                    input.close();
                    Log.w(TAG, "Main HTML document too large for isolation response interception; using normal WebView loading");
                    return null;
                }
                output.write(buffer, 0, count);
            }
            input.close();
            byte[] body = output.toByteArray();

            Map<String, String> responseHeaders = new HashMap<>();
            List<String> responseCookies = null;
            Map<String, List<String>> rawHeaders = connection.getHeaderFields();
            for (Map.Entry<String, List<String>> entry : rawHeaders.entrySet()) {
                String key = entry.getKey();
                List<String> values = entry.getValue();
                if (key == null || values == null || values.isEmpty()) continue;
                String lower = key.toLowerCase(Locale.ROOT);
                if (lower.equals("set-cookie")) {
                    responseCookies = values;
                    continue;
                }
                if (lower.equals("connection") || lower.equals("keep-alive")
                        || lower.equals("proxy-authenticate") || lower.equals("proxy-authorization")
                        || lower.equals("te") || lower.equals("trailer")
                        || lower.equals("transfer-encoding") || lower.equals("upgrade")
                        || lower.equals("content-encoding")) continue;
                String joined = String.join(", ", values);
                responseHeaders.put(key, joined);
            }

            // Preserve cookies set by the server; these are not forwarded as a combined
            // Set-Cookie header because commas can be meaningful inside cookie attributes.
            if (responseCookies != null) {
                android.webkit.CookieManager cookieManager = android.webkit.CookieManager.getInstance();
                for (String cookie : responseCookies) {
                    if (cookie == null || cookie.isEmpty()) continue;
                    CountDownLatch latch = new CountDownLatch(1);
                    cookieManager.setCookie(url.toString(), cookie, ignored -> latch.countDown());
                    try {
                        if (!latch.await(1500, TimeUnit.MILLISECONDS)) {
                            Log.w(TAG, "Timed out storing a cookie from the game HTML response");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
                cookieManager.flush();
            }

            boolean hasDip = false;
            for (String key : responseHeaders.keySet()) {
                if (key.equalsIgnoreCase("Document-Isolation-Policy")) { hasDip = true; break; }
            }
            if (!hasDip) responseHeaders.put("Document-Isolation-Policy", "isolate-and-require-corp");
            responseHeaders.put("Content-Length", Integer.toString(body.length));

            String mimeType = contentType.split(";", 2)[0].trim();
            String charset = "UTF-8";
            int charsetAt = lowerContentType.indexOf("charset=");
            if (charsetAt >= 0) {
                String charsetValue = contentType.substring(charsetAt + 8).trim().split("[;\\s]", 2)[0]
                        .replace("\"", "").replace("'", "");
                if (!charsetValue.isEmpty()) charset = charsetValue;
            }
            String reason = connection.getResponseMessage();
            if (reason == null || reason.trim().isEmpty()) reason = "OK";
            Log.i(TAG, "Added Document-Isolation-Policy to top-level HTML from " + url.getHost()
                    + "; status=" + statusCode + "; bytes=" + body.length);
            return new WebResourceResponse(mimeType, charset, statusCode, reason,
                    responseHeaders, new ByteArrayInputStream(body));
        } catch (Exception error) {
            Log.w(TAG, "Could not add Document-Isolation-Policy to the main document; using normal WebView loading", error);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void configureCrossOriginIsolationAllowlist() {
        try {
            if (!androidx.webkit.WebViewFeature.isFeatureSupported(
                    androidx.webkit.WebViewFeature.CROSS_ORIGIN_ISOLATED_ALLOWLIST)) {
                Log.w(TAG, "This WebView provider does not support the cross-origin isolated API allowlist; SharedArrayBuffer may remain unavailable");
                crossOriginIsolationAllowlistConfigured = false;
                return;
            }
            if (!androidx.webkit.WebViewFeature.isFeatureSupported(
                    androidx.webkit.WebViewFeature.MULTI_PROFILE)) {
                Log.w(TAG, "This WebView provider cannot expose a profile for cross-origin isolation allowlisting");
                crossOriginIsolationAllowlistConfigured = false;
                return;
            }
            androidx.webkit.Profile profile = androidx.webkit.WebViewCompat.getProfile(webView);
            Set<String> trustedOrigins = new HashSet<>();
            trustedOrigins.add("https://fiveplay.lol");
            trustedOrigins.add("https://gta5-87g.pages.dev");
            profile.setCrossOriginIsolatedAllowlist(trustedOrigins);
            crossOriginIsolationAllowlistConfigured = true;
            Log.i(TAG, "Cross-origin isolated APIs allowlisted only for the configured game origins");
        } catch (RuntimeException error) {
            crossOriginIsolationAllowlistConfigured = false;
            Log.w(TAG, "Could not configure cross-origin API allowlist", error);
        }
    }

    private boolean isFiveplayHost(String host) {
        return host != null && (host.equalsIgnoreCase("fiveplay.lol")
                || host.toLowerCase(Locale.ROOT).endsWith(".fiveplay.lol"));
    }

    private boolean isFallbackHost(String host) {
        return host != null && host.equalsIgnoreCase("gta5-87g.pages.dev");
    }

    private boolean isFiveplayUrl(String url) {
        if (url == null) return false;
        try { return isFiveplayHost(Uri.parse(url).getHost()); }
        catch (RuntimeException ignored) { return false; }
    }

    private void loadFallback(String reason) {
        if (webView == null || fallbackAttempted) return;
        fallbackAttempted = true;
        handler.removeCallbacks(startupTimeout);
        pageFinished = false;
        pageProgress = 0;
        statusPinned = false;
        if (status != null) {
            status.setVisibility(View.VISIBLE);
            status.setText("Fiveplay failed (" + reason + "). Loading alternate GTA V host…");
        }
        Log.w(TAG, "Switching once from Fiveplay to fallback URL: " + FALLBACK_URL + "; reason=" + reason);
        webView.stopLoading();
        webView.loadUrl(FALLBACK_URL);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String webViewVersion() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.content.pm.PackageInfo info = WebView.getCurrentWebViewPackage();
                if (info != null) return info.versionName == null ? info.packageName : info.versionName;
            }
        } catch (Throwable ignored) { }
        return "unknown";
    }

    private void showCompatibilityInfo() {
        if (webView == null) return;
        statusPinned = true;
        status.setVisibility(View.VISIBLE);
        String js = "(function(){return JSON.stringify({url:location.href,userAgent:navigator.userAgent,secure:!!isSecureContext,isolated:!!crossOriginIsolated,sharedArrayBuffer:typeof SharedArrayBuffer!=='undefined',webgpu:!!navigator.gpu,offscreenCanvas:typeof OffscreenCanvas!=='undefined',workers:typeof Worker==='function',audioWorklet:!!(window.AudioWorkletNode),canvas:!!document.querySelector('canvas')})})()";
        webView.evaluateJavascript(js, result -> {
            String featureText = result == null ? "No page response yet" : result;
            String title = "WebView / Chromium: " + webViewVersion() + "\n\n" +
                    "Page loaded: " + pageFinished + "\n" +
                    "Cross-origin API allowlist: " + crossOriginIsolationAllowlistConfigured + "\n" +
                    "Web feature report:\n" + featureText + "\n\n" +
                    "If WebGPU, SharedArrayBuffer, or crossOriginIsolated is false, this WebView may not be compatible with the game's browser runtime. This app does not install certificates or bypass TLS checks.";
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Browser compatibility")
                    .setMessage(title)
                    .setPositiveButton("Close", (d, w) -> { statusPinned = false; if (status != null) { status.setText("Fiveplay Web · tap for compatibility info"); status.setVisibility(View.VISIBLE); } })
                    .setNeutralButton("Reload page", (d, w) -> { statusPinned = false; if (webView != null) webView.reload(); })
                    .show();
        });
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private String jsKeyForCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_W: return "w";
            case KeyEvent.KEYCODE_A: return "a";
            case KeyEvent.KEYCODE_S: return "s";
            case KeyEvent.KEYCODE_D: return "d";
            case KeyEvent.KEYCODE_SPACE: return " ";
            case KeyEvent.KEYCODE_F: return "f";
            case KeyEvent.KEYCODE_E: return "e";
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return "Shift";
            case KeyEvent.KEYCODE_V: return "v";
            case KeyEvent.KEYCODE_ESCAPE: return "Escape";
            case KeyEvent.KEYCODE_ENTER: return "Enter";
            case KeyEvent.KEYCODE_DPAD_UP: return "ArrowUp";
            case KeyEvent.KEYCODE_C: return "c";
            case KeyEvent.KEYCODE_Q: return "q";
            case KeyEvent.KEYCODE_R: return "r";
            case KeyEvent.KEYCODE_ALT_LEFT: return "Alt";
            case KeyEvent.KEYCODE_TAB: return "Tab";
            case KeyEvent.KEYCODE_M: return "m";
            case KeyEvent.KEYCODE_G: return "g";
            case KeyEvent.KEYCODE_CAPS_LOCK: return "CapsLock";
            case KeyEvent.KEYCODE_CTRL_LEFT: return "Control";
            default: return "";
        }
    }

    private String jsCodeForCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_W: return "KeyW";
            case KeyEvent.KEYCODE_A: return "KeyA";
            case KeyEvent.KEYCODE_S: return "KeyS";
            case KeyEvent.KEYCODE_D: return "KeyD";
            case KeyEvent.KEYCODE_SPACE: return "Space";
            case KeyEvent.KEYCODE_F: return "KeyF";
            case KeyEvent.KEYCODE_E: return "KeyE";
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return "ShiftLeft";
            case KeyEvent.KEYCODE_V: return "KeyV";
            case KeyEvent.KEYCODE_ESCAPE: return "Escape";
            case KeyEvent.KEYCODE_ENTER: return "Enter";
            case KeyEvent.KEYCODE_DPAD_UP: return "ArrowUp";
            case KeyEvent.KEYCODE_C: return "KeyC";
            case KeyEvent.KEYCODE_Q: return "KeyQ";
            case KeyEvent.KEYCODE_R: return "KeyR";
            case KeyEvent.KEYCODE_ALT_LEFT: return "AltLeft";
            case KeyEvent.KEYCODE_TAB: return "Tab";
            case KeyEvent.KEYCODE_M: return "KeyM";
            case KeyEvent.KEYCODE_G: return "KeyG";
            case KeyEvent.KEYCODE_CAPS_LOCK: return "CapsLock";
            case KeyEvent.KEYCODE_CTRL_LEFT: return "ControlLeft";
            default: return "";
        }
    }

    private void dispatchKey(int keyCode, boolean down) {
        if (webView == null) return;
        webView.requestFocus();
        String key = jsKeyForCode(keyCode);
        String code = jsCodeForCode(keyCode);
        if (key.isEmpty() || code.isEmpty()) return;
        String js = "(function(){const key=" + JSONObject.quote(key)
                + ",code=" + JSONObject.quote(code)
                + ",down=" + (down ? "true" : "false")
                + ";const target=document.activeElement&&document.activeElement!==document.body"
                + "?document.activeElement:(document.pointerLockElement||document.querySelector('canvas')||document);"
                + "try{target.focus({preventScroll:true})}catch(e){}"
                + "const init={key:key,code:code,bubbles:true,cancelable:true,composed:true,repeat:false,"
                + "shiftKey:key==='Shift'||code==='ShiftLeft'||code==='ShiftRight',"
                + "altKey:key==='Alt'||code==='AltLeft',ctrlKey:key==='Control'||code==='ControlLeft'};"
                + "target.dispatchEvent(new KeyboardEvent(down?'keydown':'keyup',init));"
                + "if(target!==document)document.dispatchEvent(new KeyboardEvent(down?'keydown':'keyup',init));"
                + "})();";
        webView.evaluateJavascript(js, null);
        long now = SystemClock.uptimeMillis();
        webView.dispatchKeyEvent(new KeyEvent(now, now,
                down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, keyCode, 0));
    }

    private void setKeySource(String source, Set<Integer> codes) {
        if (codes == null || codes.isEmpty()) keySources.remove(source);
        else keySources.put(source, new HashSet<>(codes));
        Set<Integer> next = new HashSet<>();
        for (Set<Integer> set : keySources.values()) next.addAll(set);
        for (int code : new HashSet<>(emittedKeys)) if (!next.contains(code)) { dispatchKey(code, false); emittedKeys.remove(code); }
        for (int code : next) if (emittedKeys.add(code)) dispatchKey(code, true);
    }

    private void forwardLookTouch(long downTime, long eventTime, int action,
                                  float x, float y, int metaState) {
        if (webView == null) return;
        // Overlay and WebView fill the same FrameLayout, so coordinates are aligned.
        MotionEvent touch = MotionEvent.obtain(downTime, eventTime, action, x, y, metaState);
        try { webView.dispatchTouchEvent(touch); }
        finally { touch.recycle(); }
    }

    private void emitMouseDelta(float dx, float dy) {
        if (webView == null) return;
        if (Math.abs(dx) < 0.5f && Math.abs(dy) < 0.5f) return;

        String js = "(function(){"
                + "const dx=" + Math.round(dx) + ";"
                + "const dy=" + Math.round(dy) + ";"
                + "const t=document.pointerLockElement"
                + "||document.querySelector('canvas')||document;"
                + "const e=new MouseEvent('mousemove',{"
                + "bubbles:true,cancelable:true,"
                + "clientX:innerWidth*.6,clientY:innerHeight*.5"
                + "});"
                + "try{Object.defineProperties(e,{"
                + "movementX:{value:dx},movementY:{value:dy}"
                + "});}catch(x){}"
                + "t.dispatchEvent(e);"
                + "})();";

        webView.evaluateJavascript(js, null);
    }

    private void emitMouseWheel(int direction) {
        if (webView == null) return;
        String js = "(function(){const t=document.pointerLockElement||document.querySelector('canvas')||document;" +
                "t.dispatchEvent(new WheelEvent('wheel',{bubbles:true,cancelable:true,deltaY:" +
                (direction < 0 ? "-120" : "120") + ",deltaMode:0}))})()";
        webView.evaluateJavascript(js, null);
    }

    private void pressMappedButton(int buttonId, boolean down) {
        String src = "button:" + buttonId;
        switch (buttonId) {
            case TouchOverlay.JUMP: setKeySource(src, down ? setOf(KeyEvent.KEYCODE_SPACE) : null); break;
            case TouchOverlay.ENTER: setKeySource(src, down ? setOf(KeyEvent.KEYCODE_F) : null); break;
            case TouchOverlay.INTERACT: setKeySource(src, down ? setOf(KeyEvent.KEYCODE_E) : null); break;
            case TouchOverlay.SPRINT: setKeySource(src, down ? setOf(KeyEvent.KEYCODE_SHIFT_LEFT) : null); break;
            case TouchOverlay.CAMERA: setKeySource(src, down ? setOf(KeyEvent.KEYCODE_V) : null); break;
            case TouchOverlay.PAUSE: setKeySource(src, down ? setOf(KeyEvent.KEYCODE_ESCAPE) : null); break;
            case TouchOverlay.PHONE: setKeySource(src, down ? setOf(KeyEvent.KEYCODE_DPAD_UP) : null); break;
            case TouchOverlay.LOOK_BEHIND: setKeySource(src, down ? setOf(KeyEvent.KEYCODE_C) : null); break;
            case TouchOverlay.WEAPON: setKeySource(src, down ? setOf(KeyEvent.KEYCODE_TAB) : null); break;
            case TouchOverlay.RELOAD: setKeySource(src, down ? setOf(KeyEvent.KEYCODE_R) : null); break;
            case TouchOverlay.CHAR_SWITCH: setKeySource(src, down ? setOf(KeyEvent.KEYCODE_ALT_LEFT) : null); break;
            case TouchOverlay.MAP: setKeySource(src, down ? setOf(KeyEvent.KEYCODE_M) : null); break;
            case TouchOverlay.COVER: setKeySource(src, down ? setOf(KeyEvent.KEYCODE_Q) : null); break;
            case TouchOverlay.SPECIAL: setKeySource(src, down ? setOf(KeyEvent.KEYCODE_CAPS_LOCK) : null); break;
            case TouchOverlay.THROW: setKeySource(src, down ? setOf(KeyEvent.KEYCODE_G) : null); break;
            case TouchOverlay.STEALTH: setKeySource(src, down ? setOf(KeyEvent.KEYCODE_CTRL_LEFT) : null); break;
            case TouchOverlay.PREV_WEAPON: if (down) emitMouseWheel(-1); break;
            case TouchOverlay.NEXT_WEAPON: if (down) emitMouseWheel(1); break;
            case TouchOverlay.AIM: emitMouseButton(2, down); break;
            case TouchOverlay.FIRE: emitMouseButton(0, down); break;
            case TouchOverlay.LOOK: if (down && overlay != null) { overlay.lookMode = !overlay.lookMode; overlay.invalidate(); } break;
        }
    }

    private void emitMouseButton(int button, boolean down) {
        if (webView == null) return;
        String js = "(function(){const t=document.pointerLockElement||document.querySelector('canvas')||document;" +
                "const b=" + button + ",d=" + (down ? "true" : "false") + ";" +
                "const init={bubbles:true,cancelable:true,button:b,buttons:d?(b===0?1:2):0," +
                "clientX:innerWidth*.6,clientY:innerHeight*.5};" +
                "t.dispatchEvent(new MouseEvent(d?'mousedown':'mouseup',init));" +
                "t.dispatchEvent(new MouseEvent(d?'pointerdown':'pointerup',{bubbles:true,cancelable:true," +
                "pointerId:1,pointerType:'mouse',button:b,buttons:init.buttons,clientX:init.clientX,clientY:init.clientY}));" +
                "if(!d)t.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,button:b,buttons:0," +
                "clientX:init.clientX,clientY:init.clientY}));})();";
        webView.evaluateJavascript(js, null);
    }

    private static Set<Integer> setOf(int... codes) {
        Set<Integer> out = new HashSet<>();
        for (int code : codes) out.add(code);
        return out;
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (webView != null) webView.onResume();
    }

    @Override protected void onDestroy() {
        for (int code : new HashSet<>(emittedKeys)) dispatchKey(code, false);
        emittedKeys.clear(); keySources.clear();
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private final class TouchOverlay extends View {
        static final int JUMP = 1, ENTER = 2, INTERACT = 3, SPRINT = 4,
                CAMERA = 5, PAUSE = 6, LOOK = 7, PHONE = 8, AIM = 9, FIRE = 10,
                LOOK_BEHIND = 11, WEAPON = 12, RELOAD = 13, CHAR_SWITCH = 14,
                MAP = 15, COVER = 16, SPECIAL = 17, THROW = 18, STEALTH = 19,
                PREV_WEAPON = 20, NEXT_WEAPON = 21;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Map<Integer, Integer> pointerButtons = new HashMap<>();
        private final Set<Integer> downButtons = new HashSet<>();
        private int stickPointer = -1, lookPointer = -1, cameraStickPointer = -1;
        private float stickCx, stickCy, stickX, stickY, lookX, lookY;
        private float cameraStickX, cameraStickY;
        private float unit;
        boolean lookMode = true;
        boolean lookMoved;
        float lookStartX, lookStartY;
        long lookDownTime;

        TouchOverlay() { super(FiveplayActivity.this); setLayerType(View.LAYER_TYPE_SOFTWARE, null); setClickable(true); setFocusable(false); }
        private float u() { return Math.max(1f, getHeight()); }
        private float bx(float right) { return getWidth() - u() * right; }
        private float by(float fraction) { return u() * fraction; }
        private float br(float fraction) { return u() * fraction; }

        private final float[][] buttonDefs = {
                {JUMP, .145f, .67f, .061f}, {ENTER, .145f, .49f, .052f},
                {INTERACT, .145f, .32f, .050f}, {SPRINT, .425f, .72f, .051f},
                {CAMERA, .425f, .57f, .047f}, {PAUSE, .105f, .13f, .045f},
                {LOOK, .285f, .13f, .045f}, {PHONE, .105f, .25f, .043f},
                {AIM, .875f, .68f, .052f}, {FIRE, .875f, .84f, .058f},
                {LOOK_BEHIND, .735f, .84f, .040f}, {WEAPON, .735f, .68f, .042f},
                {RELOAD, .875f, .51f, .040f},
                {CHAR_SWITCH, .285f, .25f, .038f}, {MAP, .285f, .36f, .038f},
                {COVER, .285f, .47f, .038f}, {SPECIAL, .285f, .58f, .038f},
                {THROW, .285f, .69f, .038f}, {STEALTH, .285f, .80f, .038f},
                {PREV_WEAPON, .55f, .34f, .035f}, {NEXT_WEAPON, .55f, .46f, .035f}
        };

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            unit = u();
            p.setStrokeWidth(dp(2)); p.setStyle(Paint.Style.STROKE); p.setColor(0x68FFFFFF);
            float leftCx = unit * .18f, leftCy = unit * .77f, leftR = unit * .13f;
            c.drawCircle(leftCx, leftCy, leftR, p);
            c.drawCircle(leftCx, leftCy, leftR * .52f, p);
            if (stickPointer >= 0) { p.setStyle(Paint.Style.FILL); p.setColor(0x72FFFFFF); c.drawCircle(stickX, stickY, unit * .052f, p); }
            // Independent camera-look joystick on the right, above the action buttons.
            float camCx = getWidth() - unit * .30f, camCy = unit * .34f, camR = unit * .105f;
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(2)); p.setColor(0x68FFFFFF);
            c.drawCircle(camCx, camCy, camR, p); c.drawCircle(camCx, camCy, camR * .48f, p);
            if (cameraStickPointer >= 0) {
                p.setStyle(Paint.Style.FILL); p.setColor(0x72FFFFFF);
                c.drawCircle(cameraStickX, cameraStickY, unit * .045f, p);
            }
            p.setStyle(Paint.Style.FILL); p.setColor(0xBFFFFFFF); p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(dp(10)); c.drawText("CAMERA", camCx, camCy - camR - dp(5), p);
            for (float[] def : buttonDefs) {
                int id = (int)def[0]; float x = bx(def[1]), y = by(def[2]), r = br(def[3]);
                boolean on = downButtons.contains(id) || (id == LOOK && lookMode);
                p.setStyle(Paint.Style.FILL); p.setColor(on ? 0xA0FFFFFF : 0x35FFFFFF); c.drawCircle(x, y, r, p);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1.4f)); p.setColor(0x88FFFFFF); c.drawCircle(x, y, r, p);
                p.setStyle(Paint.Style.FILL); p.setColor(Color.WHITE); p.setTextAlign(Paint.Align.CENTER);
                p.setTextSize(Math.max(dp(10), r * .31f));
                c.drawText(label(id), x, y + p.getTextSize() * .35f, p);
            }
        }

        private String label(int id) {
            switch (id) {
                case JUMP: return "JUMP"; case ENTER: return "ENTER"; case INTERACT: return "USE";
                case SPRINT: return "RUN"; case CAMERA: return "CAM"; case PAUSE: return "ESC";
                case PHONE: return "PHONE"; case AIM: return "AIM"; case FIRE: return "FIRE";
                case LOOK_BEHIND: return "BACK"; case WEAPON: return "WHEEL"; case RELOAD: return "RELOAD";
                case CHAR_SWITCH: return "SWITCH"; case MAP: return "MAP"; case COVER: return "COVER";
                case SPECIAL: return "SPECIAL"; case THROW: return "THROW"; case STEALTH: return "STEALTH";
                case PREV_WEAPON: return "PREV"; case NEXT_WEAPON: return "NEXT";
                default: return lookMode ? "LOOK ON" : "LOOK OFF";
            }
        }

        private int hitButton(float x, float y) {
            for (float[] def : buttonDefs) {
                float r = br(def[3]);
                if (Math.hypot(x - bx(def[1]), y - by(def[2])) <= r) return (int)def[0];
            }
            return 0;
        }

        private boolean inStickZone(float x, float y) {
            float h = u(); return x < h * .34f && y > h * .52f;
        }

        private boolean inLookZone(float x, float y) {
            float h = u(), cx = getWidth() - h * .30f, cy = h * .34f;
            if (Math.hypot(x - cx, y - cy) <= h * .14f) return false;
            return lookMode && x > getWidth() * .45f && y > h * .18f && y < h * .92f;
        }

        private boolean inCameraStickZone(float x, float y) {
            float h = u(), cx = getWidth() - h * .30f, cy = h * .34f;
            return Math.hypot(x - cx, y - cy) <= h * .14f;
        }

        private void updateCameraStick(float x, float y) {
            float h = u(), cx = getWidth() - h * .30f, cy = h * .34f;
            float max = h * .085f, dx = x - cx, dy = y - cy;
            float length = (float)Math.hypot(dx, dy);
            if (length > max && length > 0) { dx *= max / length; dy *= max / length; }
            cameraStickX = cx + dx; cameraStickY = cy + dy;
            float density = getResources().getDisplayMetrics().density;
            emitMouseDelta(dx / Math.max(1f, density) * 0.30f,
                           dy / Math.max(1f, density) * 0.30f);
            invalidate();
        }

        private void setButton(int id, boolean down) {
            if (down) {
                if (downButtons.add(id)) pressMappedButton(id, true);
            } else {
                if (downButtons.remove(id)) pressMappedButton(id, false);
            }
            invalidate();
        }

        private void updateStick(float x, float y) {
            float dx = x - stickCx, dy = y - stickCy;
            float dead = u() * .035f;
            Set<Integer> movement = new HashSet<>();
            if (dy < -dead) movement.add(KeyEvent.KEYCODE_W);
            if (dy > dead) movement.add(KeyEvent.KEYCODE_S);
            if (dx < -dead) movement.add(KeyEvent.KEYCODE_A);
            if (dx > dead) movement.add(KeyEvent.KEYCODE_D);
            if (Math.hypot(dx, dy) > u() * .115f) movement.add(KeyEvent.KEYCODE_SHIFT_LEFT);
            setKeySource("joystick", movement);
            float length = (float)Math.hypot(dx, dy), max = u() * .095f;
            if (length > max && length > 0) { dx *= max / length; dy *= max / length; }
            stickX = stickCx + dx; stickY = stickCy + dy;
            invalidate();
        }

        private void clearControls() {
            for (int id : new ArrayList<>(downButtons)) setButton(id, false);
            setKeySource("joystick", null);
            if (lookPointer >= 0) forwardLookTouch(lookDownTime, SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_CANCEL, lookX, lookY, 0);
            pointerButtons.clear(); stickPointer = -1; lookPointer = -1; cameraStickPointer = -1;
            invalidate();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_CANCEL) { clearControls(); return true; }
            int idx = e.getActionIndex();
            int pid = e.getPointerId(idx);
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                float x = e.getX(idx), y = e.getY(idx);
                int button = hitButton(x, y);
                if (button != 0) { pointerButtons.put(pid, button); setButton(button, true); return true; }
                if (cameraStickPointer < 0 && inCameraStickZone(x, y)) {
                    cameraStickPointer = pid;
                    cameraStickX = getWidth() - u() * .30f;
                    cameraStickY = u() * .34f;
                    invalidate(); return true;
                }
                if (stickPointer < 0 && inStickZone(x, y)) {
                    stickPointer = pid; stickCx = x; stickCy = y; stickX = x; stickY = y; updateStick(x, y); return true;
                }
                if (lookPointer < 0 && inLookZone(x, y)) {
                    lookPointer = pid; lookX = lookStartX = x; lookY = lookStartY = y;
                    lookMoved = false; lookDownTime = e.getEventTime();
                    forwardLookTouch(lookDownTime, e.getEventTime(), MotionEvent.ACTION_DOWN, x, y, e.getMetaState());
                    return true;
                }
                // Pass unrelated taps through when no overlay gesture currently owns the surface.
                return stickPointer >= 0 || lookPointer >= 0 || !pointerButtons.isEmpty();
            }
            if (action == MotionEvent.ACTION_MOVE) {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i); float x = e.getX(i), y = e.getY(i);
                    if (id == stickPointer) updateStick(x, y);
                    else if (id == cameraStickPointer) updateCameraStick(x, y);
                    else if (id == lookPointer) {
                        float dx = x - lookX, dy = y - lookY;
                        if (!lookMoved && Math.hypot(x - lookStartX, y - lookStartY) >= dp(4)) lookMoved = true;
                        forwardLookTouch(lookDownTime, e.getEventTime(), MotionEvent.ACTION_MOVE, x, y, e.getMetaState());
                        if (lookMoved && lookMode) {
                            float density = getResources().getDisplayMetrics().density;
                            emitMouseDelta(dx / density * 2.0f, dy / density * 2.0f);
                        }
                        lookX = x; lookY = y;
                    }
                }
                return stickPointer >= 0 || lookPointer >= 0 || !pointerButtons.isEmpty();
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
                float x = e.getX(idx), y = e.getY(idx);
                Integer button = pointerButtons.remove(pid);
                if (button != null) setButton(button, false);
                if (pid == stickPointer) { stickPointer = -1; setKeySource("joystick", null); }
                if (pid == cameraStickPointer) { cameraStickPointer = -1; invalidate(); }
                if (pid == lookPointer) {
                    forwardLookTouch(lookDownTime, e.getEventTime(), MotionEvent.ACTION_UP, x, y, e.getMetaState());
                    lookPointer = -1;
                }
                return true;
            }
            return stickPointer >= 0 || lookPointer >= 0 || !pointerButtons.isEmpty();
        }
    }
}
