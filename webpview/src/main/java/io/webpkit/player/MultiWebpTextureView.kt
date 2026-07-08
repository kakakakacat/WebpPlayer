package io.webpkit.player

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.opengl.GLSurfaceView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

open class MultiWebpTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs) {

    private val tag = "MultiWebpTextureView"
    private lateinit var renderHost: RenderThreadHost
    private val renderer = MultiRenderer { if (!isDestroyed) renderHost.requestRender() }
    private val deviceProfile = WebpDeviceProfile.current()
    private val decodeDispatcher = deviceProfile.decodeDispatcher()
    private var scopeJob = SupervisorJob()
    private var scope = CoroutineScope(scopeJob + decodeDispatcher)
    private var hostReleased = false

    @Volatile private var isDestroyed = false
    @Volatile private var layersGeneration = 0L
    @Volatile private var layerStates: List<LayerState> = emptyList()

    @Volatile
    var frameRateHintEnabled: Boolean = false

    @Volatile
    private var globalFrameRateVote: Int = 0

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onPause(owner: LifecycleOwner) {
            stop()
        }

        override fun onResume(owner: LifecycleOwner) {
            start()
        }
    }

    init {
        isOpaque = false
        renderHost = createRenderHost(renderer)
        hostReleased = false
    }

    internal open fun createRenderHost(renderer: GLSurfaceView.Renderer): RenderThreadHost {
        return TextureRenderHost(this, renderer)
    }

    fun setGlobalFrameRate(fps: Int) {
        globalFrameRateVote = if (fps <= 0) 0 else fps.coerceIn(30, 60)
        setSurfaceFrameRate(globalFrameRateVote)
    }

    private fun applyFrameRateHint(playingNow: Boolean) {
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
        val texture = surfaceTexture ?: return
        val surface = Surface(texture)
        try {
            surface.setFrameRate(
                if (fps > 0) fps.toFloat() else 0f,
                android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
            )
        } catch (t: Throwable) {
            WebpLog.w(tag, "setFrameRate failed: ${t.message}")
        } finally {
            surface.release()
        }
    }

    fun setLayers(layers: List<WebpLayer>) {
        if (isDestroyed) return
        val generation = ++layersGeneration
        scopeJob.cancelChildren()
        val states = layers.map { LayerState(it) }
        layerStates = states
        renderHost.queueEvent { renderer.setLayers(states) }
        states.forEach { state -> loadLayerAsync(state, generation) }
        renderHost.requestRender()
    }

    fun setLayerVisible(index: Int, visible: Boolean) {
        if (isDestroyed) return
        val state = layerStates.getOrNull(index) ?: return
        state.isVisible = visible
        renderHost.queueEvent { renderer.setLayerVisible(index, visible) }
        renderHost.requestRender()
    }

    fun showLayer(index: Int) = setLayerVisible(index, true)

    fun hideLayer(index: Int) = setLayerVisible(index, false)

    fun setLayerPosition(index: Int, x: Float, y: Float) {
        if (isDestroyed) return
        val state = layerStates.getOrNull(index) ?: return
        state.posX = x
        state.posY = y
        renderHost.queueEvent { renderer.setLayerPosition(index, x, y) }
        renderHost.requestRender()
    }

    fun setSpriteBitmaps(bitmaps: List<Bitmap>) {
        if (isDestroyed) return
        renderHost.queueEvent { renderer.applySpriteBitmaps(bitmaps) }
        renderHost.requestRender()
    }

    fun updateSprites(instances: List<SpriteInstance>) {
        if (isDestroyed) return
        renderer.setSpriteInstances(instances)
        renderHost.requestRender()
    }

    fun getLayerConfig(index: Int): WebpLayer? = layerStates.getOrNull(index)?.config

    fun getLayerDisplayRect(index: Int): android.graphics.RectF? {
        val state = layerStates.getOrNull(index) ?: return null
        val c = state.config
        val w = if (c.width > 0f) c.width else state.frameWidth.toFloat()
        val h = if (c.height > 0f) c.height else state.frameHeight.toFloat()
        if (w <= 0f || h <= 0f) return null
        return android.graphics.RectF(state.posX, state.posY, state.posX + w, state.posY + h)
    }

    fun start() {
        if (isDestroyed) return
        layerStates.forEach { state ->
            if (state.playbackFinished && state.framesTrimmed) {
                loadLayerAsync(state, layersGeneration)
            }
        }
        renderer.setCustomPlaying(true)
        renderHost.queueEvent { renderer.setCustomPlaying(true) }
        applyFrameRateHint(true)
        renderHost.requestRender()
    }

    fun stop() {
        renderer.setCustomPlaying(false)
        renderHost.queueEvent { renderer.setCustomPlaying(false) }
        applyFrameRateHint(false)
    }

    fun isPlaying(): Boolean = renderer.playing

    fun doOnNextFrameDrawn(action: () -> Unit) {
        if (isDestroyed) return
        renderer.onNextFrameDrawn = action
        renderHost.requestRender()
    }

    private fun loadLayerAsync(state: LayerState, generation: Long) {
        val cfg = state.config
        scope.launch {
            if (isDestroyed || generation != layersGeneration) return@launch
            val decodeSize = cfg.decodeSize ?: inferDecodeSize(cfg)
            val anim = WebPAnimResultManager.getWebPAnimResult(cfg.resId, decodeSize)
            if (!isActive || isDestroyed || generation != layersGeneration) {
                anim.releaseNative()
                return@launch
            }
            if (anim == null) return@launch
            state.replacePendingAnim(anim)
            renderHost.queueEvent {
                if (!isDestroyed && generation == layersGeneration) {
                    renderer.applyPendingAnim(state)
                } else {
                    val staleAnim = state.pendingAnim
                    state.pendingAnim = null
                    staleAnim.releaseNative()
                }
            }
            renderHost.requestRender()
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
        isDestroyed = true
        layersGeneration++
        stop()
        scope.cancel()
        findViewTreeLifecycleOwner()?.lifecycle?.removeObserver(lifecycleObserver)
        renderHost.queueEvent {
            try {
                renderer.releaseAll()
            } catch (t: Throwable) {
                WebpLog.e(tag, "releaseAll failed: ${t.message}")
            }
        }
        renderHost.release()
        hostReleased = true
        super.onDetachedFromWindow()
    }
}
