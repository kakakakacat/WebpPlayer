package io.webpkit.player

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.HandlerThread
import android.view.TextureView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGL10

internal interface RenderThreadHost {
    fun queueEvent(task: () -> Unit)
    fun requestRender()
    fun release()
}

internal typealias RenderHostFactory = (TextureView, GLSurfaceView.Renderer) -> RenderThreadHost

internal class TextureRenderHost(
    private val view: TextureView,
    private val renderer: GLSurfaceView.Renderer,
) : RenderThreadHost, TextureView.SurfaceTextureListener {

    private val tag = "TextureRenderHost"
    private val thread = HandlerThread("WebpTextureRender-${nextHostId()}").apply { start() }
    private val handler = Handler(thread.looper)
    private val released = AtomicBoolean(false)
    private val renderQueued = AtomicBoolean(false)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var width = 0
    private var height = 0

    init {
        view.surfaceTextureListener = this
        if (view.isAvailable) {
            view.surfaceTexture?.let {
                onSurfaceTextureAvailable(it, view.width, view.height)
            }
        }
    }

    override fun queueEvent(task: () -> Unit) {
        if (released.get()) return
        handler.post {
            if (!released.get()) {
                task()
            }
        }
    }

    override fun requestRender() {
        if (released.get()) return
        if (!renderQueued.compareAndSet(false, true)) return
        handler.post {
            renderQueued.set(false)
            if (released.get()) return@post
            if (!isReadyToDraw()) return@post
            try {
                makeCurrent()
                renderer.onDrawFrame(null)
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            } catch (t: Throwable) {
                WebpLog.e(tag, "draw failed: ${t.message}")
            }
        }
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        val latch = CountDownLatch(1)
        handler.post {
            try {
                destroyEgl()
            } finally {
                view.post {
                    if (view.surfaceTextureListener === this@TextureRenderHost) {
                        view.surfaceTextureListener = null
                    }
                }
                latch.countDown()
                thread.quitSafely()
            }
        }
        latch.await()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (released.get()) return
        handler.post {
            if (released.get()) return@post
            this.surfaceTexture = surface
            this.width = width
            this.height = height
            createEglIfNeeded()
            createWindowSurface()
            makeCurrent()
            renderer.onSurfaceCreated(null, null)
            renderer.onSurfaceChanged(null, width, height)
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (released.get()) return
        handler.post {
            if (released.get()) return@post
            this.width = width
            this.height = height
            if (!isReadyToDraw()) return@post
            makeCurrent()
            renderer.onSurfaceChanged(null, width, height)
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (!released.get()) {
            handler.post {
                destroyEgl()
            }
        }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun isReadyToDraw(): Boolean {
        return eglDisplay !== EGL14.EGL_NO_DISPLAY &&
            eglContext !== EGL14.EGL_NO_CONTEXT &&
            eglSurface !== EGL14.EGL_NO_SURFACE &&
            width > 0 && height > 0
    }

    private fun createEglIfNeeded() {
        if (eglDisplay !== EGL14.EGL_NO_DISPLAY && eglContext !== EGL14.EGL_NO_CONTEXT) return

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay !== EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)) {
            "eglChooseConfig failed"
        }
        eglConfig = configs[0]
        checkNotNull(eglConfig) { "No EGL config" }

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE,
        )
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            eglConfig,
            EGL14.EGL_NO_CONTEXT,
            contextAttribs,
            0,
        )
        check(eglContext !== EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
    }

    private fun createWindowSurface() {
        destroySurfaceOnly()
        val texture = surfaceTexture ?: return
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            eglConfig,
            texture,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        check(eglSurface !== EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
    }

    private fun makeCurrent() {
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "eglMakeCurrent failed: 0x${Integer.toHexString(EGL14.eglGetError())}"
        }
        // Ensure the viewport is valid even when the first render request arrives
        // before the renderer's own surface-changed path runs.
        if (width > 0 && height > 0) {
            GLES20.glViewport(0, 0, width, height)
        }
    }

    private fun destroyEgl() {
        destroySurfaceOnly()
        if (eglContext !== EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
        eglConfig = null
        surfaceTexture = null
        width = 0
        height = 0
    }

    private fun destroySurfaceOnly() {
        if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
        }
        if (eglSurface !== EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private companion object {
        private var hostId = 0

        @Synchronized
        fun nextHostId(): Int {
            hostId += 1
            return hostId
        }
    }
}
