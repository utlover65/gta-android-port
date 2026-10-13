package org.utlover65.astravulkanprobe;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Live runtime/gameplay smoke test for the actual browser-delivered GTA build.
 * This is intentionally not described as a native-engine test: the supplied game.wasm
 * depends on Emscripten browser workers, WebGPU, asset I/O, audio, and input host services.
 */
public final class BrowserPortActivity extends Activity {
    private static final String TAG = "GtaGameplayTest";
    private static final String HOST = "gta.deloskiyt.pl";
    private static final String GAME_URL = "https://gta.deloskiyt.pl/?low=1&scale=0.75&fps=30&log=1&console=1";
    private static final long TEST_WINDOW_MS = 90_000L;
    private static final long TELEMETRY_INTERVAL_MS = 1_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> consoleTail = new ArrayDeque<>();
    private WebView webView;
    private TextView diagnostic;
    private TextView testResult;
    private LinearLayout controls;
    private Button runButton;
    private boolean diagnosticHidden;
    private boolean pageReady;
    private boolean testRunning;
    private long testStartedAt;
    private int inputActions;
    private int documentKeyEventsAtTestStart;
    private JSONObject lastSnapshot = new JSONObject();
    private Bitmap beforeInputBitmap;
    private double lastVisualDiffPercent = -1.0;
    private boolean inputPulseStarted;
    private String visualDiffStatus = "not measured";

    private final Runnable telemetryLoop = new Runnable() {
        @Override public void run() {
            if (webView == null) return;
            collectSnapshot();
            if (testRunning) {
                long elapsed = System.currentTimeMillis() - testStartedAt;
                if (elapsed >= TEST_WINDOW_MS) finishLiveTest();
                else updateDiagnostic("LIVE TEST RUNNING — " + (TEST_WINDOW_MS - elapsed) / 1000 + "s left");
            }
            handler.postDelayed(this, TELEMETRY_INTERVAL_MS);
        }
    };

    @Override protected void onCreate(Bundle state) {
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
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                String line = message.message() + " (" + message.sourceId() + ":" + message.lineNumber() + ")";
                addConsoleLine(message.messageLevel().toString(), line);
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Log.e(TAG, line);
                else if (message.messageLevel() == ConsoleMessage.MessageLevel.WARNING) Log.w(TAG, line);
                else Log.d(TAG, line);
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                String host = request.getUrl().getHost();
                return !"https".equalsIgnoreCase(scheme) || host == null || !HOST.equalsIgnoreCase(host);
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String host = request.getUrl().getHost();
                if (host != null && host.equalsIgnoreCase("quge5.com")) {
                    return new WebResourceResponse("application/javascript", "UTF-8",
                            new ByteArrayInputStream(new byte[0]));
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageReady = false;
                updateDiagnostic("Loading the real game page and WASM runtime…");
            }

            @Override public void onPageFinished(WebView view, String url) {
                pageReady = true;
                installTelemetryProbe();
                updateDiagnostic("Live game loaded. Start the 90-second runtime test; use Tap/Enter if a mode prompt appears.");
            }
        });
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));

        diagnostic = new TextView(this);
        diagnostic.setTextColor(Color.WHITE);
        diagnostic.setBackgroundColor(0xD9101820);
        diagnostic.setTextSize(11);
        diagnostic.setPadding(12, 8, 12, 8);
        diagnostic.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        diagnostic.setText("Opening live gameplay test…");
        diagnostic.setOnClickListener(v -> {
            diagnosticHidden = !diagnosticHidden;
            diagnostic.setVisibility(diagnosticHidden ? View.GONE : View.VISIBLE);
        });
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(diagnostic, topParams);

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(6, 4, 6, 4);
        controls.setBackgroundColor(0xC9101820);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        runButton = addButton(row1, "Run 90s test", v -> startLiveTest(), 2.0f);
        addButton(row1, "Tap center", v -> tapGameCenter(), 1.0f);
        addButton(row1, "Enter", v -> sendKey(KeyEvent.KEYCODE_ENTER, "ENTER"), 1.0f);
        addButton(row1, "Hide stats", v -> toggleDiagnostic(), 0.75f);
        controls.addView(row1, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        addButton(row2, "W", v -> sendKey(KeyEvent.KEYCODE_W, "W"), 1.0f);
        addButton(row2, "A", v -> sendKey(KeyEvent.KEYCODE_A, "A"), 1.0f);
        addButton(row2, "S", v -> sendKey(KeyEvent.KEYCODE_S, "S"), 1.0f);
        addButton(row2, "D", v -> sendKey(KeyEvent.KEYCODE_D, "D"), 1.0f);
        addButton(row2, "Space", v -> sendKey(KeyEvent.KEYCODE_SPACE, "SPACE"), 1.0f);
        controls.addView(row2, new LinearLayout.LayoutParams(-1, -2));

        testResult = new TextView(this);
        testResult.setTextColor(0xFFE5EDF7);
        testResult.setTextSize(10);
        testResult.setPadding(8, 3, 8, 3);
        testResult.setText("Not run. This test uses the real game runtime, not a synthetic scene.");
        controls.addView(testResult, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(controls, bottomParams);
        setContentView(root);

        handler.postDelayed(telemetryLoop, 500L);
        webView.loadUrl(GAME_URL);
    }

    private Button addButton(LinearLayout row, String title, View.OnClickListener listener, float weight) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(11);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(3, 1, 3, 1);
        button.setOnClickListener(listener);
        row.addView(button, new LinearLayout.LayoutParams(0, -2, weight));
        return button;
    }

    private void installTelemetryProbe() {
        if (webView == null) return;
        String js = "(function(){try{" +
                "if(!window.__gtaGameplayTest){" +
                "const s=window.__gtaGameplayTest={started:Date.now(),raf:0,progress:0,label:'waiting for engine progress',mode:0,modeCommitted:false,waitingForMode:false,crashes:[],logs:[],keyEvents:0,pointerEvents:0,gameWasmSeen:false,gameJsSeen:false};" +
                "const keep=(a,v,n)=>{a.push(String(v).slice(0,1200));if(a.length>n)a.shift()};" +
                "try{const p=new BroadcastChannel('game-progress');p.onmessage=e=>{const m=e.data||{};if(typeof m.pct==='number'){s.progress=Math.max(s.progress,m.pct);s.label=String(m.label||s.label)}if(m.choice)s.waitingForMode=true;if(m.commit){s.modeCommitted=true;s.mode=Number(m.mode||0);s.waitingForMode=false}keep(s.logs,'progress '+JSON.stringify(m),40)}}catch(e){}" +
                "try{const c=new BroadcastChannel('game-crash');c.onmessage=e=>{const m=e.data||{};keep(s.crashes,JSON.stringify(m),10)}}catch(e){}" +
                "try{const l=new BroadcastChannel('game-log');l.onmessage=e=>keep(s.logs,e.data,40)}catch(e){}" +
                "addEventListener('error',e=>keep(s.crashes,'window error: '+(e.message||e.error),10),true);" +
                "addEventListener('unhandledrejection',e=>keep(s.crashes,'unhandled rejection: '+e.reason,10),true);" +
                "document.addEventListener('keydown',()=>s.keyEvents++,true);document.addEventListener('pointerdown',()=>s.pointerEvents++,true);document.addEventListener('touchstart',()=>s.pointerEvents++,true);" +
                "const beat=()=>{s.raf++;requestAnimationFrame(beat)};requestAnimationFrame(beat);" +
                "}" +
                "const s=window.__gtaGameplayTest;const r=performance.getEntriesByType('resource').map(x=>x.name);s.gameWasmSeen=s.gameWasmSeen||r.some(x=>/game\\.wasm(?:[?#]|$)/i.test(x));s.gameJsSeen=s.gameJsSeen||r.some(x=>/game\\.js(?:[?#]|$)/i.test(x));" +
                "const canv=[...document.querySelectorAll('canvas')].map(c=>({w:c.width,h:c.height,cw:Math.round(c.getBoundingClientRect().width),ch:Math.round(c.getBoundingClientRect().height)}));" +
                "return JSON.stringify({state:s,canvases:canv,secure:!!isSecureContext,isolated:!!crossOriginIsolated,sab:typeof SharedArrayBuffer!=='undefined',webgpu:!!(navigator.gpu),workers:typeof Worker==='function',resourceCount:r.length,url:location.href});" +
                "}catch(e){return JSON.stringify({probeError:String(e)})}})()";
        webView.evaluateJavascript(js, value -> {
            if (value == null) return;
            try {
                Object decoded = new org.json.JSONTokener(value).nextValue();
                if (decoded instanceof String) decoded = new org.json.JSONTokener((String) decoded).nextValue();
                if (decoded instanceof JSONObject) {
                    lastSnapshot = (JSONObject) decoded;
                    renderTelemetry();
                }
            } catch (Exception e) { Log.w(TAG, "Telemetry snapshot parse failed: " + value, e); }
        });
    }

    private void collectSnapshot() {
        if (webView != null && pageReady) installTelemetryProbe();
    }

    private void renderTelemetry() {
        try {
            JSONObject s = lastSnapshot.optJSONObject("state");
            if (s == null) return;
            JSONArray ca = lastSnapshot.optJSONArray("canvases");
            int canvasCount = ca == null ? 0 : ca.length();
            boolean visibleCanvas = false;
            if (ca != null) for (int i = 0; i < ca.length(); i++) {
                JSONObject c = ca.optJSONObject(i);
                if (c != null && c.optInt("w") > 0 && c.optInt("h") > 0 && c.optInt("cw") > 0 && c.optInt("ch") > 0) visibleCanvas = true;
            }
            String stage = s.optString("label", "no stage reported");
            if (testRunning && s.optInt("progress") >= 84 && !inputPulseStarted) runWorldInputPulse();
            JSONArray crashArray = s.optJSONArray("crashes");
            int crashCount = crashArray == null ? 0 : crashArray.length();
            String text = "LIVE GAMEPLAY TEST — actual WebView game runtime\n" +
                    "World boot: " + s.optInt("progress") + "% · " + stage + "\n" +
                    "Page RAF ticks: " + s.optLong("raf") + " · canvas elements: " + canvasCount +
                    " · visible drawing surface: " + visibleCanvas + "\n" +
                    "WebGPU=" + lastSnapshot.optBoolean("webgpu") +
                    " · SharedArrayBuffer=" + lastSnapshot.optBoolean("sab") +
                    " · isolated=" + lastSnapshot.optBoolean("isolated") +
                    " · game.js=" + s.optBoolean("gameJsSeen") +
                    " · game.wasm=" + s.optBoolean("gameWasmSeen") + "\n" +
                    "Document key events: " + s.optInt("keyEvents") +
                    " · pointer/touch events: " + s.optInt("pointerEvents") +
                    " · crash reports: " + crashCount + "\n" +
                    "Tap this panel to hide it. Start the 90s test, choose Story/Sandbox if prompted, then try movement keys.";
            diagnostic.setText(text);
            testResult.setText("Runtime telemetry active · stage " + s.optInt("progress") + "% · visual delta: " + visualDiffStatus);
        } catch (Exception e) { Log.w(TAG, "Could not render test telemetry", e); }
    }

    private void startLiveTest() {
        if (!pageReady || webView == null) {
            testResult.setText("BLOCKED: the live game page has not finished loading.");
            return;
        }
        testRunning = true;
        testStartedAt = System.currentTimeMillis();
        inputActions = 0;
        documentKeyEventsAtTestStart = 0;
        JSONObject s = lastSnapshot.optJSONObject("state");
        if (s != null) documentKeyEventsAtTestStart = s.optInt("keyEvents");
        lastVisualDiffPercent = -1.0;
        inputPulseStarted = false;
        visualDiffStatus = "waiting for world stage before W pulse";
        testResult.setText("RUNNING: leave the game open and tap a start mode if prompted. Once the world loads, the test sends a 1.2-second W input and samples the screen. Timeout: 90 seconds.");
        runButton.setEnabled(false);
    }

    private void finishLiveTest() {
        testRunning = false;
        if (runButton != null) runButton.setEnabled(true);
        try {
            JSONObject s = lastSnapshot.optJSONObject("state");
            if (s == null) throw new IllegalStateException("No page telemetry snapshot was collected.");
            JSONArray canvases = lastSnapshot.optJSONArray("canvases");
            boolean visibleCanvas = false;
            if (canvases != null) for (int i = 0; i < canvases.length(); i++) {
                JSONObject c = canvases.optJSONObject(i);
                if (c != null && c.optInt("w") > 0 && c.optInt("h") > 0 && c.optInt("cw") > 0 && c.optInt("ch") > 0) visibleCanvas = true;
            }
            int progress = s.optInt("progress");
            int crashes = s.optJSONArray("crashes") == null ? 0 : s.optJSONArray("crashes").length();
            int keyEvents = s.optInt("keyEvents");
            boolean bootedWorld = progress >= 84;
            boolean inputReachedPage = keyEvents > documentKeyEventsAtTestStart && inputActions > 0;
            boolean visualChanged = lastVisualDiffPercent >= 0.5;
            boolean passed = bootedWorld && visibleCanvas && inputReachedPage && visualChanged && crashes == 0;
            String outcome = passed ? "GAMEPLAY SMOKE: PASS" : "GAMEPLAY SMOKE: FAIL / INCOMPLETE";
            String summary = outcome + "\n" +
                    "World-loaded stage (>=84%): " + bootedWorld + " (observed " + progress + "%)\n" +
                    "Visible canvas: " + visibleCanvas + "\n" +
                    "Input reached page: " + inputReachedPage + " (key events " + keyEvents + ")\n" +
                    "Screen changed during input: " + visualChanged + " (" +
                    (lastVisualDiffPercent < 0 ? "PixelCopy unavailable" : String.format(Locale.US, "%.2f%% center pixels", lastVisualDiffPercent)) + ")\n" +
                    "Crash/unhandled-error reports: " + crashes + "\n" +
                    "This verifies the real browser game reached a world stage and processed input; it does not claim the separate native Wasmtime path is playable.";
            testResult.setText(summary);
            diagnosticHidden = false;
            diagnostic.setVisibility(View.VISIBLE);
            diagnostic.setText(summary);
            Log.i(TAG, summary);
        } catch (Exception e) {
            testResult.setText("GAMEPLAY SMOKE: FAIL\n" + e.getMessage());
        }
    }

    private void sendKey(int keyCode, String label) {
        pressKeyDown(keyCode, label);
        releaseKey(keyCode);
    }

    private void pressKeyDown(int keyCode, String label) {
        if (webView == null) return;
        webView.requestFocus();
        long now = android.os.SystemClock.uptimeMillis();
        webView.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        inputActions++;
        testResult.setText("Sent real Android WebView key event: " + label + " · input actions=" + inputActions);
        Log.i(TAG, "Input action: " + label);
    }

    private void releaseKey(int keyCode) {
        if (webView == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        webView.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    private void tapGameCenter() {
        if (webView == null) return;
        float x = webView.getWidth() / 2.0f;
        float y = webView.getHeight() / 2.0f;
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 70, MotionEvent.ACTION_UP, x, y, 0);
        webView.dispatchTouchEvent(down);
        webView.dispatchTouchEvent(up);
        down.recycle(); up.recycle();
        inputActions++;
        testResult.setText("Sent Android touch at game center · input actions=" + inputActions);
    }

    private void toggleDiagnostic() {
        diagnosticHidden = !diagnosticHidden;
        if (diagnostic != null) diagnostic.setVisibility(diagnosticHidden ? View.GONE : View.VISIBLE);
    }


    private void runWorldInputPulse() {
        if (!testRunning || inputPulseStarted || webView == null) return;
        inputPulseStarted = true;
        visualDiffStatus = "capturing world before W";
        testResult.setText("World stage reached. Capturing a before-frame, holding W for 1.2 seconds, then sampling the viewport.");
        captureBeforeInput(() -> {
            if (!testRunning) return;
            pressKeyDown(KeyEvent.KEYCODE_W, "W (world input pulse)");
            handler.postDelayed(() -> {
                releaseKey(KeyEvent.KEYCODE_W);
                handler.postDelayed(this::captureAfterInput, 350L);
            }, 1200L);
        });
    }

    private void captureBeforeInput(Runnable done) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            visualDiffStatus = "PixelCopy needs Android 8+";
            done.run();
            return;
        }
        Bitmap bitmap = newWindowBitmap();
        if (bitmap == null) { visualDiffStatus = "capture unavailable"; done.run(); return; }
        PixelCopy.request(getWindow(), bitmap, result -> {
            if (result == PixelCopy.SUCCESS) beforeInputBitmap = bitmap;
            else { bitmap.recycle(); beforeInputBitmap = null; visualDiffStatus = "before-frame PixelCopy error=" + result; }
            done.run();
        }, handler);
    }

    private void captureAfterInput() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || beforeInputBitmap == null) {
            visualDiffStatus = "PixelCopy unavailable";
            return;
        }
        Bitmap after = newWindowBitmap();
        if (after == null) { visualDiffStatus = "after-frame capture unavailable"; return; }
        PixelCopy.request(getWindow(), after, result -> {
            if (result == PixelCopy.SUCCESS) {
                lastVisualDiffPercent = centerRegionDiffPercent(beforeInputBitmap, after);
                visualDiffStatus = String.format(Locale.US, "%.2f%% central pixels changed", lastVisualDiffPercent);
            } else {
                lastVisualDiffPercent = -1.0;
                visualDiffStatus = "after-frame PixelCopy error=" + result;
            }
            if (beforeInputBitmap != null) { beforeInputBitmap.recycle(); beforeInputBitmap = null; }
            after.recycle();
            if (testRunning) testResult.setText("Input pulse captured · visual diff: " + visualDiffStatus + " · keep playing until the 90s test completes.");
        }, handler);
    }

    private Bitmap newWindowBitmap() {
        int width = getWindow().getDecorView().getWidth();
        int height = getWindow().getDecorView().getHeight();
        if (width <= 0 || height <= 0) return null;
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    }

    /** Difference across center viewport only; UI overlays at top/bottom are excluded. */
    private double centerRegionDiffPercent(Bitmap a, Bitmap b) {
        if (a == null || b == null || a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return -1.0;
        int left = a.getWidth() / 8;
        int right = a.getWidth() * 7 / 8;
        int top = a.getHeight() / 5;
        int bottom = a.getHeight() * 4 / 5;
        long changed = 0;
        long total = 0;
        int stepX = Math.max(1, (right - left) / 160);
        int stepY = Math.max(1, (bottom - top) / 90);
        for (int y = top; y < bottom; y += stepY) {
            for (int x = left; x < right; x += stepX) {
                int p = a.getPixel(x, y), q = b.getPixel(x, y);
                int delta = Math.abs(Color.red(p) - Color.red(q)) + Math.abs(Color.green(p) - Color.green(q)) + Math.abs(Color.blue(p) - Color.blue(q));
                if (delta > 36) changed++;
                total++;
            }
        }
        return total == 0 ? -1.0 : changed * 100.0 / total;
    }

    private void addConsoleLine(String level, String line) {
        while (consoleTail.size() >= 80) consoleTail.removeFirst();
        consoleTail.addLast(level + ": " + line);
        String lower = line.toLowerCase(Locale.US);
        if (lower.contains("fatal") || lower.contains("abort") || lower.contains("webgpu") || lower.contains("shader") || lower.contains("error")) {
            Log.d(TAG, "Significant console: " + line);
        }
    }

    private void updateDiagnostic(String value) {
        if (diagnostic != null && !diagnosticHidden) diagnostic.setText(value);
    }

    @Override public void onBackPressed() {
        if (testRunning) {
            testRunning = false;
            if (runButton != null) runButton.setEnabled(true);
            testResult.setText("Live test cancelled by user.");
            return;
        }
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (beforeInputBitmap != null) { beforeInputBitmap.recycle(); beforeInputBitmap = null; }
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
