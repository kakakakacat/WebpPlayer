package io.webpkit.player

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Size
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

    // single texture for RGBA pipeline
    private val texIds = IntArray(1)
    private var textureAllocated = false

    private var viewWidth = 0
    private var viewHeight = 0

    // animated frames
    @Volatile
    private var frames: Array<ByteBuffer>? = null
    private var frameDurations: IntArray = intArrayOf()
    private var frameWidth = 0
    private var frameHeight = 0
    private var currentFrameIndex = 0
    private var lastFrameTimeMs = System.currentTimeMillis()

    // 渲染目标尺寸（用于控制实际显示大小），默认与帧尺寸一致
    private var renderWidth = 0
    private var renderHeight = 0
    private var renderGravity = android.view.Gravity.CENTER
    private var renderTranslateX = 0f
    private var renderTranslateY = 0f

    // fps 覆盖（>0 表示用固定时长 1000/fps）
    private var fpsOverride = 0

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
    }

    /**
     * 清除 foreground
     */
    fun clearForeground() {
        foregroundBitmap?.recycle()
        foregroundBitmap = null
        foregroundDirty = true
    }

    fun start() {
        playing = true
        frameHandler.removeCallbacksAndMessages(null)
        lastFrameTimeMs = System.currentTimeMillis()
        requestRender()
    }

    fun stop() {
        playing = false
        frameHandler.removeCallbacksAndMessages(null)
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
        WebpLog.d(TAG, "setFromAnimResult: frames=${anim.frames?.size}, size=${anim.canvasWidth}x${anim.canvasHeight}, targetSize=$targetSize, fps=$fps")

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
            } catch (t: Throwable) {
                WebpLog.e(TAG, "setFromAnimResult apply failed: ${t.message}")
                // 保留到 pending 以便 onSurfaceCreated 时重试
                pendingAnim = anim
            }
        } else {
            val freedKB = releaseFrames()
            if (freedKB > 0) {
                WebpLog.d(TAG, "setFromAnimResult: released old frames (no program), freed $freedKB KB")
            }
            pendingAnim = anim
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
        val f = frames
        if (f == null || f.isEmpty()) {
            // 节流打印：避免 60fps 刷屏，但又能在「看不到 webp」时确认渲染线程是否拿到了帧
            val now = System.currentTimeMillis()
            if (now - lastNoFrameLogMs > 1000) {
                lastNoFrameLogMs = now
                WebpLog.w(TAG, "onDrawFrame: 没有可渲染的帧 (frames=${if (f == null) "null" else "empty"}, playing=$playing) —— 屏幕为空白")
            }
            return
        }

        if (!firstFrameLogged) {
            firstFrameLogged = true
            WebpLog.i(TAG, "onDrawFrame: 首帧绘制 frames=${f.size}, frame=${frameWidth}x${frameHeight}, view=${viewWidth}x${viewHeight}, playing=$playing")
        }

        if (!playing) {
            drawQuad()
            drawForeground()
            return
        }

        val now = System.currentTimeMillis()
        val dur = if (fpsOverride > 0) 1000L / fpsOverride else frameDurations.getOrElse(currentFrameIndex) { 100 }.toLong()
        val elapsed = now - lastFrameTimeMs

        if (elapsed >= dur) {
            currentFrameIndex = (currentFrameIndex + 1) % f.size
            lastFrameTimeMs = now
            uploadCurrentFrameRGBA()
        }

        drawQuad()
        drawForeground()

        // 计算下一帧延迟并按需请求渲染，避免 GL 线程 sleep
        val nextDelay = (dur - (System.currentTimeMillis() - lastFrameTimeMs)).coerceAtLeast(1)
        frameHandler.postDelayed({ if (playing) requestRender() }, nextDelay)
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
        // copy frames array (keep direct buffers)
        val arr = anim.frames
        if (arr == null) {
            WebpLog.w(TAG, "applyAnimResult: anim.frames 为 null，无内容可渲染")
            return
        }
        if (arr.isEmpty()) {
            WebpLog.w(TAG, "applyAnimResult: 0 帧，无内容可渲染")
        }
        this.frames = arr
        this.frameWidth = anim.canvasWidth
        this.frameHeight = anim.canvasHeight
        // apply fps override if set
        if (fpsOverride > 0) {
            val fixed = (1000f / fpsOverride).toInt().coerceAtLeast(1)
            this.frameDurations = IntArray(arr.size) { fixed }
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
        this.lastFrameTimeMs = System.currentTimeMillis()
        firstFrameLogged = false
        textureAllocated = false // ensure allocation occurs below

        // allocate texture storage and upload first frame immediately to avoid black first frame
        ensureTextureAllocated()
        if (arr.isNotEmpty()) {
            val first = arr[0]
            try { first.position(0) } catch (t: Throwable) { try { first.rewind() } catch (_: Throwable) {} }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0,
                frameWidth, frameHeight,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, first
            )
        }
        computeMvp()
        WebpLog.i(TAG, "applyAnimResult 完成: frames=${arr.size}, frame=${frameWidth}x${frameHeight}, render=${renderWidth}x${renderHeight}, view=${viewWidth}x${viewHeight}, fpsOverride=$fpsOverride")
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
            this.lastFrameTimeMs = System.currentTimeMillis()
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
        this.lastFrameTimeMs = System.currentTimeMillis()
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

    /**
     * 在 GL 线程调用：释放当前持有的 direct ByteBuffer 对应的 native backing（通过 JNI）
     * 并清理本地引用，避免重复释放。这个方法会 swallow 异常以保证在清理阶段不会抛出。
     * @return 释放的内存大小（KB），失败返回0
     */
    fun releaseFrames(): Int {
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
        frameHandler.removeCallbacksAndMessages(null)
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
}
