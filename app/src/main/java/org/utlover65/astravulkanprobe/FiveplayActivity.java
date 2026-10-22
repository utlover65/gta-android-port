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

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Set<Integer>> keySources = new HashMap<>();
    private final Set<Integer> emittedKeys = new HashSet<>();
    private WebView webView;
    private TextView status;
    private TouchOverlay overlay;
    private boolean statusPinned;
    private boolean pageFinished;
    private int pageProgress;

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
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri uri = request.getUrl();
                if (!"https".equalsIgnoreCase(uri.getScheme())) {
                    Toast.makeText(FiveplayActivity.this, "Blocked non-HTTPS navigation", Toast.LENGTH_SHORT).show();
                    return true;
                }
                String host = uri.getHost();
                if (host != null && (host.equalsIgnoreCase("fiveplay.lol") || host.toLowerCase(Locale.ROOT).endsWith(".fiveplay.lol"))) return false;
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
                    status.setText("Connecting to the game website…");
                }
            }

            @Override public void onPageFinished(WebView view, String url) {
                pageFinished = true;
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
        webView.loadUrl(START_URL);
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

    private void dispatchKey(int keyCode, boolean down) {
        if (webView == null) return;
        webView.requestFocus();
        long now = SystemClock.uptimeMillis();
        webView.dispatchKeyEvent(new KeyEvent(now, now, down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, keyCode, 0));
    }

    private void setKeySource(String source, Set<Integer> codes) {
        if (codes == null || codes.isEmpty()) keySources.remove(source);
        else keySources.put(source, new HashSet<>(codes));
        Set<Integer> next = new HashSet<>();
        for (Set<Integer> set : keySources.values()) next.addAll(set);
        for (int code : new HashSet<>(emittedKeys)) if (!next.contains(code)) { dispatchKey(code, false); emittedKeys.remove(code); }
        for (int code : next) if (emittedKeys.add(code)) dispatchKey(code, true);
    }

    private void emitMouseDelta(float dx, float dy) {
        if (webView == null || (Math.abs(dx) < 0.5f && Math.abs(dy) < 0.5f)) return;
        String js = "(function(){const t=document.pointerLockElement||document.querySelector('canvas')||document;" +
                "const e=new MouseEvent('mousemove',{bubbles:true,cancelable:true,clientX:innerWidth*.6,clientY:innerHeight*.5});" +
                "Object.defineProperties(e,{movementX:{value:" + Math.round(dx) + "},movementY:{value:" + Math.round(dy) + "}});t.dispatchEvent(e)})()";
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
            case TouchOverlay.LOOK: if (down && overlay != null) { overlay.lookMode = !overlay.lookMode; overlay.invalidate(); } break;
        }
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
                CAMERA = 5, PAUSE = 6, LOOK = 7;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Map<Integer, Integer> pointerButtons = new HashMap<>();
        private final Set<Integer> downButtons = new HashSet<>();
        private int stickPointer = -1, lookPointer = -1;
        private float stickCx, stickCy, stickX, stickY, lookX, lookY;
        private float unit;
        boolean lookMode;

        TouchOverlay() { super(FiveplayActivity.this); setLayerType(View.LAYER_TYPE_SOFTWARE, null); setClickable(false); }
        private float u() { return Math.max(1f, getHeight()); }
        private float bx(float right) { return getWidth() - u() * right; }
        private float by(float fraction) { return u() * fraction; }
        private float br(float fraction) { return u() * fraction; }

        private final float[][] buttonDefs = {
                {JUMP, .145f, .67f, .061f}, {ENTER, .145f, .49f, .052f},
                {INTERACT, .145f, .32f, .050f}, {SPRINT, .425f, .72f, .051f},
                {CAMERA, .425f, .57f, .047f}, {PAUSE, .105f, .13f, .045f},
                {LOOK, .285f, .13f, .045f}
        };

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            unit = u();
            p.setStrokeWidth(dp(2)); p.setStyle(Paint.Style.STROKE); p.setColor(0x68FFFFFF);
            float leftCx = unit * .18f, leftCy = unit * .77f, leftR = unit * .13f;
            c.drawCircle(leftCx, leftCy, leftR, p);
            c.drawCircle(leftCx, leftCy, leftR * .52f, p);
            if (stickPointer >= 0) { p.setStyle(Paint.Style.FILL); p.setColor(0x72FFFFFF); c.drawCircle(stickX, stickY, unit * .052f, p); }
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
                default: return lookMode ? "LOOK ON" : "LOOK";
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
            return lookMode && x > getWidth() * .45f && y > u() * .18f && y < u() * .92f;
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
            pointerButtons.clear(); stickPointer = -1; lookPointer = -1;
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
                if (stickPointer < 0 && inStickZone(x, y)) {
                    stickPointer = pid; stickCx = x; stickCy = y; stickX = x; stickY = y; updateStick(x, y); return true;
                }
                if (lookPointer < 0 && inLookZone(x, y)) { lookPointer = pid; lookX = x; lookY = y; return true; }
                // Let ordinary world taps pass through to the WebView so its launch UI / pointer lock can receive them.
                return stickPointer >= 0 || lookPointer >= 0 || !pointerButtons.isEmpty();
            }
            if (action == MotionEvent.ACTION_MOVE) {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i); float x = e.getX(i), y = e.getY(i);
                    if (id == stickPointer) updateStick(x, y);
                    else if (id == lookPointer) {
                        float density = getResources().getDisplayMetrics().density;
                        emitMouseDelta((x - lookX) / density * 1.5f, (y - lookY) / density * 1.5f);
                        lookX = x; lookY = y;
                    }
                }
                return stickPointer >= 0 || lookPointer >= 0 || !pointerButtons.isEmpty();
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
                Integer button = pointerButtons.remove(pid);
                if (button != null) setButton(button, false);
                if (pid == stickPointer) { stickPointer = -1; setKeySource("joystick", null); }
                if (pid == lookPointer) lookPointer = -1;
                return true;
            }
            return stickPointer >= 0 || lookPointer >= 0 || !pointerButtons.isEmpty();
        }
    }
}
