package io.webpkit.player

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.opengl.GLSurfaceView
import androidx.annotation.RawRes
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

open class WebpTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs) {

    companion object {
        private const val TAG = "WebpTextureView"
        private var totalInstances = 0
        private var activeInstances = 0
        private val monitorHandler = Handler(Looper.getMainLooper())

        private val monitorRunnable = object : Runnable {
            override fun run() {
                if (activeInstances > 0) {
                    WebpLog.i(TAG, "[WebpTextureMonitor] Active: $activeInstances/$totalInstances")
                }
                monitorHandler.postDelayed(this, 30_000)
            }
        }

        init {
            monitorHandler.postDelayed(monitorRunnable, 30_000)
        }
    }

    @Volatile
    private var isDestroyed = false
    private var hostReleased = false

    private lateinit var renderHost: RenderThreadHost
    private val renderer = WebpRenderer { if (!isDestroyed) renderHost.requestRender() }
    private val deviceProfile = WebpDeviceProfile.current()
    private var scopeJob = SupervisorJob()
    private var scope = CoroutineScope(scopeJob + Dispatchers.Main.immediate)

    @RawRes
    private var resId: Int? = null
    private var lastSize: Size? = null
    private var lastFps: Int = 20
    private var instanceId = 0
    private var startTime = 0L
    private var isAnimating = false

    private val lifecycleObserver by lazy {
        object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) {
                stop()
            }

            override fun onResume(owner: LifecycleOwner) {
                start()
            }
        }
    }

    @Volatile
    var frameRateHintEnabled: Boolean = false

    @Volatile
    private var globalFrameRateVote: Int = 0

    init {
        isOpaque = false
        instanceId = ++totalInstances
        renderHost = createRenderHost(renderer)
        hostReleased = false
    }

    internal open fun createRenderHost(renderer: GLSurfaceView.Renderer): RenderThreadHost {
        return TextureRenderHost(this, renderer)
    }

    private fun recreateScopeIfNeeded() {
        if (scopeJob.isCancelled) {
            scopeJob = SupervisorJob()
            scope = CoroutineScope(scopeJob + Dispatchers.Main.immediate)
        }
    }

    fun setGlobalFrameRate(fps: Int) {
        globalFrameRateVote = if (fps <= 0) 0 else fps.coerceIn(30, 60)
        setSurfaceFrameRate(globalFrameRateVote)
    }

    private fun applyFrameRateHint(fps: Int) {
        if (globalFrameRateVote > 0) {
            setSurfaceFrameRate(globalFrameRateVote)
            return
        }
        if (!frameRateHintEnabled) return
        setSurfaceFrameRate(fps)
    }

    private fun setSurfaceFrameRate(fps: Int) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        val texture = surfaceTexture ?: return
        val surface = Surface(texture)
        try {
            surface.setFrameRate(
                if (fps > 0) fps.toFloat() else 0f,
                android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
            )
        } catch (t: Throwable) {
            WebpLog.w(TAG, "[WebpTextureView#$instanceId] setFrameRate failed: ${t.message}")
        } finally {
            surface.release()
        }
    }

    fun start() {
        if (!isAnimating) {
            isAnimating = true
            activeInstances++
            startTime = SystemClock.uptimeMillis()
        }
        applyFrameRateHint(lastFps)

        val replayRes = resId
        if (replayRes != null && renderer.needsReloadForReplay()) {
            reloadAndPlay(replayRes)
            return
        }

        renderHost.queueEvent {
            renderer.restartIfFinished()
            renderer.start()
        }
    }

    private fun reloadAndPlay(@RawRes res: Int) {
        scope.launch(deviceProfile.decodeDispatcher()) {
            if (isDestroyed) return@launch
            val anim = WebPAnimResultManager.getWebPAnimResult(res, lastSize)
            if (anim == null) return@launch
            renderHost.queueEvent {
                if (isDestroyed) {
                    anim.releaseNative()
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
        }
        applyFrameRateHint(0)
        renderHost.queueEvent { renderer.stop() }
    }

    fun setWebpFromRaw(@RawRes resId: Int, size: Size? = null, fps: Int = 20) {
        if (isDestroyed) return
        if (this.resId == resId) return
        this.resId = resId
        this.lastSize = size
        this.lastFps = fps

        scope.launch(deviceProfile.decodeDispatcher()) {
            if (isDestroyed) return@launch
            val anim = WebPAnimResultManager.getWebPAnimResult(resId, size)
            if (isDestroyed) return@launch
            renderHost.queueEvent {
                if (isDestroyed) return@queueEvent
                anim?.let { renderer.setFromAnimResult(it, size, fps) }
            }
        }
    }

    fun setForegroundBitmap(
        bitmap: Bitmap?,
        gravity: Int = android.view.Gravity.CENTER,
        scale: Float = 1.0f,
        translateX: Float = 0f,
        translateY: Float = 0f,
    ) {
        val snapshot = bitmap?.takeIf { !it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false)
        renderHost.queueEvent {
            renderer.setForegroundBitmap(snapshot, gravity, scale, translateX, translateY)
        }
        renderHost.requestRender()
    }

    fun clearForeground() {
        renderHost.queueEvent { renderer.clearForeground() }
        renderHost.requestRender()
    }

    fun setContentLayout(
        gravity: Int = android.view.Gravity.CENTER,
        translateX: Float = 0f,
        translateY: Float = 0f,
    ) {
        renderHost.queueEvent {
            renderer.setContentLayout(gravity, translateX, translateY)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (hostReleased) {
            renderHost = createRenderHost(renderer)
            hostReleased = false
        }
        isDestroyed = false
        recreateScopeIfNeeded()
        post {
            findViewTreeLifecycleOwner()?.lifecycle?.addObserver(lifecycleObserver)
        }
    }

    override fun onDetachedFromWindow() {
        if (isAnimating) {
            isAnimating = false
            activeInstances--
        }
        isDestroyed = true
        stop()
        scope.cancel()
        findViewTreeLifecycleOwner()?.lifecycle?.removeObserver(lifecycleObserver)
        resId = null
        renderHost.queueEvent {
            try {
                renderer.releaseAndCleanup()
            } catch (t: Throwable) {
                WebpLog.e(TAG, "[WebpTextureView#$instanceId] cleanup failed: ${t.message}")
            }
        }
        renderHost.release()
        hostReleased = true
        super.onDetachedFromWindow()
    }
}
