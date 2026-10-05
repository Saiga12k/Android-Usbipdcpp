package com.yunsmall.usbipdcpp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// 日志区只保留最近若干条：debug 级日志量大且服务长期运行，不设上限的话
// 每次追加的列表复制和展示时的整段拼接会越来越重
private const val MAX_LOG_LINES = 500

/**
 * UI 日志缓冲：消息列表与自动滚动的版本号必须同步推进（列表封顶后 size
 * 恒定、同秒重复消息的内容也可能完全相等，滚动 effect 只能靠版本号触发），
 * 两个状态收在一起由 append 单点维护。用 SnapshotStateList：追加/裁剪走
 * 元素级通知，读取方（LogSection 的拼接）按元素读取订阅重组，不依赖
 * 每次全量复制列表来触发更新
 */
class LogBuffer {
    private val _messages = mutableStateListOf<String>()

    // 只读视图：读取方按元素读取订阅重组，不需要也不应改写
    val messages: List<String> = _messages

    // 单调递增：列表每次变化（追加、清空）都推进——滚动 effect 以它为准
    var version by mutableStateOf(0L)
        private set

    fun append(formatted: String) {
        _messages.add(formatted)
        if (_messages.size > MAX_LOG_LINES) {
            _messages.removeAt(0)
        }
        version++
    }

    fun clear() {
        _messages.clear()
        version++
    }
}
