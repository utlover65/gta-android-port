package org.utlover65.astravulkanprobe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native Android/Wasmtime experiment; this intentionally does not create a WebView. */
public final class NativeGameEngineActivity extends Activity {
    private static final int PICK_WASM = 5109;
    private static final long MAX_BYTES = 256L * 1024L * 1024L;
    private static boolean libraryLoaded;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView report;
    private Button loadButton;
    private Button mainButton;
    private String wasmPath;
    private boolean initialized;

    private static boolean loadNativeLibrary() {
        if (libraryLoaded) return true;
        try { System.loadLibrary("gta_native_engine"); libraryLoaded = true; }
        catch (Throwable ignored) { libraryLoaded = false; }
        return libraryLoaded;
    }

    private static native String nativeLoadAndInitialize(String wasmPath);
    private static native String nativeTryMain();
    private static native void nativeClose();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12, 10, 12, 8);
        root.setBackgroundColor(Color.rgb(8, 12, 18));

        TextView title = new TextView(this);
        title.setText("NATIVE GTA ENGINE — WASMTIME");
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setTextSize(16);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView note = new TextView(this);
        note.setTextColor(0xFFD2DAE6);
        note.setTextSize(12);
        note.setPadding(0, 8, 0, 8);
        note.setText("No WebView is used here. This experimental path runs the supplied WASM module in the native Android ARM64 Wasmtime runtime. The module declares a 3 GiB minimum shared memory and a 16 GiB maximum; memory reservation may fail on this device. Keep it cool and stop if the device becomes unstable. The remote manifest has a native HTTPS adapter, but batch/range asset reads, the GPU worker, pthread startup, audio and input remain stubs, so this cannot provide playable GTA yet.");
        root.addView(note, new LinearLayout.LayoutParams(-1, -2));

        Button select = new Button(this);
        select.setText("1. SELECT YOUR game.wasm");
        select.setAllCaps(false);
        select.setOnClickListener(v -> pickWasm());
        root.addView(select, new LinearLayout.LayoutParams(-1, -2));

        loadButton = new Button(this);
        loadButton.setText("2. NATIVE COMPILE + START + CTORS");
        loadButton.setAllCaps(false);
        loadButton.setEnabled(false);
        loadButton.setOnClickListener(v -> loadAndInitialize());
        root.addView(loadButton, new LinearLayout.LayoutParams(-1, -2));

        mainButton = new Button(this);
        mainButton.setText("3. TRY GAME MAIN (20M FUEL CAP)");
        mainButton.setAllCaps(false);
        mainButton.setEnabled(false);
        mainButton.setOnClickListener(v -> tryMain());
        root.addView(mainButton, new LinearLayout.LayoutParams(-1, -2));

        report = new TextView(this);
        report.setTextColor(0xFFE5EDF7);
        report.setTextSize(12);
        report.setTypeface(Typeface.MONOSPACE);
        report.setText("WAITING FOR game.wasm\n\nThis is a native-runtime execution experiment. It has not yet implemented the custom renderer or other game host services.");
        ScrollView scroller = new ScrollView(this);
        scroller.addView(report);
        root.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));

        Button back = new Button(this);
        back.setText("Back"); back.setAllCaps(false);
        back.setOnClickListener(v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);

        if (!loadNativeLibrary()) {
            report.setText("NATIVE RUNTIME NOT BUILT INTO THIS APK\n\nRun the GitHub Actions build with the optional ‘native_engine’ workflow input enabled, then install that native-engine APK artifact.");
        }
    }

    private void pickWasm() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Many Android document providers do not register application/wasm as a MIME type.
        // Use the generic document picker filter and validate the actual WASM header in JNI.
        intent.setType("*/*");
        startActivityForResult(intent, PICK_WASM);
    }

    @Override @Deprecated protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_WASM || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = displayName(uri);
        report.setText("Copying selected WASM into app-private cache…");
        loadButton.setEnabled(false); mainButton.setEnabled(false);
        worker.execute(() -> {
            File target = new File(getCacheDir(), "native-game-module.wasm");
            String error = null;
            long copied = 0;
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(target)) {
                if (in == null) throw new IOException("Android document provider returned no stream");
                byte[] buffer = new byte[128 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    copied += n;
                    if (copied > MAX_BYTES) throw new IOException("File exceeds 256 MiB safety limit");
                    out.write(buffer, 0, n);
                }
                out.getFD().sync();
            } catch (Exception e) { error = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()); }
            final String failure = error;
            final long bytes = copied;
            runOnUiThread(() -> {
                if (failure != null) {
                    report.setText("WASM SELECTION FAILED\n" + failure);
                    loadButton.setEnabled(loadNativeLibrary());
                    mainButton.setEnabled(false);
                    return;
                }
                wasmPath = target.getAbsolutePath();
                initialized = false;
                loadButton.setEnabled(loadNativeLibrary());
                mainButton.setEnabled(false);
                report.setText("SELECTED: " + name + "\nCopied bytes: " + bytes + "\nReady to attempt native module initialization. No game code has run yet.");
            });
        });
    }

    /**
     * Native host adapter for the exact manifest endpoint used by the supplied game.js.
     * Called synchronously from the Wasmtime host import; the invoking native method runs on
     * this Activity's background executor, never on the UI thread.
     */
    static byte[] fetchManifestForNative() {
        HttpURLConnection connection = null;
        final int maxBytes = 32 * 1024 * 1024;
        try {
            URL url = new URL("https://gta.deloskiyt.pl/data/manifest.json");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                android.util.Log.w("GtaNativeEngine", "Manifest HTTP status=" + status);
                return null;
            }
            int contentLength = connection.getContentLength();
            if (contentLength > maxBytes) {
                android.util.Log.e("GtaNativeEngine", "Manifest exceeds 32 MiB safety limit");
                return null;
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream(
                         contentLength > 0 ? Math.min(contentLength, maxBytes) : 8192)) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                int total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > maxBytes) {
                        android.util.Log.e("GtaNativeEngine", "Manifest exceeded 32 MiB during download");
                        return null;
                    }
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        } catch (Exception e) {
            android.util.Log.e("GtaNativeEngine", "Native manifest fetch failed", e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) { }
        return uri.getLastPathSegment() == null ? "selected module" : uri.getLastPathSegment();
    }

    private void loadAndInitialize() {
        if (wasmPath == null || !loadNativeLibrary()) {
            report.setText("BLOCKED: select game.wasm and install the native-engine APK build first."); return;
        }
        loadButton.setEnabled(false); mainButton.setEnabled(false);
        report.setText("Compiling and initializing the actual module in native Wasmtime…\nThis can take several minutes.");
        worker.execute(() -> {
            String value;
            try { value = nativeLoadAndInitialize(wasmPath); }
            catch (Throwable t) { value = "NATIVE INITIALIZATION FAILED\n" + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()); }
            final String result = value;
            runOnUiThread(() -> {
                report.setText(result);
                initialized = result.startsWith("NATIVE WASM MODULE START: PASS");
                loadButton.setEnabled(!initialized && loadNativeLibrary());
                mainButton.setEnabled(initialized);
            });
        });
    }

    private void tryMain() {
        if (!initialized || !loadNativeLibrary()) return;
        mainButton.setEnabled(false);
        report.append("\n\nAttempting __main_argc_argv with 20,000,000 Wasm-instruction fuel limit…\n");
        worker.execute(() -> {
            String result;
            try { result = nativeTryMain(); }
            catch (Throwable t) { result = "NATIVE GAME MAIN FAILED\n" + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()); }
            final String value = result;
            runOnUiThread(() -> { report.append("\n" + value); mainButton.setEnabled(true); });
        });
    }

    @Override protected void onDestroy() {
        worker.execute(() -> { if (loadNativeLibrary()) nativeClose(); });
        worker.shutdown();
        super.onDestroy();
    }
}
