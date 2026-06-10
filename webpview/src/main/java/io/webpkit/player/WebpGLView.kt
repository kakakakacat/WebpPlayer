package io.webpkit.player

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Size
import androidx.annotation.RawRes
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch


/**
 * 在recyclerview等场景下,会有相同资源冲突和render生命周期冲突,资源回收等问题.
 * 导致渲染黑屏或者空白,所以不要在recyclerview等场景下使用此view
 */
open class WebpGLView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    companion object {
        private const val TAG = "WebpGLView"
        // 全局监测变量
        private var totalInstances = 0
        private var activeInstances = 0
        private val monitorHandler = Handler(Looper.getMainLooper())

        // 定期打印 WebpGLView 状态
        private val monitorRunnable = object : Runnable {
            override fun run() {
                if (activeInstances > 0) {
                    WebpLog.i(TAG, "📊 [WebpGLMonitor] Active: $activeInstances/$totalInstances")
                }
                monitorHandler.postDelayed(this, 30_000)
            }
        }

        init {
            monitorHandler.postDelayed(monitorRunnable, 30_000)
        }
    }

    private val renderer = WebpRenderer { if (!isDestroyed) requestRender() }
    private val deviceProfile = WebpDeviceProfile.current()
    private var scopeJob = SupervisorJob()
    private var scope = CoroutineScope(scopeJob + Dispatchers.Main.immediate)

    /** detach 时 scope 被 cancel，view 复用（重新 attach）时必须重建，否则后续 launch 静默不执行 */
    private fun recreateScopeIfNeeded() {
        if (scopeJob.isCancelled) {
            scopeJob = SupervisorJob()
            scope = CoroutineScope(scopeJob + Dispatchers.Main.immediate)
        }
    }

    private val lifecycleObserver by lazy {
        object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) {
                super.onPause(owner)
                WebpLog.d(TAG, "[WebpGLView#$instanceId] lifecycle onPause")
                stop()
                this@WebpGLView.onPause()
            }

            override fun onResume(owner: LifecycleOwner) {
                super.onResume(owner)
                WebpLog.d(TAG, "[WebpGLView#$instanceId] lifecycle onResume")
                this@WebpGLView.onResume()
                start()
            }
        }
    }
    @RawRes
    private var resId: Int? = null

    // 记住最近一次 setWebpFromRaw 的参数，播完回收后重播（start）时按原参数重新加载
    private var lastSize: Size? = null
    private var lastFps: Int = 20

    @Volatile
    private var isDestroyed = false  // 标记是否已销毁

    // 监测相关变量
    private var instanceId = 0
    private var startTime = 0L
    private var isAnimating = false


    init {
        instanceId = ++totalInstances
        WebpLog.d(TAG, "[WebpGLView#$instanceId] Created (total: $totalInstances)")
        WebpLog.d(TAG, "[WebpGLView#$instanceId] deviceProfile=${deviceProfile.name}, decodeParallelism=${deviceProfile.decodeParallelism}, cacheBudget=${deviceProfile.cacheBudgetBytes}")

        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setRenderer(renderer)
        preserveEGLContextOnPause = true
        // 按需渲染，由 renderer 定时 requestRender，避免 GL 线程空转阻塞
        renderMode = RENDERMODE_WHEN_DIRTY
        setZOrderOnTop(true)
    }

    @Volatile
    var debugRenderingEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) {
                WebpLog.w(TAG, "[WebpGLView#$instanceId] debugRenderingEnabled=false, suppress rendering")
                stop()
                queueEvent {
                    renderer.stop()
                    renderer.releaseFrames()
                }
            } else {
                WebpLog.i(TAG, "[WebpGLView#$instanceId] debugRenderingEnabled=true, rendering allowed")
            }
        }

    fun start() {
        if (!debugRenderingEnabled) {
            WebpLog.d(TAG, "[WebpGLView#$instanceId] start ignored, debugRenderingEnabled=false")
            return
        }

        if (!isAnimating) {
            isAnimating = true
            activeInstances++
            startTime = SystemClock.uptimeMillis()
            WebpLog.d(TAG, "[WebpGLView#$instanceId] Animation started (active: $activeInstances)")
        }

        // 有限循环动画播完后帧内存已回收：重播需要重新加载（缓存命中时只是引用计数+1，很便宜）
        val replayRes = resId
        if (replayRes != null && renderer.needsReloadForReplay()) {
            WebpLog.d(TAG, "[WebpGLView#$instanceId] start: 播放已完成且帧已回收，重新加载 resId=$replayRes 重播")
            reloadAndPlay(replayRes)
            return
        }

        queueEvent {
            // 播完但帧未回收（如单帧）时从头重播；普通暂停恢复不受影响（no-op）
            renderer.restartIfFinished()
            renderer.start()
        }
    }

    private fun reloadAndPlay(@RawRes res: Int) {
        scope.launch(deviceProfile.decodeDispatcher()) {
            if (isDestroyed) return@launch
            val anim = WebPAnimResultManager.getWebPAnimResult(res, lastSize)
            if (anim == null) {
                WebpLog.e(TAG, "[WebpGLView#$instanceId] reloadAndPlay: decode 返回 null resId=$res")
                return@launch
            }
            queueEvent {
                if (isDestroyed) {
                    anim.frames?.let { runCatching { WebPYUVDecoder.releaseNativeBuffers(it) } }
                    return@queueEvent
                }
                renderer.setFromAnimResult(anim, lastSize, lastFps)
                renderer.start()
            }
        }
    }

    fun stop() {
        if (isAnimating) {
            isAnimating = false
            activeInstances--
            val duration = SystemClock.uptimeMillis() - startTime
            WebpLog.d(TAG, "[WebpGLView#$instanceId] Animation stopped after ${duration}ms (active: $activeInstances)")
        }

        queueEvent { renderer.stop() }
    }

    /**
     * Load webp from raw resource asynchronously and play.
     */
    fun setWebpFromRaw(@RawRes resId: Int, size: Size? = null, fps: Int = 20) {
        if (!debugRenderingEnabled) {
            WebpLog.d(TAG, "[WebpGLView#$instanceId] setWebpFromRaw ignored, debugRenderingEnabled=false")
            return
        }

        if (isDestroyed) {
            WebpLog.w(TAG, "[WebpGLView#$instanceId] setWebpFromRaw ignored, view already destroyed")
            return
        }

        if (this.resId == resId) {
            // same resource, ignore
            WebpLog.d(TAG, "[WebpGLView#$instanceId] setWebpFromRaw ignored, same resId: $resId")
            return
        }
        val oldResId = this.resId
        this.resId = resId
        this.lastSize = size
        this.lastFps = fps
        WebpLog.d(TAG, "[WebpGLView#$instanceId] setWebpFromRaw: $oldResId -> $resId, size=$size, fps=$fps")

        scope.launch(deviceProfile.decodeDispatcher()) {
            // 检查是否在解码期间被销毁
            if (isDestroyed) {
                WebpLog.w(TAG, "[WebpGLView#$instanceId] setWebpFromRaw: view destroyed during decode, abort")
                return@launch
            }

            val startTime = System.currentTimeMillis()
            // 传入 size 参数给 Manager
            val anim = WebPAnimResultManager.getWebPAnimResult(resId, size)
            val decodeTime = System.currentTimeMillis() - startTime
            WebpLog.d(TAG, "[WebpGLView#$instanceId] setWebpFromRaw: decoded resId=$resId in ${decodeTime}ms, frames=${anim?.frames?.size}")

            // 再次检查是否被销毁
            if (isDestroyed) {
                WebpLog.w(TAG, "[WebpGLView#$instanceId] setWebpFromRaw: view destroyed after decode, abort GL set")
                return@launch
            }

            // pass to renderer on GL thread; forward size/fps so renderer can override render size and fps
            queueEvent {
                // GL线程中最后一次检查
                if (isDestroyed) {
                    WebpLog.w(TAG, "[WebpGLView#$instanceId] setWebpFromRaw: view destroyed in GL thread, abort")
                    return@queueEvent
                }

                anim?.let {
                    val setStartTime = System.currentTimeMillis()
                    renderer.setFromAnimResult(it, size, fps)
                    val setTime = System.currentTimeMillis() - setStartTime
                    WebpLog.d(TAG, "[WebpGLView#$instanceId] setWebpFromRaw: GL set complete in ${setTime}ms, total=${decodeTime + setTime}ms")
                }
            }
        }
    }


    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 重置销毁标志（view可能被重用）
        isDestroyed = false
        recreateScopeIfNeeded()
        WebpLog.d(TAG, "[WebpGLView#$instanceId] onAttachedToWindow: resId=$resId")

        if (!debugRenderingEnabled) {
            WebpLog.d(TAG, "[WebpGLView#$instanceId] debugRenderingEnabled=false, skip lifecycle observer")
            return
        }

        post {
            findViewTreeLifecycleOwner()?.lifecycle?.let {
                WebpLog.d(TAG, "[WebpGLView#$instanceId] add lifecycle observer")
                it.addObserver(lifecycleObserver)
            }
        }
    }

    /**
     * 设置 foreground Bitmap，会在 GL 中绘制在 webp 动画之上
     */
    fun setForegroundBitmap(
        bitmap: android.graphics.Bitmap?,
        gravity: Int = android.view.Gravity.CENTER,
        scale: Float = 1.0f,
        translateX: Float = 0f,
        translateY: Float = 0f
    ) {
        val snapshot = bitmap?.takeIf { !it.isRecycled }?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        queueEvent {
            renderer.setForegroundBitmap(snapshot, gravity, scale, translateX, translateY)
        }
    }

    fun setContentLayout(
        gravity: Int = android.view.Gravity.CENTER,
        translateX: Float = 0f,
        translateY: Float = 0f
    ) {
        queueEvent {
            renderer.setContentLayout(gravity, translateX, translateY)
        }
    }

    /**
     * 清除 foreground
     */
    fun clearForeground() {
        queueEvent {
            renderer.clearForeground()
        }
    }


    override fun onDetachedFromWindow() {
        WebpLog.d(TAG, "[WebpGLView#$instanceId] onDetachedFromWindow: resId=$resId, wasAnimating=$isAnimating")

        // 更新活跃计数
        if (isAnimating) {
            isAnimating = false
            activeInstances--
            WebpLog.d(TAG, "[WebpGLView#$instanceId] Detached while animating (active: $activeInstances)")
        }

        // 1. 立即标记为已销毁，防止新资源加载
        isDestroyed = true

        // 2. 停止渲染
        stop()

        // 3. 取消协程（停止正在进行的解码任务）
        scope.cancel()

        // 4. 移除生命周期观察者
        findViewTreeLifecycleOwner()?.lifecycle?.let {
            WebpLog.d(TAG, "[WebpGLView#$instanceId] remove lifecycle observer")
            it.removeObserver(lifecycleObserver)
        }

        // 5. 清空 resId
        resId = null

        // 6. 在 GL 线程执行清理（异步，不阻塞主线程）
        queueEvent {
            try {
                val freed = renderer.releaseAndCleanup()
                WebpLog.d(TAG, "[WebpGLView#$instanceId] onDetachedFromWindow: GL cleanup complete, freed=$freed KB")
            } catch (t: Throwable) {
                WebpLog.e(TAG, "[WebpGLView#$instanceId] onDetachedFromWindow: GL cleanup failed: ${t.message}")
            }
        }

        // 7. 强制触发一次渲染，让GL线程尽快处理上面的queueEvent
        try {
            requestRender()
        } catch (e: Exception) {
            WebpLog.w(TAG, "[WebpGLView#$instanceId] onDetachedFromWindow: requestRender failed (view may be destroyed): ${e.message}")
        }

        // 8. 调用父类方法
        super.onDetachedFromWindow()
    }
}
