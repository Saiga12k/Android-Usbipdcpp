package com.yunsmall.usbipdcpp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import com.yunsmall.usbipdcpp.ui.MainScreen
import com.yunsmall.usbipdcpp.ui.theme.UsbipdcppTheme

// 日志标签沿用拆分前的 "MainActivity"：logcat 过滤习惯不受文件拆分影响；
// MainScreen.kt 与 NetworkUtils.kt 也各自保留同名标签
private const val TAG = "MainActivity"

class MainActivity : AppCompatActivity() {

    private val usbManager: UsbManager by lazy {
        getSystemService(USB_SERVICE) as UsbManager
    }

    // 用 lazy：onDestroy 里即使从未 registerReceiver 过也会调 unregisterReceiver，
    // 此时会触发这里的初始化，但构造本身无副作用（PendingIntent 也是 lazy），
    // 而未注册时 unregisterReceiver 抛的异常在其内部已被捕获
    private val permissionManager: UsbPermissionManager by lazy {
        UsbPermissionManager(this, usbManager)
    }

    // Compose 观察的 Service 引用：ServiceConnection 回调都在主线程，直接写
    // State 让组合自动重组，不必再手工挂"状态变了"的回调并记住到处置空
    private val usbServiceHolder = mutableStateOf<UsbService?>(null)
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            // 安全转换：系统只会回传本 Service onBind 返回的 Binder，但将来若
            // 引入第二个 Binder（多 Action）或别的 IBinder 类型，硬转会在系统
            // 回调里抛 ClassCastException 崩溃；转换失败按"未连接"处理即可
            val binder = service as? UsbService.UsbBinder ?: run {
                Log.e(TAG, "onServiceConnected with unexpected binder: $service")
                return
            }
            usbServiceHolder.value = binder.getService()
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            usbServiceHolder.value = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        permissionManager.registerReceiver()

        // 启动并绑定 Service
        val serviceIntent = Intent(this, UsbService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            UsbipdcppTheme {
                MainScreen(
                    usbManager = usbManager,
                    permissionManager = permissionManager,
                    usbService = usbServiceHolder.value
                )
            }
        }
    }

    override fun onDestroy() {
        permissionManager.unregisterReceiver()
        // serviceBound 只在 onServiceConnected 里置 true：bindService 被系统拒绝
        // 或回调还没到时绑定并不存在，此时调 unbindService 会抛
        // IllegalArgumentException。回调未到就销毁的场景不会有 Service 泄漏——
        // Activity context 的绑定跟随 ActivityRecord 生命周期由系统自动清理。
        // usbServiceHolder 不用清：它与本 Activity 同生命周期，整条引用链一起回收
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        // 不停止 Service，让它继续运行
        // super 最后调用：销毁类回调的通行顺序是先清理自己持有的资源、再让基类
        // 收尾（创建类回调如 onCreate 相反，super 最先）。后续新增的清理代码
        // 直接排在上面即可
        super.onDestroy()
    }
}
