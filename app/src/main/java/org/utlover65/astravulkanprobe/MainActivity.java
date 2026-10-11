package org.utlover65.astravulkanprobe;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    static { System.loadLibrary("vulkan_probe"); }
    private static native String getVulkanReport();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setTextIsSelectable(true);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(14);
        text.setPadding(24, 24, 24, 24);
        text.setGravity(Gravity.TOP | Gravity.START);
        text.setText("Querying Vulkan...");
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        setContentView(scroll);
        new Thread(() -> {
            String result;
            try { result = getVulkanReport(); }
            catch (Throwable t) { result = "Probe failed: " + t; }
            final String report = result;
            runOnUiThread(() -> text.setText(report));
        }, "vulkan-probe").start();
    }
}
