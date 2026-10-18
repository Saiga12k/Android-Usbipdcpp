package com.yunsmall.usbipdcpp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UsbService : Service() {

    companion object {
        private const val TAG = "UsbService"
        private const val NOTIFICATION_CHANNEL_ID = "usbipd_service"
        private const val NOTIFICATION_ID = 1
        // Default listening port; the port input uses this same value.
        const val DEFAULT_PORT = 3240
    }

    /**
     * All externally visible service state. Both the UI and notification render this
     * shared state instead of manually synchronizing separate copies.
     */
    data class UiState(
        val nativeReady: Boolean = false,
        val serverRunning: Boolean = false,
        // Keep the last listening port for display and restart after stopServer clears
        // the running flag and device list.
        val port: Int = DEFAULT_PORT,
        // deviceName -> busid, used to display busids in the UI.
        val boundDevices: Map<String, String> = emptyMap()
    )

    /**
     * One-shot events with no SharedFlow replay. Dropping prompts without collectors
     * is intentional, including before UI collection starts. Binding state is
     * published independently through _state and does not depend on event delivery.
     */
    sealed interface Event {
        /**
         * The device was unplugged; wasBound tells the UI whether to display a prompt.
         */
        data class DeviceDetached(val device: UsbDevice, val wasBound: Boolean) : Event
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    // Internal coroutines initialize native code, update notifications and clean up
    // unplugged devices. MutableStateFlow.update uses a thread-safe CAS loop;
    // Main.immediate keeps notification updates on the main thread.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Shared native-thread, non-cancellable context. Start/stop, binding/registration
    // and detach cleanup must finish together to avoid untracked native bindings
    // with closed Kotlin connections. onDestroy releases remaining resources;
    // caller cancellation only affects result delivery. Reuse UsbIpNative.jniContext
    // so nested withContext calls share the interceptor required by the inline fast path.
    private val serviceNativeContext = UsbIpNative.jniContext

    private val binder = UsbBinder()

    // Access active USB connections only on nativeDispatcher, in the same critical
    // section as native calls. External state is published through _state, so a plain map suffices.
    private data class DeviceInfo(
        val connection: UsbDeviceConnection,
        val fd: Int,
        val busid: String
    )
    private val activeDevices = mutableMapOf<String, DeviceInfo>()

    // Wait until onStartCommand establishes the foreground notification before
    // allowing state-driven updates with the same notification ID.
    private var notificationStarted = false

    // onDestroy sets this before cleanup. Reject late bindings to avoid registering
    // devices already removed by cleanup. @Volatile publishes main-thread writes
    // from onDestroy to native-thread reads inside bindDevice.
    @Volatile
    private var destroying = false

    inner class UsbBinder : Binder() {
        fun getService(): UsbService = this@UsbService
    }

    // The service owns detach cleanup because it outlives the Activity. UI-owned
    // cleanup could leave activeDevices entries and connections behind. This is
    // the only detach cleanup path; the UI consumes events only for prompts.
    private val deviceDetachedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            device?.let { usbDevice ->
                // onReceive runs on the main thread. Launch native-thread cleanup in a coroutine
                // instead of blocking the main thread with runBlocking.
                serviceScope.launch { handleDeviceDetached(usbDevice) }
            }
        }
    }

    init {
        // Notifications follow _state instead of requiring refreshes at every mutation.
        serviceScope.launch {
            state.collect { if (notificationStarted) updateNotification() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Load the library and initialize libusb on the native thread to keep the main thread responsive.
        serviceScope.launch {
            val ready = UsbIpNative.init()
            _state.update { it.copy(nativeReady = ready) }
            if (!ready) {
                Log.e(TAG, "Native initialization failed, USB/IP features unavailable")
            }
        }

        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // System broadcasts still reach RECEIVER_NOT_EXPORTED; only other apps are
            // blocked. Exporting would permit forged device-detach broadcasts.
            registerReceiver(deviceDetachedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(deviceDetachedReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        notificationStarted = true
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        // Set this before queuing cleanup so late bindDevice calls cannot be accepted
        // while cleanup waits for pending native calls.
        destroying = true
        try {
            unregisterReceiver(deviceDetachedReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Receiver already unregistered", e)
        }
        // Cancel init if it has not started: g_initialized stays false and release skips
        // libusb_exit. An init already running in non-cancellable jniContext completes
        // first, so release pairs libusb_exit with successful initialization.
        serviceScope.cancel()
        // Native cleanup joins threads and may block; run it in the background to avoid
        // ANRs. closeAllDevices also runs on nativeDispatcher to serialize map writes
        // with remaining bindDevice calls. The closure retains this Service, preventing
        // garbage collection during cleanup. Pending USB calls may delay cleanup
        // indefinitely; a timeout could skip release, so rely on process exit to reclaim
        // a stuck thread. Android process termination does not depend on daemon status.
        // Release before closeAllDevices: LibusbServer::stop releases/closes/unrefs both
        // available and active devices. Only then close UsbDeviceConnection so libusb
        // cannot use an already closed fd. If service recreation races old cleanup,
        // startServerImpl's idempotent init recovers after libusb_exit. Very late cleanup
        // can stop a new server once; restarting recovers without a generation protocol.
        // Logs reach the UI only while its callback exists, otherwise logcat; cleanup
        // does not need to retain that callback. Name the thread to identify stalls.
        thread(name = "usbipd-native-cleanup") {
            // Log both ends; the timestamp difference shows delays waiting for pending
            // non-cancellable native calls and running cleanup.
            Log.i(TAG, "Native cleanup started")
            // An exception escaping this bare thread could terminate the process while the
            // Activity is still active. Final cleanup is best-effort; log failures here.
            try {
                UsbIpNative.runOnNativeThread {
                    // release stops the server and releases libusb without a separate running check.
                    // finally closes connections even if release throws.
                    try {
                        UsbIpNative.release()
                    } finally {
                        closeAllDevices()
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Native cleanup failed", t)
            }
            Log.i(TAG, "Native cleanup finished")
        }
        // Call super last after releasing owned resources, as MainActivity.onDestroy
        // does. Creation callbacks such as onCreate use the opposite order.
        super.onDestroy()
    }

    suspend fun startServer(port: Int): Boolean {
        // Reject starts during destruction, as in bindDevice, to avoid starting an
        // unmanaged server outside the cleanup lifecycle.
        if (destroying) {
            return false
        }
        // Check, start and publish state in one critical section. An external check
        // could race another startServer and publish the wrong port. The UI isStarting
        // guard only prevents repeated clicks; serialization provides correctness.
        // Always publish the actual listening port returned by JNI.
        return withContext(serviceNativeContext) {
            // Recheck because destruction may have started since the entry check.
            if (destroying) {
                return@withContext false
            }
            // An already running server is an idempotent success only for the same actual
            // port. Compare state.port rather than inferring the listener from this request.
            if (state.value.serverRunning) {
                return@withContext state.value.port == port
            }

            val actualPort = UsbIpNative.startServer(port)
            val success = actualPort >= 0
            if (success) {
                // Publish the actual native port even when state lagged after an OOM-level
                // update failure. Native start returns the real existing listener in that case,
                // keeping the displayed port accurate.
                _state.update { it.copy(serverRunning = true, port = actualPort) }
            }
            success
        }
    }

    suspend fun stopServer() {
        // Reject stops during destruction, consistently with startServer and bindDevice.
        // The cleanup thread already stops the server and releases resources.
        if (destroying) {
            return
        }
        withContext(serviceNativeContext) {
            // Recheck because destruction may have started since the entry check.
            if (destroying) {
                return@withContext
            }
            try {
                UsbIpNative.stopServer()
            } finally {
                // Even if stop throws, close connections and publish stopped state in finally.
                // Otherwise the UI could remain running after native stop. withContext serializes
                // closeAllDevices with binding; stop libusb's use of the fd before closing it.
                closeAllDevices()
                _state.update { it.copy(serverRunning = false, boundDevices = emptyMap()) }
            }
        }
    }

    suspend fun bindDevice(usbManager: UsbManager, device: UsbDevice): DeviceBindResult {
        // Reject new bindings during destruction. Calls queued after this entry check
        // are rejected inside the critical section.
        if (destroying) {
            return DeviceBindResult.Failure.ServerNotRunning
        }
        // Access the device map and call native code in the same native-thread critical
        // section. The non-cancellable context keeps binding and registration together,
        // preventing untracked native bindings. Caller cancellation only affects delivery.
        return withContext(serviceNativeContext) {
            // Recheck inside the critical section: onDestroy may have set the flag since
            // entry. Cleanup uses the same jniContext; reject bindings that would otherwise
            // be immediately removed by the queued cleanup task.
            if (destroying) {
                return@withContext DeviceBindResult.Failure.ServerNotRunning
            }
            // Reject duplicate bindings; replacing the map entry would leak the old connection.
            if (activeDevices.containsKey(device.deviceName)) {
                return@withContext DeviceBindResult.Failure.DeviceInUse
            }

            val connection = usbManager.openDevice(device)
            if (connection == null) {
                return@withContext DeviceBindResult.Failure.DeviceOpenFailed
            }

            // After registration activeDevices owns the connection. All other paths, including
            // binding failures and exceptions, close it in finally to prevent fd leaks.
            var registered = false
            try {
                // A successful openDevice normally has a valid fd; reject an invalid one
                // defensively and let finally close it. getFileDescriptor is public since API 12
                // and returns a raw int fd, so reflection is unnecessary.
                val fd = connection.fileDescriptor
                if (fd < 0) {
                    return@withContext DeviceBindResult.Failure.DeviceOpenFailed
                }

                when (val result = UsbIpNative.bindUsbDevice(fd, device.vendorId, device.productId)) {
                    is DeviceBindResult.Success -> {
                        activeDevices[device.deviceName] = DeviceInfo(connection, fd, result.busid)
                        // Transfer connection ownership only after map insertion succeeds. An OOM-level
                        // insertion failure leaves registered false so finally closes the connection;
                        // stopServer, detach or release reclaims the untracked native binding. Setting
                        // registered early would leak the connection on failure. Set it before state
                        // publication, though: a state failure must not close an owned connection.
                        // Cleanup eventually reconciles UI/native state; this path does not self-repair.
                        registered = true
                        _state.update {
                            it.copy(boundDevices = it.boundDevices + (device.deviceName to result.busid))
                        }
                        Log.i(TAG, "Device bound: ${device.deviceName} -> ${result.busid}")
                        result
                    }
                    is DeviceBindResult.Failure -> result
                }
            } catch (t: CancellationException) {
                // Propagate cancellation according to structured concurrency instead of reporting ordinary failure.
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "bindDevice failed unexpectedly: ${device.deviceName}", t)
                // Registration proves native binding succeeded; only later state publication
                // failed at OOM level. Report Success so a retry does not misleadingly return
                // DeviceInUse. Retain registration for stopServer/detach/release to reconcile
                // state. Rollback could also fail here; use the existing cleanup path consistently.
                val boundBusid = if (registered) activeDevices[device.deviceName]?.busid else null
                if (boundBusid != null) {
                    DeviceBindResult.Success(boundBusid)
                } else {
                    DeviceBindResult.Failure.UnknownError
                }
            } finally {
                if (!registered) {
                    // Log close failures; throwing from finally would become an uncaught coroutine
                    // exception and crash the app.
                    runCatching { connection.close() }
                        .onFailure { e -> Log.w(TAG, "close device failed", e) }
                }
            }
        }
    }

    suspend fun unbindDevice(deviceName: String): DeviceUnbindResult {
        // During destruction cleanup already removes all devices. Report idempotent
        // success, as for DeviceNotFound: the connection and state will be cleared.
        if (destroying) {
            return DeviceUnbindResult.Success
        }
        // Use serviceNativeContext so caller cancellation cannot interrupt cleanup, as in bindDevice.
        return withContext(serviceNativeContext) {
            // Recheck because destruction may have started since the entry check.
            if (destroying) {
                return@withContext DeviceUnbindResult.Success
            }
            val info = activeDevices[deviceName]
                ?: return@withContext DeviceUnbindResult.Failure.DeviceNotFound

            // Even if native code no longer has the device, clear the Kotlin connection
            // and state to avoid leaks and a stale bound indicator.
            fun cleanup() {
                // Remove registration before closing so close failures cannot prevent state
                // updates. closeAllDevices uses the same order.
                val removed = activeDevices.remove(deviceName)
                runCatching { removed?.connection?.close() }
                    .onFailure { e -> Log.w(TAG, "close device failed", e) }
                _state.update { it.copy(boundDevices = it.boundDevices - deviceName) }
            }

            // Keep registration on exception: native code may still own the binding, and
            // retaining the Kotlin entry allows retry instead of orphaning the device.
            try {
                when (val result = UsbIpNative.unbindUsbDevice(info.fd)) {
                    DeviceUnbindResult.Success -> {
                        cleanup()
                        Log.i(TAG, "Device unbound: $deviceName")
                        result
                    }
                    // If the server or device has disappeared, clear the Kotlin connection and
                    // state and report idempotent success. Reporting failure after successful
                    // cleanup would contradict the UI state.
                    DeviceUnbindResult.Failure.DeviceNotFound,
                    DeviceUnbindResult.Failure.ServerNotRunning -> {
                        cleanup()
                        Log.w(TAG, "Device already gone in native: $deviceName")
                        DeviceUnbindResult.Success
                    }
                    DeviceUnbindResult.Failure.DeviceInUse -> result
                    else -> {
                        Log.e(TAG, "Unknown unbind error: $result for $deviceName")
                        result
                    }
                }
            } catch (t: CancellationException) {
                // Propagate cancellation instead of converting it to ordinary failure, as in bindDevice.
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "unbindDevice failed unexpectedly: $deviceName", t)
                DeviceUnbindResult.Failure.UnknownError
            }
        }
    }

    /**
     * Handle detach entirely on the native thread: notify native code before closing
     * the Kotlin connection. notify_device_removed removes the device or stops its
     * session. After physical detach the fd is invalid; libusb owns a separate fd,
     * so the closes are independent. Only the service receiver invokes this cleanup.
     */
    private suspend fun handleDeviceDetached(usbDevice: UsbDevice) {
        // Use serviceNativeContext to keep registration, native notification and state
        // updates in one non-cancellable transaction. Emit the event inside too; resuming
        // outside could be cancelled during destruction before event delivery.
        withContext(serviceNativeContext) {
            val info = activeDevices.remove(usbDevice.deviceName)
            if (info == null) {
                if (!_events.tryEmit(Event.DeviceDetached(usbDevice, false))) {
                    Log.w(TAG, "DeviceDetached event dropped (buffer full): ${usbDevice.deviceName}")
                }
                return@withContext
            }
            // Finish Kotlin cleanup even if native notification fails: the device is
            // physically gone, so its connection and bound state must both be cleared.
            try {
                UsbIpNative.notifyDeviceRemoved(info.busid)
            } catch (t: CancellationException) {
                // Propagate cancellation instead of converting it to ordinary failure, as in bindDevice.
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "notifyDeviceRemoved failed: ${usbDevice.deviceName}", t)
            } finally {
                // Log close failures so state cleanup and detach notification still run;
                // otherwise the UI could show an unplugged device as bound forever.
                runCatching { info.connection.close() }
                    .onFailure { e -> Log.w(TAG, "close device failed", e) }
                _state.update { it.copy(boundDevices = it.boundDevices - usbDevice.deviceName) }
                // Emit the event with the state update in finally; cancellation could skip
                // statements after finally and lose the detach prompt. A full event buffer only
                // loses the prompt, not cleanup. Log dropped events for diagnosis.
                if (!_events.tryEmit(Event.DeviceDetached(usbDevice, true))) {
                    Log.w(TAG, "DeviceDetached event dropped (buffer full): ${usbDevice.deviceName}")
                }
            }
            Log.i(TAG, "Device detached: ${usbDevice.deviceName}")
        }
    }

    // Call only on nativeDispatcher. Close devices and clear bound state together
    // so stopServer/onDestroy cannot forget to update _state. Catch each close
    // failure so remaining connections and state are still cleared.
    private fun closeAllDevices() {
        // Catch the likely misuse: a call from the main-thread serviceScope. Fully
        // checking dispatcher ownership would require exposing nativeDispatcher,
        // which is unnecessary for hypothetical call paths.
        check(Looper.myLooper() != Looper.getMainLooper()) { "closeAllDevices must not run on the main thread" }
        activeDevices.values.forEach {
            runCatching { it.connection.close() }
                .onFailure { e -> Log.w(TAG, "close device failed", e) }
        }
        activeDevices.clear()
        _state.update { it.copy(boundDevices = emptyMap()) }
        Log.i(TAG, "All devices closed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "USB/IP Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            // Display the current running state, including stopped, to avoid misleading users.
            .setContentText(getString(if (state.value.serverRunning) R.string.server_running else R.string.server_stopped))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification())
    }
}
