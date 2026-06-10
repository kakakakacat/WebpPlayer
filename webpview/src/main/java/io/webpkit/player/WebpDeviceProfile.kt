package io.webpkit.player

import android.os.Build
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class WebpDeviceProfile(
    val name: String,
    val decodeParallelism: Int,
    val cacheBudgetBytes: Long,
    val maxCacheEntries: Int,
    /**
     * 单帧解码输出的像素数上限（w*h）。超限的素材会在 native 侧按比例强制降采样，
     * 保护低端机不被超大动画打爆内存。0 = 不限制。
     */
    val maxDecodePixels: Int = 0,
    /** 该机型是否启用 AHardwareBuffer 零拷贝路径（与全局开关 [WebPYUVDecoder.hardwareBuffersEnabled] 取与）。 */
    val hardwareBuffers: Boolean = true,
) {
    /**
     * 全局共享的解码 dispatcher：
     * - 按并行度缓存单例，保证全进程解码并发受同一个上限约束
     *   （`limitedParallelism` 每次调用都是独立限流器，之前形同虚设）；
     * - 专用线程并降到后台优先级：解码批量进行时不与 UI 线程抢核，
     *   吞吐几乎不变，启动期主线程更顺。
     */
    fun decodeDispatcher(): CoroutineDispatcher {
        val parallelism = decodeParallelism.coerceAtLeast(1)
        return sharedDecodeDispatchers.getOrPut(parallelism) {
            val counter = AtomicInteger(0)
            Executors.newFixedThreadPool(parallelism) { r ->
                Thread({
                    try {
                        android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_BACKGROUND
                        )
                    } catch (_: Throwable) {
                        // JVM 单测环境没有 android.os.Process，忽略
                    }
                    r.run()
                }, "WebpDecode-${counter.incrementAndGet()}").apply { isDaemon = true }
            }.asCoroutineDispatcher()
        }
    }

    companion object {
        private val sharedDecodeDispatchers = ConcurrentHashMap<Int, CoroutineDispatcher>()

        fun current(): WebpDeviceProfile {
            return resolve(
                model = Build.MODEL.orEmpty(),
                boardPlatform = Build.BOARD.orEmpty(),
                socModel = readBuildField("SOC_MODEL"),
                gpuRenderer = ""
            )
        }

        fun resolve(
            model: String,
            boardPlatform: String,
            socModel: String,
            gpuRenderer: String,
        ): WebpDeviceProfile {
            val normalizedModel = model.lowercase()
            val normalizedBoard = boardPlatform.lowercase()
            val normalizedSoc = socModel.lowercase()
            val normalizedGpu = gpuRenderer.lowercase()

            // 缓存预算说明：引用计数 clone 落地后，缓存里的那份就是唯一一份帧内存
            // （消费者只是 +1 引用），entries=1 的旧设定是深拷贝时代的遗产——
            // 多条目能显著提升命中率（一次命中省一次 ~300ms 的完整解码）。

            if (
                normalizedModel.contains("dn-eng41") ||
                normalizedBoard.contains("bengal") ||
                normalizedSoc.contains("sm6225") ||
                normalizedGpu.contains("adreno 610")
            ) {
                return WebpDeviceProfile(
                    name = "sm6225_adreno610",
                    decodeParallelism = 1,
                    cacheBudgetBytes = 24L * 1024 * 1024,
                    maxCacheEntries = 3,
                    maxDecodePixels = 600 * 600,
                )
            }

            if (
                normalizedModel.contains("dn-eng81") ||
                normalizedBoard.contains("mt6789") ||
                normalizedSoc.contains("mt8781") ||
                normalizedGpu.contains("mali-g57")
            ) {
                return WebpDeviceProfile(
                    name = "mt8781_malig57",
                    decodeParallelism = 2,
                    cacheBudgetBytes = 32L * 1024 * 1024,
                    maxCacheEntries = 4,
                    maxDecodePixels = 900 * 900,
                )
            }

            return WebpDeviceProfile(
                name = "default",
                decodeParallelism = 2,
                cacheBudgetBytes = 24L * 1024 * 1024,
                maxCacheEntries = 3,
                maxDecodePixels = 1024 * 1024,
            )
        }

        private fun readBuildField(fieldName: String): String {
            return runCatching {
                Build::class.java.getField(fieldName).get(null) as? String
            }.getOrNull().orEmpty()
        }
    }
}
