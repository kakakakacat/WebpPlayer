# Changelog

## 1.0.5 (2026-06-15)

### New
- `MultiWebpGLView` now supports app-driven dynamic sprite overlays via
  `setSpriteBitmaps()` and `updateSprites()`, drawn above all WebP layers in the
  same GL pass with per-instance position and alpha.

### Fixes
- `WebpGLView.setForegroundBitmap()` / `clearForeground()` now request a render
  immediately on `RENDERMODE_WHEN_DIRTY` surfaces, so foreground overlays no
  longer wait behind WebP frame timing to become visible.
- Foreground textures are re-uploaded correctly after GL context recreation and
  their MVP is recomputed on surface-size changes.
- Added Robolectric regression tests for foreground render wake-up behavior.

## 1.0.4 (2026-06-10)

### Performance
- **Preload cost pre-check**: `preloadWebPAnimResults` now peeks the webp
  container header (new `getAnimInfo` JNI, no pixel decode) and skips entries
  whose decoded size would exceed the cache budget — previously such preloads
  burned ~1s+ of CPU decoding animations that were evicted immediately.
- **Concurrent decode dedup**: simultaneous cache misses for the same resource
  now decode exactly once (per-key locking, shared with the async refill path);
  previously each caller decoded its own copy and all but one were discarded.
- **Decode threads run at background priority** on a dedicated pool, so bulk
  decoding at startup no longer competes with the UI thread for little cores.
  The async refill path now also respects the per-device parallelism cap.
- **Relaxed cache profiles**: with refcounted clones the cache entry is the
  only copy in memory, so budgets/entries grew accordingly
  (sm6225: 8MB/1 → 24MB/3, mt8781: 20MB/2 → 32MB/4, default: 12MB/1 → 24MB/3).

### New
- `WebpDeviceProfile.maxDecodePixels`: per-SoC cap on decoded frame area;
  oversized assets are force-downscaled in native to protect low-end devices.
- `WebpDeviceProfile.hardwareBuffers`: per-SoC switch for the zero-copy path.
- `WebPYUVDecoder.peekAnimInfo()`: cheap container-header metadata
  (canvas size, frame count, loop count, alpha) without decoding pixels.

## 1.0.3 (2026-06-10)

### Performance
- **Zero-copy rendering pipeline**: frames decode straight into `AHardwareBuffer`
  (CPU/GPU shared memory) and are sampled through per-frame `EGLImage`-backed
  textures. Steady-state playback does **zero per-frame upload and zero memcpy**
  — previously every frame tick copied the full RGBA frame into the GPU via
  `glTexSubImage2D` (~23 MB/s for a 540² @ 20 fps animation).
  Falls back automatically to the ByteBuffer + upload path when hardware-buffer
  allocation or EGLImage creation fails; `WebPYUVDecoder.hardwareBuffersEnabled`
  is a global kill switch.
- **Vsync-aligned frame pacing**: frame ticks moved from main-thread
  `Handler.postDelayed` to `Choreographer.postFrameCallbackDelayed`, eliminating
  mid-frame wakeups.
- **Absolute-grid frame alignment**: fixed-fps animations anchor their frame
  advancement to a shared absolute time grid, so same-fps layers — including
  layers on different GLSurfaceViews — advance and submit on the same vsync.
  SurfaceFlinger composition work for multiple out-of-phase surfaces drops
  accordingly (measured ~40 → ~18 compositions/s on the reference device).
- GLSurfaceViews no longer request an unused 16-bit depth buffer.

### New APIs
- `setGlobalFrameRate(fps)` on `WebpGLView` / `MultiWebpGLView`: deliberately
  vote a global render frame rate (clamped to 30–60, 0 clears) to save power.
  Note: uid-level frameRateOverride throttles the whole app's vsync, hence the
  30 fps floor.
- `frameRateHintEnabled` (default **off**): opt-in playback-fps hint via
  `Surface.setFrameRate`. Off by default because on render-rate-switching
  devices a low vote drags the entire screen's render rate down (observed
  60 Hz panel forced to 15–30 fps rendering, janking all sibling UI).
- `WebPAnimResult.hardwareFrames` / `frameCount`; `MultiWebpGLViewContainer`
  is now `open` for subclassing.

## 1.0.2 (2026-06-10)

### Performance
- Cache clone is a native refcount bump instead of a deep copy (no more
  full-frame memcpy per consumer; memory no longer doubled).
- Frame scaling switched to libyuv `ARGBScale` (NEON), ~8x faster than the
  previous scalar bilinear.
- Decode parallelism cap now applies process-wide (shared dispatcher).
- Static single-frame webps stop re-uploading/rescheduling after first draw.
- Direct-ByteBuffer decode entry: webp payload no longer pinned/copied through
  a Java array during decode.

### Fixes
- OOB read when releasing foreign direct buffers (ownership tracked in a
  pointer registry).
- Native memory leak when `pendingAnim` was overwritten before GL was ready.
- `WebpGLView` could never load again after detach/re-attach.
- Frame pacing uses `uptimeMillis` and no longer drifts slow.
- Decode target size is aspect-fit and never upscales.

### New
- Finite-loop webps (`loop_count > 0`) play N times, hold the last frame, and
  reclaim the native memory of all other frames; `start()` replays via a cheap
  cache reload.

## 1.0.1 (2026-06-09)
- Unified logcat tag `WebpKit`; blank-screen diagnostics.

## 1.0.0 (2026-06-09)
- Initial release: GPU-composited animated WebP player (`WebpGLView`,
  `MultiWebpGLView`, `WebpImageView`), native libwebp decode, frame cache.
