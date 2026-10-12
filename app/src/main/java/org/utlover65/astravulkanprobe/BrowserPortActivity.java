package org.utlover65.astravulkanprobe;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Compatibility gate for the supplied browser port.
 *
 * This deliberately runs the original port in Android WebView first. It tests whether the
 * installed WebView exposes the browser APIs the WASM build requires before we replace its
 * browser-side WebGPU presentation with the native Dawn surface implementation.
 */
public final class BrowserPortActivity extends Activity {
    private static final String TAG = "GtaBrowserPort";
    private static final String HOST = "gta.deloskiyt.pl";
    private static final String GAME_URL = "https://gta.deloskiyt.pl/?low=1&scale=0.75&fps=30";

    private WebView webView;
    private TextView diagnostic;
    private boolean diagnosticHidden;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);
        // The game is served over HTTPS. Do not enable file access or mixed-content loading.
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                String line = message.message() + " (" + message.sourceId() + ":" + message.lineNumber() + ")";
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.e(TAG, line);
                } else if (message.messageLevel() == ConsoleMessage.MessageLevel.WARNING) {
                    Log.w(TAG, line);
                } else {
                    Log.d(TAG, line);
                }
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Keep navigation in the intended game origin; do not launch arbitrary sites
                // from an embedded game page.
                String scheme = request.getUrl().getScheme();
                String host = request.getUrl().getHost();
                return !"https".equalsIgnoreCase(scheme) || host == null || !HOST.equalsIgnoreCase(host);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // The captured index contains an unrelated third-party ad-tag script. Block it;
                // the game runtime, game data, shaders and title assets are same-origin.
                String host = request.getUrl().getHost();
                if (host != null && host.equalsIgnoreCase("quge5.com")) {
                    return new WebResourceResponse(
                            "application/javascript",
                            "UTF-8",
                            new ByteArrayInputStream(new byte[0]));
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                setDiagnostic("Loading browser runtime…");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // Report feature availability from the actual WebView, not from the Chrome app.
                String probe = "(() => JSON.stringify({" +
                        "secure: !!self.isSecureContext," +
                        "isolated: !!self.crossOriginIsolated," +
                        "sharedArrayBuffer: typeof SharedArrayBuffer !== 'undefined'," +
                        "webgpu: !!(navigator && navigator.gpu)," +
                        "offscreenCanvas: typeof OffscreenCanvas !== 'undefined'," +
                        "audioWorklet: typeof AudioWorkletNode !== 'undefined'," +
                        "workers: typeof Worker !== 'undefined'," +
                        "cores: navigator.hardwareConcurrency || 0" +
                        "}))()";
                view.evaluateJavascript(probe, value -> {
                    try {
                        String decoded = value == null ? "{}" : value;
                        if (decoded.startsWith("\"") && decoded.endsWith("\"")) {
                            decoded = new org.json.JSONTokener(decoded).nextValue().toString();
                        }
                        JSONObject caps = new JSONObject(decoded);
                        setDiagnostic("WebView runtime check (tap to hide)\n" +
                                "WebGPU=" + caps.optBoolean("webgpu") +
                                "  SharedArrayBuffer=" + caps.optBoolean("sharedArrayBuffer") +
                                "  crossOriginIsolated=" + caps.optBoolean("isolated") +
                                "\nOffscreenCanvas=" + caps.optBoolean("offscreenCanvas") +
                                "  AudioWorklet=" + caps.optBoolean("audioWorklet") +
                                "  cores=" + caps.optInt("cores") +
                                "\nA false WebGPU or isolation result means this WebView cannot run this build as-is.");
                    } catch (Exception e) {
                        Log.w(TAG, "Could not parse WebView capability report: " + value, e);
                        setDiagnostic("Page loaded; could not read runtime capabilities. Check WebView console logs.");
                    }
                });
            }
        });

        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));

        diagnostic = new TextView(this);
        diagnostic.setTextColor(Color.WHITE);
        diagnostic.setBackgroundColor(0xCC101820);
        diagnostic.setTextSize(11);
        diagnostic.setPadding(12, 8, 12, 8);
        diagnostic.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        diagnostic.setText("Opening the browser port…");
        diagnostic.setOnClickListener(v -> {
            diagnosticHidden = !diagnosticHidden;
            diagnostic.setVisibility(diagnosticHidden ? View.GONE : View.VISIBLE);
        });
        FrameLayout.LayoutParams diagnosticParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(diagnostic, diagnosticParams);
        setContentView(root);

        // The port's original server supplies COOP/COEP and all runtime/shader endpoints. This
        // is intentionally a remote-online baseline, not a claim that the ZIP is self-contained.
        webView.loadUrl(GAME_URL);
    }

    private void setDiagnostic(String text) {
        if (diagnostic != null && !diagnosticHidden) diagnostic.setText(text);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
