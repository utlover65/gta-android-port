package org.utlover65.astravulkanprobe;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Audits the real browser-build WASM module before attempting a native engine integration. */
public final class NativeGameplayPreflightActivity extends Activity {
    private static final int PICK_WASM = 4107;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView report;
    private Button choose;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12, 10, 12, 8);
        root.setBackgroundColor(Color.rgb(8, 12, 18));
        TextView title = new TextView(this);
        title.setText("NATIVE GAME ENGINE PREFLIGHT");
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setTextSize(16);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView intro = new TextView(this);
        intro.setTextColor(0xFFD0D8E4);
        intro.setTextSize(12);
        intro.setPadding(0, 8, 0, 8);
        intro.setText("Select the original game.wasm file. This reads the actual WASM module header, imports and memory requirements. It does not execute it or include game assets.");
        root.addView(intro, new LinearLayout.LayoutParams(-1, -2));
        choose = new Button(this);
        choose.setText("SELECT game.wasm AND AUDIT");
        choose.setAllCaps(false);
        choose.setOnClickListener(v -> pickWasm());
        root.addView(choose, new LinearLayout.LayoutParams(-1, -2));
        report = new TextView(this);
        report.setTextColor(0xFFE5EDF7);
        report.setTextSize(12);
        report.setTypeface(Typeface.MONOSPACE);
        report.setText("WAITING FOR game.wasm\n\nA native gameplay pass requires a native WebAssembly runtime plus implementations of this module's host imports, shared-memory threading, GPU command protocol, asset I/O, audio and input.");
        ScrollView scroll = new ScrollView(this);
        scroll.addView(report);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Button back = new Button(this);
        back.setText("Back");
        back.setAllCaps(false);
        back.setOnClickListener(v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    private void pickWasm() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/wasm");
        try { startActivityForResult(intent, PICK_WASM); }
        catch (Exception e) {
            intent.setType("application/octet-stream");
            startActivityForResult(intent, PICK_WASM);
        }
    }

    @Override @Deprecated protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_WASM || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Exception ignored) { }
        report.setText("Reading and auditing the selected WASM module…");
        choose.setEnabled(false);
        executor.execute(() -> {
            String value;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Content provider returned no stream");
                value = audit(in, displayName(uri));
            } catch (Exception e) {
                value = "AUDIT FAILED\n" + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            }
            final String resultText = value;
            runOnUiThread(() -> { report.setText(resultText); choose.setEnabled(true); });
        });
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) { }
        return uri.getLastPathSegment() == null ? "selected file" : uri.getLastPathSegment();
    }

    private static String audit(InputStream in, String name) throws IOException {
        byte[] magic = readExact(in, 8);
        if (magic[0] != 0 || magic[1] != 'a' || magic[2] != 's' || magic[3] != 'm')
            throw new IOException("Selected file is not a WebAssembly binary");
        int version = (magic[4] & 255) | ((magic[5] & 255) << 8) | ((magic[6] & 255) << 16) | ((magic[7] & 255) << 24);
        if (version != 1) throw new IOException("Unsupported WASM binary version: " + version);
        List<String> imports = new ArrayList<>();
        List<String> memories = new ArrayList<>();
        long totalBytesSkipped = 0;
        while (true) {
            int id = in.read();
            if (id < 0) break;
            long size = readUleb(in, 5);
            if (size > 64L * 1024L * 1024L) throw new IOException("Unexpectedly large metadata section: " + size);
            if (id == 2 || id == 5) {
                byte[] payload = readExact(in, (int) size);
                CursorBytes c = new CursorBytes(payload);
                if (id == 2) {
                    long count = c.uleb(5);
                    if (count > 100000) throw new IOException("Unreasonable import count");
                    for (long i = 0; i < count; i++) {
                        String module = c.name();
                        String symbol = c.name();
                        int kind = c.u8();
                        if (kind == 0) { c.uleb(5); imports.add(module + "." + symbol + " [function]"); }
                        else if (kind == 1) { c.u8(); c.limits(false); }
                        else if (kind == 2) {
                            Memory m = c.memoryLimits();
                            memories.add(memoryLine("import " + module + "." + symbol, m));
                        }
                        else if (kind == 3) { c.u8(); c.u8(); }
                        else if (kind == 4) { c.u8(); c.uleb(5); }
                        else throw new IOException("Unknown import kind " + kind);
                    }
                } else {
                    long count = c.uleb(5);
                    for (long i = 0; i < count; i++) memories.add(memoryLine("defined memory " + i, c.memoryLimits()));
                }
                if (!c.atEnd()) throw new IOException("Metadata section " + id + " parse ended at " + c.pos + "/" + payload.length);
            } else {
                skipFully(in, size);
                totalBytesSkipped += size;
            }
        }
        long custom = imports.stream().filter(s -> s.contains("wasm_") || s.contains("wgpu_")).count();
        long pthread = imports.stream().filter(s -> s.toLowerCase(Locale.ROOT).contains("pthread") || s.contains("thread_mailbox") || s.contains("thread_init")).count();
        long syscall = imports.stream().filter(s -> s.contains("__syscall_") || s.startsWith("wasi_snapshot_preview1.")).count();
        boolean gpu = imports.stream().anyMatch(s -> s.contains("wgpu_start_worker"));
        boolean io = imports.stream().anyMatch(s -> s.contains("wasm_httpfs_io_start_js"));
        boolean audio = imports.stream().anyMatch(s -> s.contains("wasm_audio_publish_js"));
        boolean input = imports.stream().anyMatch(s -> s.contains("wasm_input_publish_js"));
        StringBuilder b = new StringBuilder();
        b.append("ENGINE MODULE AUDIT\nFile: ").append(name).append("\nWASM binary v1: PASS\n");
        b.append("Function imports found: ").append(imports.size()).append("\n");
        b.append("Browser/custom host imports (wasm_/wgpu_): ").append(custom).append("\n");
        b.append("Thread/runtime imports: ").append(pthread).append("\n");
        b.append("Filesystem/WASI imports: ").append(syscall).append("\n\nMEMORY\n");
        for (String m : memories) b.append(m).append('\n');
        b.append("\nCUSTOM ENGINE CONTRACTS\n");
        b.append("GPU command worker entry: ").append(gpu ? "PRESENT" : "NOT FOUND").append('\n');
        b.append("Remote/HTTP filesystem startup: ").append(io ? "PRESENT" : "NOT FOUND").append('\n');
        b.append("Audio publish import: ").append(audio ? "PRESENT" : "NOT FOUND").append('\n');
        b.append("Input publish import: ").append(input ? "PRESENT" : "NOT FOUND").append('\n');
        b.append("\nNATIVE GAMEPLAY STATUS: BLOCKED\n");
        b.append("This APK does not embed a WASM execution runtime or implement these imported host functions. The current native tests only draw test frames and exercise generic Android services. No GTA code was executed by this audit.\n");
        b.append("Next integration work: choose a runtime supporting this module's memory/threading features; implement the complete Emscripten/WASI syscall ABI; replace the browser GPU worker/command ring with Dawn-native rendering; port HTTP/batch asset I/O, audio mixer publication and input callbacks; then run a real game bootstrap.\n");
        b.append("Skipped non-metadata section bytes: ").append(totalBytesSkipped).append('\n');
        b.append("\nCustom import names (first 40):\n");
        int shown = 0;
        for (String s : imports) {
            if (s.contains("wasm_") || s.contains("wgpu_") || s.contains("pthread") || s.contains("syscall")) {
                b.append(" • ").append(s).append('\n');
                if (++shown >= 40) { b.append(" • … remaining imports omitted\n"); break; }
            }
        }
        return b.toString();
    }

    private static String memoryLine(String title, Memory m) {
        double minGiB = m.minPages * 65536.0 / (1024 * 1024 * 1024);
        String max = m.hasMax ? String.format(Locale.US, "%.2f GiB", m.maxPages * 65536.0 / (1024 * 1024 * 1024)) : "unbounded";
        return title + ": " + (m.memory64 ? "memory64" : "memory32") + ", shared=" + m.shared +
                ", min=" + m.minPages + " pages (" + String.format(Locale.US, "%.2f", minGiB) + " GiB), max=" + max;
    }

    private static byte[] readExact(InputStream in, int count) throws IOException {
        byte[] data = new byte[count]; int offset = 0;
        while (offset < count) { int n = in.read(data, offset, count - offset); if (n < 0) throw new IOException("Unexpected end of file"); if (n == 0) continue; offset += n; }
        return data;
    }
    private static long readUleb(InputStream in, int maxBytes) throws IOException {
        long v = 0; int shift = 0;
        for (int i = 0; i < maxBytes; i++) { int x = in.read(); if (x < 0) throw new IOException("Unexpected EOF in LEB128"); v |= (long)(x & 127) << shift; if ((x & 128) == 0) return v; shift += 7; }
        throw new IOException("Malformed LEB128 value");
    }
    private static void skipFully(InputStream in, long count) throws IOException {
        long left = count;
        while (left > 0) { long n = in.skip(left); if (n > 0) { left -= n; continue; } if (in.read() < 0) throw new IOException("Unexpected EOF while skipping section"); left--; }
    }
    private static final class Memory { long minPages, maxPages; boolean hasMax, shared, memory64; }
    private static final class CursorBytes {
        final byte[] data; int pos;
        CursorBytes(byte[] data) { this.data = data; }
        boolean atEnd() { return pos == data.length; }
        int u8() throws IOException { if (pos >= data.length) throw new IOException("Metadata section truncated"); return data[pos++] & 255; }
        long uleb(int maxBytes) throws IOException { long v=0; int shift=0; for(int i=0;i<maxBytes;i++){int x=u8(); v|=(long)(x&127)<<shift; if((x&128)==0)return v; shift+=7;} throw new IOException("Malformed section LEB128"); }
        String name() throws IOException { long n=uleb(5); if(n>data.length-pos)throw new IOException("Invalid import name length"); String s=new String(data,pos,(int)n,StandardCharsets.UTF_8);pos+=(int)n;return s; }
        Memory memoryLimits() throws IOException { long flags=uleb(5); Memory m=new Memory(); m.hasMax=(flags&1)!=0; m.shared=(flags&2)!=0; m.memory64=(flags&4)!=0; m.minPages=uleb(m.memory64?10:5); if(m.hasMax)m.maxPages=uleb(m.memory64?10:5); return m; }
        void limits(boolean ignored) throws IOException { memoryLimits(); }
    }
}
