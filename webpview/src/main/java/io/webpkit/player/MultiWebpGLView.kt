package io.webpkit.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.HardwareBuffer
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.util.AttributeSet
import android.util.Size
import androidx.annotation.RawRes
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Data classes
// ---------------------------------------------------------------------------

/**
 * Describes one WebP layer to be rendered on the [MultiWebpGLView].
 *
 * @param resId     Raw resource id of the WebP file.
 * @param x         Left edge of the layer in **view pixels** (top-left origin).
 * @param y         Top edge of the layer in **view pixels** (top-left origin).
 * @param width     Display width in view pixels (0 = use decoded frame width).
 * @param height    Display height in view pixels (0 = use decoded frame height).
 * @param fps       Target playback fps (0 = use durations embedded in WebP).
 * @param decodeSize Optional down-scale hint passed to the native decoder.
 *                   When null the native decoder uses the original canvas size.
 * @param visible   Initial visibility for this layer.
 */
data class WebpLayer(
    @RawRes val resId: Int,
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f,
    val fps: Int = 0,
    val decodeSize: Size? = null,
    val visible: Boolean = true,
)

/**
 * One dynamic sprite drawn **above** all WebP layers, sharing the same quad/program.
 * Unlike [WebpLayer] (a decoded WebP at a fixed slot), sprites are app-driven: the host
 * pushes a fresh list every frame via [MultiWebpGLView.updateSprites], so position/alpha
 * can change per frame with **no main-thread View invalidate** (rendering happens on the
 * GL thread). The bitmap is referenced by [texIndex] into the list given to
 * [MultiWebpGLView.setSpriteBitmaps].
 *
 * @param texIndex Index into the bitmap list registered via [MultiWebpGLView.setSpriteBitmaps].
 * @param x        Left edge in **view pixels** (top-left origin).
 * @param y        Top edge in **view pixels** (top-left origin).
 * @param width    Display width in view pixels.
 * @param height   Display height in view pixels.
 * @param alpha    Opacity multiplier 0..1 (applied on top of the texture's own alpha).
 */
data class SpriteInstance(
    val texIndex: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val alpha: Float = 1f,
)

// ---------------------------------------------------------------------------
// Per-layer GL state (all access on GL thread)
// ---------------------------------------------------------------------------

private class LayerState(val config: WebpLayer) {
    @Volatile var isVisible: Boolean = config.visible

    // Live position (view pixels, top-left origin). Initialized from config but can be
    // updated at runtime via setLayerPosition without re-decoding the layer.
    @Volatile var posX: Float = config.x
    @Volatile var posY: Float = config.y

    // Animation data (software/ByteBuffer mode)
    var frames: Array<ByteBuffer>? = null
    var frameDurations: IntArray = IntArray(0)
    var frameWidth: Int = 0
    var frameHeight: Int = 0

    // Zero-copy mode (AHardwareBuffer + EGLImage): one texture per frame,
    // playback just rebinds — no per-frame upload.
    var hardwareFrames: Array<HardwareBuffer>? = null
    var hwTextures: IntArray = IntArray(0)
    var hwImages: LongArray = LongArray(0)

    fun frameCount(): Int = hardwareFrames?.size ?: frames?.size ?: 0

    // Playback
    var currentFrameIndex: Int = 0
    var lastFrameTimeMs: Long = SystemClock.uptimeMillis()

    // Finite-loop playback (webp loop_count > 0): play N times then hold the
    // last frame and reclaim the other frames' native memory.
    var loopCount: Int = 0          // 0 = loop forever
    var completedLoops: Int = 0
    @Volatile var playbackFinished: Boolean = false
    @Volatile var framesTrimmed: Boolean = false

    // GL
    val texId: IntArray = IntArray(1)
    var textureAllocated: Boolean = false

    // MVP (computed once per surface-change or layer-load)
    val mvp: FloatArray = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    // Pending data from IO thread → applied on GL thread
    @Volatile var pendingAnim: WebPAnimResult? = null

    fun effectiveFps(): Int = config.fps

    /**
     * Compute MVP that maps [-1,1] quad → the pixel rect defined by [config].
     * View dimensions needed to convert pixels → NDC.
     */
    fun computeMvp(viewWidth: Int, viewHeight: Int) {
        if (viewWidth == 0 || viewHeight == 0) {
            Matrix.setIdentityM(mvp, 0)
            return
        }

        val dispW = if (config.width > 0f) config.width else frameWidth.toFloat()
        val dispH = if (config.height > 0f) config.height else frameHeight.toFloat()
        if (dispW == 0f || dispH == 0f) {
            Matrix.setIdentityM(mvp, 0)
            return
        }

        // NDC scale: half-size relative to view
        val sx = dispW / viewWidth.toFloat()
        val sy = dispH / viewHeight.toFloat()

        // Pixel top-left → NDC center of the quad
        // NDC x = (pixelLeft + dispW/2) / viewWidth * 2 - 1
        val cx = (posX + dispW * 0.5f) / viewWidth.toFloat() * 2f - 1f
        // NDC y is flipped: pixel y=0 is top → NDC y=+1
        val cy = 1f - (posY + dispH * 0.5f) / viewHeight.toFloat() * 2f

        Matrix.setIdentityM(mvp, 0)
        Matrix.translateM(mvp, 0, cx, cy, 0f)
        Matrix.scaleM(mvp, 0, sx, sy, 1f)
    }
}

// ---------------------------------------------------------------------------
// Multi-layer Renderer
// ---------------------------------------------------------------------------

private class MultiRenderer(
    private val requestRender: () -> Unit
) : GLSurfaceView.Renderer {

    private val TAG = "MultiWebpRenderer"

    // shared quad geometry ([-1,1] unit quad, drawn with per-layer MVP)
    private val QUAD_VERTS = floatArrayOf(
        -1f,  1f,
        -1f, -1f,
         1f,  1f,
         1f, -1f,
    )
    private val QUAD_TEX = floatArrayOf(
        0f, 0f,
        0f, 1f,
        1f, 0f,
        1f, 1f,
    )
    private val sharedVertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_VERTS.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(QUAD_VERTS); position(0) }
    private val sharedTexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_TEX.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(QUAD_TEX); position(0) }

    private val VS = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying   vec2 vTexCoord;
        uniform   mat4 uMVP;
        void main() {
            gl_Position = uMVP * aPosition;
            vTexCoord   = aTexCoord;
        }
    """.trimIndent()

    private val FS = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D tex;
        uniform float uAlpha;
        void main() {
            gl_FragColor = texture2D(tex, vTexCoord) * uAlpha;
        }
    """.trimIndent()

    private var program   = 0
    private var aPosLoc   = 0
    private var aTexLoc   = 0
    private var uMvpLoc   = 0
    private var uTexLoc   = 0
    private var uAlphaLoc = 0

    private var viewWidth  = 0
    private var viewHeight = 0

    @Volatile var playing = false

    /** One-shot callback fired (on main thread) after the next completed draw. */
    @Volatile var onNextFrameDrawn: (() -> Unit)? = null

    // Layers list — written only from GL thread or before surface is created
    @Volatile
    private var layers: List<LayerState> = emptyList()

    // Dynamic sprite overlay (app-driven; e.g. floating bubbles). Drawn above all webp
    // layers using the shared quad/program with per-instance alpha. [spriteBitmaps] +
    // [spriteTexIds] are GL-thread state; [spriteInstances] is a @Volatile reference swapped
    // wholesale from the caller each frame (immutable list → safe to read on GL thread).
    private var spriteBitmaps: List<Bitmap> = emptyList()
    private var spriteTexIds: IntArray = IntArray(0)
    @Volatile private var spriteInstances: List<SpriteInstance> = emptyList()
    private val spriteMvp = FloatArray(16)

    // Frame-pacing handler (main looper, lightweight)
    private val frameHandler = Handler(Looper.getMainLooper())
    private val frameTick = Runnable {
        if (playing) requestRender()
    }

    // Choreographer：帧 tick 对齐 vsync（延迟到期后的下一个 vsync 触发），
    // 避免落在帧中间的无效唤醒。构造发生在主线程；拿不到时回退 Handler。
    private val choreographer: Choreographer? = try {
        Choreographer.getInstance()
    } catch (_: Throwable) {
        null
    }
    private val frameCallback = Choreographer.FrameCallback { if (playing) requestRender() }

    private fun scheduleNextFrame(delayMs: Long) {
        val ch = choreographer
        if (ch != null) {
            ch.removeFrameCallback(frameCallback)
            ch.postFrameCallbackDelayed(frameCallback, delayMs)
        } else {
            frameHandler.postDelayed(frameTick, delayMs)
        }
    }

    private fun cancelScheduledFrame() {
        choreographer?.removeFrameCallback(frameCallback)
        frameHandler.removeCallbacks(frameTick)
    }

    // ---------------------------------------------------------------------------

    fun setLayers(newLayers: List<LayerState>) {
        // Called from GL thread (via queueEvent)
        // Release old GL textures
        layers.forEach { old ->
            if (old.texId[0] != 0) {
                GLES20.glDeleteTextures(1, old.texId, 0)
                old.texId[0] = 0
            }
            old.releaseNativeFrames()
            // 未被消费的 pendingAnim 同样持有 native buffer，不释放就泄漏
            old.replacePendingAnim(null)
        }
        layers = newLayers
        // Allocate textures + upload first frame if program already up
        if (program != 0) {
            newLayers.forEach { layer ->
                layer.pendingAnim?.let { anim ->
                    applyAnim(layer, anim)
                    layer.pendingAnim = null
                }
                allocTexture(layer)
            }
        }
        recomputeAllMvp()
    }

    /** Called from GL thread when a single layer's anim data arrives. */
    fun applyPendingAnim(layer: LayerState) {
        val anim = layer.pendingAnim ?: return
        layer.pendingAnim = null
        applyAnim(layer, anim)
        requestRender()
    }

    fun setCustomPlaying(shouldPlay: Boolean) {
        playing = shouldPlay
        cancelScheduledFrame()
        if (shouldPlay) {
            layers.forEach { it.lastFrameTimeMs = SystemClock.uptimeMillis() }
        }
    }

    fun setLayerVisible(index: Int, visible: Boolean) {
        val layer = layers.getOrNull(index) ?: return
        layer.isVisible = visible
    }

    fun setLayerPosition(index: Int, x: Float, y: Float) {
        val layer = layers.getOrNull(index) ?: return
        layer.posX = x
        layer.posY = y
        layer.computeMvp(viewWidth, viewHeight)
    }

    /** Upload the sprite texture set (GL thread). Replaces any previous set. */
    fun applySpriteBitmaps(bitmaps: List<Bitmap>) {
        if (spriteTexIds.isNotEmpty()) {
            try { GLES20.glDeleteTextures(spriteTexIds.size, spriteTexIds, 0) } catch (_: Throwable) {}
            spriteTexIds = IntArray(0)
        }
        spriteBitmaps = bitmaps
        if (bitmaps.isEmpty() || program == 0) return
        val ids = IntArray(bitmaps.size)
        GLES20.glGenTextures(bitmaps.size, ids, 0)
        bitmaps.forEachIndexed { i, bmp ->
            configureTexture(ids[i])
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[i])
            runCatching {
                if (!bmp.isRecycled) GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            }
        }
        spriteTexIds = ids
    }

    /** Swap the per-frame sprite instance list (any thread; immutable reference). */
    fun setSpriteInstances(list: List<SpriteInstance>) {
        spriteInstances = list
    }

    /** Map a pixel rect (top-left origin) → MVP for the shared [-1,1] quad. */
    private fun computeSpriteMvp(out: FloatArray, x: Float, y: Float, w: Float, h: Float) {
        if (viewWidth == 0 || viewHeight == 0 || w <= 0f || h <= 0f) {
            Matrix.setIdentityM(out, 0)
            return
        }
        val vw = viewWidth.toFloat()
        val vh = viewHeight.toFloat()
        val sx = w / vw
        val sy = h / vh
        val cx = (x + w * 0.5f) / vw * 2f - 1f
        val cy = 1f - (y + h * 0.5f) / vh * 2f
        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, cx, cy, 0f)
        Matrix.scaleM(out, 0, sx, sy, 1f)
    }

    // ---------------------------------------------------------------------------
    // GLSurfaceView.Renderer
    // ---------------------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)

        program   = buildProgram(VS, FS)
        aPosLoc   = GLES20.glGetAttribLocation(program,  "aPosition")
        aTexLoc   = GLES20.glGetAttribLocation(program,  "aTexCoord")
        uMvpLoc   = GLES20.glGetUniformLocation(program, "uMVP")
        uTexLoc   = GLES20.glGetUniformLocation(program, "tex")
        uAlphaLoc = GLES20.glGetUniformLocation(program, "uAlpha")

        // (Re)allocate textures for any layer that already has data
        layers.forEach { layer ->
            // Regenerate texture handle (old one may be from dead context)
            GLES20.glGenTextures(1, layer.texId, 0)
            configureTexture(layer.texId[0])
            layer.textureAllocated = false

            val anim = layer.pendingAnim
            if (anim != null) {
                applyAnim(layer, anim)
                layer.pendingAnim = null
            } else if (layer.hardwareFrames?.isNotEmpty() == true) {
                // 硬件帧不依赖 GL context，重建 texture + EGLImage 绑定即可
                rebuildLayerHardwareTextures(layer)
            } else if (layer.frames?.isNotEmpty() == true) {
                // Surface was recreated (e.g. after a visibility toggle) but the
                // decoded frames survived: re-upload the current frame now so the
                // first draw doesn't sample an uninitialized texture.
                allocTexture(layer)
                uploadFrame(layer)
            }
        }

        // Re-upload sprite textures (lost with the old GL context).
        if (spriteBitmaps.isNotEmpty()) applySpriteBitmaps(spriteBitmaps)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth  = width
        viewHeight = height
        GLES20.glViewport(0, 0, width, height)
        recomputeAllMvp()
    }

    override fun onDrawFrame(gl: GL10?) {
        drawFrameInternal()
        // One-shot frame-drawn notification (used for snapshot hand-off).
        // Posted to main; by the time it runs the buffer has been swapped.
        onNextFrameDrawn?.let { cb ->
            onNextFrameDrawn = null
            frameHandler.post(cb)
        }
    }

    private fun drawFrameInternal() {
        cancelScheduledFrame()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (program == 0) return

        val currentLayers = layers
        if (currentLayers.isEmpty()) return

        GLES20.glUseProgram(program)

        // Bind geometry once
        GLES20.glEnableVertexAttribArray(aPosLoc)
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, sharedVertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexLoc)
        GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 0, sharedTexBuffer)
        GLES20.glUniform1i(uTexLoc, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        // WebP layers are fully opaque (alpha comes from the texture); sprites override below.
        GLES20.glUniform1f(uAlphaLoc, 1f)

        var nextWakeMs = Long.MAX_VALUE
        val now = SystemClock.uptimeMillis()

        currentLayers.forEach { layer ->
            // Apply any pending anim that arrived from IO thread
            if (layer.pendingAnim != null) {
                applyPendingAnim(layer)
            }
            if (!layer.isVisible) return@forEach

            val count = layer.frameCount()
            if (count == 0) return@forEach
            val hwMode = layer.hardwareFrames != null
            if (!hwMode && !layer.textureAllocated) allocTexture(layer)

            // 单帧图层无动画可推进；播放完成的有限循环图层停在末帧——都不再参与调度
            if (playing && count > 1 && !layer.playbackFinished) {
                val fps = layer.effectiveFps()
                val dur = if (fps > 0) {
                    (1000L / fps)
                } else {
                    layer.frameDurations.getOrElse(layer.currentFrameIndex) { 100 }.toLong()
                }
                val elapsed = now - layer.lastFrameTimeMs
                if (elapsed >= dur) {
                    val atLastFrame = layer.currentFrameIndex == count - 1
                    if (atLastFrame && layer.loopCount > 0 && layer.completedLoops + 1 >= layer.loopCount) {
                        // 有限循环播完：停在末帧并回收其余帧内存
                        layer.completedLoops = layer.loopCount
                        finishLayerPlayback(layer)
                    } else {
                        if (atLastFrame) layer.completedLoops++
                        layer.currentFrameIndex = (layer.currentFrameIndex + 1) % count
                        if (fps > 0) {
                            // 固定 fps：锚定到绝对时间网格，相同 fps 的各层（含其它
                            // GLSurfaceView）在同一 vsync 推进，整面一次绘制推进多层，
                            // SurfaceFlinger 合成次数从错相位的 ~N×fps 合并回 fps
                            layer.lastFrameTimeMs = now - (now % dur)
                        } else {
                            // 可变帧时长：累加防漂移；落后超一帧则对齐
                            layer.lastFrameTimeMs += dur
                            if (now - layer.lastFrameTimeMs >= dur) {
                                layer.lastFrameTimeMs = now
                            }
                        }
                        // 软件模式上传新帧；硬件模式帧常驻 GPU，draw 时换绑定即可
                        if (!hwMode) uploadFrame(layer)
                    }
                }
                if (!layer.playbackFinished) {
                    val timeUntilNext = (dur - (SystemClock.uptimeMillis() - layer.lastFrameTimeMs)).coerceAtLeast(1L)
                    if (timeUntilNext < nextWakeMs) nextWakeMs = timeUntilNext
                }
            }

            // Draw layer
            val texToBind = if (hwMode) {
                if (layer.hwTextures.isEmpty()) 0
                else layer.hwTextures[layer.currentFrameIndex.coerceIn(0, layer.hwTextures.size - 1)]
            } else {
                layer.texId[0]
            }
            if (texToBind == 0) return@forEach
            GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, layer.mvp, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texToBind)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        // Dynamic sprites — drawn above all webp layers with per-instance position + alpha.
        // Geometry attribs are still bound from the layer pass above.
        val sprites = spriteInstances
        val sids = spriteTexIds
        if (sprites.isNotEmpty() && sids.isNotEmpty()) {
            sprites.forEach { s ->
                val tex = sids.getOrNull(s.texIndex) ?: return@forEach
                if (tex == 0) return@forEach
                computeSpriteMvp(spriteMvp, s.x, s.y, s.width, s.height)
                GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, spriteMvp, 0)
                GLES20.glUniform1f(uAlphaLoc, s.alpha)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
        }

        GLES20.glDisableVertexAttribArray(aPosLoc)
        GLES20.glDisableVertexAttribArray(aTexLoc)

        // Schedule next frame wake-up (only if playing and there are frames)
        if (playing && nextWakeMs != Long.MAX_VALUE) {
            scheduleNextFrame(nextWakeMs)
        }
    }

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------

    /**
     * 图层播放完成（GL 线程）：停在末帧，回收除当前帧外的 native 内存。
     * 释放走 native 引用计数，缓存仍持有同一块内存时只是计数 -1，不会悬空；
     * 被释放帧的 ByteBuffer 随旧数组一起丢弃，后续不可能再触碰已释放内存。
     */
    private fun finishLayerPlayback(layer: LayerState) {
        layer.playbackFinished = true

        // 硬件模式：保留当前帧的 buffer/texture/EGLImage，释放其余
        layer.hardwareFrames?.let { hw ->
            if (hw.size <= 1) return
            val keepIdx = layer.currentFrameIndex.coerceIn(0, hw.size - 1)
            for (i in hw.indices) {
                if (i == keepIdx) continue
                runCatching { if (!hw[i].isClosed) hw[i].close() }
                if (layer.hwImages.getOrElse(i) { 0L } != 0L) {
                    runCatching { WebPYUVDecoder.eglImageDestroy(layer.hwImages[i]) }
                }
            }
            if (layer.hwTextures.size == hw.size) {
                val toDelete = IntArray(hw.size - 1)
                var j = 0
                for (i in layer.hwTextures.indices) if (i != keepIdx) toDelete[j++] = layer.hwTextures[i]
                runCatching { GLES20.glDeleteTextures(toDelete.size, toDelete, 0) }
                layer.hwTextures = intArrayOf(layer.hwTextures[keepIdx])
            }
            layer.hwImages = longArrayOf(layer.hwImages.getOrElse(keepIdx) { 0L })
            layer.hardwareFrames = arrayOf(hw[keepIdx])
            layer.currentFrameIndex = 0
            layer.framesTrimmed = true
            WebpLog.i(TAG, "finishLayerPlayback: resId=${layer.config.resId} 播完(loop=${layer.loopCount})，回收 ${hw.size - 1} 个硬件帧")
            return
        }

        val f = layer.frames ?: return
        if (f.size <= 1) return
        val keepIdx = layer.currentFrameIndex.coerceIn(0, f.size - 1)
        val keep = f[keepIdx]
        val rest = Array(f.size - 1) { i -> f[if (i < keepIdx) i else i + 1] }
        try {
            val freedKB = WebPYUVDecoder.releaseNativeBuffers(rest)
            WebpLog.i(TAG, "finishLayerPlayback: resId=${layer.config.resId} 播完(loop=${layer.loopCount})，回收 ${f.size - 1} 帧，freed=$freedKB KB")
        } catch (t: Throwable) {
            WebpLog.e(TAG, "finishLayerPlayback: release failed: ${t.message}")
        }
        layer.frames = arrayOf(keep)
        layer.currentFrameIndex = 0
        layer.framesTrimmed = true
    }

    /**
     * 为图层的硬件帧重建 GL 资源（GL 线程）：每帧一个 texture 绑定到 EGLImage。
     * EGLImage 创建失败的帧退化为一次性 CPU 上传（之后播放同样零上传）。
     */
    private fun rebuildLayerHardwareTextures(layer: LayerState) {
        layer.releaseHardwareGl()
        val hw = layer.hardwareFrames ?: return
        if (program == 0 || hw.isEmpty()) return

        layer.hwTextures = IntArray(hw.size)
        layer.hwImages = LongArray(hw.size)
        GLES20.glGenTextures(hw.size, layer.hwTextures, 0)
        var fallbackCount = 0
        for (i in hw.indices) {
            configureTexture(layer.hwTextures[i])
            val image = try {
                WebPYUVDecoder.eglImageCreate(hw[i])
            } catch (t: Throwable) {
                0L
            }
            layer.hwImages[i] = image
            if (image != 0L) {
                WebPYUVDecoder.eglImageTargetTexture2D(image)
            } else {
                fallbackCount++
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    layer.frameWidth, layer.frameHeight, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
                )
                runCatching {
                    WebPYUVDecoder.uploadHardwareBufferToTexture(hw[i], layer.frameWidth, layer.frameHeight)
                }
            }
        }
        if (fallbackCount > 0) {
            WebpLog.w(TAG, "rebuildLayerHardwareTextures: resId=${layer.config.resId} $fallbackCount/${hw.size} 帧 EGLImage 失败，已一次性上传兜底")
        }
    }

    private fun applyAnim(layer: LayerState, anim: WebPAnimResult) {
        layer.releaseNativeFrames()
        val hw = anim.hardwareFrames
        val arr = anim.frames
        if (hw == null && arr == null) return
        val count = hw?.size ?: arr!!.size
        layer.hardwareFrames  = hw
        layer.frames          = if (hw == null) arr else null
        layer.frameWidth      = anim.canvasWidth
        layer.frameHeight     = anim.canvasHeight
        layer.currentFrameIndex = 0
        layer.lastFrameTimeMs   = SystemClock.uptimeMillis()
        layer.loopCount         = anim.loopCount
        layer.completedLoops    = 0
        layer.playbackFinished  = false
        layer.framesTrimmed     = false

        val fps = layer.effectiveFps()
        layer.frameDurations = if (fps > 0) {
            val fixed = (1000f / fps).toInt().coerceAtLeast(1)
            IntArray(count) { fixed }
        } else {
            anim.durations
        }

        layer.textureAllocated = false
        layer.computeMvp(viewWidth, viewHeight)

        if (program == 0) return

        if (hw != null) {
            // 零拷贝路径：一次性建好全部 texture/EGLImage
            rebuildLayerHardwareTextures(layer)
            return
        }

        // 软件路径：ensure texture is allocated and first frame uploaded
        if (layer.texId[0] == 0) {
            GLES20.glGenTextures(1, layer.texId, 0)
            configureTexture(layer.texId[0])
        }
        allocTexture(layer)
        if (arr!!.isNotEmpty()) {
            safeRewind(arr[0])
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, layer.texId[0])
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0,
                layer.frameWidth, layer.frameHeight,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, arr[0]
            )
        }
    }

    private fun allocTexture(layer: LayerState) {
        if (layer.textureAllocated || layer.frameWidth == 0 || layer.frameHeight == 0) return
        if (layer.texId[0] == 0) {
            GLES20.glGenTextures(1, layer.texId, 0)
            configureTexture(layer.texId[0])
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, layer.texId[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            layer.frameWidth, layer.frameHeight, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        layer.textureAllocated = true
    }

    private fun configureTexture(texId: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun uploadFrame(layer: LayerState) {
        val frames = layer.frames ?: return
        if (!layer.textureAllocated) allocTexture(layer)
        val buf = frames[layer.currentFrameIndex.coerceIn(0, frames.size - 1)]
        safeRewind(buf)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, layer.texId[0])
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0,
            layer.frameWidth, layer.frameHeight,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
        )
    }

    private fun recomputeAllMvp() {
        layers.forEach { it.computeMvp(viewWidth, viewHeight) }
    }

    fun releaseAll() {
        cancelScheduledFrame()
        frameHandler.removeCallbacksAndMessages(null)
        playing = false
        layers.forEach { layer ->
            layer.releaseNativeFrames()
            layer.replacePendingAnim(null)
            if (layer.texId[0] != 0) {
                try { GLES20.glDeleteTextures(1, layer.texId, 0) } catch (_: Throwable) {}
                layer.texId[0] = 0
            }
        }
        if (spriteTexIds.isNotEmpty()) {
            try { GLES20.glDeleteTextures(spriteTexIds.size, spriteTexIds, 0) } catch (_: Throwable) {}
            spriteTexIds = IntArray(0)
        }
        spriteInstances = emptyList()
        if (program != 0) {
            try { GLES20.glDeleteProgram(program) } catch (_: Throwable) {}
            program = 0
        }
    }

    // ---------------------------------------------------------------------------
    // Shader helpers
    // ---------------------------------------------------------------------------

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compileShader(GLES20.GL_VERTEX_SHADER,   vs)
        val f = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val msg = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw RuntimeException("Link failed: $msg")
        }
        return p
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val msg = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("Compile failed ($type): $msg")
        }
        return s
    }
}

// ---------------------------------------------------------------------------
// Extension helpers
// ---------------------------------------------------------------------------

private fun safeRewind(buf: ByteBuffer) {
    try { buf.position(0) } catch (_: Throwable) {
        try { buf.rewind()  } catch (_: Throwable) {}
    }
}

private fun LayerState.releaseNativeFrames() {
    frames?.let { try { WebPYUVDecoder.releaseNativeBuffers(it) } catch (_: Throwable) {} }
    frames = null
    releaseHardwareGl()
    hardwareFrames?.forEach { hb ->
        try {
            if (!hb.isClosed) hb.close()
        } catch (_: Throwable) {
        }
    }
    hardwareFrames = null
    textureAllocated = false
}

/** 删除图层硬件帧的 GL 资源（texture + EGLImage），不动 AHardwareBuffer 本体。GL 线程调用。 */
private fun LayerState.releaseHardwareGl() {
    if (hwTextures.isNotEmpty()) {
        try { GLES20.glDeleteTextures(hwTextures.size, hwTextures, 0) } catch (_: Throwable) {}
        hwTextures = IntArray(0)
    }
    hwImages.forEach { image ->
        if (image != 0L) {
            try { WebPYUVDecoder.eglImageDestroy(image) } catch (_: Throwable) {}
        }
    }
    hwImages = LongArray(0)
}

/**
 * 替换 pendingAnim 并释放被覆盖的旧值——未消费的 pendingAnim 持有 native buffer，
 * 覆盖前不释放就泄漏一份完整动画。
 */
private fun LayerState.replacePendingAnim(anim: WebPAnimResult?) {
    val old = pendingAnim
    pendingAnim = anim
    if (old != null && old !== anim) {
        old.releaseNative()
    }
}

// ---------------------------------------------------------------------------
// MultiWebpGLView
// ---------------------------------------------------------------------------

/**
 * A [GLSurfaceView] that renders multiple WebP animations on a single GL surface.
 *
 * Usage:
 * ```kotlin
 * view.setLayers(
 *     listOf(
 *         WebpLayer(R.raw.anim_a, x=0f,   y=0f,   width=200f, height=200f, fps=24),
 *         WebpLayer(R.raw.anim_b, x=210f, y=0f,   width=100f, height=100f, fps=12),
 *     )
 * )
 * view.start()
 * ```
 *
 * Call [start] / [stop] to control playback. Lifecycle is managed automatically
 * when the view is inside a [LifecycleOwner].
 *
 * **Do not use inside RecyclerView** — same caveat as [WebpGLView].
 */
open class MultiWebpGLView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    private val TAG = "MultiWebpGLView"

    private val renderer = MultiRenderer { if (!isDestroyed) requestRender() }
    private val deviceProfile = WebpDeviceProfile.current()
    private val decodeDispatcher = deviceProfile.decodeDispatcher()
    private var scopeJob = SupervisorJob()
    private var scope = CoroutineScope(scopeJob + decodeDispatcher)

    @Volatile private var isDestroyed = false
    @Volatile private var layersGeneration = 0L
    @Volatile private var layerStates: List<LayerState> = emptyList()

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onPause(owner: LifecycleOwner) {
            stop()
            this@MultiWebpGLView.onPause()
        }
        override fun onResume(owner: LifecycleOwner) {
            this@MultiWebpGLView.onResume()
            start()
        }
    }

    init {
        WebpLog.d(TAG, "deviceProfile=${deviceProfile.name}, decodeParallelism=${deviceProfile.decodeParallelism}, cacheBudget=${deviceProfile.cacheBudgetBytes}")
        setEGLContextClientVersion(2)
        // 不申请 depth/stencil：纯 2D 合成用不到，省一块 surface 内存
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setRenderer(renderer)
        preserveEGLContextOnPause = true
        renderMode = RENDERMODE_WHEN_DIRTY
        setZOrderOnTop(true)
    }

    /**
     * 是否向系统声明播放帧率（API 30+ Surface.setFrameRate，取各层显式 fps 最大值）。
     *
     * **默认关闭**：支持渲染帧率切换的设备会按本 surface 的低帧率投票把**整个屏幕**
     * 的渲染率往下拖（实测 Adreno610 设备 60Hz 面板被压到 30/15fps 渲染），页面上
     * 其它 UI 跟着卡。只有 webp 动画独占整屏、且希望省电时才应打开。
     */
    @Volatile
    var frameRateHintEnabled: Boolean = false

    /** 显式全局帧率投票，0 = 未设置。见 [setGlobalFrameRate]。 */
    @Volatile
    private var globalFrameRateVote: Int = 0

    /**
     * 主动向系统投一张帧率票（API 30+ Surface.setFrameRate），用于**有意**影响整机
     * 渲染帧率以省电。范围强制夹在 30-60：uid 级 frameRateOverride 会把同应用所有
     * UI 的 vsync 一起降到投票值附近，低于 30 时页面操作会有明显卡顿感，故不放开。
     *
     * 优先级高于 [frameRateHintEnabled] 的播放帧率提示，且不随 start/stop 变化；
     * surface 重建后会自动重新声明。传 fps <= 0 清除投票，恢复系统默认调度。
     */
    fun setGlobalFrameRate(fps: Int) {
        globalFrameRateVote = if (fps <= 0) 0 else fps.coerceIn(30, 60)
        WebpLog.i(TAG, "setGlobalFrameRate: $fps -> vote=$globalFrameRateVote")
        setSurfaceFrameRate(globalFrameRateVote)
    }

    private fun applyFrameRateHint(playingNow: Boolean) {
        // 显式全局投票优先，播放状态变化不得覆盖它
        if (globalFrameRateVote > 0) {
            setSurfaceFrameRate(globalFrameRateVote)
            return
        }
        if (!frameRateHintEnabled) return
        val maxFps = if (playingNow) {
            layerStates.maxOfOrNull { it.config.fps.coerceAtLeast(0) } ?: 0
        } else {
            0
        }
        setSurfaceFrameRate(maxFps)
    }

    private fun setSurfaceFrameRate(fps: Int) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        try {
            val surface = holder.surface ?: return
            if (!surface.isValid) return
            surface.setFrameRate(
                if (fps > 0) fps.toFloat() else 0f,
                android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
            )
        } catch (t: Throwable) {
            WebpLog.w(TAG, "setFrameRate failed: ${t.message}")
        }
    }

    override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, w: Int, h: Int) {
        super.surfaceChanged(holder, format, w, h)
        // surface 重建后重新声明帧率偏好（全局投票优先）
        if (globalFrameRateVote > 0) {
            setSurfaceFrameRate(globalFrameRateVote)
        } else if (renderer.playing) {
            applyFrameRateHint(true)
        }
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Set (or replace) the list of WebP layers to render.
     * Layers are drawn in order — index 0 is at the bottom.
     *
     * This call triggers async decoding for each layer and is safe to call
     * before or after the surface is created.
     */
    fun setLayers(layers: List<WebpLayer>) {
        if (isDestroyed) return
        val generation = ++layersGeneration
        scopeJob.cancelChildren()

        // Build LayerState objects immediately (lightweight)
        val states = layers.map { LayerState(it) }
        layerStates = states

        // Push empty layer list to GL thread so old textures are released
        queueEvent { renderer.setLayers(states) }

        // Kick off async decode for each layer
        states.forEach { state ->
            loadLayerAsync(state, generation)
        }
    }

    /**
     * Toggle one layer visibility by index in [setLayers] order.
     * Hidden layers are not drawn and do not advance animation frames.
     */
    fun setLayerVisible(index: Int, visible: Boolean) {
        if (isDestroyed) return
        val state = layerStates.getOrNull(index) ?: return
        state.isVisible = visible
        queueEvent { renderer.setLayerVisible(index, visible) }
        requestRender()
    }

    fun showLayer(index: Int) = setLayerVisible(index, true)

    fun hideLayer(index: Int) = setLayerVisible(index, false)

    /**
     * Move one layer (by index in [setLayers] order) to a new top-left position in
     * **view pixels**, without re-decoding. Useful to keep an overlay glued to another
     * view whose position changes at runtime.
     */
    fun setLayerPosition(index: Int, x: Float, y: Float) {
        if (isDestroyed) return
        val state = layerStates.getOrNull(index) ?: return
        state.posX = x
        state.posY = y
        queueEvent { renderer.setLayerPosition(index, x, y) }
        requestRender()
    }

    /**
     * Register the bitmap set used by dynamic sprites (see [SpriteInstance.texIndex]).
     * Upload happens on the GL thread; keep the bitmaps alive (they are re-uploaded if the
     * GL context is recreated). Pass an empty list to drop the sprite textures.
     */
    fun setSpriteBitmaps(bitmaps: List<Bitmap>) {
        if (isDestroyed) return
        queueEvent { renderer.applySpriteBitmaps(bitmaps) }
        requestRender()
    }

    /**
     * Replace the dynamic sprites drawn above all WebP layers and render one frame.
     * Cheap enough to call every animation frame from the main thread — the actual draw
     * runs on the GL thread, so this does **not** invalidate the View hierarchy. Pass an
     * empty list to clear all sprites.
     */
    fun updateSprites(instances: List<SpriteInstance>) {
        if (isDestroyed) return
        renderer.setSpriteInstances(instances)
        requestRender()
    }

    /** The original [WebpLayer] config for [index] in [setLayers] order, or null. */
    fun getLayerConfig(index: Int): WebpLayer? = layerStates.getOrNull(index)?.config

    /**
     * The on-screen pixel rect (view coordinates, top-left origin) a layer occupies,
     * matching exactly how the GL surface stretches it. Falls back to the decoded
     * frame size when the layer config left width/height at 0. Returns null when the
     * layer is unknown or its size isn't known yet (not decoded / not laid out).
     */
    fun getLayerDisplayRect(index: Int): RectF? {
        val state = layerStates.getOrNull(index) ?: return null
        val c = state.config
        val w = if (c.width > 0f) c.width else state.frameWidth.toFloat()
        val h = if (c.height > 0f) c.height else state.frameHeight.toFloat()
        if (w <= 0f || h <= 0f) return null
        return RectF(state.posX, state.posY, state.posX + w, state.posY + h)
    }

    /** Start (or resume) playback of all layers. */
    fun start() {
        if (isDestroyed) return
        // 有限循环图层播完后帧内存已回收：重播需重新加载（缓存命中时只是引用计数+1）。
        // 新数据未到前 GL 侧继续显示停留的末帧，不会闪黑。
        layerStates.forEach { state ->
            if (state.playbackFinished && state.framesTrimmed) {
                WebpLog.d(TAG, "start: 图层 resId=${state.config.resId} 已播完且回收，重新加载重播")
                loadLayerAsync(state, layersGeneration)
            }
        }
        renderer.setCustomPlaying(true)
        queueEvent { renderer.setCustomPlaying(true) }
        applyFrameRateHint(true)
        requestRender()
    }

    /** Pause playback of all layers (last rendered frame stays visible). */
    fun stop() {
        renderer.setCustomPlaying(false)
        queueEvent { renderer.setCustomPlaying(false) }
        applyFrameRateHint(false)
    }

    /** Whether playback is currently running. */
    fun isPlaying(): Boolean = renderer.playing

    /**
     * One-shot: invoked on the main thread after the next completed draw.
     * Used by [MultiWebpGLViewContainer] to know when the recreated surface
     * has real content and the snapshot overlay can be removed.
     */
    fun doOnNextFrameDrawn(action: () -> Unit) {
        if (isDestroyed) return
        renderer.onNextFrameDrawn = action
        requestRender()
    }

    // ---------------------------------------------------------------------------
    // Internal
    // ---------------------------------------------------------------------------

    private fun loadLayerAsync(state: LayerState, generation: Long) {
        val cfg = state.config
        scope.launch {
            if (isDestroyed || generation != layersGeneration) return@launch
            val decodeSize = cfg.decodeSize ?: inferDecodeSize(cfg)
            WebpLog.d(TAG, "loadLayerAsync start: resId=${cfg.resId}, size=$decodeSize")
            val anim = WebPAnimResultManager.getWebPAnimResult(cfg.resId, decodeSize)
            if (!isActive || isDestroyed || generation != layersGeneration) {
                releaseAnimResult(anim)
                return@launch
            }
            if (anim == null) {
                WebpLog.e(TAG, "loadLayerAsync: decode returned null for resId=${cfg.resId}")
                return@launch
            }
            WebpLog.d(TAG, "loadLayerAsync done: resId=${cfg.resId}, frames=${anim.frames?.size}")
            // Deliver to GL thread（覆盖前先释放未消费的旧 pendingAnim）
            state.replacePendingAnim(anim)
            queueEvent {
                if (!isDestroyed && generation == layersGeneration) {
                    renderer.applyPendingAnim(state)
                } else {
                    val staleAnim = state.pendingAnim
                    state.pendingAnim = null
                    releaseAnimResult(staleAnim)
                }
            }
            requestRender()
        }
    }

    private fun recreateScopeIfNeeded() {
        if (scopeJob.isCancelled) {
            scopeJob = SupervisorJob()
            scope = CoroutineScope(scopeJob + decodeDispatcher)
        }
    }

    private fun inferDecodeSize(layer: WebpLayer): Size? {
        val width = layer.width.takeIf { it > 0f }?.roundToInt() ?: return null
        val height = layer.height.takeIf { it > 0f }?.roundToInt() ?: return null
        return Size(width.coerceAtLeast(1), height.coerceAtLeast(1))
    }

    private fun releaseAnimResult(anim: WebPAnimResult?) {
        anim.releaseNative()
    }

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isDestroyed = false
        recreateScopeIfNeeded()
        post {
            findViewTreeLifecycleOwner()?.lifecycle?.addObserver(lifecycleObserver)
        }
    }

    override fun onDetachedFromWindow() {
        isDestroyed = true
        layersGeneration++
        stop()
        scope.cancel()
        findViewTreeLifecycleOwner()?.lifecycle?.removeObserver(lifecycleObserver)
        queueEvent {
            try { renderer.releaseAll() } catch (t: Throwable) {
                WebpLog.e(TAG, "releaseAll failed: ${t.message}")
            }
        }
        try { requestRender() } catch (_: Exception) {}
        super.onDetachedFromWindow()
    }
}
