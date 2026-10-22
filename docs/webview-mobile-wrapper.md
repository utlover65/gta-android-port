# WebView mobile wrapper

This build replaces the app launcher with a full-screen Android `WebView` and a translucent touchscreen overlay. The game itself remains hosted by the configured HTTPS site; this APK does not bundle the site's game code or assets.

## Controls

- Left virtual stick: W/A/S/D; push farther out to hold Shift.
- JUMP, ENTER/EXIT, USE, RUN, CAM and ESC: Android key events forwarded to the web page.
- LOOK: toggles a right-side swipe zone that emits synthetic mouse movement. With LOOK off, ordinary taps pass through to the web page so its launcher/menu and game-focus controls can work.
- Tap the top-left status chip for an API compatibility report: the installed WebView version, WebGPU, SharedArrayBuffer, `crossOriginIsolated`, OffscreenCanvas, workers and AudioWorklet.

Some browser games require trusted physical input, pointer lock, WebGPU features, or cross-origin isolation that Android WebView may not provide. Synthetic mouse movement is not trusted input. This is a testable wrapper, not a guarantee that a website's game runtime is compatible. If the report shows missing APIs, use a compatible browser rather than trying to bypass the site's browser or certificate checks.

## Security

- Only HTTPS main-frame navigation to `fiveplay.lol` subdomains is kept inside the wrapper.
- File/content access is disabled, microphone/camera permission requests are denied, and downloads are disabled.
- Normal TLS certificate validation remains enabled. Never install an unfamiliar site-provided root/LAN certificate to make a page work.
- No JavaScript-to-native bridge is exposed.

## Build

GitHub Actions runs `gradle --no-daemon -PwebOnly=true assembleDebug`. `webOnly=true` skips native CMake/NDK configuration. Existing native source files are preserved in the repository but are no longer the launcher path for this APK.
