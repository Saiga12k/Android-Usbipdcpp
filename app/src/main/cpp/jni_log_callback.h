#pragma once

#include <jni.h>
#include <spdlog/spdlog.h>

namespace jni_log {

// 初始化JNI日志回调。env 用于替换已有回调时释放旧全局引用，防止重复调用泄漏。
// callback_obj 必须是全局引用，所有权交给本函数：成功后被回调持有、由后续的
// init/cleanup 释放；失败有两条互斥出口，新增失败分支必须归入其一：
//   返回 false —— 本函数已当场释放引用（当前仅"取不到 JavaVM"一处）；
//   抛出       —— 本函数未释放引用，仍归调用方（setLogCallbackImpl 的 catch 释放）。
// 返回 false 表示全局状态完全未变（引用已释放），调用方不应启用新的日志通路。
// 契约：接管全局引用之后本函数不再抛异常——唯一理论抛出点（取锁失败）发生在
// 接管之前；接管后的收尾只有旧全局引用的释放（DeleteGlobalRef 按 JNI 规范
// 不抛异常），不构成第二个抛出点。实现修订必须保持这一"要么完整接管、要么
// 原样不动"的原子性：setLogCallbackImpl 的 callback_ref_owned 所有权标记
// 依赖"返回 true 即已接管、抛出即未接管"这对判据——某条失败路径若既抛出
// 又释放引用，那个 catch 会二次 DeleteGlobalRef
bool init(JNIEnv* env, jobject callback_obj, jmethodID log_method);

// 清理
void cleanup(JNIEnv* env);

// spdlog回调函数
void log_callback(spdlog::level::level_enum level, const std::string& message);

} // namespace jni_log