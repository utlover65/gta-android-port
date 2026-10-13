package org.utlover65.astravulkanprobe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends Activity implements SurfaceHolder.Callback {
    private static final int MODE_DAWN = 1;
    private static final int MODE_VULKAN = 2;

    static {
        // Dawn is explicitly loaded before the JNI library that links against it.
        System.loadLibrary("webgpu_dawn");
        System.loadLibrary("vulkan_probe");
        System.loadLibrary("dawn_probe");
    }

    static native String presentVulkanFrame(Surface surface);
    static native String presentWebGPUFrame(Surface surface);
    static native void releaseWebGPU();

    private final ExecutorService renderExecutor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "astra-render-test"));
    private final AtomicInteger requestSerial = new AtomicInteger(0);

    private TextView status;
    private Surface currentSurface;
    private volatile boolean surfaceReady = false;
    private volatile int selectedMode = MODE_DAWN;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        FrameLayout root = new FrameLayout(this);
        SurfaceView surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0xCC101820);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextSize(14);
        status.setPadding(18, 14, 18, 14);
        status.setText("Starting Dawn WebGPU Android/Vulkan test...");
        FrameLayout.LayoutParams statusParams =
                new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(status, statusParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(8, 6, 8, 6);
        controls.setBackgroundColor(0xDD101820);

        Button dawnButton = new Button(this);
        dawnButton.setText("Dawn WebGPU");
        dawnButton.setAllCaps(false);
        dawnButton.setOnClickListener(v -> selectRenderer(MODE_DAWN));
        controls.addView(dawnButton,
                new LinearLayout.LayoutParams(0, -2, 1));

        Button vulkanButton = new Button(this);
        vulkanButton.setText("Vulkan fallback");
        vulkanButton.setAllCaps(false);
        vulkanButton.setOnClickListener(v -> selectRenderer(MODE_VULKAN));
        controls.addView(vulkanButton,
                new LinearLayout.LayoutParams(0, -2, 1));

        Button browserPortButton = new Button(this);
        browserPortButton.setText("Browser port");
        browserPortButton.setAllCaps(false);
        browserPortButton.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, BrowserPortActivity.class)));
        controls.addView(browserPortButton,
                new LinearLayout.LayoutParams(0, -2, 1));

        Button nativeGameplayButton = new Button(this);
        nativeGameplayButton.setText("Native game audit");
        nativeGameplayButton.setAllCaps(false);
        nativeGameplayButton.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, NativeGameplayPreflightActivity.class)));
        controls.addView(nativeGameplayButton,
                new LinearLayout.LayoutParams(0, -2, 1));

        Button nativeEngineButton = new Button(this);
        nativeEngineButton.setText("Native WASM engine");
        nativeEngineButton.setAllCaps(false);
        nativeEngineButton.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, NativeGameEngineActivity.class)));
        controls.addView(nativeEngineButton,
                new LinearLayout.LayoutParams(0, -2, 1));

        Button nativeEngineButton = new Button(this);
        nativeEngineButton.setText("Native WASM engine");
        nativeEngineButton.setAllCaps(false);
        nativeEngineButton.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, NativeGameEngineActivity.class)));
        controls.addView(nativeEngineButton,
                new LinearLayout.LayoutParams(0, -2, 1));

        Button nativeEngineButton = new Button(this);
        nativeEngineButton.setText("Native WASM engine");
        nativeEngineButton.setAllCaps(false);
        nativeEngineButton.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, NativeGameEngineActivity.class)));
        controls.addView(nativeEngineButton,
                new LinearLayout.LayoutParams(0, -2, 1));

        Button nativeEngineButton = new Button(this);
        nativeEngineButton.setText("Native WASM engine");
        nativeEngineButton.setAllCaps(false);
        nativeEngineButton.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, NativeGameEngineActivity.class)));
        controls.addView(nativeEngineButton,
                new LinearLayout.LayoutParams(0, -2, 1));

        Button nativeEngineButton = new Button(this);
        nativeEngineButton.setText("Native WASM engine");
        nativeEngineButton.setAllCaps(false);
        nativeEngineButton.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, NativeGameEngineActivity.class)));
        controls.addView(nativeEngineButton,
                new LinearLayout.LayoutParams(0, -2, 1));

        Button nativePortTestButton = new Button(this);
        nativePortTestButton.setText("Native host test");
        nativePortTestButton.setAllCaps(false);
        nativePortTestButton.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, NativePortTestActivity.class)));
        controls.addView(nativePortTestButton,
                new LinearLayout.LayoutParams(0, -2, 1));

        FrameLayout.LayoutParams controlsParams =
                new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(controls, controlsParams);
        setContentView(root);
    }

    private void selectRenderer(int mode) {
        selectedMode = mode;
        int request = requestSerial.incrementAndGet();
        if (!surfaceReady || currentSurface == null) {
            status.setText("Waiting for Android surface...");
            return;
        }

        Surface surface = currentSurface;
        status.setText(mode == MODE_DAWN
                ? "Running Dawn WebGPU adapter/device/surface test..."
                : "Running the known-good Vulkan presentation test...");

        renderExecutor.execute(() -> {
            String result;
            try {
                if (mode == MODE_DAWN) {
                    result = presentWebGPUFrame(surface);
                } else {
                    releaseWebGPU();
                    result = presentVulkanFrame(surface);
                }
            } catch (Throwable t) {
                result = "FAIL: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            final String message = result;
            runOnUiThread(() -> {
                if (request == requestSerial.get() && status != null) {
                    status.setText(message);
                }
            });
        });
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        currentSurface = holder.getSurface();
        surfaceReady = true;
        selectRenderer(selectedMode);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (!surfaceReady) {
            status.setText("Surface size: " + width + "x" + height);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        currentSurface = null;
        requestSerial.incrementAndGet();
        renderExecutor.execute(MainActivity::releaseWebGPU);
    }

    @Override
    protected void onDestroy() {
        requestSerial.incrementAndGet();
        renderExecutor.execute(MainActivity::releaseWebGPU);
        renderExecutor.shutdown();
        super.onDestroy();
    }
}
