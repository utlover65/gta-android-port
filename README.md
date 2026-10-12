# GTA Android Port — Step 1: Astra Vulkan Probe

This is the first executable milestone for the Android port: an Android app that queries the tablet's Vulkan driver before we modify the GTA native host.

**This is not the GTA port yet.** It checks whether the device exposes Vulkan, reports GPU/device/API information, and checks the BC/ASTC/ETC2 formats relevant to the renderer.

## Build it from your tablet using GitHub Actions

1. Extract this ZIP.
2. Create a new empty GitHub repository, for example `gta-android-port`.
3. Upload all extracted files/folders to the repository root. Keep `.github/workflows/android.yml` in place.
4. Open **Actions** → **Build Astra Vulkan Probe** → **Run workflow**.
5. When the run finishes, download the `astra-vulkan-probe-debug-apk` artifact and extract it.
6. Install `app-debug.apk` on the REDMAGIC Astra and open **Astra Vulkan Probe**.
7. Copy the report text and send it back here.

## Pass criteria

- At least one Vulkan physical device is listed.
- Device Vulkan API version is 1.1 or newer.
- The report shows compression format support and baseline feature flags.

This is only a GPU/driver probe. It does not prove Dawn, the GTA engine, or the full game will launch.

## Next step

Use the report to select the Dawn/Vulkan build strategy, then port the actual native host: memory mapping and threading, Android window surface, audio output, filesystem paths, and touch/controller input. Keep existing renderer and game features; don't delete functionality to make an early build appear successful.

No GTA assets are included. Use only game data you are legally entitled to use.


## Browser port compatibility gate

The **Browser port** button opens the live browser build in an Android WebView using a low-memory profile. The overlay reports whether that exact WebView exposes WebGPU, OffscreenCanvas, SharedArrayBuffer, and cross-origin isolation. This is an online compatibility test, not yet an offline/native integration; the original Dawn and Vulkan probes remain available.

The integration notes in `docs/browser-runtime-integration.md` explain the runtime contracts found in the provided bundle and why its ZIP is not yet a complete offline deployment.
