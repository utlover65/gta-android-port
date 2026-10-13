package org.utlover65.astravulkanprobe;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native Android host smoke tests. This does not load or emulate the GTA WASM engine. */
public final class NativePortTestActivity extends Activity implements SurfaceHolder.Callback {
    static { System.loadLibrary("native_host_test"); }
    private static native String runNativeChecks(String appFilesDir);
    private final ExecutorService testExecutor = Executors.newSingleThreadExecutor(r ->
            new Thread(r, "native-port-host-test"));
    private final AtomicBoolean alive = new AtomicBoolean(true);

    private TextView report;
    private SurfaceView renderSurface;
    private Surface nativeSurface;
    private volatile boolean surfaceReady;
    private volatile boolean testRunning;
    private String dawnResult = "NOT RUN";
    private String vulkanResult = "NOT RUN";
    private String storageResult = "NOT RUN";
    private String audioResult = "NOT RUN";
    private String workerResult = "NOT RUN";
    private String nativeHostResult = "NOT RUN";
    private String inputResult = "WAITING FOR TAP";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(8, 12, 18));

        TextView title = new TextView(this);
        title.setText("NATIVE ANDROID PORT TEST — HOST ONLY");
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setTextSize(15);
        title.setPadding(12, 10, 12, 8);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout graphicsBox = new FrameLayout(this);
        renderSurface = new SurfaceView(this);
        renderSurface.getHolder().addCallback(this);
        renderSurface.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                inputResult = "PASS — touch x=" + Math.round(event.getX()) +
                        " y=" + Math.round(event.getY());
                refreshReport();
                return true;
            }
            return true;
        });
        graphicsBox.addView(renderSurface, new FrameLayout.LayoutParams(-1, -1));
        TextView hint = new TextView(this);
        hint.setText("Native ANativeWindow output area\nTap here to test touch input");
        hint.setTextColor(0xCCFFFFFF);
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        hint.setBackgroundColor(0x66101820);
        hint.setClickable(false);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(-1, -1);
        graphicsBox.addView(hint, hintParams);
        root.addView(graphicsBox, new LinearLayout.LayoutParams(-1, 0, 1.0f));

        report = new TextView(this);
        report.setTextColor(0xFFE5EDF7);
        report.setTypeface(Typeface.MONOSPACE);
        report.setTextSize(11);
        report.setPadding(12, 8, 12, 8);
        ScrollView reportScroll = new ScrollView(this);
        reportScroll.addView(report);
        root.addView(reportScroll, new LinearLayout.LayoutParams(-1, 0, 1.0f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addButton(row, "RUN ALL", v -> runFullSuite());
        addButton(row, "DAWN", v -> runRenderer(true));
        addButton(row, "VULKAN", v -> runRenderer(false));
        addButton(row, "AUDIO", v -> runAudioTest());
        root.addView(row, new LinearLayout.LayoutParams(-1, -2));

        Button back = new Button(this);
        back.setText("Back to probe menu");
        back.setAllCaps(false);
        back.setOnClickListener(v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        refreshReport();
    }

    private void addButton(LinearLayout row, String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void runFullSuite() {
        if (testRunning) return;
        if (!surfaceReady || nativeSurface == null || !nativeSurface.isValid()) {
            setReportPrefix("WAITING: Android render surface is not ready yet.");
            return;
        }
        testRunning = true;
        dawnResult = vulkanResult = storageResult = audioResult = workerResult = "RUNNING";
        refreshReport();
        testExecutor.execute(() -> {
            long t = System.nanoTime();
            String r;
            try {
                r = MainActivity.presentWebGPUFrame(nativeSurface);
            } catch (Throwable ex) {
                r = "FAIL — " + ex.getClass().getSimpleName() + ": " + safeMessage(ex);
            }
            dawnResult = resultWithDuration(r, t);
            postReport();

            try {
                MainActivity.releaseWebGPU();
                t = System.nanoTime();
                r = MainActivity.presentVulkanFrame(nativeSurface);
            } catch (Throwable ex) {
                r = "FAIL — " + ex.getClass().getSimpleName() + ": " + safeMessage(ex);
            }
            vulkanResult = resultWithDuration(r, t);
            postReport();

            storageResult = testStorage();
            postReport();
            workerResult = testWorker();
            try {
                nativeHostResult = runNativeChecks(getFilesDir().getAbsolutePath());
            } catch (Throwable ex) {
                nativeHostResult = "FAIL — " + ex.getClass().getSimpleName() + ": " + safeMessage(ex);
            }
            postReport();
            audioResult = playTestTone();
            postReport();
            testRunning = false;
            postReport();
        });
    }

    private void runRenderer(boolean dawn) {
        if (testRunning) return;
        if (!surfaceReady || nativeSurface == null || !nativeSurface.isValid()) {
            setReportPrefix("WAITING: Android render surface is not ready yet.");
            return;
        }
        testRunning = true;
        refreshReport();
        testExecutor.execute(() -> {
            long start = System.nanoTime();
            String result;
            try {
                if (dawn) {
                    result = MainActivity.presentWebGPUFrame(nativeSurface);
                    dawnResult = resultWithDuration(result, start);
                } else {
                    MainActivity.releaseWebGPU();
                    result = MainActivity.presentVulkanFrame(nativeSurface);
                    vulkanResult = resultWithDuration(result, start);
                }
            } catch (Throwable ex) {
                result = "FAIL — " + ex.getClass().getSimpleName() + ": " + safeMessage(ex);
                if (dawn) dawnResult = result; else vulkanResult = result;
            }
            testRunning = false;
            postReport();
        });
    }

    private void runAudioTest() {
        if (testRunning) return;
        testRunning = true;
        audioResult = "RUNNING";
        refreshReport();
        testExecutor.execute(() -> {
            audioResult = playTestTone();
            testRunning = false;
            postReport();
        });
    }

    private String testStorage() {
        File file = new File(getFilesDir(), "native-host-self-test.tmp");
        byte[] expected = "astra-native-host-test-v1".getBytes(StandardCharsets.UTF_8);
        try {
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(expected); }
            byte[] actual = new byte[expected.length];
            int offset = 0;
            try (FileInputStream in = new FileInputStream(file)) {
                while (offset < actual.length) {
                    int n = in.read(actual, offset, actual.length - offset);
                    if (n < 0) break;
                    offset += n;
                }
            }
            boolean pass = offset == expected.length;
            for (int i = 0; pass && i < expected.length; i++) pass = expected[i] == actual[i];
            return pass ? "PASS — app-private write/read/delete" : "FAIL — file contents differed";
        } catch (Exception ex) {
            return "FAIL — " + ex.getClass().getSimpleName() + ": " + safeMessage(ex);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private String testWorker() {
        final Object lock = new Object();
        final boolean[] completed = {false};
        Thread thread = new Thread(() -> {
            synchronized (lock) { completed[0] = true; lock.notifyAll(); }
        }, "native-port-worker-check");
        thread.start();
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 2000;
            while (!completed[0] && System.currentTimeMillis() < deadline) {
                try { lock.wait(Math.max(1, deadline - System.currentTimeMillis())); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); return "FAIL — interrupted"; }
            }
        }
        return completed[0] ? "PASS — background Java thread scheduled" : "FAIL — worker timeout";
    }

    private String playTestTone() {
        ToneGenerator generator = null;
        try {
            generator = new ToneGenerator(AudioManager.STREAM_MUSIC, 65);
            boolean started = generator.startTone(ToneGenerator.TONE_PROP_BEEP, 180);
            if (!started) return "FAIL — Android ToneGenerator refused test tone";
            try { Thread.sleep(220); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            return "PASS — Android audio API accepted test tone (listen for beep)";
        } catch (Throwable ex) {
            return "FAIL — " + ex.getClass().getSimpleName() + ": " + safeMessage(ex);
        } finally {
            if (generator != null) generator.release();
        }
    }

    private String resultWithDuration(String result, long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000L;
        return (result == null ? "FAIL — no result" : result.replace('\n', ' ')) + " [" + ms + " ms]";
    }

    private String safeMessage(Throwable ex) {
        String message = ex.getMessage();
        return message == null ? "no detail" : message.replace('\n', ' ');
    }

    private void postReport() {
        runOnUiThread(this::refreshReport);
    }

    private void setReportPrefix(String prefix) {
        report.setText(prefix + "\n\n" + reportText());
    }

    private String reportText() {
        return "Android Surface: " + (surfaceReady ? "PASS" : "WAITING") + "\n" +
                "Dawn WebGPU native frame: " + dawnResult + "\n" +
                "Vulkan native frame: " + vulkanResult + "\n" +
                "App-private filesystem: " + storageResult + "\n" +
                "Java worker scheduling: " + workerResult + "\n" +
                "NDK thread + file I/O:\n" + nativeHostResult + "\n" +
                "Android audio API: " + audioResult + "\n" +
                "Touch input: " + inputResult + "\n\n" +
                "GAME ENGINE: NOT INTEGRATED\n" +
                "This validates Android host subsystems only. The current GTA bundle is a browser/WASM build with custom JS, GPU-worker, IO-worker and audio-worklet imports; it is not a native game library.\n" +
                "Result is not a GTA gameplay pass.";
    }

    private void refreshReport() {
        if (report != null && alive.get()) report.setText(reportText());
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        nativeSurface = holder.getSurface();
        surfaceReady = nativeSurface != null && nativeSurface.isValid();
        refreshReport();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        refreshReport();
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        nativeSurface = null;
        refreshReport();
        testExecutor.execute(MainActivity::releaseWebGPU);
    }

    @Override protected void onDestroy() {
        alive.set(false);
        testExecutor.execute(MainActivity::releaseWebGPU);
        testExecutor.shutdown();
        super.onDestroy();
    }
}
