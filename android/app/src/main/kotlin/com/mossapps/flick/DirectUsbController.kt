package com.mossapps.flick

// Audio capability mapping:
//   Kotlin "usbDac"      → Rust AudioCapability::UsbDac      / BackendType::UsbDirect
//   Kotlin "hiResInternal" → Rust AudioCapability::HiResInternal / BackendType::DapNative or MixerBitPerfect
//   Kotlin "standard"    → Rust AudioCapability::Standard     / BackendType::ResampledFallback
//
// Route type mapping:
//   Kotlin "usb"       → USB DAC/AMP (direct or Android-managed)
//   Kotlin "internal"  → Built-in audio (could be DAP internal DAC or phone)
//   Kotlin "wired"     → Wired headphones/line out
//   Kotlin "bluetooth" → Bluetooth audio
//   Kotlin "dock"      → Dock audio
//   Kotlin "unknown"   → Unidentified route
//
// DAP brand detection happens in Rust (audio/device.rs DAP_REGISTRY) and Dart
// (android_audio_device_service.dart isLikelyDap). Kotlin does NOT detect DAP brands.

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioDeviceCallback
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.util.Log
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlin.math.roundToInt

/**
 * Everything the direct USB DAC needs from Android: the `com.mossapps.flick/uac2`
 * and `com.mossapps.flick/audio_device` channels, the DAC's USB claim and its
 * native (Rust) registration, route and volume queries, audio focus, and the
 * receivers and observers that report hot-plug and volume changes to Dart.
 *
 * It holds an application [Context], not an activity, and lives as long as the
 * process ([FlickEngine] owns it). That is what lets direct USB work when the
 * engine was started with no activity (Android Auto, a media button): the channel
 * handlers, the USB hot-plug receiver and the volume observer exist from
 * [FlickEngine.prepare] on and are never torn down by an activity.
 *
 * Only the permission prompt needs a screen. [attachActivity] says one exists;
 * with none, a USB permission that is not already held is never requested (the
 * dialog would appear on a locked phone) and the request is answered as denied.
 *
 * Main thread only.
 */
class DirectUsbController(private val appContext: Context) {
    private val UAC2_CHANNEL = "com.mossapps.flick/uac2"
    private val AUDIO_DEVICE_CHANNEL = "com.mossapps.flick/audio_device"

    // Load the Rust shared library before calling into the native hooks below.
    init {
        System.loadLibrary("rust_lib_flick_player")
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "com.mossapps.flick.USB_PERMISSION"
    }

    private val activityPresence = ActivityPresence()

    /** The permission prompt has a screen to appear on. */
    private val hasActivity: Boolean
        get() = activityPresence.isPresent

    private var processWideRegistrationsDone = false

    private class Uac2PermissionRequest(val deviceName: String, val id: Int) {
        val results = mutableListOf<MethodChannel.Result>()
        val callbacks = mutableListOf<(Boolean) -> Unit>()
    }
    private var pendingUac2Permission: Uac2PermissionRequest? = null
    private var usbPermissionReceiver: BroadcastReceiver? = null
    private var usbHotplugReceiver: BroadcastReceiver? = null
    private val promptedUsbPermissionDeviceNames = mutableSetOf<String>()
    private var uac2DeviceCache: List<Map<String, Any?>>? = null
    private var uac2Channel: MethodChannel? = null
    private var audioDeviceChannel: MethodChannel? = null
    /** The DAC's name while Flick holds it; null when none is claimed or it is being released. */
    val activeDirectUsbDeviceName: String?
        get() = if (directUsbReleasePending) null else directUsbConnection?.deviceName
    private var exclusiveDacModeEnabled = false
    private var directUsbPlaybackActive = false
    private var killIsochronousUsbOnQuit = true
    private var directUsbFocusGain: Int? = null
    private var directUsbAudioFocusRequest: AudioFocusRequest? = null
    private val directUsbAudioFocusChangeListener =
        AudioManager.OnAudioFocusChangeListener { focusChange ->
            Log.i("UAC2", "Direct USB audio focus changed: $focusChange")
        }
    private var cachedMusicVolumeBeforeMute: Int? = null
    private var volumeContentObserver: ContentObserver? = null
    private val volumeObserverHandler = Handler(Looper.getMainLooper())
    private var volumeObserverDebounceRunnable: Runnable? = null
    private var suppressVolumeObserver = false
    private var suppressVolumeObserverRunnable: Runnable? = null
    private var lastObservedVolume: Int = -1
    private var lastObservedMuted: Boolean? = null
    /** Direct USB + UAC2 hardware volume: last native snapshot (not STREAM_MUSIC). */
    private var lastObservedHardwareVolume: Double = Double.NaN
    /** Matches nativeGetRustDirectUsbHardwareMute: -1 unknown, 0/1; Int.MIN_VALUE = unset. */
    private var lastObservedHardwareMute: Int = Int.MIN_VALUE
    private val priorityAnchorService by lazy { PriorityAnchorService(appContext) }
    private var audioDeviceCallback: AudioDeviceCallback? = null

    /** Dart's developer-mode switch. Also read by MainActivity's Bluetooth logging. */
    @Volatile
    var developerMode: Boolean = false
        private set

    // The native route and its USB interface ownership outlive any Activity.
    private var directUsbConnection: DirectUsbConnection? = null
    private var directUsbReleasePending = false
    private val directUsbReleaseHandler = Handler(Looper.getMainLooper())
    private val directUsbReleaseWaiters = mutableListOf<MethodChannel.Result>()
    private var nextUsbPermissionRequestId = 1002

    /**
     * Registers the channels on [flutterEngine] and, once per process, the
     * receivers and observers that feed them. Called from [FlickEngine.prepare],
     * so it runs with or without an activity.
     */
    fun attach(flutterEngine: FlutterEngine) {
        registerChannels(flutterEngine)
        if (processWideRegistrationsDone) return
        processWideRegistrationsDone = true
        // A failure is logged and not thrown: an engine with no activity (the
        // car) must still start, just without direct USB's hot-plug events.
        try {
            registerUsbHotplugReceiver()
            registerVolumeContentObserver()
            registerAudioDeviceCallback()
        } catch (t: Throwable) {
            Log.e("UAC2", "Direct USB receiver registration failed", t)
        }
    }

    /** The activity exists, so a USB permission prompt has a screen to appear on. */
    fun attachActivity(activity: Activity) {
        activityPresence.attach(activity)
    }

    /**
     * The activity is going away. Same as before direct USB moved out of the
     * activity: a permission prompt it still had open is answered as denied, and
     * with "stop USB on quit" on, the DAC goes back to Android (a recreation
     * keeps the DAC's remembered volume, a finish writes it back). The receivers
     * and observers stay: the engine, and a session with no activity, outlive it.
     */
    fun onActivityDestroyed(activity: Activity, isFinishing: Boolean) {
        if (activityPresence.detach(activity)) {
            pendingUac2Permission?.let { finishUac2Permission(it, false) }
        }
        if (killIsochronousUsbOnQuit) {
            // Quitting hands the DAC to Android; a recreation (rotation) does not.
            deactivateDirectUsb(originalVolumeActionOnRelease(isFinishing, isActiveDirectUsbDeviceAttached()))
        }
    }

    private fun registerChannels(flutterEngine: FlutterEngine) {
        // UAC 2.0 USB Host API (Android): list devices and request permission
        uac2Channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, UAC2_CHANNEL)
        uac2Channel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "listDevices" -> {
                    val refresh = call.argument<Boolean>("refresh") ?: false
                    val devices = listUac2Devices(refresh)
                    result.success(devices)
                }
                "requestPermission" -> {
                    val deviceName = call.argument<String>("deviceName")
                    if (deviceName != null) {
                        requestUac2Permission(deviceName, result)
                    } else {
                        result.error("INVALID_ARGUMENT", "deviceName is required", null)
                    }
                }
                "hasPermission" -> {
                    val deviceName = call.argument<String>("deviceName")
                    if (deviceName != null) {
                        val has = hasUac2Permission(deviceName)
                        result.success(has)
                    } else {
                        result.error("INVALID_ARGUMENT", "deviceName is required", null)
                    }
                }
                "getRouteStatus" -> {
                    result.success(
                        getRouteStatus(
                            preferredDeviceName = call.argument<String>("deviceName"),
                            preferredProductName = call.argument<String>("productName"),
                            preferredVendorId = call.argument<Number>("vendorId")?.toInt(),
                            preferredProductId = call.argument<Number>("productId")?.toInt(),
                            preferredSerial = call.argument<String>("serial"),
                        )
                    )
                }
                "getAudioCapabilities" -> {
                    result.success(
                        getAudioCapabilities(
                            preferredDeviceName = call.argument<String>("deviceName"),
                            preferredProductName = call.argument<String>("productName"),
                            preferredVendorId = call.argument<Number>("vendorId")?.toInt(),
                            preferredProductId = call.argument<Number>("productId")?.toInt(),
                            preferredSerial = call.argument<String>("serial"),
                        )
                    )
                }
                "getDirectUsbDiagnostics" -> {
                    result.success(getDirectUsbDiagnostics())
                }
                "setDirectUsbReconnectTarget" -> {
                    val name = call.argument<String>("deviceName")
                    val cancel = call.argument<Boolean>("cancel") == true
                    val allowed = name == null || name == activeDirectUsbDeviceName
                    result.success(allowed && nativeSetRustDirectUsbReconnectTarget(name, cancel))
                }
                "setRouteVolume" -> {
                    val volume = call.argument<Double>("volume")
                    if (volume != null) {
                        result.success(setRouteVolume(
                            volume,
                            hardwareOnly = call.argument<Boolean>("hardwareOnly") == true,
                            expectedDeviceName = call.argument<String>("deviceName"),
                        ))
                    } else {
                        result.error("INVALID_ARGUMENT", "volume is required", null)
                    }
                }
                "getRouteVolume" -> {
                    result.success(getRouteVolume(
                        hardwareOnly = call.argument<Boolean>("hardwareOnly") == true,
                        expectedDeviceName = call.argument<String>("deviceName"),
                    ))
                }
                "setRouteMuted" -> {
                    val muted = call.argument<Boolean>("muted")
                    if (muted != null) {
                        result.success(setRouteMuted(
                            muted,
                            hardwareOnly = call.argument<Boolean>("hardwareOnly") == true,
                            expectedDeviceName = call.argument<String>("deviceName"),
                        ))
                    } else {
                        result.error("INVALID_ARGUMENT", "muted is required", null)
                    }
                }
                "getRouteMuted" -> {
                    result.success(getRouteMuted(
                        hardwareOnly = call.argument<Boolean>("hardwareOnly") == true,
                        expectedDeviceName = call.argument<String>("deviceName"),
                    ))
                }
                "verifyHardwareVolumeHealth" -> {
                    result.success(verifyHardwareVolumeHealth())
                }
                "activateDirectUsb" -> {
                    val deviceName = call.argument<String>("deviceName")
                    val restoreVolume = call.argument<Double>("restoreVolume")
                    if (deviceName != null) {
                        try {
                            result.success(activateDirectUsb(
                                deviceName,
                                restoreVolume,
                                expectedSerial = call.argument<String>("expectedSerial"),
                                expectedVendorId = call.argument<Int>("expectedVendorId"),
                                expectedProductId = call.argument<Int>("expectedProductId"),
                            ))
                        } catch (e: DirectUsbClaimException) {
                            result.error("DIRECT_USB_CLAIM_FAILED", e.message, null)
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "deviceName is required", null)
                    }
                }
                "queueDirectUsbHardwareVolumeRestore" -> {
                    val deviceName = call.argument<String>("deviceName")
                    val restoreVolume = call.argument<Double>("restoreVolume")
                    if (deviceName != null && restoreVolume != null) {
                        if (activeDirectUsbDeviceName != deviceName) {
                            result.success(false)
                        } else {
                            result.success(
                                restoreDirectUsbHardwareVolume(deviceName, restoreVolume)
                            )
                        }
                    } else {
                        result.error(
                            "INVALID_ARGUMENT",
                            "deviceName and restoreVolume are required",
                            null,
                        )
                    }
                }
                "setExclusiveDacMode" -> {
                    val enabled = call.argument<Boolean>("enabled")
                    if (enabled != null) {
                        result.success(setExclusiveDacMode(enabled))
                    } else {
                        result.error("INVALID_ARGUMENT", "enabled is required", null)
                    }
                }
                "setDirectUsbPlaybackActive" -> {
                    val active = call.argument<Boolean>("active")
                    if (active != null) {
                        result.success(setDirectUsbPlaybackActive(active))
                    } else {
                        result.error("INVALID_ARGUMENT", "active is required", null)
                    }
                }
                "setDirectUsbPlaybackFormat" -> {
                    val sampleRate = call.argument<Int>("sampleRate")
                    val bitDepth = call.argument<Int>("bitDepth")
                    val channels = call.argument<Int>("channels")
                    val isDop = call.argument<Boolean>("isDop") ?: false
                    val isNativeDsd = call.argument<Boolean>("isNativeDsd") ?: false
                    if (sampleRate != null && bitDepth != null && channels != null) {
                        result.success(
                            nativeSetRustDirectUsbPlaybackFormat(
                                sampleRate,
                                bitDepth,
                                channels,
                                isDop,
                                isNativeDsd,
                            )
                        )
                    } else {
                        result.error(
                            "INVALID_ARGUMENT",
                            "sampleRate, bitDepth, and channels are required",
                            null,
                        )
                    }
                }
                "clearDirectUsbPlaybackFormat" -> {
                    result.success(nativeSetRustDirectUsbPlaybackFormat(0, 0, 0, false, false))
                }
                "deactivateDirectUsb" -> {
                    // Android gets the DAC unless Dart is about to claim it again.
                    val handBack = call.argument<Boolean>("handBack") ?: true
                    val stopped = deactivateDirectUsb(originalVolumeActionOnRelease(handBack, isActiveDirectUsbDeviceAttached()))
                    if (directUsbReleasePending) {
                        // Don't start a managed fallback while Flick still owns the DAC.
                        directUsbReleaseWaiters += result
                    } else {
                        result.success(stopped)
                    }
                }
                "setKillIsochronousUsbOnQuit" -> {
                    val enabled = call.argument<Boolean>("enabled") ?: true
                    killIsochronousUsbOnQuit = enabled
                    result.success(true)
                }
                "setDeveloperMode" -> {
                    val enabled = call.argument<Boolean>("enabled") ?: false
                    developerMode = enabled
                    nativeSetRustDeveloperMode(enabled)
                    result.success(true)
                }
                "markDirectUsbFallback" -> {
                    val reason = call.argument<String>("reason")
                    result.success(nativeMarkRustDirectUsbFallback(reason))
                }
                "startPriorityAnchor" -> {
                    priorityAnchorService.start()
                    result.success(true)
                }
                "stopPriorityAnchor" -> {
                    priorityAnchorService.stop()
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }

        audioDeviceChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            AUDIO_DEVICE_CHANNEL,
        )
        audioDeviceChannel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "getPlaybackDeviceInfo" -> result.success(getPlaybackDeviceInfo())
                else -> result.notImplemented()
            }
        }
    }

    // ========== UAC 2.0 USB Host (Android) ==========

    private fun listUac2Devices(refresh: Boolean = false): List<Map<String, Any?>> {
        if (!refresh && uac2DeviceCache != null) {
            return uac2DeviceCache!!
        }
        
        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbManager == null) {
            android.util.Log.e("UAC2", "USB service unavailable")
            return emptyList()
        }
        
        val routeLabelHint = currentRouteLabelHint()
        val deviceList = usbManager.deviceList ?: return emptyList()
        val result = mutableListOf<Map<String, Any?>>()
        
        for (device in deviceList.values) {
            try {
                if (!isLikelyUsbAudioDevice(device, routeLabelHint)) continue

                // Extract strings (available without opening device on API 21+)
                val productName = device.productName ?: "USB Audio Device"
                val manufacturer = device.manufacturerName ?: ""
                val serial = safeUsbSerial(device) ?: device.deviceName
                
                result.add(mapOf(
                    "deviceName" to device.deviceName,
                    "vendorId" to device.vendorId,
                    "productId" to device.productId,
                    "productName" to productName,
                    "manufacturer" to manufacturer,
                    "serial" to serial,
                    "hasPermission" to usbManager.hasPermission(device),
                ))
            } catch (e: Exception) {
                android.util.Log.w("UAC2", "Failed to process device ${device.deviceName}: ${e.message}")
                // Continue with other devices
            }
        }

        if (result.isEmpty() && !routeLabelHint.isNullOrBlank()) {
            Log.i(
                "UAC2",
                "No UsbManager DAC candidates matched route \"$routeLabelHint\". Inventory: " +
                    deviceList.values.joinToString { device -> usbDeviceDebugSummary(device) },
            )
        }
        if (result.isNotEmpty()) {
            Log.i(
                "UAC2",
                "UsbManager DAC candidates: " +
                    result.joinToString { item ->
                        "${item["productName"] ?: item["deviceName"]}@${item["deviceName"]} permission=${item["hasPermission"]}"
                    },
            )
        }
        
        uac2DeviceCache = result
        return result
    }

    private fun isUac2Device(device: UsbDevice): Boolean {
        return try {
            if (device.deviceClass == UsbConstants.USB_CLASS_AUDIO) {
                return true
            }

            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    (iface.interfaceSubclass == 0x01 ||
                        iface.interfaceSubclass == 0x02 ||
                        iface.interfaceSubclass == 0x03)
                ) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            android.util.Log.w("UAC2", "Error checking device: ${e.message}")
            false
        }
    }

    private fun deviceHasIsochronousEndpoint(device: UsbDevice): Boolean {
        return try {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                for (endpointIndex in 0 until iface.endpointCount) {
                    val endpoint = iface.getEndpoint(endpointIndex)
                    if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_ISOC) {
                        return true
                    }
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun normalizeUsbText(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return value
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    private fun normalizedUsbTokens(value: String?): Set<String> {
        return normalizeUsbText(value)
            .split(' ')
            .filter { it.length >= 3 }
            .toSet()
    }

    private fun routeLabelMatchesUsbDevice(routeLabelHint: String?, device: UsbDevice): Boolean {
        if (routeLabelHint.isNullOrBlank()) {
            return false
        }

        val normalizedRoute = normalizeUsbText(routeLabelHint)
        val routeTokens = normalizedUsbTokens(routeLabelHint)
        if (normalizedRoute.isBlank() || routeTokens.isEmpty()) {
            return false
        }

        val candidateTexts = listOfNotNull(
            device.productName,
            device.manufacturerName,
            "${device.manufacturerName ?: ""} ${device.productName ?: ""}",
            device.deviceName,
        )

        return candidateTexts.any { candidate ->
            val normalizedCandidate = normalizeUsbText(candidate)
            val candidateTokens = normalizedUsbTokens(candidate)
            candidateTokens.isNotEmpty() &&
                (
                    routeTokens.intersect(candidateTokens).size >= 2 ||
                        normalizedRoute.contains(normalizedCandidate) ||
                        normalizedCandidate.contains(normalizedRoute)
                )
        }
    }

    private fun looksLikeUsbAudioName(device: UsbDevice): Boolean {
        val haystack = normalizeUsbText(
            listOfNotNull(
                device.productName,
                device.manufacturerName,
            ).joinToString(" "),
        )
        if (haystack.isBlank()) {
            return false
        }

        val keywords = listOf(
            "audio",
            "usb audio",
            "dac",
            "headset",
            "headphone",
            "dongle",
            "amp",
            "hi-fi",
            "hifi",
            "sound",
            "speaker",
            "c-media",
            "realtek",
            "conexant",
            "pnp",
            "output",
            "cx",
            "cm108",
            "hs",
        )
        return keywords.any { keyword -> haystack.contains(keyword) }
    }

    private fun isLikelyUsbAudioDevice(
        device: UsbDevice,
        routeLabelHint: String? = currentRouteLabelHint(),
    ): Boolean {
        if (isUac2Device(device)) {
            return true
        }

        if (routeLabelMatchesUsbDevice(routeLabelHint, device)) {
            return true
        }

        if (deviceHasIsochronousEndpoint(device) && looksLikeUsbAudioName(device)) {
            return true
        }

        if (deviceHasIsochronousEndpoint(device) && hasUsbAudioOutputViaDeviceInfo()) {
            return true
        }

        return false
    }

    private fun hasUsbAudioOutputViaDeviceInfo(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    private fun usbDeviceDebugSummary(device: UsbDevice): String {
        val label = listOfNotNull(device.manufacturerName, device.productName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "unknown" }
        return "$label@${device.deviceName}[${device.vendorId}:${device.productId}] " +
            "audioClass=${isUac2Device(device)} iso=${deviceHasIsochronousEndpoint(device)}"
    }

    private fun currentRouteLabelHint(): String? {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        return describeCurrentOutputRoute(audioManager)["routeLabel"] as? String
    }

    private fun hasUac2Permission(deviceName: String): Boolean {
        return try {
            val usbManager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
            val device = usbManager.deviceList?.get(deviceName) ?: return false
            usbManager.hasPermission(device)
        } catch (e: Exception) {
            android.util.Log.e("UAC2", "Error checking permission: ${e.message}")
            false
        }
    }

    private fun requestUac2Permission(deviceName: String, result: MethodChannel.Result) {
        requestUac2PermissionInternal(deviceName, result = result)
    }

    private fun requestUac2PermissionInternal(
        deviceName: String,
        result: MethodChannel.Result? = null,
        callback: ((Boolean) -> Unit)? = null,
    ) {
        try {
            val usbManager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager
            if (usbManager == null) {
                promptedUsbPermissionDeviceNames.remove(deviceName)
                result?.error("UAC2_ERROR", "USB service unavailable", null)
                callback?.invoke(false)
                return
            }
            
            val device = usbManager.deviceList?.get(deviceName)
            if (device == null) {
                promptedUsbPermissionDeviceNames.remove(deviceName)
                result?.error("NOT_FOUND", "USB device not found: $deviceName", null)
                callback?.invoke(false)
                return
            }
            
            if (usbManager.hasPermission(device)) {
                result?.success(true)
                callback?.invoke(true)
                return
            }
            
            // The attach receiver may have opened the same Android prompt
            // before Flutter requested permission to verify the DAC's serial.
            pendingUac2Permission?.let { pending ->
                if (pending.deviceName == deviceName) {
                    result?.let { pending.results.add(it) }
                    callback?.let { pending.callbacks.add(it) }
                } else {
                    result?.error("USB_PERMISSION_PENDING", "Another USB permission request is pending", null)
                    callback?.invoke(false)
                }
                return
            }
            if (!hasActivity) {
                // No screen to show the prompt on (the car started the engine, the
                // phone may be locked). Only a permission already held works here.
                Log.i("UAC2", "Not requesting USB permission for $deviceName: no activity to show the prompt")
                result?.error(
                    "PERMISSION_DENIED",
                    "USB permission unavailable for $deviceName: it is not granted and no screen is open to ask",
                    null,
                )
                callback?.invoke(false)
                return
            }
            val request = Uac2PermissionRequest(deviceName, nextUsbPermissionRequestId++)
            result?.let { request.results.add(it) }
            callback?.let { request.callbacks.add(it) }
            pendingUac2Permission = request
            val permissionIntent = PendingIntent.getBroadcast(
                appContext,
                request.id,
                Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
                    .putExtra("requestId", request.id),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ONE_SHOT,
            )
            usbPermissionReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == ACTION_USB_PERMISSION &&
                        intent.getIntExtra("requestId", -1) == request.id &&
                        pendingUac2Permission === request
                    ) {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        }
                        val allowed = granted && device?.deviceName == request.deviceName &&
                            usbManager.deviceList[request.deviceName]?.let { usbManager.hasPermission(it) } == true
                        finishUac2Permission(request, allowed)
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION))
            }
            usbManager.requestPermission(device, permissionIntent)
        } catch (e: Exception) {
            promptedUsbPermissionDeviceNames.remove(deviceName)
            android.util.Log.e("UAC2", "Error requesting permission: ${e.message}")
            val pending = pendingUac2Permission
            if (pending?.deviceName == deviceName) {
                finishUac2Permission(pending, false, "UAC2_ERROR")
            } else {
                result?.error("UAC2_ERROR", "Failed to request permission: ${e.message}", null)
                callback?.invoke(false)
            }
        }
    }

    private fun finishUac2Permission(
        request: Uac2PermissionRequest,
        granted: Boolean,
        errorCode: String = "PERMISSION_DENIED",
    ) {
        if (pendingUac2Permission !== request) return
        pendingUac2Permission = null
        appContext.unregisterReceiverSafely(usbPermissionReceiver)
        usbPermissionReceiver = null
        if (granted) uac2DeviceCache = null
        request.results.forEach {
            if (granted) it.success(true)
            else it.error(errorCode, "USB permission unavailable for ${request.deviceName}", null)
        }
        request.callbacks.forEach { it(granted) }
    }

    private fun activateDirectUsb(
        deviceName: String,
        restoreVolume: Double? = null,
        expectedSerial: String? = null,
        expectedVendorId: Int? = null,
        expectedProductId: Int? = null,
    ): Boolean {
        return try {
            val usbManager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
            val device = usbManager.deviceList?.get(deviceName) ?: return false
            if (!usbManager.hasPermission(device)) {
                Log.e("UAC2", "Cannot activate direct USB without permission for $deviceName")
                return false
            }
            if (expectedSerial != null &&
                (device.vendorId != expectedVendorId ||
                    device.productId != expectedProductId ||
                    safeUsbSerial(device) != expectedSerial)
            ) {
                throw DirectUsbClaimException("The previously playing USB DAC could not be verified.")
            }

            if (activeDirectUsbDeviceName == deviceName) {
                // Auto-engage can enable bit-perfect after DSP registered the DAC.
                // Do not skip its restore just because the connection is reusable.
                return restoreDirectUsbHardwareVolume(deviceName, restoreVolume)
            }
            if (directUsbConnection != null &&
                !deactivateDirectUsb(originalVolumeActionOnRelease(true, isActiveDirectUsbDeviceAttached()))
            ) {
                throw DirectUsbClaimException("The previous USB DAC is still being released. Retry in a moment.")
            }
            val connection = usbManager.openDevice(device) ?: run {
                Log.e("UAC2", "Failed to open USB device for direct playback: $deviceName")
                return false
            }
            val fileDescriptor = connection.fileDescriptor
            if (fileDescriptor < 0) {
                connection.close()
                Log.e("UAC2", "USB device returned invalid file descriptor: $deviceName")
                return false
            }
            val registration = DirectUsbConnection(device, connection)
            val registered = registration.claims.register(
                selectInterfaces = { directUsbPlaybackInterfaces(connection, device) },
                registerNative = { interfaces ->
                    val ids = interfaces.map { it.id }.toIntArray()
                    nativeRegisterRustDirectUsbDevice(
                        fileDescriptor,
                        device.vendorId,
                        device.productId,
                        registration.productName,
                        deviceName,
                        ids,
                    ).also { accepted ->
                        if (accepted) {
                            Log.i("UAC2", "[USB] Force-claimed interfaces ${ids.toList()} for $deviceName")
                        }
                    }
                },
            )
            if (!registered) {
                Log.e("UAC2", "Rust rejected direct USB registration for $deviceName")
                return false
            }
            directUsbConnection = registration

            if (!restoreDirectUsbHardwareVolume(deviceName, restoreVolume)) {
                deactivateDirectUsb()
                return false
            }
            if (!nativeSetRustDirectUsbLockEnabled(true)) {
                Log.e("UAC2", "Rust direct USB idle lock failed for $deviceName")
                deactivateDirectUsb()
                return false
            }
            updateDirectUsbAudioFocus()
            Log.i("UAC2", "Direct USB DAC activated for $deviceName")
            true
        } catch (e: DirectUsbClaimException) {
            Log.w("UAC2", e.message ?: "USB audio interface claim failed")
            throw e
        } catch (e: Exception) {
            Log.e("UAC2", "Failed to activate direct USB DAC: ${e.message}", e)
            if (activeDirectUsbDeviceName == deviceName) deactivateDirectUsb()
            false
        }
    }

    private fun restoreDirectUsbHardwareVolume(deviceName: String, volume: Double?): Boolean {
        if (volume == null) return true
        if (!volume.isFinite() || volume !in 0.0..1.0) {
            Log.e("UAC2", "Invalid hardware volume restore value: $volume")
            return false
        }
        if (!nativeQueueRustDirectUsbHardwareVolumeRestore(volume)) {
            Log.e("UAC2", "Rust could not restore hardware volume for $deviceName")
            return false
        }
        return true
    }

    private class DirectUsbClaimException(message: String) : Exception(message)

    private class DirectUsbConnection(
        device: UsbDevice,
        connection: UsbDeviceConnection,
    ) {
        val deviceName = device.deviceName
        val productName = device.productName?.takeIf { it.isNotBlank() } ?: "USB DAC"
        val claims = DirectUsbInterfaceClaims<UsbInterface>(
            forceClaim = { usbInterface ->
                if (!connection.claimInterface(usbInterface, true)) {
                    throw DirectUsbClaimException(
                        "Could not claim USB audio interface ${usbInterface.id} on $productName. " +
                            "Try enabling Disable USB audio routing in Android Developer options, then reconnect the DAC.",
                    )
                }
            },
            releaseInterface = { usbInterface ->
                try {
                    if (usbInterface.interfaceSubclass == 0x02 && usbInterface.alternateSetting == 0 &&
                        !connection.setInterface(usbInterface)
                    ) {
                        Log.w("UAC2", "[USB] Failed to reset interface ${usbInterface.id} on $deviceName")
                    }
                } catch (e: Exception) {
                    Log.w("UAC2", "[USB] Failed to reset interface ${usbInterface.id} on $deviceName", e)
                }
                try {
                    // Unlike close(), releaseInterface also asks Android to rebind the driver.
                    if (!connection.releaseInterface(usbInterface)) {
                        Log.w("UAC2", "[USB] Failed to release interface ${usbInterface.id} on $deviceName")
                    }
                } catch (e: Exception) {
                    Log.w("UAC2", "[USB] Failed to release interface ${usbInterface.id} on $deviceName", e)
                }
            },
            closeConnection = connection::close,
        )
    }

    private fun directUsbPlaybackInterfaces(
        connection: UsbDeviceConnection,
        device: UsbDevice,
    ): List<UsbInterface> {
        val configurations = (0 until device.configurationCount).map(device::getConfiguration)
        val configuration = configurations.singleOrNull() ?: run {
            val value = ByteArray(1)
            // GET_CONFIGURATION (standard device request): interface IDs are
            // only meaningful within the currently selected configuration.
            val read = connection.controlTransfer(UsbConstants.USB_DIR_IN, 0x08, 0, 0, value, 1, 500)
            configurations.firstOrNull { read == 1 && it.id == (value[0].toInt() and 0xff) }
                ?: throw DirectUsbClaimException("Could not read the active USB configuration for ${device.deviceName}.")
        }

        val audioInterfaces = (0 until configuration.interfaceCount)
            .map(configuration::getInterface)
            .filter { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO }
        val control = audioInterfaces.filter { it.interfaceSubclass == 0x01 }
        val streaming = audioInterfaces.filter { it.interfaceSubclass == 0x02 }.groupBy { it.id }
            .values.mapNotNull { alternates ->
                val output = alternates.firstOrNull { intf ->
                    (0 until intf.endpointCount).any { index ->
                        val endpoint = intf.getEndpoint(index)
                        endpoint.direction == UsbConstants.USB_DIR_OUT &&
                            endpoint.type == UsbConstants.USB_ENDPOINT_XFER_ISOC
                    }
                } ?: return@mapNotNull null
                alternates.firstOrNull { it.alternateSetting == 0 } ?: output
            }
        if (control.isEmpty() || streaming.isEmpty()) {
            throw DirectUsbClaimException("No USB audio control and playback interfaces found for ${device.deviceName}.")
        }
        return (control + streaming).distinctBy { it.id }
    }

    fun handleUsbAttachIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            return
        }

        val attachedDevice: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        }
        maybeRequestPermissionForAttachedDevice(attachedDevice)
    }

    private fun maybeRequestPermissionForAttachedDevice(
        device: UsbDevice?,
        reason: String = "attach broadcast",
    ) {
        if (device == null || !isLikelyUsbAudioDevice(device)) {
            return
        }

        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        if (usbManager.hasPermission(device)) {
            return
        }
        if (pendingUac2Permission != null) {
            return
        }
        // Leave the device unprompted: the activity asks when it opens.
        if (!hasActivity) {
            return
        }
        if (!promptedUsbPermissionDeviceNames.add(device.deviceName)) {
            return
        }

        Log.i(
            "UAC2",
            "Requesting USB permission for DAC candidate: " +
                "${device.productName ?: device.deviceName} ($reason)",
        )
        requestUac2PermissionInternal(
            device.deviceName,
            callback = { granted ->
                if (granted) {
                    uac2DeviceCache = null
                    uac2Channel?.invokeMethod("onDeviceAttached", null)
                    audioDeviceChannel?.invokeMethod("onPlaybackDevicesChanged", null)
                }
            },
        )
    }

    fun maybeRequestPermissionForConnectedUsbAudioDevices(reason: String) {
        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val routeLabelHint = currentRouteLabelHint()
        val candidates = usbManager.deviceList.values.filter { device ->
            isLikelyUsbAudioDevice(device, routeLabelHint)
        }
        if (candidates.isEmpty()) {
            return
        }

        val pendingCandidate = candidates.firstOrNull { device ->
            !usbManager.hasPermission(device) &&
                !promptedUsbPermissionDeviceNames.contains(device.deviceName)
        } ?: return

        maybeRequestPermissionForAttachedDevice(
            pendingCandidate,
            reason = reason,
        )
    }

    private fun setExclusiveDacMode(enabled: Boolean): Boolean {
        exclusiveDacModeEnabled = enabled
        val rustUpdated = if (activeDirectUsbDeviceName != null) {
            nativeSetRustDirectUsbLockEnabled(enabled)
        } else {
            true
        }
        updateDirectUsbAudioFocus()
        return rustUpdated
    }

    private fun setDirectUsbPlaybackActive(active: Boolean): Boolean {
        if (!active && !directUsbPlaybackActive) {
            return true
        }
        directUsbPlaybackActive = active
        updateDirectUsbAudioFocus()
        return true
    }

    private fun isActiveDirectUsbDeviceAttached(): Boolean = currentActiveDirectUsbDevice() != null

    /**
     * Lets go of the DAC. [originalVolume] says what native does with the level
     * the DAC had before Flick changed it, as it clears the registration: write
     * it back when Android gets the DAC, forget it when the DAC is gone, keep it
     * when Flick holds on or takes the DAC straight back (the default, which is
     * also what every failed activation wants).
     */
    private fun deactivateDirectUsb(originalVolume: OriginalVolumeAction = OriginalVolumeAction.KEEP): Boolean {
        return try {
            if (directUsbReleasePending) return false
            directUsbPlaybackActive = false
            nativeSetRustDirectUsbLockEnabled(false)
            nativeSetRustDirectUsbOriginalVolumeAction(originalVolume.code)
            nativeClearRustDirectUsbPlayback()
            val registration = directUsbConnection
            val stopped = nativeWaitRustDirectUsbSessionStopped(0)
            val released = if (registration == null) {
                stopped
            } else if (stopped) {
                finishDirectUsbRelease(registration)
            } else {
                directUsbReleasePending = true
                Log.i("UAC2", "[USB] Waiting for transfer shutdown before releasing ${registration.deviceName}")
                finishDeferredDirectUsbRelease(registration)
                false
            }
            updateDirectUsbAudioFocus()
            released
        } catch (e: Exception) {
            Log.e("UAC2", "Failed to deactivate direct USB DAC: ${e.message}", e)
            false
        }
    }

    private fun updateDirectUsbAudioFocus() {
        if (shouldHoldDirectUsbAudioFocus()) {
            if (directUsbFocusGain == null) {
                Log.i("UAC2", "[USB] Requesting audio focus for direct USB playback")
                requestDirectUsbAudioFocus(AudioManager.AUDIOFOCUS_GAIN)
            }
        } else {
            if (directUsbFocusGain != null || directUsbAudioFocusRequest != null) {
                Log.i("UAC2", "[USB] Releasing direct USB audio focus")
            }
            abandonDirectUsbAudioFocus()
        }
    }

    private fun shouldHoldDirectUsbAudioFocus(): Boolean {
        return activeDirectUsbDeviceName != null &&
            (directUsbPlaybackActive || nativeIsRustDirectUsbSessionActive())
    }

    private fun requestDirectUsbAudioFocus(focusGain: Int) {
        if (directUsbFocusGain == focusGain) {
            return
        }

        abandonDirectUsbAudioFocus()
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(focusGain)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(directUsbAudioFocusChangeListener)
                .setAcceptsDelayedFocusGain(false)
                .build()
            directUsbAudioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                directUsbAudioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                focusGain,
            )
        }

        if (granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            directUsbFocusGain = focusGain
        } else {
            directUsbAudioFocusRequest = null
            directUsbFocusGain = null
            Log.w("UAC2", "Direct USB audio focus request denied (gain=$focusGain)")
        }
    }

    private fun abandonDirectUsbAudioFocus() {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            directUsbAudioFocusRequest?.let { request ->
                audioManager.abandonAudioFocusRequest(request)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(directUsbAudioFocusChangeListener)
        }

        directUsbAudioFocusRequest = null
        directUsbFocusGain = null
    }

    private fun finishDirectUsbRelease(registration: DirectUsbConnection): Boolean {
        val released = try {
            registration.claims.release()
            true
        } catch (e: Exception) {
            Log.e("UAC2", "Failed to close direct USB DAC: ${e.message}", e)
            false
        } finally {
            directUsbConnection = null
            directUsbReleasePending = false
        }
        val waiters = directUsbReleaseWaiters.toList()
        directUsbReleaseWaiters.clear()
        waiters.forEach { it.success(released) }
        return released
    }

    private fun finishDeferredDirectUsbRelease(registration: DirectUsbConnection) {
        directUsbReleaseHandler.postDelayed({
            if (nativeWaitRustDirectUsbSessionStopped(0)) {
                finishDirectUsbRelease(registration)
            } else {
                finishDeferredDirectUsbRelease(registration)
            }
        }, 25)
    }

    private fun getRouteStatus(
        preferredDeviceName: String? = null,
        preferredProductName: String? = null,
        preferredVendorId: Int? = null,
        preferredProductId: Int? = null,
        preferredSerial: String? = null,
    ): Map<String, Any?> {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val directUsbRegistered = activeDirectUsbDeviceName != null
        val baseRoute = if (directUsbRegistered) {
            cachedDirectUsbCapabilityRoute()
        } else {
            describeCurrentOutputRoute(audioManager).toMutableMap()
        }
        val preferredUsbDevice = findPreferredUsbAudioDevice(
            preferredDeviceName = preferredDeviceName,
            preferredProductName = preferredProductName,
            preferredVendorId = preferredVendorId,
            preferredProductId = preferredProductId,
            preferredSerial = preferredSerial,
        )

        val hasSystemVolumeControl = audioManager != null && !audioManager.isVolumeFixed
        val hasDirectUsbHardwareVolume = hasDirectUsbHardwareVolume()
        val hasVolumeControl = if (directUsbRegistered) {
            hasDirectUsbHardwareVolume
        } else {
            hasDirectUsbHardwareVolume || hasSystemVolumeControl
        }
        val volumeControlWritable = when {
            hasDirectUsbHardwareVolume -> true
            hasVolumeControl -> true
            else -> false
        }
        baseRoute["hasVolumeControl"] = hasVolumeControl
        baseRoute["volumeControlWritable"] = volumeControlWritable
        baseRoute["volumeMode"] = when {
            hasDirectUsbHardwareVolume -> "hardware"
            hasVolumeControl -> "system"
            else -> "unavailable"
        }
        baseRoute["volume"] = if (hasVolumeControl) {
            if (hasDirectUsbHardwareVolume) {
                nativeGetRustDirectUsbHardwareVolume()
            } else {
                getRouteVolume()
            }
        } else null
        baseRoute["muted"] = when {
            hasDirectUsbHardwareVolume -> when (nativeGetRustDirectUsbHardwareMute()) {
                1 -> true
                0 -> false
                else -> null
            }
            hasVolumeControl -> getRouteMuted()
            else -> null
        }
        baseRoute["preferredUsbDeviceDetected"] = preferredUsbDevice != null
        baseRoute["preferredUsbDeviceName"] = preferredUsbDevice?.deviceName
        baseRoute["preferredUsbProductName"] = preferredUsbDevice?.productName
        baseRoute["directUsbRegistered"] = directUsbRegistered
        baseRoute["directUsbDeviceName"] = activeDirectUsbDeviceName

        return baseRoute
    }

    private fun hasDirectUsbHardwareVolume(): Boolean {
        return activeDirectUsbDeviceName != null && nativeHasRustDirectUsbHardwareVolume()
    }

    private fun isDirectUsbSessionFrozen(): Boolean {
        return activeDirectUsbDeviceName != null && shouldHoldDirectUsbAudioFocus()
    }

    private fun currentActiveDirectUsbDevice(): UsbDevice? {
        val deviceName = activeDirectUsbDeviceName ?: return null
        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        return usbManager.deviceList?.get(deviceName)
    }

    private fun cachedDirectUsbCapabilityRoute(): MutableMap<String, Any?> {
        val activeDevice = currentActiveDirectUsbDevice()
        val productName = directUsbConnection?.productName ?: "USB DAC"
        return activeDevice?.let { buildUsbRouteMap(it).toMutableMap() } ?: mutableMapOf(
            "routeType" to "usb",
            "routeLabel" to productName,
            "isExternal" to true,
            "productName" to productName,
            "manufacturer" to "USB Audio",
            "deviceName" to activeDirectUsbDeviceName,
        )
    }

    private fun findPreferredUsbAudioDevice(
        preferredDeviceName: String? = null,
        preferredProductName: String? = null,
        preferredVendorId: Int? = null,
        preferredProductId: Int? = null,
        preferredSerial: String? = null,
    ): UsbDevice? {
        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        val routeLabelHint = preferredProductName ?: currentRouteLabelHint()
        val candidates = usbManager.deviceList.values.filter { device ->
            isLikelyUsbAudioDevice(device, routeLabelHint)
        }
        if (candidates.isEmpty()) return null

        return candidates.firstOrNull { device ->
            preferredDeviceName != null && device.deviceName == preferredDeviceName
        } ?: candidates.firstOrNull { device ->
            preferredSerial != null &&
                (safeUsbSerial(device) == preferredSerial || device.deviceName == preferredSerial)
        } ?: candidates.firstOrNull { device ->
            preferredVendorId != null &&
                preferredProductId != null &&
                device.vendorId == preferredVendorId &&
                device.productId == preferredProductId
        } ?: candidates.firstOrNull { device ->
            preferredProductName != null &&
                !device.productName.isNullOrBlank() &&
                device.productName == preferredProductName
        } ?: candidates.firstOrNull()
    }

    private fun currentBestOutputDevice(audioManager: AudioManager?): AudioDeviceInfo? {
        if (audioManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null
        }

        return audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .minByOrNull { device -> outputRoutePriority(device.type) }
    }

    private fun describeCurrentOutputRoute(audioManager: AudioManager?): Map<String, Any?> {
        val bestOutput = currentBestOutputDevice(audioManager)
        if (bestOutput != null) {
            val routeType = routeTypeForAudioDevice(bestOutput.type)
            val label = bestOutput.productName
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: defaultRouteLabel(routeType)
            return mutableMapOf(
                "routeType" to routeType,
                "routeLabel" to label,
                "isExternal" to isExternalRouteType(routeType),
                "productName" to defaultProductName(routeType, label),
                "manufacturer" to Build.MANUFACTURER,
            )
        }

        return mutableMapOf(
            "routeType" to "internal",
            "routeLabel" to "Built-in output",
            "isExternal" to false,
            "productName" to "Device DAC",
            "manufacturer" to Build.MANUFACTURER,
        )
    }

    private fun isPhysicalAudioOutput(device: AudioDeviceInfo): Boolean {
        return when (device.type) {
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_AUX_LINE,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC,
            AudioDeviceInfo.TYPE_DOCK -> true
            else -> false
        }
    }

    private fun getPlaybackDeviceInfo(): Map<String, Any?> {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val outputs = if (audioManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        } else {
            emptyList()
        }
        val hasUsbAudioRoute = outputs.any { device ->
            device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        val physicalOutputs = outputs.filter { device -> isPhysicalAudioOutput(device) }
        val preferredPhysicalOutput = physicalOutputs.minByOrNull { device ->
            outputRoutePriority(device.type)
        }
        val hasWiredAudioRoute = physicalOutputs.any { device ->
            routeTypeForAudioDevice(device.type) == "wired"
        }
        // Force-claim can remove the DAC from AudioManager while it stays attached.
        val directUsbDevice = currentActiveDirectUsbDevice()
        val route = directUsbDevice?.let(::buildUsbRouteMap) ?: describeCurrentOutputRoute(audioManager)
        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager
        val attachedUac2Device = directUsbDevice ?: usbManager
            ?.deviceList
            ?.values
            ?.firstOrNull { device -> isLikelyUsbAudioDevice(device, route["routeLabel"] as? String) }
        val hasAttachedUac2Device = attachedUac2Device != null
        val physicalOutputType = if (directUsbDevice != null) "usb" else preferredPhysicalOutput
            ?.let { device -> routeTypeForAudioDevice(device.type) }
            ?: if (hasAttachedUac2Device) "usb" else null
        val physicalOutputId = directUsbDevice?.deviceName
            ?: preferredPhysicalOutput?.id?.toString()
            ?: attachedUac2Device?.deviceName
        // Android can expose (or remove) its managed audio route after the USB
        // broadcast. Keep the physical connection identity independent of that
        // route's AudioDeviceInfo.id, including when Flick owns the device.
        val physicalOutputConnectionId = attachedUac2Device?.deviceName
            ?: preferredPhysicalOutput?.id?.toString()
        val physicalOutputLabel = directUsbDevice?.productName?.takeIf { it.isNotBlank() }
            ?: preferredPhysicalOutput?.productName?.toString()?.takeIf { it.isNotBlank() }
            ?: attachedUac2Device?.productName
                ?.takeIf { it.isNotBlank() }
            ?: (route["routeLabel"] as? String)

        return mapOf(
            "hasUsbDac" to (hasUsbAudioRoute || hasAttachedUac2Device),
            "hasUsbAudioRoute" to hasUsbAudioRoute,
            "hasAttachedUac2Device" to hasAttachedUac2Device,
            "hasWiredAudioRoute" to hasWiredAudioRoute,
            "hasPhysicalOutput" to (physicalOutputs.isNotEmpty() || hasAttachedUac2Device),
            "physicalOutputType" to physicalOutputType,
            "physicalOutputId" to physicalOutputId,
            "physicalOutputConnectionId" to physicalOutputConnectionId,
            "physicalOutputLabel" to physicalOutputLabel,
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "routeType" to route["routeType"],
            "routeLabel" to route["routeLabel"],
        )
    }

    private fun buildUsbRouteMap(device: UsbDevice): Map<String, Any?> {
        val productName = device.productName?.takeIf { it.isNotBlank() } ?: "USB DAC"
        val manufacturer = device.manufacturerName?.takeIf { it.isNotBlank() } ?: "USB Audio"
        return mutableMapOf(
            "routeType" to "usb",
            "routeLabel" to productName,
            "isExternal" to true,
            "productName" to productName,
            "manufacturer" to manufacturer,
            "deviceName" to device.deviceName,
            "vendorId" to device.vendorId,
            "productId" to device.productId,
            "serial" to (safeUsbSerial(device) ?: device.deviceName),
        )
    }

    private fun getDirectUsbDiagnostics(): Map<String, Any?> {
        return mapOf(
            "activeDirectUsbDeviceName" to activeDirectUsbDeviceName,
            "directUsbRegistered" to (activeDirectUsbDeviceName != null),
            "exclusiveDacModeEnabled" to exclusiveDacModeEnabled,
            "directUsbPlaybackActive" to directUsbPlaybackActive,
            "audioFocusHeld" to (directUsbFocusGain != null),
            "audioFocusGain" to directUsbFocusGain,
            "rustAudioStateJson" to nativeGetRustAudioDebugStateJson(),
        )
    }

    private fun getAudioCapabilities(
        preferredDeviceName: String? = null,
        preferredProductName: String? = null,
        preferredVendorId: Int? = null,
        preferredProductId: Int? = null,
        preferredSerial: String? = null,
    ): Map<String, Any?> {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val freezeDirectUsbSession = isDirectUsbSessionFrozen()
        val routeStatus = if (freezeDirectUsbSession) {
            cachedDirectUsbCapabilityRoute().apply {
                put("directUsbRegistered", true)
                put("preferredUsbDeviceDetected", true)
            }
        } else {
            getRouteStatus(
                preferredDeviceName = preferredDeviceName,
                preferredProductName = preferredProductName,
                preferredVendorId = preferredVendorId,
                preferredProductId = preferredProductId,
                preferredSerial = preferredSerial,
            )
        }
        val routeType = if (freezeDirectUsbSession) {
            "usb"
        } else {
            routeStatus["routeType"] as? String ?: "unknown"
        }
        val preferredUsbDevice = if (freezeDirectUsbSession) {
            null
        } else {
            findPreferredUsbAudioDevice(
                preferredDeviceName = preferredDeviceName,
                preferredProductName = preferredProductName,
                preferredVendorId = preferredVendorId,
                preferredProductId = preferredProductId,
                preferredSerial = preferredSerial,
            )
        }
        val bestOutput = currentBestOutputDevice(audioManager)
        val supportedSampleRates = bestOutput
            ?.sampleRates
            ?.filter { it > 0 }
            ?.distinct()
            ?.sorted()
            ?: emptyList()
        val nativeSampleRate = audioManager
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()
        val maxSupportedSampleRate = listOfNotNull(
            supportedSampleRates.maxOrNull(),
            nativeSampleRate,
        ).maxOrNull()
        val hasProAudio = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO)
        val hasLowLatency = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY)
        val hiResInternal = (routeType == "internal" || routeType == "wired") &&
            ((maxSupportedSampleRate ?: 0) > 48_000 || (hasProAudio && (nativeSampleRate ?: 0) > 48_000))

        val capabilities = mutableListOf<String>()
        if (routeType == "usb" || preferredUsbDevice != null) {
            capabilities += "usbDac"
        }
        if (hiResInternal) {
            capabilities += "hiResInternal"
        }
        if (capabilities.isEmpty()) {
            capabilities += "standard"
        }

        return mutableMapOf(
            "capabilities" to capabilities,
            "routeType" to routeType,
            "routeLabel" to routeStatus["routeLabel"],
            "maxSupportedSampleRate" to maxSupportedSampleRate,
            "nativeSampleRate" to nativeSampleRate,
            "supportedSampleRates" to supportedSampleRates,
            "hasProAudio" to hasProAudio,
            "hasLowLatency" to hasLowLatency,
        )
    }

    private fun outputRoutePriority(type: Int): Int {
        return when (type) {
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_DOCK -> 0
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC,
            AudioDeviceInfo.TYPE_AUX_LINE,
            AudioDeviceInfo.TYPE_BUS -> 1
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> 2
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> 3
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> 4
            else -> 4
        }
    }

    private fun routeTypeForAudioDevice(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"
            AudioDeviceInfo.TYPE_DOCK -> "dock"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC,
            AudioDeviceInfo.TYPE_AUX_LINE,
            AudioDeviceInfo.TYPE_BUS -> "wired"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> "bluetooth"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "internal"
            else -> "unknown"
        }
    }

    private fun isExternalRouteType(routeType: String): Boolean {
        return routeType == "usb" || routeType == "dock"
    }

    private fun defaultProductName(routeType: String, label: String): String {
        return when (routeType) {
            "internal" -> "Device DAC"
            "usb" -> label.ifBlank { "USB DAC" }
            "dock" -> label.ifBlank { "Dock Audio" }
            "wired" -> label.ifBlank { "Headphone Output" }
            "bluetooth" -> label.ifBlank { "Bluetooth Output" }
            else -> label.ifBlank { "Audio Output" }
        }
    }

    private fun defaultRouteLabel(routeType: String): String {
        return when (routeType) {
            "internal" -> "Built-in output"
            "usb" -> "USB DAC"
            "dock" -> "Dock audio"
            "wired" -> "Headphone output"
            "bluetooth" -> "Bluetooth output"
            else -> "Audio output"
        }
    }

    private fun safeUsbSerial(device: UsbDevice): String? {
        return try {
            device.serialNumber
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun getRouteVolume(hardwareOnly: Boolean = false, expectedDeviceName: String? = null): Double? {
        if (expectedDeviceName != null && expectedDeviceName != activeDirectUsbDeviceName) return null
        if (hasDirectUsbHardwareVolume()) {
            val hardwareVolume = nativeGetRustDirectUsbHardwareVolume()
            return if (!hardwareVolume.isFinite()) null else hardwareVolume.coerceIn(0.0, 1.0)
        }
        // STREAM_MUSIC does not control a libusb stream. Never substitute it
        // for a missing or disconnected DAC Feature Unit.
        if (hardwareOnly || activeDirectUsbDeviceName != null) return null

        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume <= 0) return 1.0

        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return (currentVolume.toDouble() / maxVolume.toDouble()).coerceIn(0.0, 1.0)
    }

    /// Returns: 1 = readable hardware level, 0 = control failure, -1 = unavailable.
    private fun verifyHardwareVolumeHealth(): Int {
        if (!hasDirectUsbHardwareVolume()) return -1
        return nativeVerifyRustDirectUsbHardwareVolumeHealth()
    }

    private fun setRouteVolume(volume: Double, hardwareOnly: Boolean = false, expectedDeviceName: String? = null): Boolean {
        if (!volume.isFinite()) return false
        if (expectedDeviceName != null && expectedDeviceName != activeDirectUsbDeviceName) return false
        if (hasDirectUsbHardwareVolume()) {
            val clamped = volume.coerceIn(0.0, 1.0)
            Log.d("VolFlow", "setRouteVolume hw: sending SET_CUR $clamped")
            val ok = nativeSetRustDirectUsbHardwareVolume(clamped)
            Log.d("VolFlow", "setRouteVolume hw: native returned $ok")
            return ok
        }
        if (hardwareOnly || activeDirectUsbDeviceName != null) return false

        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        if (audioManager.isVolumeFixed) return false

        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume <= 0) return false

        val clamped = volume.coerceIn(0.0, 1.0)
        val targetVolume = (clamped * maxVolume.toDouble()).roundToInt().coerceIn(0, maxVolume)
        if (targetVolume > 0) {
            cachedMusicVolumeBeforeMute = targetVolume
        }
        suppressVolumeObserverBriefly()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
        return true
    }

    private fun getRouteMuted(hardwareOnly: Boolean = false, expectedDeviceName: String? = null): Boolean? {
        if (expectedDeviceName != null && expectedDeviceName != activeDirectUsbDeviceName) return null
        if (hasDirectUsbHardwareVolume()) {
            return when (nativeGetRustDirectUsbHardwareMute()) {
                0 -> false
                1 -> true
                else -> null
            }
        }
        if (hardwareOnly || activeDirectUsbDeviceName != null) return null

        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.isStreamMute(AudioManager.STREAM_MUSIC) || currentVolume == 0
        } else {
            currentVolume == 0
        }
    }

    private fun setRouteMuted(muted: Boolean, hardwareOnly: Boolean = false, expectedDeviceName: String? = null): Boolean {
        if (expectedDeviceName != null && expectedDeviceName != activeDirectUsbDeviceName) return false
        if (hasDirectUsbHardwareVolume()) {
            return nativeSetRustDirectUsbHardwareMute(muted)
        }
        if (hardwareOnly || activeDirectUsbDeviceName != null) return false

        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        if (audioManager.isVolumeFixed) return false

        val streamType = AudioManager.STREAM_MUSIC
        suppressVolumeObserverBriefly()
        if (muted) {
            val currentVolume = audioManager.getStreamVolume(streamType)
            if (currentVolume > 0) {
                cachedMusicVolumeBeforeMute = currentVolume
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_MUTE, 0)
            }
            audioManager.setStreamVolume(streamType, 0, 0)
            return true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_UNMUTE, 0)
        }
        // Read actual OS volume — it may have been changed via hardware buttons while muted
        val currentAfterUnmute = audioManager.getStreamVolume(streamType)
        if (currentAfterUnmute == 0) {
            val maxVolume = audioManager.getStreamMaxVolume(streamType).coerceAtLeast(1)
            val restoreVolume = (cachedMusicVolumeBeforeMute ?: (maxVolume / 2).coerceAtLeast(1))
                .coerceIn(1, maxVolume)
            audioManager.setStreamVolume(streamType, restoreVolume, 0)
        }
        return true
    }

    private fun suppressVolumeObserverBriefly() {
        suppressVolumeObserverRunnable?.let { volumeObserverHandler.removeCallbacks(it) }
        suppressVolumeObserver = true
        suppressVolumeObserverRunnable = Runnable { suppressVolumeObserver = false }
        volumeObserverHandler.postDelayed(suppressVolumeObserverRunnable!!, 200)
    }

    private fun registerVolumeContentObserver() {
        // Seed cached values so the first real change is detected
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (hasDirectUsbHardwareVolume()) {
            lastObservedHardwareVolume = nativeGetRustDirectUsbHardwareVolume()
            lastObservedHardwareMute = nativeGetRustDirectUsbHardwareMute()
        } else {
            val seedVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            lastObservedVolume = seedVolume
            lastObservedMuted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                seedVolume == 0 || audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
            } else {
                seedVolume == 0
            }
        }

        volumeContentObserver = object : ContentObserver(volumeObserverHandler) {
            override fun onChange(selfChange: Boolean) {
                if (suppressVolumeObserver) return
                // Debounce: cancel pending, schedule new
                volumeObserverDebounceRunnable?.let { volumeObserverHandler.removeCallbacks(it) }
                volumeObserverDebounceRunnable = Runnable {
                    val volume = getRouteVolume()
                    val muted = getRouteMuted()
                    if (hasDirectUsbHardwareVolume()) {
                        val hwVol = nativeGetRustDirectUsbHardwareVolume()
                        val hwMute = nativeGetRustDirectUsbHardwareMute()
                        val hwVolChanged = when {
                            hwVol.isNaN() && lastObservedHardwareVolume.isNaN() -> false
                            hwVol.isNaN() != lastObservedHardwareVolume.isNaN() -> true
                            hwVol.isNaN() -> false
                            lastObservedHardwareVolume.isNaN() -> true
                            else -> kotlin.math.abs(hwVol - lastObservedHardwareVolume) > 1e-6
                        }
                        val hwMuteChanged = hwMute != lastObservedHardwareMute
                        if (hwVolChanged || hwMuteChanged) {
                            lastObservedHardwareVolume = hwVol
                            lastObservedHardwareMute = hwMute
                            uac2Channel?.invokeMethod("onVolumeChanged", mapOf(
                                "volume" to volume,
                                "muted" to muted,
                            ))
                        }
                    } else {
                        val volRaw = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        // Only notify Flutter if volume or mute actually changed
                        if (volRaw != lastObservedVolume || muted != lastObservedMuted) {
                            lastObservedVolume = volRaw
                            lastObservedMuted = muted
                            uac2Channel?.invokeMethod("onVolumeChanged", mapOf(
                                "volume" to volume,
                                "muted" to muted,
                            ))
                        }
                    }
                }
                volumeObserverHandler.postDelayed(volumeObserverDebounceRunnable!!, 150)
            }
        }
        appContext.contentResolver.registerContentObserver(
            android.provider.Settings.System.CONTENT_URI,
            true,
            volumeContentObserver!!,
        )
    }

    private fun registerUsbHotplugReceiver() {
        usbHotplugReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        // Invalidate cache on device attach
                        uac2DeviceCache = null
                        val attachedDevice: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        }
                        maybeRequestPermissionForAttachedDevice(attachedDevice)
                        // Notify Flutter if channel is available
                        if (attachedDevice != null && isLikelyUsbAudioDevice(attachedDevice, currentRouteLabelHint())) {
                            uac2Channel?.invokeMethod("onDeviceAttached", null)
                        }
                        audioDeviceChannel?.invokeMethod("onPlaybackDevicesChanged", null)
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        // Invalidate cache on device detach
                        uac2DeviceCache = null
                        val detachedDevice: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        }
                        val detachedName = detachedDevice?.deviceName
                        val wasDirect = detachedName != null && detachedName == activeDirectUsbDeviceName
                        if (wasDirect) {
                            // Gone: nothing to write back to, and a replug starts fresh.
                            deactivateDirectUsb(OriginalVolumeAction.FORGET)
                        }
                        detachedName?.let { promptedUsbPermissionDeviceNames.remove(it) }
                        pendingUac2Permission?.takeIf { it.deviceName == detachedName }?.let {
                            finishUac2Permission(it, false, "NOT_FOUND")
                        }
                        if (wasDirect || (detachedDevice != null && isLikelyUsbAudioDevice(detachedDevice))) {
                            uac2Channel?.invokeMethod("onDeviceDetached", detachedName)
                        }
                        audioDeviceChannel?.invokeMethod("onPlaybackDevicesChanged", null)
                    }
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(usbHotplugReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(usbHotplugReceiver, filter)
        }
    }

    private fun registerAudioDeviceCallback() {
        if (audioDeviceCallback != null) {
            return
        }
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                // Keep the priority anchor pinned to the current music route
                // (e.g. re-pin from speaker to the 3.5mm jack when headphones
                // are plugged in mid-playback).
                priorityAnchorService.updateRouteDevice()
                audioDeviceChannel?.invokeMethod("onPlaybackDevicesChanged", null)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                priorityAnchorService.updateRouteDevice()
                audioDeviceChannel?.invokeMethod("onPlaybackDevicesChanged", null)
            }
        }
        try {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
            audioDeviceCallback = callback
        } catch (e: Exception) {
            Log.w("AudioDevice", "AudioDeviceCallback registration failed: ${e.message}")
        }
    }

    private external fun nativeRegisterRustDirectUsbDevice(
        fd: Int,
        vendorId: Int,
        productId: Int,
        productName: String,
        deviceName: String?,
        claimedInterfaces: IntArray,
    ): Boolean
    private external fun nativeSetRustDirectUsbPlaybackFormat(
        sampleRate: Int,
        bitDepth: Int,
        channels: Int,
        isDop: Boolean,
        isNativeDsd: Boolean,
    ): Boolean
    private external fun nativeSetRustDirectUsbLockEnabled(enabled: Boolean): Boolean
    private external fun nativeHasRustDirectUsbHardwareVolume(): Boolean
    private external fun nativeGetRustDirectUsbHardwareVolume(): Double
    private external fun nativeSetRustDirectUsbHardwareVolume(volume: Double): Boolean
    private external fun nativeQueueRustDirectUsbHardwareVolumeRestore(volume: Double): Boolean
    private external fun nativeGetRustDirectUsbHardwareMute(): Int
    private external fun nativeSetRustDirectUsbHardwareMute(muted: Boolean): Boolean
    private external fun nativeVerifyRustDirectUsbHardwareVolumeHealth(): Int
    private external fun nativeGetRustAudioDebugStateJson(): String?
    private external fun nativeSetRustDirectUsbOriginalVolumeAction(action: Int): Boolean
    private external fun nativeClearRustDirectUsbPlayback(): Boolean
    private external fun nativeWaitRustDirectUsbSessionStopped(timeoutMs: Int): Boolean
    private external fun nativeSetRustDirectUsbReconnectTarget(
        deviceName: String?,
        cancel: Boolean
    ): Boolean
    private external fun nativeIsRustDirectUsbSessionActive(): Boolean
    private external fun nativeMarkRustDirectUsbFallback(reason: String?): Boolean
    private external fun nativeSetRustDeveloperMode(enabled: Boolean)
}

internal fun Context.unregisterReceiverSafely(receiver: BroadcastReceiver?) {
    if (receiver == null) return
    try {
        unregisterReceiver(receiver)
    } catch (_: IllegalArgumentException) {
    } catch (_: Exception) {
    }
}
