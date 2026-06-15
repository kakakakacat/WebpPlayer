package io.webpkit.player

import android.graphics.Bitmap
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WebpRendererTest {

    @Test
    fun setForegroundBitmap_requestsRender() {
        var renderRequests = 0
        val renderer = WebpRenderer { renderRequests++ }

        renderer.setForegroundBitmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))

        assertEquals(1, renderRequests)
    }

    @Test
    fun clearForeground_requestsRender() {
        var renderRequests = 0
        val renderer = WebpRenderer { renderRequests++ }

        renderer.clearForeground()

        assertEquals(1, renderRequests)
    }

    @Test
    fun webpGlViewSetForegroundBitmap_requestsRenderImmediately() {
        val view = object : WebpGLView(RuntimeEnvironment.getApplication() as Context) {
            var renderRequests = 0

            override fun requestRender() {
                renderRequests++
            }

            override fun queueEvent(r: Runnable?) {
                // Ignore GL-thread work; this test only verifies the wake-up signal.
            }
        }

        view.setForegroundBitmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))

        assertEquals(1, view.renderRequests)
    }
}
