# Native Android host test (not yet a GTA gameplay test)

The **Native Port Test** screen removes WebView from this test path. Its render target is an Android `SurfaceView` and the graphics tests invoke the existing JNI renderers directly:

- **Dawn WebGPU**: creates a Dawn instance, Android native-window surface, Vulkan-backed adapter/device/queue, configures the surface, submits a render-pass clear, and presents one frame.
- **Vulkan**: creates an Android Vulkan surface/swapchain, submits a clear render pass, and presents one frame.
- **App-private storage**: writes, reads, compares, then deletes a small temporary file.
- **Background worker**: confirms a Java worker thread can be scheduled and completed.
- **Android audio API**: requests a short `ToneGenerator` beep; listen for it, because API acceptance alone cannot prove the speaker is audible.
- **Touch input**: tap the render area and confirm it records the touch coordinates.

The suite reports the results and renderer call durations on screen. Run Dawn and Vulkan separately if a combined run stops after a failure.

## What it does not test

This is a native **host/platform smoke test**, not a full GTA game-engine test. The supplied game bundle is an Emscripten/WebAssembly build and depends on JavaScript host imports, a worker-driven WebGPU command ring, remote/batch asset I/O, pthread/shared Wasm memory, and an AudioWorklet. The repository does not contain a native GTA engine library or source tree to load into this activity. A full native gameplay test needs either a native engine build/source port, or a deliberately engineered WASM runtime plus replacements for those browser imports. Dawn successfully drawing a clear frame is not proof of GTA rendering support.

The test currently renders one frame each time a renderer test is selected. Surface lifecycle and resizing beyond Android's initial surface creation still need work before it can become a continuous engine renderer.
