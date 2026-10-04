package com.yunsmall.usbipdcpp.util

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice

fun isCameraDevice(device: UsbDevice): Boolean {
    for (i in 0 until device.interfaceCount) {
        if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
    }
    return false
}
