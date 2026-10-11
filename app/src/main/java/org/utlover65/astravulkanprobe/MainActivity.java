package org.utlover65.astravulkanprobe;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.TextView;

public final class MainActivity extends Activity
        implements SurfaceHolder.Callback {

    static {
        System.loadLibrary("vulkan_probe");
    }

    private static native String presentVulkanFrame(Surface surface);

    private TextView status;
    private volatile boolean testStarted = false;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        FrameLayout root = new FrameLayout(this);

        SurfaceView surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);

        root.addView(
            surfaceView,
            new FrameLayout.LayoutParams(-1, -1)
        );

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0xB0000000);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextSize(14);
        status.setPadding(20, 16, 20, 16);
        status.setText("Waiting for Android surface...");

        root.addView(
            status,
            new FrameLayout.LayoutParams(-1, -2)
        );

        setContentView(root);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (testStarted) return;
        testStarted = true;

        status.setText("Testing Vulkan surface presentation...");

        Surface surface = holder.getSurface();

        new Thread(() -> {
            String result;

            try {
                result = presentVulkanFrame(surface);
            } catch (Throwable t) {
                result = "FAIL: " + t;
            }

            final String message = result;

            runOnUiThread(() -> status.setText(message));
        }, "vulkan-present-test").start();
    }

    @Override
    public void surfaceChanged(
            SurfaceHolder holder,
            int format,
            int width,
            int height
    ) {
        if (!testStarted) {
            status.setText(
                "Surface size: " + width + "x" + height
            );
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // The native test is one-shot. Relaunch the app to rerun it.
    }
        }
