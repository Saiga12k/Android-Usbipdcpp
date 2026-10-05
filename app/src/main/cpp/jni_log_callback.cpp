#include "jni_log_callback.h"
#include <android/log.h>
#include <mutex>

namespace jni_log {

namespace {
    JavaVM* g_jvm = nullptr;
    jobject g_callback_obj = nullptr;
    jmethodID g_log_method = nullptr;
    // 与 usbipd_jni.cpp 的 LOG_TAG 一致，便于 logcat 按同一标签过滤本模块输出
    constexpr char LOG_TAG[] = "UsbIpNative";

    // detach 的统一出口：失败（如 JNI_EDETACHED）只记一行，避免排查
    // attach/detach 泄漏时缺线索
    void detach_if_needed(JavaVM* jvm, bool need_detach) {
        if (need_detach && jvm->DetachCurrentThread() != JNI_OK) {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "jni_log: DetachCurrentThread failed");
        }
    }
    // 保护 g_callback_obj 等全局：cleanup 删除全局引用时，log_callback
    // 可能正由 usbipdcpp 的日志线程执行，不用锁会访问已释放的引用
    std::mutex g_mutex;
}

InitResult init(JNIEnv* env, jobject callback_obj, jmethodID log_method) {
    // MainActivity 的 DisposableEffect 每次重建都会调用 setLogCallback，
    // 不释放旧引用会导致全局引用永久泄漏
    // GetJavaVM 只读 env 内部的 vm 指针（不碰引用表、不阻塞），放锁外执行；
    // 写 g_jvm 仍在锁内，避免与 cleanup 的置空竞争
    JavaVM* jvm = nullptr;
    if (env->GetJavaVM(&jvm) != JNI_OK || jvm == nullptr) {
        // 规范上允许失败（实现上取 env 缓存的 vm 指针，基本不会）：失败时全局
        // 状态保持不变（旧回调继续工作），只把本次的新引用释放掉
        env->DeleteGlobalRef(callback_obj);
        return InitResult::FailedRefReleased;
    }
    jobject old = nullptr;
    {
        std::lock_guard lock(g_mutex);
        old = g_callback_obj;
        g_jvm = jvm;
        g_callback_obj = callback_obj;
        g_log_method = log_method;
    }
    // DeleteGlobalRef 触碰 JVM 引用表，放锁外执行：与本文件"JNI 调用不进锁"
    // 的约定一致，也避免持锁等待 JVM 内部锁
    if (old) {
        env->DeleteGlobalRef(old);
    }
    return InitResult::Installed;
}

void cleanup(JNIEnv* env) {
    // 与 init 同纪律：锁内只摘引用，DeleteGlobalRef 放锁外执行
    jobject old = nullptr;
    {
        std::lock_guard lock(g_mutex);
        old = g_callback_obj;
        g_callback_obj = nullptr;
        g_jvm = nullptr;
        g_log_method = nullptr;
    }
    if (old) {
        env->DeleteGlobalRef(old);
    }
}

void log_callback(spdlog::level::level_enum level, const std::string& message) {
    // 锁内白名单：只允许不阻塞、且不会重入本锁的 JNI 调用（GetEnv、
    // AttachCurrentThread、NewLocalRef、读缓存的 methodID）；其余 JNI 调用
    //（NewStringUTF、CallVoidMethod 等）一律放锁外：
    // - 本地引用独立引用计数，锁外调用期间 cleanup 删除全局引用对象仍存活
    //   （快照裸指针方案在这里是 use-after-free，不能用于缩小锁范围）
    // - 锁外执行用户代码，Kotlin 回调未来即使同步触发 native 日志也不会死锁
    JavaVM* jvm = nullptr;
    JNIEnv* env = nullptr;
    jobject callback = nullptr;
    jmethodID method = nullptr;
    bool need_detach = false;
    {
        std::lock_guard lock(g_mutex);
        if (!g_jvm || !g_callback_obj || !g_log_method) return;
        jvm = g_jvm;

        // attach 放锁内执行：ART 的实现会创建 java.lang.Thread 的 peer 对象，
        // 但该路径不经过本模块的日志回调（唯一会重入 g_mutex 的是 spdlog
        // 输出），不存在同线程二次加锁；且需要 GetEnv 后才能在锁内派生本地引用。
        // 锁外 attach 的方案（取 jvm→解锁→attach→回锁派生引用）要在回锁后
        // 重新处理"回调已被 cleanup 清掉"的窗口，复杂度换不来实际收益
        int get_env_result = jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (get_env_result == JNI_EDETACHED) {
            // 用完即 detach 而不缓存 attach 状态：日志线程由库创建，线程退出时
            // 我们没有补救机会，保持 attached 会违反 JNI"退出前必须 detach"的要求；
            // attach/detach 是微秒级操作，不做优化
            if (jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                need_detach = true;
            } else {
                // 拿不到 env：本条日志只能丢弃，打一行便于从 logcat 排查线程状态异常
                __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "jni_log: AttachCurrentThread failed");
                return;
            }
        } else if (get_env_result != JNI_OK) {
            // 同 attach 失败：JNI_EVERSION 等意味着本线程拿不到可用 env，
            // 日志被静默丢弃，打一行便于排查 ROM 兼容问题
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "jni_log: GetEnv failed: %d", get_env_result);
            return;
        }

        // 这里是"JNI 调用放锁外"的例外：NewLocalRef 只碰引用表、不执行
        // 用户代码也不阻塞（同锁内 attach 的理由），故允许在锁内派生
        callback = env->NewLocalRef(g_callback_obj);
        method = g_log_method;
    }
    // env 为空只可能是 JVM 行为异常（正常实现下 attach/GetEnv 成功时必然给出
    // 有效 env，此时 need_detach 也不会置位），单独短路以免下方逻辑依赖
    // "env 非空"这个不可达假设；若违约实现里 attach 报成功却没写 env，
    // need_detach 已置位，照样 detach，出口与下方分支保持一致
    if (!env) {
        detach_if_needed(jvm, need_detach);
        return;
    }
    if (!callback || !method) {
        // NewLocalRef 失败（OOM）会挂起 OutOfMemoryError，必须清掉：异常残留在
        // 已 attach 的线程上（如发起调用的 UI 线程）会破坏其后续 JNI 调用。
        // 已 attach 的线程必须 detach，否则线程永久附加在 JVM 上
        env->ExceptionClear();
        detach_if_needed(jvm, need_detach);
        return;
    }

    // NewStringUTF 按 NUL 结尾语义转换：消息若含内嵌 NUL（格式化参数带入的
    // 理论情形）会被截断，只影响这一条日志的显示，不做额外检测。
    // 四字节 UTF-8（如 emoji）无需担心：ART 的 NewStringUTF 接受标准 UTF-8，
    // 会正确转换为代理对（比 JNI 规范要求的 modified UTF-8 更宽容）
    jstring jmessage = env->NewStringUTF(message.c_str());
    if (!jmessage) {
        // OOM 时 NewStringUTF 返回 null 并挂 OutOfMemoryError，清掉避免残留
        env->ExceptionClear();
        env->DeleteLocalRef(callback);
        detach_if_needed(jvm, need_detach);
        return;
    }

    jint jlevel = static_cast<jint>(level);
    env->CallVoidMethod(callback, method, jlevel, jmessage);
    env->DeleteLocalRef(jmessage);
    env->DeleteLocalRef(callback);

    // Kotlin 回调抛异常时清除，避免异常残留在 JNI 栈上破坏后续调用
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }

    detach_if_needed(jvm, need_detach);
}

} // namespace jni_log