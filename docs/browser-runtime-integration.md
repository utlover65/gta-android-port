# Browser-port integration notes (verified against `gta5.zip`)

## What the supplied bundle contains

The archive contains the Emscripten glue (`game.js`), compiled game engine (`game.wasm`), page loader, WebGPU worker, IO worker, audio worklet, game-data manifest, and a subset of the `game-assets` tree. The page snapshot is `gta-website/gta.deloskiyt.pl/index.html`.

**The ZIP is not a self-contained offline deployment.** Its manifest names 5,814 data files, while the ZIP contains only 2,893 files under `gta-files/game-assets/`; the ZIP totals about 443 MB uncompressed, and no `shaders/` tree appears in it. The live service also exposes endpoints used by the IO worker (`/data/manifest.json`, `/data/batch`, `/data/batchc/…`) and shader packs under the build prefix. The provided page uses `/b/8b0b5899ed` as its runtime prefix. Merely putting `game.wasm` beside `index.html` would not be enough.

## Actual host contracts found in the files

- `index.html` checks `crossOriginIsolated` before it boots the game, transfers its `<canvas>` to an `OffscreenCanvas`, and creates `loader.js`.
- `loader.js` creates `wgpu_worker.js` and `io_worker.js`, then loads `game.js` and starts the Emscripten module.
- The compiled module is pthread-enabled and uses shared Wasm memory plus atomics; it also imports custom host calls for GPU-worker startup, audio/input publishing, and HTTP-backed file reads.
- `wgpu_worker.js` consumes the engine's shared-memory command ring and translates the translated D3D11-style command stream to WebGPU. Its Node path is for headless/debug testing: its own comments state that Node has no presentation surface. That path does not automatically connect to Android's `ANativeWindow`.
- `io_worker.js` expects the server's manifest and range/batch APIs, persists the working set in IndexedDB, and stores save/settings data separately.
- `audio-worklet.js` plays the mixer output from shared Wasm memory through `AudioWorklet`.

Emscripten's pthread documentation confirms that browser pthread deployments depend on `SharedArrayBuffer` and cross-origin isolation (COOP/COEP): <https://emscripten.org/docs/porting/pthreads.html>.

## Why this patch adds a WebView compatibility activity first

The new activity embeds the real live page as a temporary feasibility gate. It reports feature availability from the actual Android WebView provider—`navigator.gpu`, `OffscreenCanvas`, `SharedArrayBuffer`, `crossOriginIsolated`, workers, and `AudioWorklet`—rather than assuming Chrome and WebView expose the same set of features. It uses a low-memory startup profile (`low=1`, 75% render scale, 30 FPS) for the initial test and blocks the known unrelated `quge5.com` ad tag in the captured HTML.

This does **not** claim a fully native/offline port. It answers a practical question first: can the installed WebView execute the browser build with its original host APIs? If that works, a standalone wrapper is a short-term launch path. If it does not, the diagnostic output identifies the missing browser API before implementing a native host. The existing Dawn `ANativeWindow` probe remains unchanged; the final native path will still need to make the game's command ring and worker protocol drive that surface, as well as provide file IO, audio, and input.

## Test outcomes to record

A useful first pass should show all four of `WebGPU`, `SharedArrayBuffer`, `crossOriginIsolated`, and `OffscreenCanvas` as `true`. A `false` on any of those is a concrete compatibility blocker; a true API flag alone does not prove that the game reaches the world or that the GPU has every required feature/limit. Keep Android WebView console errors and the page's loading HUD when reporting failure.
