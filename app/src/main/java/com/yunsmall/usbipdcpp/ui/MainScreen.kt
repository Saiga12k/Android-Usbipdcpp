package com.yunsmall.usbipdcpp.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.yunsmall.usbipdcpp.DeviceBindResult
import com.yunsmall.usbipdcpp.DeviceUnbindResult
import com.yunsmall.usbipdcpp.LogCallback
import com.yunsmall.usbipdcpp.R
import com.yunsmall.usbipdcpp.UsbIpNative
import com.yunsmall.usbipdcpp.UsbPermissionManager
import com.yunsmall.usbipdcpp.UsbService
import com.yunsmall.usbipdcpp.util.getDeviceIpAddress
import com.yunsmall.usbipdcpp.util.isCameraDevice
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Keep the original MainActivity tag so existing logcat filters still work.
private const val TAG = "MainActivity"

// deviceName means UsbDevice.deviceName, the system name used as map keys.
// displayName means the product name presented to users.

// A restored pending camera request may never receive a result after process
// death. After this timeout, the next bind click replaces it; see onBindDevice.
private const val CAMERA_PENDING_TIMEOUT_MS = 60_000L

// Reuse thread-safe DateTimeFormatter on the per-log-line hot path.
// SimpleDateFormat would implicitly require all callers to stay on the main thread.
private val logTimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

fun setLanguage(language: String) {
    val localeList = if (language == "system") {
        LocaleListCompat.getEmptyLocaleList()
    } else {
        LocaleListCompat.forLanguageTags(language)
    }
    AppCompatDelegate.setApplicationLocales(localeList)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    usbManager: UsbManager,
    permissionManager: UsbPermissionManager,
    usbService: UsbService?
) {
    var isStarting by remember { mutableStateOf(false) }
    var isStopping by remember { mutableStateOf(false) }
    // Preserve the user's port input across rotation with rememberSaveable.
    var portText by rememberSaveable { mutableStateOf(UsbService.DEFAULT_PORT.toString()) }
    // Do not save logs in the Bundle: native logging continues after rotation, and
    // serializing up to 500 strings adds unnecessary overhead.
    val logBuffer = remember { LogBuffer() }
    var devices by remember { mutableStateOf(mapOf<String, UsbDevice>()) }
    var showFullLog by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    // Track a set of busy devices so multiple binds/unbinds can run concurrently,
    // with progress and button disabling applied per device.
    var busyDevices by remember { mutableStateOf(setOf<String>()) }
    // Save the name because UsbDevice is not Parcelable. rememberSaveable lets
    // permission callbacks locate the device after Activity recreation. Normal
    // results survive recreation; launch exceptions reset state below. After process
    // death a result may never arrive, so onBindDevice expires restored requests
    // using their submission timestamp.
    var pendingBindDeviceName by rememberSaveable { mutableStateOf<String?>(null) }
    // elapsedRealtime is monotonic within a boot and survives process recreation.
    // A reboot clears the task stack, so saved values cannot cross clock origins.
    var pendingBindSubmittedAt by rememberSaveable { mutableStateOf(0L) }

    // Observe the Service as the sole source of server and binding state. Before
    // binding use a read-only empty flow. Remember its instance to avoid restarting
    // collectAsState on every recomposition.
    val emptyServiceState = remember { flowOf(UsbService.UiState()) }
    // collectAsState keeps a stable State object with current delegated reads.
    // rememberLauncherForActivityResult forwards through rememberUpdatedState,
    // so callbacks use the latest performBind and nativeReady state.
    val serviceState by (usbService?.state ?: emptyServiceState).collectAsState(UsbService.UiState())
    val serverRunning = serviceState.serverRunning
    val boundDevices = serviceState.boundDevices

    // Copy back the actual port only while running. Otherwise rotation would
    // replace unsent, saved user input with the default port.
    LaunchedEffect(serviceState.port, serverRunning) {
        if (serverRunning) {
            portText = serviceState.port.toString()
        }
    }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Permission callbacks span Activity recreation; rememberUpdatedState reads
    // the latest service instead of retaining a pre-binding null snapshot.
    val currentUsbService by rememberUpdatedState(usbService)

    // Share readiness checks/messages before and after permission requests. Read
    // nativeReady from service.state.value directly; the composition's serviceState
    // may still reflect the old flow in the frame immediately after service binding.
    fun requireReadyService(): UsbService? {
        val service = currentUsbService ?: run {
            Toast.makeText(context, context.getString(R.string.service_not_ready), Toast.LENGTH_SHORT).show()
            return null
        }
        // Report native initialization failure explicitly before attempting a bind.
        if (!service.state.value.nativeReady) {
            Toast.makeText(context, context.getString(R.string.native_init_failed), Toast.LENGTH_SHORT).show()
            return null
        }
        return service
    }

    // Bind the device after obtaining USB permission.
    fun performBind(device: UsbDevice) {
        // Check readiness before opening a permission dialog. bindAfterPermission checks
        // again because the result may arrive after Activity recreation.
        if (requireReadyService() == null) {
            return
        }
        // Results arrive through onPermissionResult, synchronously when already authorized.
        if (!permissionManager.requestPermission(device)) {
            // Report an existing permission request so a rejected duplicate does not look unresponsive.
            Toast.makeText(context, context.getString(R.string.permission_request_pending), Toast.LENGTH_SHORT).show()
        }
    }

    // Continue binding in the current composition after permission is granted.
    // Do not capture the requesting composition, which may be destroyed while
    // the permission dialog is open.
    fun bindAfterPermission(device: UsbDevice) {
        // Recheck current service/native state after permission returns.
        // requireReadyService is shared with performBind to keep these checks consistent.
        val service = requireReadyService() ?: return
        // displayName is for users; device.deviceName is the system name used as a key.
        val displayName = device.productName?.takeIf { it.isNotEmpty() }
            ?: context.getString(R.string.unknown_device)
        scope.launch {
            busyDevices = busyDevices + device.deviceName
            try {
                val result = service.bindDevice(usbManager, device)
                // The Service StateFlow propagates binding state to the UI automatically.
                when (result) {
                    is DeviceBindResult.Success -> {
                        Toast.makeText(context, context.getString(R.string.bind_success, displayName), Toast.LENGTH_SHORT).show()
                    }
                    is DeviceBindResult.Failure -> {
                        Toast.makeText(context, context.getString(R.string.bind_failed, result.getMessage(context)), Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                busyDevices = busyDevices - device.deviceName
            }
        }
    }

    // Clear the pending camera device and timestamp together; they describe one
    // request and must remain paired for stale-request detection.
    fun clearPendingCameraBind() {
        pendingBindDeviceName = null
        pendingBindSubmittedAt = 0L
    }

    // Request camera permission.
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val deviceName = pendingBindDeviceName ?: return@rememberLauncherForActivityResult
        clearPendingCameraBind()
        // Look up the device again after Activity recreation. Report unplugged devices
        // so granting permission does not appear to have had no effect.
        val device = usbManager.deviceList[deviceName] ?: run {
            Toast.makeText(context, context.getString(R.string.device_unavailable), Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        if (granted) {
            performBind(device)
        } else {
            // Distinguish denied camera permission from an unavailable device in the message.
            Toast.makeText(context, context.getString(R.string.camera_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    // The closure captures the remembered logBuffer instance, not a snapshot.
    // Even the function retained by the first DisposableEffect reads current state,
    // so rememberUpdatedState forwarding is unnecessary.
    fun addLog(message: String) {
        logBuffer.append("[${LocalTime.now().format(logTimeFormat)}] $message")
    }

    fun refreshDevices() {
        devices = permissionManager.getDeviceList()
        addLog("Found ${devices.size} USB device(s)")
    }

    val ipAddress = remember { mutableStateOf<String?>(null) }

    // Obtain the IP address.
    LaunchedEffect(serverRunning) {
        if (serverRunning) {
            // Network interface enumeration may be slow; use IO to keep the main thread responsive.
            ipAddress.value = withContext(Dispatchers.IO) { getDeviceIpAddress() }
        } else {
            // Clear the address after stopping; it is stale until the next server start.
            ipAddress.value = null
        }
    }

    // Register native logging; NativeLogBridge handles disposal and main-thread delivery.
    NativeLogBridge { addLog(it) }

    // Refresh once initially, then on device broadcasts or manual refresh.
    LaunchedEffect(Unit) { refreshDevices() }

    // Observe USB attachment/detachment through the permission manager's BroadcastReceiver.
    DisposableEffect(permissionManager) {
        permissionManager.setOnDeviceListChangedListener {
            refreshDevices()
        }
        onDispose {
            permissionManager.setOnDeviceListChangedListener(null)
        }
    }

    // The requesting composition may be gone when USB permission returns. Continue
    // binding here, as for camera permission. permissionManager is a sufficient key:
    // the listener reads the latest service through currentUsbService instead of
    // capturing a service snapshot.
    DisposableEffect(permissionManager) {
        permissionManager.setOnPermissionResultListener { device, granted ->
            if (!granted) {
                Toast.makeText(context, context.getString(R.string.device_unavailable), Toast.LENGTH_SHORT).show()
                return@setOnPermissionResultListener
            }
            // Look up the current device and report if it was unplugged during the dialog,
            // as in the camera permission callback.
            val current = usbManager.deviceList[device.deviceName]
            if (current == null) {
                Toast.makeText(context, context.getString(R.string.device_unavailable), Toast.LENGTH_SHORT).show()
                return@setOnPermissionResultListener
            }
            bindAfterPermission(current)
        }
        onDispose {
            permissionManager.setOnPermissionResultListener(null)
        }
    }

    // The Service owns detach cleanup even after Activity destruction. The UI only
    // shows event prompts and observes binding state through serviceState.
    LaunchedEffect(usbService) {
        usbService?.events?.collect { event ->
            when (event) {
                is UsbService.Event.DeviceDetached -> {
                    if (event.wasBound) {
                        val displayName = event.device.productName?.takeIf { it.isNotEmpty() }
                            ?: context.getString(R.string.unknown_device)
                        Toast.makeText(context, context.getString(R.string.device_detached, displayName), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { MainTopBar(onAboutClick = { showAbout = true }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ServerControlPanel(
                serverRunning = serverRunning,
                isStarting = isStarting,
                isStopping = isStopping,
                portText = portText,
                onPortChange = { portText = it },
                onStart = {
                    // Reject out-of-range ports before native unsigned-short conversion can wrap
                    // them to a different listening port than the UI displays.
                    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
                    if (port == null) {
                        Toast.makeText(context, context.getString(R.string.invalid_port), Toast.LENGTH_SHORT).show()
                        return@ServerControlPanel
                    }
                    // Share requireReadyService with bind/unbind for consistent checks/messages.
                    // It reads the latest service through currentUsbService.
                    val service = requireReadyService() ?: return@ServerControlPanel
                    isStarting = true
                    scope.launch {
                        // Reset in finally so cancellation during composition disposal cannot leave
                        // a partially completed starting state.
                        try {
                            // The Service StateFlow switches the UI to running on success. Failure leaves
                            // state unchanged, so explicitly report errors such as a port already in use.
                            val started = service.startServer(port)
                            if (!started) {
                                Toast.makeText(context, context.getString(R.string.start_failed), Toast.LENGTH_SHORT).show()
                            }
                        } finally {
                            isStarting = false
                        }
                    }
                },
                onStop = {
                    // Use the same readiness check as onStart.
                    val service = requireReadyService() ?: return@ServerControlPanel
                    isStopping = true
                    scope.launch {
                        try {
                            // The Service StateFlow clears running and binding state after stopping.
                            service.stopServer()
                        } finally {
                            // Reset stopping even if composition disposal cancels the coroutine.
                            isStopping = false
                        }
                    }
                }
            )

            StatusCard(
                serverRunning = serverRunning,
                boundCount = boundDevices.size,
                ipAddress = ipAddress.value,
                // Display the actual state port; portText is a draft and may briefly differ
                // before LaunchedEffect copies back the listening port.
                port = serviceState.port
            )

            DeviceListSection(
                devices = devices,
                boundDevices = boundDevices,
                busyDevices = busyDevices,
                serverRunning = serverRunning,
                onBindDevice = { device ->
                    if (!serverRunning) {
                        Toast.makeText(context, context.getString(R.string.please_start_server), Toast.LENGTH_SHORT).show()
                        return@DeviceListSection
                    }
                    // Check the latest service reference, the same source used by performBind.
                    if (currentUsbService == null) {
                        Toast.makeText(context, context.getString(R.string.service_not_ready), Toast.LENGTH_SHORT).show()
                        return@DeviceListSection
                    }
                    // Camera devices require CAMERA permission before binding.
                    if (isCameraDevice(device) &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                    ) {
                        if (pendingBindDeviceName != null) {
                            // Process death may remove the dialog without delivering a result. Expire
                            // the restored request after the timeout and proceed with the new request.
                            if (SystemClock.elapsedRealtime() - pendingBindSubmittedAt > CAMERA_PENDING_TIMEOUT_MS) {
                                clearPendingCameraBind()
                            } else {
                                // A request is already in flight. Do not overwrite pendingBindDeviceName and
                                // bind the wrong device when permission returns. Explain the ignored click.
                                Toast.makeText(context, context.getString(R.string.permission_request_pending), Toast.LENGTH_SHORT).show()
                                return@DeviceListSection
                            }
                        }
                        pendingBindDeviceName = device.deviceName
                        pendingBindSubmittedAt = SystemClock.elapsedRealtime()
                        try {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        } catch (e: Exception) {
                            // A launch exception produces no callback. Reset the saved pending request
                            // so it cannot block this device across Activity recreation.
                            clearPendingCameraBind()
                            Log.e(TAG, "Failed to launch camera permission request", e)
                        }
                    } else {
                        performBind(device)
                    }
                },
                onUnbindDevice = { device ->
                    // Use the same readiness check as onStart.
                    val service = requireReadyService() ?: return@DeviceListSection
                    val displayName = device.productName?.takeIf { it.isNotEmpty() }
                        ?: context.getString(R.string.unknown_device)
                    scope.launch {
                        busyDevices = busyDevices + device.deviceName
                        try {
                            val result = service.unbindDevice(device.deviceName)
                            // The Service StateFlow propagates unbinding state to the UI automatically.
                            when (result) {
                                is DeviceUnbindResult.Success -> {
                                    Toast.makeText(context, context.getString(R.string.unbind_success, displayName), Toast.LENGTH_SHORT).show()
                                }
                                is DeviceUnbindResult.Failure -> {
                                    Toast.makeText(context, context.getString(R.string.unbind_failed, result.getMessage(context)), Toast.LENGTH_SHORT).show()
                                }
                            }
                        } finally {
                            busyDevices = busyDevices - device.deviceName
                        }
                    }
                },
                onRefresh = { refreshDevices() }
            )

            LogSection(
                logMessages = logBuffer.messages,
                logVersion = logBuffer.version,
                onClear = { logBuffer.clear() },
                onViewFullLog = { showFullLog = true },
                onCopyLog = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Log", logBuffer.messages.joinToString("\n")))
                    Toast.makeText(context, context.getString(R.string.log_copied), Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    if (showFullLog) {
        FullLogDialog(
            logMessages = logBuffer.messages,
            logVersion = logBuffer.version,
            onDismiss = { showFullLog = false }
        )
    }

    if (showAbout) {
        // Handle package lookup failure defensively, even for the current package.
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get package info", e)
            "unknown"
        }
        val githubUrl = "https://github.com/yunsmall/Android-Usbipdcpp"
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text(stringResource(R.string.about_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.about_description))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.about_version, version))
                    Text(stringResource(R.string.about_license))
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl))
                            context.startActivity(intent)
                        }
                    ) {
                        Text(stringResource(R.string.about_github), color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
}

// Bridge native logs: manage registration/disposal and main-thread delivery.
// addLog captures the remembered logBuffer. DisposableEffect(Unit) can retain
// the initial lambda because it uses that stable instance, not a state snapshot.
@Composable
private fun NativeLogBridge(addLog: (String) -> Unit) {
    DisposableEffect(Unit) {
        // Native logging calls onLog on its own thread. Dispatch to the main thread
        // before changing Compose state.
        val mainHandler = Handler(Looper.getMainLooper())
        val callback = object : LogCallback {
            override fun onLog(level: Int, message: String) {
                // Trim defensively; the native pattern normally has no trailing whitespace.
                // Any tasks remaining after disposal only touch unobserved disposed state,
                // so they do not need separate tracking.
                mainHandler.post { addLog(message.trim()) }
            }
        }
        UsbIpNative.setLogCallback(callback)
        onDispose {
            // Clear the JNI callback on disposal to release its global reference to the old
            // Activity, including when no replacement will be created. During rotation the
            // old instance is destroyed before the new one registers. Unusual split-screen
            // ordering may pause logs until reentry but does not affect correctness; no
            // cleanup token is needed. Clear the callback before removing queued tasks.
            UsbIpNative.setLogCallback(null)
            mainHandler.removeCallbacksAndMessages(null)
        }
    }
}

// The top bar owns the title, language menu and About action. Menu expansion
// stays local; selection calls file-level setLanguage. MainScreen owns the
// About dialog and exposes it through a callback. TopAppBar needs an opt-in
// on this function; MainScreen's opt-in only covers its own body.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(onAboutClick: () -> Unit) {
    var showLanguageMenu by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(stringResource(R.string.app_title)) },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        actions = {
            TextButton(onClick = onAboutClick) {
                Text(stringResource(R.string.about))
            }
            Box {
                TextButton(onClick = { showLanguageMenu = true }) {
                    Text(stringResource(R.string.language))
                }
                DropdownMenu(
                    expanded = showLanguageMenu,
                    onDismissRequest = { showLanguageMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.language_en)) },
                        onClick = {
                            setLanguage("en")
                            showLanguageMenu = false
                        }
                    )
                }
            }
        }
    )
}
