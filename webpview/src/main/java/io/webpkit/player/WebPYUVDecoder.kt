package io.webpkit.player

import android.content.Context
import android.util.Size
import java.nio.ByteBuffer

object WebPYUVDecoder {

    private const val TAG = "WebPYUVDecoder"

    /**
     * 原生库是否加载成功。当以 aar 形式集成却没有把 libwebpkit.so 打进包里时，
     * 这里会是 false —— 这是「看不到 webp」最常见的原因，务必先看这条日志。
     */
    @JvmStatic
    @Volatile
    var nativeLoaded: Boolean = false
        private set

    init {
        nativeLoaded = try {
            System.loadLibrary("webpkit")
            WebpLog.i(TAG, "System.loadLibrary(\"webpkit\") 成功，原生解码可用")
            true
        } catch (t: Throwable) {
            WebpLog.e(
                TAG,
                "System.loadLibrary(\"webpkit\") 失败！aar 中可能缺少 libwebpkit.so（检查 abi/jniLibs 是否打包）: ${t.message}",
                t
            )
            false
        }
    }

    // JNI: 原有方法，解码为原始尺寸
    external fun decodeAllFrames(data: ByteArray): WebPAnimResult?

    // JNI: 新增方法，解码为指定尺寸（targetWidth/targetHeight > 0 时缩放）
    external fun decodeAllFramesWithSize(data: ByteArray, targetWidth: Int, targetHeight: Int): WebPAnimResult?

    // JNI: releaseNativeBuffers(frames: Array<ByteBuffer?>) -> Int (返回释放的KB数)
    // frees malloc'ed pointers backing NewDirectByteBuffer and returns freed memory in KB
    external fun releaseNativeBuffers(frames: Array<ByteBuffer>?): Int

    // JNI: cloneNativeBuffers(frames) -> deep-copied native-backed direct ByteBuffers
    external fun cloneNativeBuffers(frames: Array<ByteBuffer>?): Array<ByteBuffer>?


    fun decodeRawWebPToAnim(context: Context, resId: Int, targetSize: Size? = null): WebPAnimResult? {
        if (!nativeLoaded) {
            WebpLog.e(TAG, "decodeRawWebPToAnim 跳过：原生库未加载，无法解码 resId=$resId")
            return null
        }

        val bytes = try {
            context.resources.openRawResource(resId).use { it.readBytes() }
        } catch (t: Throwable) {
            WebpLog.e(TAG, "读取 raw 资源失败 resId=$resId: ${t.message}", t)
            return null
        }
        WebpLog.d(TAG, "decodeRawWebPToAnim: resId=$resId, bytes=${bytes.size}, targetSize=$targetSize")
        if (bytes.isEmpty()) {
            WebpLog.e(TAG, "decodeRawWebPToAnim: raw 资源为空 resId=$resId")
            return null
        }

        val result = try {
            if (targetSize != null && targetSize.width > 0 && targetSize.height > 0) {
                // 使用指定尺寸解码
                decodeAllFramesWithSize(bytes, targetSize.width, targetSize.height)?.also {
                    // 将 targetSize 记录到结果中
                    return WebPAnimResult(it.frames, it.canvasWidth, it.canvasHeight, it.durations)
                }
            } else {
                // 使用原始尺寸解码
                decodeAllFrames(bytes)
            }
        } catch (t: Throwable) {
            WebpLog.e(TAG, "decode failed: ${t.message}", t)
            null
        }

        if (result == null) {
            WebpLog.e(TAG, "decode returned null (resId=$resId)")
            return null
        }
        val frameCount = result.frames?.size ?: 0
        if (frameCount == 0) {
            WebpLog.w(TAG, "decode 成功但 0 帧 (resId=$resId, canvas=${result.canvasWidth}x${result.canvasHeight}) —— 将不会显示任何内容")
        }
        WebpLog.d(TAG, "Decoded anim: $frameCount frames, canvas=${result.canvasWidth}x${result.canvasHeight}, targetSize=$targetSize")
        return result
    }
}
