package io.webpkit.player

import android.util.Log

/**
 * Lightweight logging facade for the WebP player. Wraps [android.util.Log] so the
 * library carries no dependency on any host-app logger. Disable globally via
 * [enabled] (e.g. in release builds).
 *
 * 所有日志统一使用同一个 logcat tag [TAG]（"WebpKit"），调用方传入的 `tag`
 * 会被拼接到消息前缀里（形如 `[WebpRenderer] ...`）。这样排查问题时只需:
 *
 *     adb logcat -s WebpKit
 *
 * 即可看到整个库（解码 / 缓存 / GL 渲染 / 生命周期）的全部日志。
 */
object WebpLog {

    /** 全库统一的 logcat tag。用 `adb logcat -s WebpKit` 过滤即可看到所有日志。 */
    const val TAG = "WebpKit"

    /** Master switch. Set to false to silence all library logging. */
    @JvmStatic
    @Volatile
    var enabled: Boolean = true

    /** 把调用方传入的子标签拼成消息前缀，便于定位来源类。 */
    private fun fmt(tag: String?, msg: String?): String {
        val sub = tag.orEmpty()
        val body = msg.orEmpty()
        return if (sub.isEmpty()) body else "[$sub] $body"
    }

    @JvmStatic
    fun v(tag: String?, msg: String?) {
        if (enabled) Log.v(TAG, fmt(tag, msg))
    }

    @JvmStatic
    fun i(tag: String?, msg: String?) {
        if (enabled) Log.i(TAG, fmt(tag, msg))
    }

    @JvmStatic
    fun d(tag: String?, msg: String?) {
        if (enabled) Log.d(TAG, fmt(tag, msg))
    }

    @JvmStatic
    fun w(tag: String?, msg: String?) {
        if (enabled) Log.w(TAG, fmt(tag, msg))
    }

    @JvmStatic
    fun w(tag: String?, msg: String?, throwable: Throwable?) {
        if (enabled) Log.w(TAG, fmt(tag, msg), throwable)
    }

    @JvmStatic
    fun e(tag: String?, msg: String?) {
        if (enabled) Log.e(TAG, fmt(tag, msg))
    }

    @JvmStatic
    fun e(tag: String?, msg: String?, throwable: Throwable?) {
        if (enabled) Log.e(TAG, fmt(tag, msg), throwable)
    }
}
