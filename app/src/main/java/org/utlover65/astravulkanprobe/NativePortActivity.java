package org.utlover65.astravulkanprobe;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native AOT GTA V host. This is not a WebView: the engine is translated from the supplied
 * game.wasm at build time and executes in a native ARM64 shared library, using Dawn/Vulkan.
 * Game data and translated shaders stay in the user's external folder and are never bundled.
 */
public final class NativePortActivity extends Activity implements SurfaceHolder.Callback {
    private static final int VK_ESCAPE = 0x1B;
    private static final int VK_SPACE = 0x20;
    private static final int VK_SHIFT = 0xA0;
    private static final String DEFAULT_ROOT = "/storage/emulated/0/GTA-V-Port";

    private static boolean nativeLibraryReady;
    private static synchronized boolean loadNativeLibrary() {
        if (nativeLibraryReady) return true;
        try {
            System.loadLibrary("webgpu_dawn");
            System.loadLibrary("gta_native_engine");
            nativeLibraryReady = true;
        } catch (Throwable t) {
            android.util.Log.e("GtaNativeAOT", "Native AOT library not built/loaded", t);
            nativeLibraryReady = false;
        }
        return nativeLibraryReady;
    }

    private static native String nativeSetWindowSurface(Surface surface);
    private static native String nativeStart(String rootDir, String userDir, String mode, int width, int height);
    private static native void nativeKey(int windowsVk, int down);
    private static native void nativeButton(int bit, int down);
    private static native void nativeMouseDelta(int dx, int dy);
    private static native void nativeWheel(int notches);
    private static native void nativeFocus(int focused);
    private static native void nativeSuspend(int suspended);

    private final ExecutorService engineWorker = Executors.newSingleThreadExecutor(r -> new Thread(r, "gta-native-start"));
    private EditText rootInput;
    private TextView gameStatus;
    private FrameLayout gameFrame;
    private SurfaceView gameSurface;
    private TouchControls touchControls;
    private String selectedRoot;
    private String selectedMode = "sandbox";
    private boolean startRequested;
    private boolean engineStartRequested;
    private boolean engineStarted;
    private boolean surfaceInvalidated;
    private boolean padFireDown;
    private boolean padAimDown;
    private boolean padFireButtonDown;
    private boolean padAimButtonDown;
    private boolean padFireAxisDown;
    private boolean padAimAxisDown;
    private int surfaceWidth;
    private int surfaceHeight;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!loadNativeLibrary()) {
            showBuildRequired();
            return;
        }
        showSetup();
    }

    private void showBuildRequired() {
        TextView message = new TextView(this);
        message.setTextColor(Color.WHITE);
        message.setBackgroundColor(Color.rgb(16, 20, 28));
        message.setTextSize(16);
        message.setPadding(24, 24, 24, 24);
        message.setText("NATIVE AOT ENGINE IS NOT IN THIS APK\n\nBuild with the repository's native-gtav-android GitHub Actions workflow using your own game.wasm. This screen does not fall back to a WebView and does not pretend the game started.");
        setContentView(message);
    }

    private void showSetup() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(18, 14, 18, 18);
        root.setBackgroundColor(Color.rgb(12, 16, 24));

        TextView title = new TextView(this);
        title.setText("GTA V — Native Android AOT Port");
        title.setTextColor(Color.WHITE);
        title.setTextSize(21);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView instructions = new TextView(this);
        instructions.setTextColor(0xFFD5DEEA);
        instructions.setTextSize(13);
        instructions.setPadding(0, 10, 0, 12);
        instructions.setText("Uses the native ARM64 host, Dawn/Vulkan renderer, native threads, file reader, AAudio output and touch-to-key/mouse controls. Your own full game data and shaders must already be on storage. Nothing from the 20+ GB game-data folder is included in this APK.");
        root.addView(instructions, new LinearLayout.LayoutParams(-1, -2));

        TextView pathLabel = new TextView(this);
        pathLabel.setTextColor(Color.WHITE);
        pathLabel.setText("Folder containing data/ and b/<build>/shaders/");
        root.addView(pathLabel, new LinearLayout.LayoutParams(-1, -2));
        rootInput = new EditText(this);
        rootInput.setSingleLine(true);
        rootInput.setTextColor(Color.WHITE);
        rootInput.setHintTextColor(0xFF9BA6B6);
        rootInput.setHint("/storage/emulated/0/GTA-V-Port");
        rootInput.setText(DEFAULT_ROOT);
        rootInput.setSelectAllOnFocus(true);
        root.addView(rootInput, new LinearLayout.LayoutParams(-1, -2));

        RadioGroup modes = new RadioGroup(this);
        modes.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton story = new RadioButton(this);
        story.setId(View.generateViewId());
        story.setText("Story Mode");
        story.setTextColor(Color.WHITE);
        RadioButton sandbox = new RadioButton(this);
        sandbox.setId(View.generateViewId());
        sandbox.setText("Sandbox / Free Roam");
        sandbox.setTextColor(Color.WHITE);
        sandbox.setChecked(true);
        modes.addView(story, new RadioGroup.LayoutParams(0, -2, 1));
        modes.addView(sandbox, new RadioGroup.LayoutParams(0, -2, 1));
        modes.setOnCheckedChangeListener((group, checkedId) -> selectedMode = checkedId == story.getId() ? "story" : "sandbox");
        root.addView(modes, new LinearLayout.LayoutParams(-1, -2));

        Button permissions = new Button(this);
        permissions.setText("Grant storage access");
        permissions.setAllCaps(false);
        permissions.setOnClickListener(v -> requestStorageAccess());
        root.addView(permissions, new LinearLayout.LayoutParams(-1, -2));

        Button start = new Button(this);
        start.setText("Start native GTA V");
        start.setAllCaps(false);
        start.setOnClickListener(v -> startGameFromSettings());
        root.addView(start, new LinearLayout.LayoutParams(-1, -2));

        TextView note = new TextView(this);
        note.setTextColor(0xFF9DA9BB);
        note.setTextSize(12);
        note.setPadding(0, 12, 0, 0);
        note.setText("Mobile profile: 30 fps cap and 1 GiB reported video memory. A successful native-host launch is not the same as a fully loaded world; watch logcat for engine/GPU/file errors. Allow ample free storage and monitor temperature.");
        root.addView(note, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(this, "All-files access is already enabled", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            Toast.makeText(this, "External-storage permission is not available on this Android version.", Toast.LENGTH_LONG).show();
        }
    }

    private String validateGameRoot(String rootText) {
        if (rootText == null || rootText.trim().isEmpty()) return "Enter your game folder path.";
        File root = new File(rootText.trim());
        if (!root.isDirectory() || !root.canRead()) return "Folder cannot be read: " + root.getAbsolutePath();
        File manifest = new File(root, "data/manifest.json");
        if (!manifest.isFile() || !manifest.canRead()) return "Missing readable data/manifest.json under " + root.getAbsolutePath();
        File buildRoot = new File(root, "b");
        File[] builds = buildRoot.listFiles();
        if (builds == null) return "Missing b/<build>/shaders. Put the browser build's translated shader folder beside data/.";
        for (File build : builds) {
            File shaders = new File(build, "shaders");
            String[] children = shaders.list();
            if (shaders.isDirectory() && children != null && children.length > 0) return null;
        }
        return "No non-empty b/<build>/shaders folder found. The native renderer needs the translated shaders.";
    }

    private void startGameFromSettings() {
        if (!loadNativeLibrary()) { showBuildRequired(); return; }
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            requestStorageAccess();
            Toast.makeText(this, "Grant storage access, return here, then tap Start again.", Toast.LENGTH_LONG).show();
            return;
        }
        String root = rootInput.getText() == null ? "" : rootInput.getText().toString().trim();
        String problem = validateGameRoot(root);
        if (problem != null) {
            new android.app.AlertDialog.Builder(this).setTitle("Game files not ready").setMessage(problem).setPositiveButton("OK", null).show();
            return;
        }
        selectedRoot = new File(root).getAbsolutePath();
        startRequested = true;
        showGameSurface();
    }

    private void showGameSurface() {
        gameFrame = new FrameLayout(this);
        gameSurface = new SurfaceView(this);
        gameSurface.getHolder().addCallback(this);
        gameSurface.setFocusable(true);
        gameSurface.setFocusableInTouchMode(true);
        gameFrame.addView(gameSurface, new FrameLayout.LayoutParams(-1, -1));

        touchControls = new TouchControls(this);
        gameFrame.addView(touchControls, new FrameLayout.LayoutParams(-1, -1));

        gameStatus = new TextView(this);
        gameStatus.setTextColor(Color.WHITE);
        gameStatus.setTextSize(11);
        gameStatus.setTypeface(android.graphics.Typeface.MONOSPACE);
        gameStatus.setPadding(8, 6, 8, 6);
        gameStatus.setBackgroundColor(0x99081018);
        gameStatus.setText("NATIVE AOT: waiting for Android surface…");
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        gameFrame.addView(gameStatus, statusParams);
        setContentView(gameFrame);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        if (surfaceInvalidated && (engineStarted || engineStartRequested)) {
            setStatus("The original native window was destroyed. This build cannot recreate its Dawn surface yet; force-stop the entire app process and relaunch it; this build cannot restart the guest engine in-place.");
            return;
        }
        String message;
        try { message = nativeSetWindowSurface(holder.getSurface()); }
        catch (Throwable t) { message = "Native window error: " + t.getClass().getSimpleName() + ": " + t.getMessage(); }
        setStatus(message);
        tryStartNativeEngine(holder);
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        surfaceWidth = width;
        surfaceHeight = height;
        tryStartNativeEngine(holder);
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceInvalidated = engineStartRequested || engineStarted;
        try { nativeSuspend(1); } catch (Throwable ignored) { }
        if (surfaceInvalidated) {
            setStatus("Surface detached. This native build cannot safely rebind Dawn to a replacement window; force-stop the entire app process and relaunch it; this build cannot restart the guest engine in-place.");
        }
    }

    private synchronized void tryStartNativeEngine(SurfaceHolder holder) {
        if (!startRequested || engineStartRequested || surfaceWidth <= 0 || surfaceHeight <= 0 || holder.getSurface() == null || !holder.getSurface().isValid()) return;
        engineStartRequested = true;
        final String root = selectedRoot;
        final String mode = selectedMode;
        final String userDir = new File(getFilesDir(), "gta-userdata").getAbsolutePath();
        final int w = surfaceWidth, h = surfaceHeight;
        setStatus("Reserving shared WASM memory and starting the native host. This may take time…");
        engineWorker.execute(() -> {
            String message;
            try { message = nativeStart(root, userDir, mode, w, h); }
            catch (Throwable t) { message = "NATIVE START FAILED: " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()); }
            final String result = message;
            runOnUiThread(() -> {
                engineStarted = result.startsWith("NATIVE HOST: STARTED");
                if (engineStarted && surfaceInvalidated) {
                    setStatus("Native host started, but the Android surface was destroyed during startup. Close and reopen this screen; do not continue rendering against the old window. Check logcat for the current attempt.");
                } else {
                    setStatus(result);
                }
            });
        });
    }

    private void setStatus(String text) {
        runOnUiThread(() -> { if (gameStatus != null) gameStatus.setText(text); });
    }

    private static int toWindowsVk(int code) {
        // Controller face buttons map to common GTA keyboard actions; bumpers/triggers are handled as mouse buttons.
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A: return VK_SPACE;       // jump / confirm
            case KeyEvent.KEYCODE_BUTTON_B: return 'F';            // enter / vehicle
            case KeyEvent.KEYCODE_BUTTON_X: return 'Q';            // cover
            case KeyEvent.KEYCODE_BUTTON_Y: return 'V';            // camera
            case KeyEvent.KEYCODE_BUTTON_START: return VK_ESCAPE;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return 0x09;
        }
        if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z) return 'A' + code - KeyEvent.KEYCODE_A;
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) return '0' + code - KeyEvent.KEYCODE_0;
        switch (code) {
            case KeyEvent.KEYCODE_SPACE: return VK_SPACE;
            case KeyEvent.KEYCODE_ESCAPE: case KeyEvent.KEYCODE_BACK: return VK_ESCAPE;
            case KeyEvent.KEYCODE_ENTER: return 0x0D;
            case KeyEvent.KEYCODE_TAB: return 0x09;
            case KeyEvent.KEYCODE_DEL: return 0x08;
            case KeyEvent.KEYCODE_SHIFT_LEFT: case KeyEvent.KEYCODE_SHIFT_RIGHT: return VK_SHIFT;
            case KeyEvent.KEYCODE_CTRL_LEFT: case KeyEvent.KEYCODE_CTRL_RIGHT: return 0xA2;
            case KeyEvent.KEYCODE_ALT_LEFT: case KeyEvent.KEYCODE_ALT_RIGHT: return 0xA4;
            case KeyEvent.KEYCODE_DPAD_UP: return 0x26;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 0x28;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 0x25;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 0x27;
            case KeyEvent.KEYCODE_PAGE_UP: return 0x21;
            case KeyEvent.KEYCODE_PAGE_DOWN: return 0x22;
            case KeyEvent.KEYCODE_HOME: return 0x24;
            case KeyEvent.KEYCODE_MOVE_END: return 0x23;
            case KeyEvent.KEYCODE_INSERT: return 0x2D;
            case KeyEvent.KEYCODE_FORWARD_DEL: return 0x2E;
            case KeyEvent.KEYCODE_F1: return 0x70;
            case KeyEvent.KEYCODE_F2: return 0x71;
            case KeyEvent.KEYCODE_F3: return 0x72;
            case KeyEvent.KEYCODE_F4: return 0x73;
            case KeyEvent.KEYCODE_F5: return 0x74;
            case KeyEvent.KEYCODE_F6: return 0x75;
            default: return 0;
        }
    }

    private void updateControllerMouseButtons() {
        boolean fire = padFireButtonDown || padFireAxisDown;
        boolean aim = padAimButtonDown || padAimAxisDown;
        if (fire != padFireDown) {
            padFireDown = fire;
            try { nativeButton(0, fire ? 1 : 0); } catch (Throwable ignored) { }
        }
        if (aim != padAimDown) {
            padAimDown = aim;
            try { nativeButton(1, aim ? 1 : 0); } catch (Throwable ignored) { }
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        final int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BUTTON_R1) {
            padFireButtonDown = down;
            updateControllerMouseButtons();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_L1) {
            padAimButtonDown = down;
            updateControllerMouseButtons();
            return true;
        }
        int vk = toWindowsVk(code);
        if (vk != 0) {
            try { nativeKey(vk, down ? 1 : 0); } catch (Throwable ignored) { }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK &&
                event.getAction() == MotionEvent.ACTION_MOVE) {
            // Convert a connected controller to the keyboard/mouse ABI the engine already consumes.
            float x = event.getAxisValue(MotionEvent.AXIS_X);
            float y = event.getAxisValue(MotionEvent.AXIS_Y);
            float rx = event.getAxisValue(MotionEvent.AXIS_RX);
            float ry = event.getAxisValue(MotionEvent.AXIS_RY);
            if (Math.abs(rx) < 0.01f && Math.abs(ry) < 0.01f) {
                rx = event.getAxisValue(MotionEvent.AXIS_Z);
                ry = event.getAxisValue(MotionEvent.AXIS_RZ);
            }
            float leftTrigger = event.getAxisValue(MotionEvent.AXIS_LTRIGGER);
            float rightTrigger = event.getAxisValue(MotionEvent.AXIS_RTRIGGER);
            padAimAxisDown = leftTrigger > 0.50f;
            padFireAxisDown = rightTrigger > 0.50f;
            updateControllerMouseButtons();
            nativeKey('A', x < -0.25f ? 1 : 0); nativeKey('D', x > 0.25f ? 1 : 0);
            nativeKey('W', y < -0.25f ? 1 : 0); nativeKey('S', y > 0.25f ? 1 : 0);
            if (Math.abs(rx) > 0.08f || Math.abs(ry) > 0.08f)
                nativeMouseDelta((int)(rx * 16.0f), (int)(ry * 16.0f));
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    @Override protected void onPause() {
        if (engineStarted) {
            try { nativeSuspend(1); nativeFocus(0); } catch (Throwable ignored) { }
        }
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (engineStarted && !surfaceInvalidated) {
            try { nativeFocus(1); nativeSuspend(0); } catch (Throwable ignored) { }
        }
    }

    @Override protected void onDestroy() {
        try { nativeFocus(0); nativeSuspend(1); } catch (Throwable ignored) { }
        engineWorker.shutdown();
        super.onDestroy();
    }

    private static final class TouchControls extends View {
        private static final int KEY_W = 'W', KEY_A = 'A', KEY_S = 'S', KEY_D = 'D';
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Map<Integer, Integer> pointerAction = new HashMap<>();
        private final Set<Integer> heldKeys = new HashSet<>();
        private int stickPointer = -1;
        private float stickOriginX, stickOriginY, stickX, stickY;
        private final Set<Integer> buttonsDown = new HashSet<>();
        private float lastLookX, lastLookY;
        private int lookPointer = -1;

        private static final int ACT_FIRE = 1, ACT_AIM = 2, ACT_JUMP = 3, ACT_ENTER = 4,
                ACT_COVER = 5, ACT_SPRINT = 6, ACT_PAUSE = 7, ACT_WHEEL_UP = 8, ACT_WHEEL_DOWN = 9;

        TouchControls(Activity activity) { super(activity); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }

        private float scale() { return Math.max(1.0f, getHeight()); }
        private float radius(float r) { return scale() * r; }
        private float cx(float right) { return getWidth() - scale() * right; }
        private float cy(float y) { return scale() * y; }

        private final float[][] buttons = {
            {ACT_FIRE, -0.16f, 0.72f, 0.085f}, {ACT_AIM, -0.34f, 0.84f, 0.065f},
            {ACT_JUMP, -0.34f, 0.60f, 0.065f}, {ACT_ENTER, -0.17f, 0.47f, 0.060f},
            {ACT_COVER, -0.10f, 0.32f, 0.055f}, {ACT_SPRINT, -0.47f, 0.68f, 0.055f},
            {ACT_PAUSE, -0.11f, 0.11f, 0.050f}, {ACT_WHEEL_UP, -0.45f, 0.37f, 0.055f},
            {ACT_WHEEL_DOWN, -0.45f, 0.50f, 0.055f}
        };
        private float buttonX(float right) { return getWidth() + scale() * right; }
        private int hitButton(float x, float y) {
            for (float[] b : buttons) {
                float r = radius(b[3]);
                if (Math.hypot(x - buttonX(b[1]), y - cy(b[2])) <= r) return (int)b[0];
            }
            return 0;
        }
        private void setKey(int vk, boolean down) {
            if (down) { if (heldKeys.add(vk)) nativeKey(vk, 1); }
            else { if (heldKeys.remove(vk)) nativeKey(vk, 0); }
        }
        private void setButton(int action, boolean down) {
            if (down) buttonsDown.add(action); else buttonsDown.remove(action);
            switch (action) {
                case ACT_FIRE: nativeButton(0, down ? 1 : 0); break;
                case ACT_AIM: nativeButton(1, down ? 1 : 0); break;
                case ACT_JUMP: setKey(VK_SPACE, down); break;
                case ACT_ENTER: setKey('F', down); break;
                case ACT_COVER: setKey('Q', down); break;
                case ACT_SPRINT: setKey(VK_SHIFT, down); break;
                case ACT_PAUSE: setKey(VK_ESCAPE, down); break;
                case ACT_WHEEL_UP: if (down) nativeWheel(1); break;
                case ACT_WHEEL_DOWN: if (down) nativeWheel(-1); break;
            }
            invalidate();
        }
        private void updateStick(float x, float y) {
            float dx = x - stickOriginX, dy = y - stickOriginY;
            float dead = radius(0.025f), sprint = radius(0.12f);
            boolean up = dy < -dead, down = dy > dead, left = dx < -dead, right = dx > dead;
            setKey(KEY_W, up); setKey(KEY_S, down); setKey(KEY_A, left); setKey(KEY_D, right);
            setKey(VK_SHIFT, Math.hypot(dx, dy) > sprint);
            float len = (float)Math.hypot(dx, dy), max = radius(0.12f);
            if (len > max && len > 0) { dx = dx * max / len; dy = dy * max / len; }
            stickX = stickOriginX + dx; stickY = stickOriginY + dy;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float h = scale();
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2f); paint.setColor(0x66FFFFFF);
            c.drawCircle(h * 0.18f, h * 0.78f, radius(0.12f), paint);
            if (stickPointer >= 0) c.drawCircle(stickX, stickY, radius(0.055f), paint);
            for (float[] b : buttons) {
                int action = (int)b[0];
                float r = radius(b[3]), x = buttonX(b[1]), y = cy(b[2]);
                paint.setStyle(Paint.Style.FILL); paint.setColor(buttonsDown.contains(action) ? 0x88FFFFFF : 0x2EFFFFFF);
                c.drawCircle(x, y, r, paint);
                paint.setStyle(Paint.Style.STROKE); paint.setColor(0x77FFFFFF); c.drawCircle(x, y, r, paint);
                paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE); paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(Math.max(10f, r * 0.30f));
                String label = action == ACT_FIRE ? "FIRE" : action == ACT_AIM ? "AIM" : action == ACT_JUMP ? "JUMP" : action == ACT_ENTER ? "ENTER" : action == ACT_COVER ? "COVER" : action == ACT_SPRINT ? "RUN" : action == ACT_PAUSE ? "ESC" : action == ACT_WHEEL_UP ? "W+" : "W-";
                c.drawText(label, x, y + paint.getTextSize() * 0.35f, paint);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            final int action = e.getActionMasked();
            final int index = e.getActionIndex();
            final int id = e.getPointerId(index);
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                float x = e.getX(index), y = e.getY(index);
                int hit = hitButton(x, y);
                if (hit != 0) { pointerAction.put(id, hit); setButton(hit, true); return true; }
                if (stickPointer < 0 && x < getWidth() * 0.40f && y > getHeight() * 0.28f) {
                    stickPointer = id; stickOriginX = x; stickOriginY = y; stickX = x; stickY = y; updateStick(x, y); return true;
                }
                if (lookPointer < 0) { lookPointer = id; lastLookX = x; lastLookY = y; }
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int pointerId = e.getPointerId(i);
                    float x = e.getX(i), y = e.getY(i);
                    if (pointerId == stickPointer) updateStick(x, y);
                    else if (pointerId == lookPointer && !pointerAction.containsKey(pointerId)) {
                        float factor = 2.2f * 1280.0f / Math.max(1.0f, getWidth());
                        nativeMouseDelta(Math.round((x - lastLookX) * factor), Math.round((y - lastLookY) * factor));
                        lastLookX = x; lastLookY = y;
                    }
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
                if (action == MotionEvent.ACTION_CANCEL) {
                    for (int b : new ArrayList<>(buttonsDown)) setButton(b, false);
                    for (int k : new ArrayList<>(heldKeys)) setKey(k, false);
                    pointerAction.clear(); stickPointer = -1; lookPointer = -1; invalidate(); return true;
                }
                Integer hit = pointerAction.remove(id);
                if (hit != null) setButton(hit, false);
                if (id == stickPointer) { stickPointer = -1; updateStick(stickOriginX, stickOriginY); }
                if (id == lookPointer) lookPointer = -1;
                return true;
            }
            return true;
        }
    }
}
