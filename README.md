# WebpKit Player

English | [简体中文](README.zh-CN.md)

A self-contained Android library for high-performance **animated WebP** playback,
built on a trimmed, bundled copy of [libwebp](https://chromium.googlesource.com/webm/libwebp)
(encoder + decoder source) compiled to a native `.so`.

It decodes every frame of an animated WebP to RGBA via JNI and renders it either
on an OpenGL ES 2.0 surface (lowest overhead, GPU-composited) or through the
platform `ImageDecoder` / `AnimatedImageDrawable` pipeline.

- **Zero host dependencies** — the library has its own logging, threading and
  context bootstrap. Drop the AAR in and go; no init call required.
- **Native libwebp** — full encode/decode C source is bundled and compiled from
  scratch (no prebuilt binaries), currently packaged for `arm64-v8a`.
- **Frame caching** with a device-aware memory budget and async refill.

## How it works & how it compares

This library uses **full-decode + GPU compositing**: every frame of the WebP is
decoded to RGBA *once* by native libwebp (JNI) into native-backed buffers, then
played back as plain OpenGL ES texture swaps. Playback costs almost no
main-thread/CPU work — the GPU composites — and decoded frames are cached
(clone-on-read, LRU, device-aware budget) and reused across views.

That is a very different trade-off from the platform `AnimatedImageDrawable` or
Glide, which decode **on demand, every loop, on the CPU** and draw through the
View's canvas:

| | **WebpKit (this lib)** | **Platform `ImageDecoder` / `AnimatedImageDrawable`** | **Glide (animated WebP)** |
|---|---|---|---|
| Decode model | All frames decoded **once**, up front (native) | Frames decoded **on demand**, every loop | On demand (delegates to platform decoder on API 28+) |
| Playback cost | GPU texture swap — **~no per-frame CPU** | Per-frame CPU decode on every loop | Per-frame CPU decode + Glide pipeline |
| Many concurrent anims | **One** GL surface, one draw loop for all layers | N Views → N invalidate/onDraw cycles | N Drawables → N invalidate cycles |
| Compositing | **GPU** | CPU / HW canvas via View | CPU / HW canvas via View |
| Memory | **Higher** — all frames stay resident | Lower — only working frames | Lower + Glide caches |
| Remote / disk loading | Not built in (raw resources) | No | **Yes** — Glide's strength |
| RecyclerView / lists | **Not suitable** (GL surface + z-order) | Fine | **Best** — designed for it |
| Draw a `View` on top | Needs `MultiWebpGLViewContainer` (see [Z-order](#z-order-drawing-a-view-above-the-animation)) | Trivial | Trivial |

**Use WebpKit** when you have a few high-fps / multi-layer animations on a
relatively static screen (splash screens, full-screen effects, character /
sticker compositions) and want buttery-smooth playback with minimal CPU.
**Prefer the platform decoder or Glide** for list / scrolling content, remotely
loaded images, or when you must freely stack views on top.

### Performance advantages

- **No per-frame CPU decode during playback** — frames are pre-decoded; each
  displayed frame is a single `glTexSubImage2D` + draw, so playback stays smooth
  even at high fps and with several layers.
- **Decode once, reuse everywhere** — `WebPAnimResultManager` caches
  native-backed frames (clone-on-read, LRU, per-device memory budget); the same
  resource shown in multiple views is decoded only once.
- **Multi-layer on a single surface** — `MultiWebpGLView` composites N animations
  in one GL context / one frame loop, instead of N animated views each driving
  their own invalidate → onDraw.
- **Off-main-thread decode** and explicit native-memory release.

### Trade-offs (read this)

- **Memory** — every frame is decoded and kept resident, so a long / large WebP
  costs more RAM than the on-demand decoders. Pass the `size` / `decodeSize`
  hints to downscale at decode time.
- **Z-order** — the GL views call `setZOrderOnTop(true)`, so the surface is drawn
  **above every sibling view**; you cannot draw a normal `View` on top of it
  directly. See [Z-order](#z-order-drawing-a-view-above-the-animation) for the fix.
- **Not for RecyclerView** — surface lifecycle / resource conflicts cause flicker.

## Modules / public API

| Class | What it does |
|-------|--------------|
| `WebpGLView` | A `GLSurfaceView` that decodes and plays a single animated WebP from a raw resource. |
| `WebpImageView` | An `ImageView` that plays an animated WebP via `AnimatedImageDrawable` (sync or async decode). |
| `MultiWebpGLView` | Renders **multiple** WebP layers on one GL surface, each with its own position, size and fps. |
| `MultiWebpGLViewContainer` | Wraps `MultiWebpGLView` and lets a single layer be lifted off the GL surface so another view can be drawn above it. |
| `WebPAnimResultManager` | Decode cache (clone-on-read, native-backed buffers, LRU eviction). |
| `WebPYUVDecoder` | Low-level JNI decoder (`decodeAllFrames`, `decodeAllFramesWithSize`, buffer clone/release). |

## Requirements

- `minSdk` 28 (uses `ImageDecoder` / `AnimatedImageDrawable`)
- ABI: `arm64-v8a`
- NDK + CMake only needed to build from source — consumers of the AAR do not need them.

## Install

### Option A — Gradle dependency (recommended, auto-download)

Add the repository, then one line for the dependency. Transitive deps
(coroutines, lifecycle) are pulled in automatically via the published POM.

`settings.gradle.kts` (or your project `build.gradle`):

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://raw.githubusercontent.com/kakakakacat/WebpPlayer/mvn-repo/") }
    }
}
```

App `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.webpkit:player:1.0.1")
}
```

<details>
<summary>Groovy DSL</summary>

```groovy
// settings.gradle
maven { url 'https://raw.githubusercontent.com/kakakakacat/WebpPlayer/mvn-repo/' }
// build.gradle
implementation 'io.webpkit:player:1.0.1'
```
</details>

### Option B — drop in the AAR manually

Copy `player-1.0.1.aar` into your app's `libs/` folder and add:

```kotlin
dependencies {
    implementation(files("libs/player-1.0.1.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
}
```

### Option C — build from source

```bash
git clone https://github.com/kakakakacat/WebpPlayer.git
cd WebpPlayer
./gradlew :webpview:assembleRelease
# → webpview/build/outputs/aar/webpview-release.aar
```

## Usage

### Single animation on a GL surface

```kotlin
val view = WebpGLView(context)
view.setWebpFromRaw(R.raw.my_anim, fps = 20)
view.start()   // play
view.stop()    // pause
```

### Simple ImageView playback

```kotlin
val iv = WebpImageView(context)
iv.setWebpFromRaw(R.raw.my_anim)
iv.start()
```

### Multiple layers

```kotlin
val gl = MultiWebpGLView(context)
gl.setLayers(
    listOf(
        WebpLayer(R.raw.bg,  x = 0f,   y = 0f,   width = 300f, height = 300f, fps = 24),
        WebpLayer(R.raw.fx,  x = 80f,  y = 80f,  width = 140f, height = 140f, fps = 12),
    )
)
gl.start()
gl.setLayerPosition(1, 100f, 100f)   // move a layer without re-decoding
gl.hideLayer(1)                       // toggle visibility
```

### Z-order: drawing a `View` above the animation

Because the GL views call `setZOrderOnTop(true)`, the animation surface is drawn
**above all sibling views** — adding a normal `View` next to it will *not* place
it on top of the animation. When you need to overlay a regular `View` (badge,
button, label, countdown…) on top of one animation, use
**`MultiWebpGLViewContainer`**. It lifts a *single* layer off the GL surface and
replaces it, pixel-for-pixel, with a `WebpImageView` in the normal view hierarchy
(the GL surface goes transparent where that layer was), so covering views work
again — while every other layer keeps GPU-animating, untouched.

```kotlin
val container = MultiWebpGLViewContainer(context)
container.glView.setLayers(
    listOf(
        WebpLayer(R.raw.bg,    x = 0f,  y = 0f,  width = 300f, height = 300f, fps = 24),
        WebpLayer(R.raw.badge, x = 80f, y = 80f, width = 140f, height = 140f, fps = 12),
    )
)
container.glView.start()

// Lift layer #1 off the GL surface so a normal View can be drawn above it.
// onFrozen fires once the hand-off is seamless (overlay drawn, GL layer hidden).
container.freezeLayer(1, onFrozen = {
    val label = TextView(context).apply { text = "NEW" }
    container.addView(
        label,
        FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            leftMargin = 80; topMargin = 80   // sits above the (now frozen) layer
        },
    )
})

// Hand the layer back to the GPU surface when the overlay is no longer needed.
container.unfreezeLayer(1)
```

`freezeLayer` / `unfreezeLayer` are seamless (the overlay is only swapped in/out
after a real frame has been drawn) and safe to call repeatedly. They also accept
a `delayMs` if you want to defer the swap.

> ⚠️ Still avoid **all** GL views inside a `RecyclerView` (surface lifecycle /
> resource conflicts cause flicker), even via the container.

## Initialization

The application context is captured automatically at process start by a tiny
`ContentProvider` (`WebpInitProvider`). If you strip manifest providers, call
once yourself:

```kotlin
WebpContext.init(applicationContext)
```

To silence logging (e.g. in release):

```kotlin
WebpLog.enabled = false
```

## License

MIT for the wrapper code. Bundled libwebp is BSD-licensed by Google — see
[`LICENSE`](LICENSE) and `webpview/src/main/cpp/libwebp/COPYING`.
