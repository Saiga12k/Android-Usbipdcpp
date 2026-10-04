package com.yunsmall.usbipdcpp

import androidx.annotation.IntDef

/**
 * spdlog 级别常量：数值与 native 侧 spdlog::level::level_enum 严格一致，
 * 供 onLog 实现做过滤/着色时取值，不用在 UI 侧重抄一遍数字
 */
object SpdLogLevels {
    const val TRACE = 0
    const val DEBUG = 1
    const val INFO = 2
    const val WARN = 3
    const val ERROR = 4
    const val CRITICAL = 5
    const val OFF = 6
}

// SOURCE 保留：只用于编码期的类型提示（lint/IDE），不进入运行时产物
@IntDef(
    SpdLogLevels.TRACE, SpdLogLevels.DEBUG, SpdLogLevels.INFO, SpdLogLevels.WARN,
    SpdLogLevels.ERROR, SpdLogLevels.CRITICAL, SpdLogLevels.OFF
)
@Retention(AnnotationRetention.SOURCE)
annotation class SpdLogLevel

/**
 * native 日志回调。onLog 由 native 日志线程同步调用，该线程要等它返回才继续处理
 * 后续日志：实现必须立即返回，重活自行切线程，否则会拖慢整条 native 日志通路。
 * 严禁在 onLog 内调用 UsbIpNative 的任何方法：日志可能发自持有 native 内部锁的
 * 调用链（如 g_server_mutex 内的 spdlog 输出），重入 native 会死锁
 */
interface LogCallback {
    /**
     * @param level spdlog 级别（spdlog::level::level_enum 的整数值：0=trace … 5=critical；
     *        取值常量见 SpdLogLevels）。当前 UI 未区分级别，保留参数以对应 spdlog
     *        原始形态，将来做过滤/着色不必改 JNI
     * @param message 已按 pattern 格式化好的日志文本
     */
    fun onLog(@SpdLogLevel level: Int, message: String)
}