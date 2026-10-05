#pragma once

#include <spdlog/sinks/base_sink.h>
#include <spdlog/spdlog.h>
#include <mutex>
#include <string>

// 自定义sink，通过JNI回调把日志传到Kotlin
template<typename Mutex>
class jni_callback_sink : public spdlog::sinks::base_sink<Mutex> {
public:
    // 函数指针而非 std::function：sink_it_ 是每条日志的热路径，类型层面排除
    // 捕获 lambda（会引入堆分配）的可能；将来若要传捕获 lambda，先想清楚
    // 热路径上的分配代价再改这里。
    // 约定：传入的必须是经全局查表的转发函数（当前是 jni_log::log_callback），
    // 不能在构造时绑定到某个具体回调——回调替换（setLogCallback）不重建已有的
    // sink，日志永远送到"当前"回调正依赖这一层间接
    using callback_fn = void (*)(spdlog::level::level_enum, const std::string&);

    explicit jni_callback_sink(callback_fn callback) : callback_(std::move(callback)) {}

protected:
    void sink_it_(const spdlog::details::log_msg& msg) override {
        if (!callback_) return;

        // 格式化与 std::string 构造都可能 bad_alloc，callback_ 内部也可能抛；
        // 上游有在 JNI 方法 try 之外直接 spdlog::info 的调用点（如服务器启动
        // 日志），异常逃到那里会穿越 extern "C" 边界直接 terminate。失败只丢
        // 这一条日志，不能升级成进程必死
        try {
            spdlog::memory_buf_t formatted;
            this->formatter_->format(msg, formatted);
            std::string log_str = fmt::to_string(formatted);

            callback_(msg.level, log_str);
        } catch (...) {
            // 此处不能再走日志（重入本 sink），丢弃即可
        }
    }

    void flush_() override {}

private:
    callback_fn callback_;
};

using jni_callback_sink_mt = jni_callback_sink<std::mutex>;