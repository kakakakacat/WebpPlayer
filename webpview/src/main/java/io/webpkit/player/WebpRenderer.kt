package io.webpkit.player

import android.hardware.HardwareBuffer
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Size
import android.view.Choreographer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.min

class WebpRenderer(
    private val requestRender: () -> Unit
) : GLSurfaceView.Renderer {
    private val TAG = "WebpRenderer"

    // vertex coords (x,y) and tex coords (s,t)
    private val vertexCoords = floatArrayOf(
        -1f, 1f,   // left top
        -1f, -1f,  // left bottom
        1f, 1f,    // right top
        1f, -1f    // right bottom
    )

    private val texCoords = floatArrayOf(
        0f, 0f,
        0f, 1f,
        1f, 0f,
        1f, 1f
    )

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(vertexCoords.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(vertexCoords); position(0)
        }

    private val texBuffer: FloatBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(texCoords); position(0)
        }

    // simple RGBA shader
    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        uniform mat4 uMVP;
        void main() {
            gl_Position = uMVP * aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
       precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D tex;
        void main() {
            gl_FragColor = texture2D(tex, vTexCoord);
        }
    """.trimIndent()

    private var program = 0
    private var aPosLoc = 0
    private var aTexLoc = 0
    private var uMvpLoc = 0
    private var texSamplerLoc = 0

    // single texture for RGBA pipeline (software/ByteBuffer mode)
    private val texIds = IntArray(1)
    private var textureAllocated = false

    // ===== 零拷贝模式（AHardwareBuffer + EGLImage）=====
    // 每帧一个 texture + EGLImage，加载时建好；播放时只换 glBindTexture，无任何上传
    private var hardwareFrames: Array<HardwareBuffer>? = null
    private var hwTextures = IntArray(0)
    private var hwImages = LongArray(0)
    private val hwActive: Boolean get() = hardwareFrames != null

    private var viewWidth = 0
    private var viewHeight = 0

    // animated frames
    @Volatile
    private var frames: Array<ByteBuffer>? = null
    private var frameDurations: IntArray = intArrayOf()
    private var frameWidth = 0
    private var frameHeight = 0
    private var currentFrameIndex = 0
    // 帧调度一律用 uptimeMillis：墙钟（currentTimeMillis）被校时/改时间会让动画卡死或狂奔
    private var lastFrameTimeMs = SystemClock.uptimeMillis()

    // 渲染目标尺寸（用于控制实际显示大小），默认与帧尺寸一致
    private var renderWidth = 0
    private var renderHeight = 0
    private var renderGravity = android.view.Gravity.CENTER
    private var renderTranslateX = 0f
    private var renderTranslateY = 0f

    // fps 覆盖（>0 表示用固定时长 1000/fps）
    private var fpsOverride = 0

    // ===== 有限循环（loop_count > 0 的 webp 播 N 次后停止）=====
    private var loopCount = 0          // 0 = 无限循环
    private var completedLoops = 0
    /** 播放完成（停在末帧，不再调度） */
    @Volatile
    private var playbackFinished = false
    /** 播完后已回收除末帧外的 native 内存；重播必须重新 setFromAnimResult */
    @Volatile
    private var framesTrimmed = false

    // 新增：在 GL 未就绪时缓存 anim，onSurfaceCreated 时应用
    @Volatile
    private var pendingAnim: WebPAnimResult? = null
    private var pendingRenderSize: Size? = null
    private var pendingFps: Int = 0

    // MVP for scaling
    private val mvp = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    @Volatile
    private var playing = false  // 播放状态标志

    // 诊断日志辅助：首帧只打一次，无帧告警按秒节流
    private var firstFrameLogged = false
    private var lastNoFrameLogMs = 0L

    // ========== Foreground 相关 ==========
    private val foregroundTexIds = IntArray(1)
    private var foregroundTextureAllocated = false
    private var foregroundBitmap: android.graphics.Bitmap? = null
    private var foregroundWidth = 0
    private var foregroundHeight = 0
    private var foregroundGravity = android.view.Gravity.CENTER
    private var foregroundScale = 1.0f
    private var foregroundTranslateX = 0f
    private var foregroundTranslateY = 0f
    @Volatile
    private var foregroundDirty = false  // 标记需要上传新的 foreground 纹理
    private val foregroundMvp = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    /**
     * 设置 foreground Bitmap（在主线程调用，会在 GL 线程上传纹理）
     */
    fun setForegroundBitmap(
        bitmap: android.graphics.Bitmap?,
        gravity: Int = android.view.Gravity.CENTER,
        scale: Float = 1.0f,
        translateX: Float = 0f,
        translateY: Float = 0f
    ) {
        foregroundBitmap?.recycle()
        foregroundBitmap = null
        if (bitmap == null || bitmap.isRecycled) {
            foregroundDirty = true
            return
        }
        // bitmap ownership is transferred by caller
        foregroundBitmap = bitmap
        foregroundWidth = bitmap.width
        foregroundHeight = bitmap.height
        foregroundGravity = gravity
        foregroundScale = scale
        foregroundTranslateX = translateX
        foregroundTranslateY = translateY
        foregroundDirty = true
        requestRender()
    }

    /**
     * 清除 foreground
     */
    fun clearForeground() {
        foregroundBitmap?.recycle()
        foregroundBitmap = null
        foregroundDirty = true
        requestRender()
    }

    fun start() {
        playing = true
        cancelScheduledFrame()
        lastFrameTimeMs = SystemClock.uptimeMillis()
        requestRender()
    }

    /**
     * 播放完成且帧内存已回收时为 true。此时 renderer 自己没有数据可重播，
     * 调用方（如 WebpGLView.start）需要重新走一次 setFromAnimResult（缓存命中时
     * 只是引用计数 +1，代价很低）。
     */
    fun needsReloadForReplay(): Boolean = playbackFinished && framesTrimmed

    /**
     * 在 GL 线程调用：播放已完成且帧数据仍完整（未回收）时从头重播。
     * 普通暂停->恢复不受影响（playbackFinished 为 false 时这里是 no-op）。
     * 帧 buffer 解码后只读，所以直接复位索引重新上传不存在脏数据问题。
     */
    fun restartIfFinished() {
        if (!playbackFinished || framesTrimmed) return
        completedLoops = 0
        playbackFinished = false
        currentFrameIndex = 0
        lastFrameTimeMs = SystemClock.uptimeMillis()
        // 硬件模式绑定发生在 onDrawFrame，软件模式需要重新上传第 0 帧
        if (!hwActive && frames?.isNotEmpty() == true && program != 0) {
            uploadCurrentFrameRGBA()
        }
        requestRender()
    }

    /** 播放完成：停在当前（末）帧，回收其余帧的 native 内存。 */
    private fun finishPlayback() {
        playbackFinished = true

        // 硬件模式：保留当前帧的 buffer/texture/EGLImage，释放其余
        hardwareFrames?.let { hw ->
            if (hw.size <= 1) return
            val keepIdx = currentFrameIndex.coerceIn(0, hw.size - 1)
            for (i in hw.indices) {
                if (i == keepIdx) continue
                runCatching { if (!hw[i].isClosed) hw[i].close() }
                if (hwImages.getOrElse(i) { 0L } != 0L) {
                    runCatching { WebPYUVDecoder.eglImageDestroy(hwImages[i]) }
                }
            }
            if (hwTextures.size == hw.size) {
                val toDelete = IntArray(hw.size - 1)
                var j = 0
                for (i in hwTextures.indices) if (i != keepIdx) toDelete[j++] = hwTextures[i]
                runCatching { GLES20.glDeleteTextures(toDelete.size, toDelete, 0) }
                hwTextures = intArrayOf(hwTextures[keepIdx])
            }
            hwImages = longArrayOf(hwImages.getOrElse(keepIdx) { 0L })
            hardwareFrames = arrayOf(hw[keepIdx])
            currentFrameIndex = 0
            framesTrimmed = true
            WebpLog.i(TAG, "finishPlayback: 播放完成(loop=$loopCount)，回收 ${hw.size - 1} 个硬件帧")
            return
        }

        val f = frames ?: return
        if (f.size <= 1) return
        val keepIdx = currentFrameIndex.coerceIn(0, f.size - 1)
        val keep = f[keepIdx]
        // 只保留正在显示的末帧（surface 重建时还要靠它重新上传纹理），其余全部释放。
        // 释放走 native 引用计数，缓存若还持有同一块内存则只是计数 -1，不会悬空。
        val rest = Array(f.size - 1) { i -> f[if (i < keepIdx) i else i + 1] }
        try {
            val freedKB = WebPYUVDecoder.releaseNativeBuffers(rest)
            WebpLog.i(TAG, "finishPlayback: 播放完成(loop=$loopCount)，回收 ${f.size - 1} 帧，freed=$freedKB KB")
        } catch (t: Throwable) {
            WebpLog.e(TAG, "finishPlayback: release failed: ${t.message}")
        }
        // 丢弃旧数组：被释放帧的 ByteBuffer 不再可达，杜绝后续误用已释放内存
        frames = arrayOf(keep)
        currentFrameIndex = 0
        framesTrimmed = true
    }

    fun stop() {
        playing = false
        cancelScheduledFrame()
    }

    fun setContentLayout(
        gravity: Int = android.view.Gravity.CENTER,
        translateX: Float = 0f,
        translateY: Float = 0f
    ) {
        renderGravity = gravity
        renderTranslateX = translateX
        renderTranslateY = translateY
        computeMvp()
    }

    // 新增重载：从 WebPAnimResult 设置帧并可指定 targetSize/fps（必须在 GL 线程或 queueEvent 中调用）
    fun setFromAnimResult(anim: WebPAnimResult, targetSize: Size? = null, fps: Int = 0) {
        WebpLog.d(TAG, "setFromAnimResult: frames=${anim.frameCount}(hw=${anim.hardwareFrames != null}), size=${anim.canvasWidth}x${anim.canvasHeight}, targetSize=$targetSize, fps=$fps")

        // store overrides
        pendingRenderSize = targetSize
        pendingFps = fps

        if (program != 0) {
            try {
                val freedKB = releaseFrames()
                if (freedKB > 0) {
                    WebpLog.d(TAG, "setFromAnimResult: released old frames, freed $freedKB KB")
                }
                applyAnimResult(anim)
                // 直接应用成功后，残留的旧 pendingAnim 不会再被消费，必须释放
                replacePendingAnim(null)
            } catch (t: Throwable) {
                WebpLog.e(TAG, "setFromAnimResult apply failed: ${t.message}")
                // 保留到 pending 以便 onSurfaceCreated 时重试
                replacePendingAnim(anim)
            }
        } else {
            val freedKB = releaseFrames()
            if (freedKB > 0) {
                WebpLog.d(TAG, "setFromAnimResult: released old frames (no program), freed $freedKB KB")
            }
            replacePendingAnim(anim)
        }
    }

    /**
     * 替换 pendingAnim。被覆盖的旧 pendingAnim 不会有任何人消费，必须在这里释放它持有的
     * native buffer，否则 GL 未就绪期间连续 set 两次就泄漏一份完整动画。
     */
    private fun replacePendingAnim(anim: WebPAnimResult?) {
        val old = pendingAnim
        pendingAnim = anim
        if (old != null && old !== anim) {
            old.releaseNative()
            WebpLog.d(TAG, "replacePendingAnim: released stale pendingAnim")
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glEnable(GLES20.GL_BLEND)
        // 使用预乘 alpha 的 blend function
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // Ensure pixel unpack alignment is 1 to avoid driver doing slow-path copies
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)

        program = createProgram(vertexShaderCode, fragmentShaderCode)
        aPosLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uMvpLoc = GLES20.glGetUniformLocation(program, "uMVP")
        texSamplerLoc = GLES20.glGetUniformLocation(program, "tex")
        if (program == 0) {
            WebpLog.e(TAG, "onSurfaceCreated: 着色器程序创建失败，GL 无法渲染 —— 屏幕将为空白")
        } else {
            WebpLog.i(TAG, "onSurfaceCreated: GL 就绪 program=$program, hasPendingAnim=${pendingAnim != null}")
        }

        // 主纹理
        GLES20.glGenTextures(1, texIds, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // Foreground 纹理
        GLES20.glGenTextures(1, foregroundTexIds, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, foregroundTexIds[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        foregroundTextureAllocated = false
        // context 重建后 foreground 纹理同样失效：若 bitmap 仍在（CPU 侧引用不随 context 丢失），
        // 标记 dirty 以便下一帧重新上传，否则 drawForeground 会因 !foregroundTextureAllocated
        // 提前 return —— 表现为 webp 恢复后 foreground 消失，像是被动画盖住了
        if (foregroundBitmap?.isRecycled == false) {
            foregroundDirty = true
        }

        // context 重建后旧纹理全部失效：复位软件纹理状态并恢复已有内容
        textureAllocated = false
        if (hwActive) {
            // 硬件帧仍在（AHardwareBuffer 不依赖 GL context），重建 texture + EGLImage 绑定
            rebuildHardwareTextures()
        } else if (frames?.isNotEmpty() == true) {
            ensureTextureAllocated()
            uploadCurrentFrameRGBA()
        }

        // apply pending anim if any
        pendingAnim?.let {
            try {
                // propagate pending overrides
                if (pendingRenderSize != null) {
                    renderWidth = pendingRenderSize!!.width
                    renderHeight = pendingRenderSize!!.height
                }
                fpsOverride = pendingFps
                applyAnimResult(it)
            } catch (t: Throwable) {
                WebpLog.e(TAG, "apply pending anim failed: ${t.message}")
            } finally {
                pendingAnim = null
                pendingRenderSize = null
                pendingFps = 0
            }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width; viewHeight = height
        GLES20.glViewport(0, 0, width, height)
        computeMvp()
        if (foregroundBitmap?.isRecycled == false) {
            computeForegroundMvp()
        }
    }

    private fun computeMvp() {
        // keep aspect ratio of frame inside view
        if (frameWidth == 0 || frameHeight == 0 || viewWidth == 0 || viewHeight == 0) {
            Matrix.setIdentityM(mvp, 0)
            return
        }
        val vw = viewWidth.toFloat()
        val vh = viewHeight.toFloat()
        val fw = if (renderWidth > 0) renderWidth.toFloat() else frameWidth.toFloat()
        val fh = if (renderHeight > 0) renderHeight.toFloat() else frameHeight.toFloat()
        val scale = min(vw / fw, vh / fh)
        val sx = fw * scale / vw
        val sy = fh * scale / vh
        val horizontalGravity = renderGravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK
        val verticalGravity = renderGravity and android.view.Gravity.VERTICAL_GRAVITY_MASK
        var tx = when (horizontalGravity) {
            android.view.Gravity.LEFT -> -1f + sx
            android.view.Gravity.RIGHT -> 1f - sx
            else -> 0f
        }
        var ty = when (verticalGravity) {
            android.view.Gravity.TOP -> 1f - sy
            android.view.Gravity.BOTTOM -> -1f + sy
            else -> 0f
        }
        tx += renderTranslateX / vw * 2f
        ty -= renderTranslateY / vh * 2f
        Matrix.setIdentityM(mvp, 0)
        Matrix.translateM(mvp, 0, tx, ty, 0f)
        Matrix.scaleM(mvp, 0, sx, sy, 1f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val count = currentFrameCount()
        if (count == 0) {
            // 节流打印：避免 60fps 刷屏，但又能在「看不到 webp」时确认渲染线程是否拿到了帧
            val now = SystemClock.uptimeMillis()
            if (now - lastNoFrameLogMs > 1000) {
                lastNoFrameLogMs = now
                WebpLog.w(TAG, "onDrawFrame: 没有可渲染的帧 (playing=$playing) —— 屏幕为空白")
            }
            return
        }

        if (!firstFrameLogged) {
            firstFrameLogged = true
            WebpLog.i(TAG, "onDrawFrame: 首帧绘制 frames=$count(hw=$hwActive), frame=${frameWidth}x${frameHeight}, view=${viewWidth}x${viewHeight}, playing=$playing")
        }

        // 单帧（静态 webp）没有动画可推进；播放完成的有限循环动画停在末帧。
        // 两种情况都画完即止，不再定时唤醒重复上传同一帧
        if (!playing || count <= 1 || playbackFinished) {
            bindCurrentTexture()
            drawQuad()
            drawForeground()
            return
        }

        val now = SystemClock.uptimeMillis()
        val dur = if (fpsOverride > 0) 1000L / fpsOverride else frameDurations.getOrElse(currentFrameIndex) { 100 }.toLong()
        val elapsed = now - lastFrameTimeMs

        if (elapsed >= dur) {
            val atLastFrame = currentFrameIndex == count - 1
            if (atLastFrame && loopCount > 0 && completedLoops + 1 >= loopCount) {
                // 有限循环播放完毕：停在末帧并回收其余帧内存
                completedLoops = loopCount
                finishPlayback()
                bindCurrentTexture()
                drawQuad()
                drawForeground()
                return
            }
            if (atLastFrame) completedLoops++
            currentFrameIndex = (currentFrameIndex + 1) % count
            if (fpsOverride > 0) {
                // 固定 fps：推进时刻锚定到绝对时间网格（uptime 对 dur 取模）。
                // 相同 fps 的所有动画——包括不同 GLSurfaceView——会在同一个 vsync
                // 推进/提交，SurfaceFlinger 合成次数随之合并（错相位时多个 18fps
                // surface 会让 SF 每秒合成 30-40 次，对齐后回到 18 次）
                lastFrameTimeMs = now - (now % dur)
            } else {
                // 可变帧时长：累加而非重置为 now，避免每帧丢余量整体变慢；
                // 落后超一帧（如长时间暂停后）直接对齐，避免追帧狂奔
                lastFrameTimeMs += dur
                if (now - lastFrameTimeMs >= dur) {
                    lastFrameTimeMs = now
                }
            }
            // 软件模式上传新帧；硬件模式帧已常驻 GPU，绘制前换绑定即可
            if (!hwActive) {
                uploadCurrentFrameRGBA()
            }
        }

        bindCurrentTexture()
        drawQuad()
        drawForeground()

        // 计算下一帧延迟并按需请求渲染，避免 GL 线程 sleep
        val nextDelay = (dur - (SystemClock.uptimeMillis() - lastFrameTimeMs)).coerceAtLeast(1)
        scheduleNextFrame(nextDelay)
    }

    private fun currentFrameCount(): Int = hardwareFrames?.size ?: frames?.size ?: 0

    /** 绘制前把当前帧对应的纹理绑到 TEXTURE0（两种模式统一入口） */
    private fun bindCurrentTexture() {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        if (hwActive) {
            val idx = currentFrameIndex.coerceIn(0, hwTextures.size - 1)
            if (idx >= 0 && hwTextures.isNotEmpty()) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hwTextures[idx])
            }
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
        }
    }

    /**
     * 为硬件帧重建 GL 侧资源（GL 线程）：每帧一个 texture，绑定到其 AHardwareBuffer
     * 的 EGLImage。EGLImage 创建失败时退化为「一次性 CPU 上传到该 texture」——
     * 之后播放同样是零上传，只是加载时多了一轮拷贝。
     */
    private fun rebuildHardwareTextures() {
        releaseHardwareTextures()
        val hw = hardwareFrames ?: return
        if (program == 0 || hw.isEmpty()) return

        hwTextures = IntArray(hw.size)
        hwImages = LongArray(hw.size)
        GLES20.glGenTextures(hw.size, hwTextures, 0)
        var fallbackCount = 0
        for (i in hw.indices) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hwTextures[i])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val image = try {
                WebPYUVDecoder.eglImageCreate(hw[i])
            } catch (t: Throwable) {
                0L
            }
            hwImages[i] = image
            if (image != 0L) {
                WebPYUVDecoder.eglImageTargetTexture2D(image)
            } else {
                fallbackCount++
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    frameWidth, frameHeight, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
                )
                try {
                    WebPYUVDecoder.uploadHardwareBufferToTexture(hw[i], frameWidth, frameHeight)
                } catch (t: Throwable) {
                    WebpLog.e(TAG, "rebuildHardwareTextures: fallback upload failed: ${t.message}")
                }
            }
        }
        if (fallbackCount > 0) {
            WebpLog.w(TAG, "rebuildHardwareTextures: $fallbackCount/${hw.size} 帧 EGLImage 创建失败，已用一次性上传兜底")
        }
    }

    /** 删除硬件帧的 GL 资源（texture + EGLImage），不动 AHardwareBuffer 本体 */
    private fun releaseHardwareTextures() {
        if (hwTextures.isNotEmpty()) {
            try {
                GLES20.glDeleteTextures(hwTextures.size, hwTextures, 0)
            } catch (_: Throwable) {
            }
            hwTextures = IntArray(0)
        }
        for (image in hwImages) {
            if (image != 0L) {
                try {
                    WebPYUVDecoder.eglImageDestroy(image)
                } catch (_: Throwable) {
                }
            }
        }
        hwImages = LongArray(0)
    }

    private fun ensureTextureAllocated() {
        if (textureAllocated || frameWidth == 0 || frameHeight == 0) return
        // allocate texture storage once with null pixels to avoid black on first render
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            frameWidth, frameHeight, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        textureAllocated = true
    }

    private fun uploadCurrentFrameRGBA() {
        val f = frames ?: return
        val idx = currentFrameIndex.coerceIn(0, f.size - 1)
        val buf = f[idx]
        ensureTextureAllocated()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
        // Ensure buffer position is at start to avoid reading wrong offset / extra copying
        try {
            buf.position(0)
        } catch (t: Throwable) {
            // fallback to rewind if position setter not supported for some implementations
            try { buf.rewind() } catch (_: Throwable) {}
        }
        // use TexSubImage2D to avoid reallocating texture each frame
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0,
            frameWidth, frameHeight,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
        )
    }

    private fun drawQuad() {
        GLES20.glUseProgram(program)

        GLES20.glEnableVertexAttribArray(aPosLoc)
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(aTexLoc)
        GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 0, texBuffer)

        GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvp, 0)

        GLES20.glUniform1i(texSamplerLoc, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosLoc)
        GLES20.glDisableVertexAttribArray(aTexLoc)
    }

    /**
     * 绘制 foreground（在主内容之上）
     */
    private fun drawForeground() {
        val bitmap = foregroundBitmap ?: return
        if (bitmap.isRecycled) return

        // 上传纹理（如果需要）
        if (foregroundDirty) {
            uploadForegroundTexture(bitmap)
            computeForegroundMvp()
            foregroundDirty = false
        }

        if (!foregroundTextureAllocated) return

        // 绘制 foreground
        GLES20.glUseProgram(program)

        GLES20.glEnableVertexAttribArray(aPosLoc)
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(aTexLoc)
        GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 0, texBuffer)

        // 使用 foreground 的 MVP
        GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, foregroundMvp, 0)

        // 绑定 foreground 纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, foregroundTexIds[0])
        GLES20.glUniform1i(texSamplerLoc, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosLoc)
        GLES20.glDisableVertexAttribArray(aTexLoc)

        // 恢复绑定主纹理，避免影响下一帧
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
    }

    /**
     * 上传 foreground bitmap 到纹理
     */
    private fun uploadForegroundTexture(bitmap: android.graphics.Bitmap) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, foregroundTexIds[0])

        // 使用 GLUtils 上传 bitmap
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        foregroundTextureAllocated = true
    }

    /**
     * 计算 foreground 的 MVP 矩阵（根据 gravity, scale, translate）
     */
    private fun computeForegroundMvp() {
        if (viewWidth == 0 || viewHeight == 0 || foregroundWidth == 0 || foregroundHeight == 0) {
            Matrix.setIdentityM(foregroundMvp, 0)
            return
        }

        val vw = viewWidth.toFloat()
        val vh = viewHeight.toFloat()
        val fw = foregroundWidth * foregroundScale
        val fh = foregroundHeight * foregroundScale

        // 归一化尺寸（相对于 view）
        val sx = fw / vw
        val sy = fh / vh

        // 根据 gravity 计算位置偏移（归一化坐标 -1 到 1）
        var tx = 0f
        var ty = 0f

        val horizontalGravity = foregroundGravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK
        val verticalGravity = foregroundGravity and android.view.Gravity.VERTICAL_GRAVITY_MASK

        // 水平位置
        tx = when (horizontalGravity) {
            android.view.Gravity.LEFT -> -1f + sx
            android.view.Gravity.RIGHT -> 1f - sx
            else -> 0f // CENTER_HORIZONTAL 或默认
        }

        // 垂直位置
        ty = when (verticalGravity) {
            android.view.Gravity.TOP -> 1f - sy
            android.view.Gravity.BOTTOM -> -1f + sy
            else -> 0f // CENTER_VERTICAL 或默认
        }

        // 加上用户指定的偏移（像素转归一化）
        tx += foregroundTranslateX / vw * 2f
        ty -= foregroundTranslateY / vh * 2f  // Y 轴方向相反

        Matrix.setIdentityM(foregroundMvp, 0)
        Matrix.translateM(foregroundMvp, 0, tx, ty, 0f)
        Matrix.scaleM(foregroundMvp, 0, sx, sy, 1f)
    }

    // 将原本在 setAnimatedFrames 中对 GL 的操作提取到这里（必须在 GL 线程并且在 GL 已就绪时调用）
    private fun applyAnimResult(anim: WebPAnimResult) {
        val hw = anim.hardwareFrames
        val arr = anim.frames
        if (hw == null && arr == null) {
            WebpLog.w(TAG, "applyAnimResult: anim 无帧数据，无内容可渲染")
            return
        }
        val count = hw?.size ?: arr!!.size
        if (count == 0) {
            WebpLog.w(TAG, "applyAnimResult: 0 帧，无内容可渲染")
        }
        this.hardwareFrames = hw
        this.frames = if (hw == null) arr else null
        this.frameWidth = anim.canvasWidth
        this.frameHeight = anim.canvasHeight
        // apply fps override if set
        if (fpsOverride > 0) {
            val fixed = (1000f / fpsOverride).toInt().coerceAtLeast(1)
            this.frameDurations = IntArray(count) { fixed }
        } else {
            this.frameDurations = anim.durations
        }
        // apply render size override if present; otherwise default to frame size
        if (pendingRenderSize != null) {
            renderWidth = pendingRenderSize!!.width
            renderHeight = pendingRenderSize!!.height
            // clear pending after consumption
            pendingRenderSize = null
        } else if (renderWidth == 0 || renderHeight == 0) {
            renderWidth = frameWidth
            renderHeight = frameHeight
        }

        this.currentFrameIndex = 0
        this.lastFrameTimeMs = SystemClock.uptimeMillis()
        this.loopCount = anim.loopCount
        this.completedLoops = 0
        this.playbackFinished = false
        this.framesTrimmed = false
        firstFrameLogged = false
        textureAllocated = false // ensure allocation occurs below

        if (hw != null) {
            // 零拷贝路径：一次性建好全部 texture/EGLImage，之后播放零上传
            rebuildHardwareTextures()
        } else {
            // 软件路径：allocate texture storage and upload first frame immediately
            ensureTextureAllocated()
            if (arr!!.isNotEmpty()) {
                val first = arr[0]
                try { first.position(0) } catch (t: Throwable) { try { first.rewind() } catch (_: Throwable) {} }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
                GLES20.glTexSubImage2D(
                    GLES20.GL_TEXTURE_2D, 0, 0, 0,
                    frameWidth, frameHeight,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, first
                )
            }
        }
        computeMvp()
        WebpLog.i(TAG, "applyAnimResult 完成: frames=$count(hw=${hw != null}), frame=${frameWidth}x${frameHeight}, render=${renderWidth}x${renderHeight}, view=${viewWidth}x${viewHeight}, fpsOverride=$fpsOverride")
        // 不再需要手动请求渲染，RENDERMODE_CONTINUOUSLY 会自动渲染
    }

    // 修改原有 setAnimatedFrames：如果 GL 未就绪则只保存数据（不会调用 GLES）
    fun setAnimatedFrames(framesArr: Array<ByteBuffer?>, width: Int, height: Int, durations: IntArray?) {
        // release old frames before replacing to avoid leaks / use-after-free
        releaseFrames()

        // 如果 GL 还没初始化（program == 0），则只保存为 pendingAnim-like 数据
        if (program == 0) {
            val nonNull = Array(framesArr.size) { i -> framesArr[i] ?: ByteBuffer.allocateDirect(0) }
            this.frames = nonNull
            this.frameWidth = width
            this.frameHeight = height
            this.frameDurations = durations ?: IntArray(nonNull.size) { 100 }
            this.currentFrameIndex = 0
            this.lastFrameTimeMs = SystemClock.uptimeMillis()
            resetLoopStateForRawFrames()
            computeMvp()
            // 不执行 GLES 上传，等待 onSurfaceCreated 时统一上传
            return
        }

        // GL 已就绪，直接应用（与 applyAnimResult 行为一致）
        val nonNull = Array(framesArr.size) { i -> framesArr[i] ?: ByteBuffer.allocateDirect(0) }
        this.frames = nonNull
        this.frameWidth = width
        this.frameHeight = height
        if (fpsOverride > 0) {
            val fixed = (1000f / fpsOverride).toInt().coerceAtLeast(1)
            this.frameDurations = IntArray(nonNull.size) { fixed }
        } else {
            this.frameDurations = durations ?: IntArray(nonNull.size) { 100 }
        }
        if (renderWidth == 0 || renderHeight == 0) {
            renderWidth = width
            renderHeight = height
        }
        this.currentFrameIndex = 0
        this.lastFrameTimeMs = SystemClock.uptimeMillis()
        resetLoopStateForRawFrames()
        ensureTextureAllocated()
        if (nonNull.isNotEmpty()) {
            val first = nonNull[0]
            try { first.position(0) } catch (t: Throwable) { try { first.rewind() } catch (_: Throwable) {} }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0,
                frameWidth, frameHeight,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, first
            )
        }
        computeMvp()
        // 不再需要手动请求渲染，RENDERMODE_CONTINUOUSLY 会自动渲染
    }

    /** setAnimatedFrames 直传的裸帧数组没有 loop 信息，保持旧行为：无限循环 */
    private fun resetLoopStateForRawFrames() {
        loopCount = 0
        completedLoops = 0
        playbackFinished = false
        framesTrimmed = false
    }

    /**
     * 在 GL 线程调用：释放当前持有的 direct ByteBuffer 对应的 native backing（通过 JNI）
     * 并清理本地引用，避免重复释放。这个方法会 swallow 异常以保证在清理阶段不会抛出。
     * @return 释放的内存大小（KB），失败返回0
     */
    fun releaseFrames(): Int {
        // 硬件帧：先删 GL 资源（texture/EGLImage），再 close buffer（AHB 引用计数）
        if (hardwareFrames != null) {
            releaseHardwareTextures()
            hardwareFrames?.forEach { hb ->
                try {
                    if (!hb.isClosed) hb.close()
                } catch (_: Throwable) {
                }
            }
            hardwareFrames = null
        }

        val f = frames ?: return 0
        var freedKB = 0
        try {
            // 调用 JNI 释放 malloc'ed backing（如果 JNI 实现存在）
            freedKB = WebPYUVDecoder.releaseNativeBuffers(f)
            WebpLog.d(TAG, "releaseFrames: freed $freedKB KB")
        } catch (t: Throwable) {
            WebpLog.e(TAG, "releaseNativeBuffers failed: ${t.message}")
        } finally {
            frames = null
        }
        return freedKB
    }

    /**
     * 在 GL 线程调用：释放 GL 相关资源（texture / program）以及 frame buffers
     */
    fun releaseAndCleanup(): Int {
        cancelScheduledFrame()
        var freedKB = 0
        try {
            // stop rendering
            playing = false
            // release frame native memory
            freedKB = releaseFrames()
            WebpLog.d(TAG, "releaseAndCleanup: released $freedKB KB native memory")

            // delete main texture
            try {
                GLES20.glDeleteTextures(1, texIds, 0)
            } catch (t: Throwable) {
                WebpLog.e(TAG, "glDeleteTextures failed: ${t.message}")
            }
            textureAllocated = false

            // delete foreground texture
            try {
                GLES20.glDeleteTextures(1, foregroundTexIds, 0)
            } catch (t: Throwable) {
                WebpLog.e(TAG, "glDeleteTextures (foreground) failed: ${t.message}")
            }
            foregroundTextureAllocated = false

            // recycle foreground bitmap
            foregroundBitmap?.recycle()
            foregroundBitmap = null

            // delete program
            try {
                if (program != 0) {
                    GLES20.glDeleteProgram(program)
                }
            } catch (t: Throwable) {
                WebpLog.e(TAG, "glDeleteProgram failed: ${t.message}")
            } finally {
                program = 0
            }

            // reset render overrides
            renderWidth = 0
            renderHeight = 0
            fpsOverride = 0
        } catch (t: Throwable) {
            WebpLog.e(TAG, "releaseAndCleanup failed: ${t.message}")
        }
        return freedKB
    }


    // --- utility: compile / link shaders ---
    private fun loadShader(type: Int, code: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, code)
        GLES20.glCompileShader(sh)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val info = GLES20.glGetShaderInfoLog(sh)
            WebpLog.e(TAG, "Could not compile shader: $info")
            GLES20.glDeleteShader(sh)
            return 0
        }
        return sh
    }

    private fun createProgram(vs: String, fs: String): Int {
        val v = loadShader(GLES20.GL_VERTEX_SHADER, vs)
        val f = loadShader(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val info = GLES20.glGetProgramInfoLog(p)
            WebpLog.e(TAG, "Could not link program: $info")
            GLES20.glDeleteProgram(p)
            return 0
        }
        return p
    }

    private val frameHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Choreographer 调度：帧 tick 落在 vsync 上（postFrameCallbackDelayed 在延迟到期后的
    // 下一个 vsync 触发），避免 postDelayed 落在帧中间导致的无效唤醒/迟到一帧。
    // renderer 在主线程构造，getInstance 绑定主线程 looper；拿不到时回退 Handler。
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
            frameHandler.postDelayed({ if (playing) requestRender() }, delayMs)
        }
    }

    private fun cancelScheduledFrame() {
        choreographer?.removeFrameCallback(frameCallback)
        frameHandler.removeCallbacksAndMessages(null)
    }
}
