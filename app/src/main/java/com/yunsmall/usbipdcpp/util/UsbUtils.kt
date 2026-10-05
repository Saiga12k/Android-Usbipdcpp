package com.yunsmall.usbipdcpp.util

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice

fun isCameraDevice(device: UsbDevice): Boolean {
    // 视频类有两种声明方式：设备级 class 直接声明（部分 UVC 设备），或接口级
    // 声明（复合设备）。只看接口类会在前一种上漏判，两处都要看
    if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
    for (i in 0 until device.interfaceCount) {
        if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
    }
    return false
}
