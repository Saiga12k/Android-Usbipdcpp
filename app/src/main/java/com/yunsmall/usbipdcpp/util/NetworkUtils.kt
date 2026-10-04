package com.yunsmall.usbipdcpp.util

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

// 沿用拆分前的 "MainActivity" 标签：logcat 过滤习惯不受文件拆分影响
private const val TAG = "MainActivity"

// 获取设备IP地址
fun getDeviceIpAddress(): String? {
    try {
        // getNetworkInterfaces 文档允许返回 null（无网络接口）：结果同样是
        // 拿不到地址，直接短路，免得 NPE 走 catch 打印误导性的错误日志
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        // Wi-Fi/热点类接口的地址才是 PC 侧能连到的；蜂窝（rmnet/wwan）的地址是
        // 运营商内网，双网并存时枚举顺序不保证 Wi-Fi 在前，故先扫一遍优先命中；
        // 都没有才退回第一个可用 IPv4（只有蜂窝时显示总比 null 强）
        var fallback: String? = null
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            // 跳过回环接口和未启用的接口
            if (networkInterface.isLoopback || !networkInterface.isUp) continue
            // 跳过 VPN/虚拟网卡（tun/ppp 前缀），避免返回虚拟接口地址
            // 接口名是 ASCII，大小写转换用 ROOT：随系统 locale（如土耳其语
            // 的 i/I 规则）转换会得出意料之外的名字
            val ifName = networkInterface.name?.lowercase(Locale.ROOT) ?: ""
            if (ifName.startsWith("tun") || ifName.startsWith("ppp") || ifName.startsWith("vpn")) continue

            val ipv4 = firstIpv4Address(networkInterface) ?: continue
            // ap 前缀是热点接口（如 ap0），swlan 是部分 ROM 的命名
            if (ifName.startsWith("wlan") || ifName.startsWith("ap") || ifName.startsWith("swlan")) {
                return ipv4
            }
            if (fallback == null) {
                fallback = ipv4
            }
        }
        return fallback
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get IP address", e)
    }
    return null
}

// 取接口的第一个非回环 IPv4 地址；无则返回 null
private fun firstIpv4Address(networkInterface: NetworkInterface): String? {
    val addresses = networkInterface.inetAddresses
    while (addresses.hasMoreElements()) {
        val address = addresses.nextElement()
        if (!address.isLoopbackAddress && address is Inet4Address) {
            return address.hostAddress
        }
    }
    return null
}
