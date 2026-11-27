package com.mossapps.flick

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

/** Opt-in Android host context. This channel carries structured events only. */
internal class UsbSupportCapture(
    private val context: Context,
    private val beginNative: () -> Long,
    private val finishNative: (Long) -> String?,
) {
    private class Attempt(val id: Long, val startedNs: Long) {
        val events = ArrayDeque<Map<String, Any?>>()
        var dropped = 0
        var firstFailure: Map<String, Any?>? = null
    }

    private var attempt: Attempt? = null
    private var channel: MethodChannel? = null
    private val main = Handler(Looper.getMainLooper())
    private val exportWorker by lazy { Executors.newSingleThreadExecutor() }

    fun attach(engine: FlutterEngine) {
        channel?.setMethodCallHandler(null)
        channel = MethodChannel(engine.dartExecutor.binaryMessenger, "com.mossapps.flick/usb_support_diagnostics")
        channel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "begin" -> begin(result)
                "finish" -> finish(call.argument<Number>("attemptId")?.toLong(), result)
                else -> result.notImplemented()
            }
        }
    }

    private fun begin(result: MethodChannel.Result) {
        if (attempt != null) {
            result.error("CAPTURE_ACTIVE", null, null)
            return
        }
        val id = beginNative()
        if (id <= 0) {
            result.error("CAPTURE_UNAVAILABLE", null, null)
            return
        }
        attempt = Attempt(id, SystemClock.elapsedRealtimeNanos())
        val devices = runCatching {
            val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            usb?.deviceList?.values.orEmpty().asSequence()
                .filter(::isAudioDevice)
                .take(16)
                .map { device -> deviceFacts(device) + ("permission" to usb?.hasPermission(device)) }
                .toList()
        }.getOrDefault(emptyList())
        result.success(mapOf(
            "attemptId" to id,
            "host" to mapOf(
                "appVersion" to BuildConfig.VERSION_NAME,
                "appBuild" to BuildConfig.VERSION_CODE,
                "model" to Build.MODEL,
                "systemVersion" to Build.VERSION.RELEASE,
                "sdkInt" to Build.VERSION.SDK_INT,
                "architecture" to Build.SUPPORTED_ABIS.firstOrNull(),
            ),
            "attachedDevices" to devices,
        ))
    }

    private fun finish(id: Long?, result: MethodChannel.Result) {
        val active = attempt
        if (active == null || active.id != id) {
            result.error("CAPTURE_MISMATCH", null, null)
            return
        }
        attempt = null
        exportWorker.execute {
            val native = runCatching { finishNative(active.id) }.getOrNull()
            val report = mapOf(
                "attemptId" to active.id,
                "androidEvents" to active.events.toList(),
                "androidDroppedEvents" to active.dropped,
                "androidFirstFailure" to active.firstFailure,
                "nativeJson" to native,
            )
            main.post { result.success(report) }
        }
    }

    fun record(
        stage: String,
        code: String,
        failed: Boolean = false,
        device: UsbDevice? = null,
        details: Map<String, Any?> = emptyMap(),
    ) {
        val active = attempt ?: return
        val event = mapOf(
            "elapsedUs" to (SystemClock.elapsedRealtimeNanos() - active.startedNs) / 1000,
            "stage" to stage,
            "code" to code,
        ) + (device?.let(::deviceFacts) ?: emptyMap()) + details
        if (active.events.size >= 256) {
            active.events.removeFirst()
            active.dropped++
        }
        active.events.addLast(event)
        if (failed && active.firstFailure == null) {
            active.firstFailure = event
            channel?.invokeMethod("failure", mapOf("attemptId" to active.id, "stage" to stage))
        }
    }

    private fun isAudioDevice(device: UsbDevice): Boolean =
        device.deviceClass == UsbConstants.USB_CLASS_AUDIO || (0 until device.interfaceCount).any {
            device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO
        }

    private fun deviceFacts(device: UsbDevice): Map<String, Any?> = mapOf(
        "vendorId" to device.vendorId,
        "productId" to device.productId,
        "productName" to device.productName?.take(120),
        "deviceVersion" to device.version,
    )
}
