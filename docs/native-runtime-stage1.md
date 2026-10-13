# Native runtime stage 1 — Wasmtime on Android ARM64

## What this adds

An opt-in native Android execution path. It embeds the official Wasmtime ARM64 Android C API shared
library in the APK and calls it from a JNI/C++ host. The Activity uses Android's document picker to
copy the selected `game.wasm` to app-private storage. It then:

1. Enables Wasmtime memory64, threads, shared memory, SIMD, and fuel metering.
2. Compiles the selected binary on-device (no browser or WebView involved).
3. Examines its imports and binds the declared `env.memory` import as a Wasmtime shared memory.
4. Provides selected native clock/logging/WASI/manifest callbacks and explicit default-returning stubs for imports that have not been ported.
5. Instantiates the module, allowing the Wasm start section/data initialization to execute.
6. Calls `__wasm_call_ctors` if exported.
7. Exposes a separate call to `__main_argc_argv` with a 20,000,000-instruction fuel cap.

## What it does not do

This does not yet render GTA V or enable movement. Default-returning imports are scaffolding only.
The following host services still need real native implementations:

- `wgpu_start_worker` and the shared-memory GPU command ring, translated into Dawn/Vulkan commands.
- HTTP/batch range reads and cache semantics. The manifest fetch has a narrow native HTTPS adapter, but the worker protocol is not implemented.
- Emscripten pthread start/synchronization.
- Audio mixer publication and timing.
- Input publication, controller/touch mapping, and game-side event consumption.
- Required WASI/syscall behavior and the target runtime's environment.

A “NATIVE WASM MODULE START: PASS” result means the module compiled, instantiated, ran its start section,
and executed its constructor export with temporary host stubs. It is not a playable-game result. The
main-entry button may report an early trap, exhaust its fuel budget, or return; none of those outcomes
is itself proof that the world rendered.

## Safety constraints

The observed module declares one shared memory64 import with 49,152 minimum pages (3 GiB) and 262,144
maximum pages (16 GiB). The activity does not try to allocate the maximum up front, but runtime memory
reservation and physical page touching remain platform-dependent. The module file-size cap is 256 MiB;
the known supplied module is ~63.2 MB. Run with adequate free RAM, cooling, and battery.

## Build

The GitHub Actions workflow adds an optional `native_engine` workflow-dispatch boolean. When enabled,
it downloads Wasmtime v49.0.2's `aarch64-android-c-api` release asset, checks its pinned SHA-256,
verifies that the matching headers expose the compiler and threads APIs, stages the shared library and
matching public headers, includes the upstream Apache-2.0/LLVM-exception license in the APK assets,
and asks CMake to build `gta_native_engine`.
The ordinary Vulkan/Dawn APK build remains the default and does not require the Wasmtime library.

## Next real gameplay step

Use runtime logs and the main-entry result to discover which host services are requested first. Replace
the no-op bindings one at a time, beginning with clock/logging and asset-manifest/file reads, then port
the GPU worker's command protocol to the existing native Dawn/Vulkan surface. Do not label gameplay as
working until a frame from the game engine and an actual player movement event are demonstrated.
