package com.mossapps.flick

import android.Manifest
import com.mossapps.flick.widgets.WidgetLaunchInbox
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.database.ContentObserver
import android.database.Cursor
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.mediarouter.media.MediaRouter
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.os.PowerManager
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.documentfile.provider.DocumentFile
import com.mossapps.flick.audiofx.JustAudioProcessingController
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.min

class MainActivity: FlutterActivity() {
    private val CHANNEL = "com.mossapps.flick/storage"
    private val INTEGRATION_CHANNEL = "com.mossapps.flick/integration"
    private val EQUALIZER_CHANNEL = "com.mossapps.flick/equalizer"
    private val WIDGET_CHANNEL = "com.mossapps.flick/widget"
    private val OVERLAY_CHANNEL = "com.mossapps.flick/overlay"
    private val BLUETOOTH_CHANNEL = "com.mossapps.flick/bluetooth"
    private val BLUETOOTH_EVENT_CHANNEL = "com.mossapps.flick/bluetooth_events"
    private val CAST_CHANNEL = "com.mossapps.flick/cast"
    private val CAST_EVENT_CHANNEL = "com.mossapps.flick/cast_events"
    private val DLNA_ROUTE_CHANNEL = "com.mossapps.flick/dlna_route"
    private val DIAGNOSTICS_CHANNEL = "com.mossapps.flick/diagnostics"
    private val LOCKER_PACKAGE = "com.mossapps.locker"
    private val LOCKER_RETURN_URI = "locker://return?source=flick"
    // private val CONVERTER_CHANNEL = "com.mossapps.flick/converter"
    private val REQUEST_OPEN_DOCUMENT_TREE = 1001
    private val REQUEST_OPEN_DOCUMENT = 1003
    private val REQUEST_CREATE_DOCUMENT = 1004
    private val REQUEST_OVERLAY_PERMISSION = 1005

    private var pendingDocumentTreeResult: MethodChannel.Result? = null
    private var pendingOpenDocumentResult: MethodChannel.Result? = null
    private var pendingCreateDocumentResult: MethodChannel.Result? = null
    private var pendingOverlayPermissionResult: MethodChannel.Result? = null
    private val justAudioProcessingController = JustAudioProcessingController()
    private var integrationChannel: MethodChannel? = null
    private var pendingExternalPlaybackPayload: Map<String, Any?>? = null
    private var mediaStoreContentObserver: ContentObserver? = null
    private var mediaFilesContentObserver: ContentObserver? = null
    private var mediaStoreEventSink: EventChannel.EventSink? = null
    // private var audioConverter: AudioConverter? = null
    // Coroutine scope for background tasks
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private var widgetChannel: MethodChannel? = null
    private val widgetLaunchInbox = WidgetLaunchInbox()
    private val HOME_WIDGET_LAUNCH_ACTION = "com.mossapps.flick.WIDGET_LAUNCH"
    private var a2dpProxy: BluetoothA2dp? = null
    private var bluetoothEventSink: EventChannel.EventSink? = null
    private var aclReceiver: BroadcastReceiver? = null
    private var castController: CastController? = null
    private var castEventSink: EventChannel.EventSink? = null
    private var dlnaRouteProvider: DlnaRouteProvider? = null

    // Load the Rust shared library before calling into native startup hooks.
    init {
        System.loadLibrary("rust_lib_flick_player")
    }

    // Direct USB lives in the process-lifetime controller, so it works with no
    // activity; this activity only tells it that a screen exists.
    private val directUsb: DirectUsbController
        get() = FlickEngine.directUsb(applicationContext)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before the engine is configured: its first USB permission prompt needs this.
        directUsb.attachActivity(this)
        super.onCreate(savedInstanceState)

        if (!nativeInitRustAndroidContext(applicationContext)) {
            Log.e("Flick", "Failed to initialize Rust Android audio context")
        } else {
            Log.i("Flick", "Rust Android audio context initialized")
        }

        handleExternalPlaybackIntent(intent)
        directUsb.handleUsbAttachIntent(intent)
        directUsb.maybeRequestPermissionForConnectedUsbAudioDevices(reason = "activity create")
        registerBluetoothAclReceiver()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchWidgetIntent(intent)
        handleExternalPlaybackIntent(intent)
        directUsb.handleUsbAttachIntent(intent)
        directUsb.maybeRequestPermissionForConnectedUsbAudioDevices(reason = "new intent")
    }

    override fun provideFlutterEngine(context: android.content.Context): FlutterEngine? {
        return FlickEngine.ensure(context)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        // Idempotent: an engine FlickEngine already prepared is left alone.
        FlickEngine.prepare(applicationContext, flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "openDocumentTree" -> {
                    pendingDocumentTreeResult = result
                    openDocumentTree()
                }
                "openDocument" -> {
                    @Suppress("UNCHECKED_CAST")
                    val mimeTypes = (call.argument<List<String>>("mimeTypes") ?: listOf(
                        "audio/x-mpegurl",
                        "application/vnd.apple.mpegurl",
                        "application/x-mpegurl",
                        "audio/mpegurl",
                        "text/plain"
                    )) as List<String>
                    if (pendingOpenDocumentResult != null) {
                        result.error(
                            "OPERATION_IN_PROGRESS",
                            "Another document picker request is already in progress",
                            null
                        )
                        return@setMethodCallHandler
                    }
                    pendingOpenDocumentResult = result
                    openDocument(mimeTypes)
                }
                "createDocument" -> {
                    val fileName = call.argument<String>("fileName")
                    val mimeType = call.argument<String>("mimeType") ?: "audio/x-mpegurl"
                    if (fileName != null && fileName.isNotBlank()) {
                        pendingCreateDocumentResult = result
                        createDocument(fileName, mimeType)
                    } else {
                        result.error("INVALID_ARGUMENT", "fileName is required", null)
                    }
                }
                "takePersistableUriPermission" -> {
                    val uri = call.argument<String>("uri")
                    if (uri != null) {
                        val success = takePersistableUriPermission(uri)
                        result.success(success)
                    } else {
                        result.error("INVALID_ARGUMENT", "URI is required", null)
                    }
                }
                "releasePersistableUriPermission" -> {
                    val uri = call.argument<String>("uri")
                    if (uri != null) {
                        releasePersistableUriPermission(uri)
                        result.success(null)
                    } else {
                        result.error("INVALID_ARGUMENT", "URI is required", null)
                    }
                }
                "getPersistedUriPermissions" -> {
                    val uris = getPersistedUriPermissions()
                    result.success(uris)
                }
                "saveImageToGallery" -> {
                    val bytes = call.argument<ByteArray>("bytes")
                    val fileName = call.argument<String>("fileName") ?: "flick_recap.png"
                    val albumName = call.argument<String>("albumName") ?: "Flick"
                    if (bytes == null || bytes.isEmpty()) {
                        result.error("INVALID_ARGUMENT", "bytes are required", null)
                    } else {
                        val imageBytes = bytes
                        mainScope.launch {
                            try {
                                val savedUri = withContext(Dispatchers.IO) {
                                    saveImageToGallery(imageBytes, fileName, albumName)
                                }
                                result.success(savedUri)
                            } catch (e: SecurityException) {
                                result.error(
                                    "STORAGE_PERMISSION_REQUIRED",
                                    "Storage permission is required to save images on this Android version.",
                                    null
                                )
                            } catch (e: Exception) {
                                result.error("SAVE_IMAGE_ERROR", "Failed to save image: ${e.message}", null)
                            }
                        }
                    }
                }
                "listAudioFiles" -> {
                    val uri = call.argument<String>("uri")
                    val filterNonMusicFilesAndFolders =
                        call.argument<Boolean>("filterNonMusicFilesAndFolders") ?: true
                    if (uri != null) {
                        // Launch in background to avoid blocking UI
                        mainScope.launch {
                            try {
                                val files = withContext(Dispatchers.IO) {
                                    fastScanAudioFiles(uri, filterNonMusicFilesAndFolders)
                                }
                                result.success(files)
                            } catch (e: Exception) {
                                result.error("SCAN_ERROR", "Failed to scan folder: ${e.message}", null)
                            }
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "URI is required", null)
                    }
                }
                "listPlaylistFiles" -> {
                    val uri = call.argument<String>("uri")
                    val filterNonMusicFilesAndFolders =
                        call.argument<Boolean>("filterNonMusicFilesAndFolders") ?: true
                    if (uri != null) {
                        mainScope.launch {
                            try {
                                val files = withContext(Dispatchers.IO) {
                                    scanPlaylistFiles(uri, filterNonMusicFilesAndFolders)
                                }
                                result.success(files)
                            } catch (e: Exception) {
                                result.error("SCAN_ERROR", "Failed to scan folder: ${e.message}", null)
                            }
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "URI is required", null)
                    }
                }
                "fetchAudioMetadata" -> {
                    val uris = call.argument<List<String>>("uris")
                    if (uris != null) {
                        mainScope.launch {
                            try {
                                val metadata = withContext(Dispatchers.IO) {
                                    extractMetadataForFiles(uris)
                                }
                                result.success(metadata)
                            } catch (e: Exception) {
                                result.error("METADATA_ERROR", "Failed to fetch metadata: ${e.message}", null)
                            }
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "URIs list is required", null)
                    }
                }
                "fetchEmbeddedArtwork" -> {
                    val uri = call.argument<String>("uri")
                    if (uri != null) {
                        mainScope.launch {
                            try {
                                val artwork = withContext(Dispatchers.IO) {
                                    extractEmbeddedArtwork(uri)
                                }
                                result.success(artwork)
                            } catch (e: Exception) {
                                result.error("ARTWORK_ERROR", "Failed to fetch embedded artwork: ${e.message}", null)
                            }
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "URI is required", null)
                    }
                }
                "cacheUriForPlayback" -> {
                    val uri = call.argument<String>("uri")
                    val extensionHint = call.argument<String>("extensionHint")
                    val maxSizeBytes = call.argument<Number>("maxSizeBytes")?.toLong()
                    val maxStagingBytes = call.argument<Number>("maxStagingBytes")?.toLong()
                    if (uri != null) {
                        mainScope.launch {
                            try {
                                val stagedPath = withContext(Dispatchers.IO) {
                                    cacheUriForPlayback(uri, extensionHint, maxSizeBytes, maxStagingBytes)
                                }
                                result.success(stagedPath)
                            } catch (e: Exception) {
                                result.error("CACHE_URI_ERROR", "Failed to stage audio URI: ${e.message}", null)
                            }
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "URI is required", null)
                    }
                }
                "prunePlaybackStaging" -> {
                    val maxBytes = call.argument<Number>("maxBytes")?.toLong()
                    mainScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                prunePlaybackStaging(maxBytes, null)
                            }
                            result.success(null)
                        } catch (e: Exception) {
                            result.error("PRUNE_STAGING_ERROR", "Failed to prune playback staging: ${e.message}", null)
                        }
                    }
                }
                "readSiblingArtwork" -> {
                    val uri = call.argument<String>("uri")
                    if (uri != null) {
                        mainScope.launch {
                            try {
                                val artwork = withContext(Dispatchers.IO) {
                                    readSiblingArtwork(uri)
                                }
                                result.success(artwork)
                            } catch (e: Exception) {
                                result.error("ARTWORK_ERROR", "Failed to read sibling artwork: ${e.message}", null)
                            }
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "URI is required", null)
                    }
                }
                "readSiblingLyrics" -> {
                    val audioUri = call.argument<String>("audioUri")
                    if (audioUri != null) {
                        mainScope.launch {
                            try {
                                val lyrics = withContext(Dispatchers.IO) {
                                    readSiblingLyrics(audioUri)
                                }
                                result.success(lyrics)
                            } catch (e: Exception) {
                                result.error("LYRICS_READ_ERROR", "Failed to read lyrics: ${e.message}", null)
                            }
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "audioUri is required", null)
                    }
                }
                "readEmbeddedLyrics" -> {
                    val audioUri = call.argument<String>("audioUri")
                    if (audioUri != null) {
                        mainScope.launch {
                            try {
                                val lyrics = withContext(Dispatchers.IO) {
                                    readEmbeddedLyrics(audioUri)
                                }
                                result.success(lyrics)
                            } catch (e: Exception) {
                                result.error("LYRICS_EMBEDDED_ERROR", "Failed to read embedded lyrics: ${e.message}", null)
                            }
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "audioUri is required", null)
                    }
                }
                "getDocumentDisplayName" -> {
                    val uri = call.argument<String>("uri")
                    if (uri != null) {
                        val displayName = getDocumentDisplayName(uri)
                        result.success(displayName)
                    } else {
                        result.error("INVALID_ARGUMENT", "URI is required", null)
                    }
                }
                "resolveTreeUriToPath" -> {
                    val uri = call.argument<String>("uri")
                    if (uri != null) {
                        result.success(resolveTreeUriToPath(uri))
                    } else {
                        result.error("INVALID_ARGUMENT", "URI is required", null)
                    }
                }
                "resolveStorageInfo" -> {
                    val uri = call.argument<String>("uri")
                    if (uri != null) {
                        result.success(resolveStorageInfo(uri))
                    } else {
                        result.error("INVALID_ARGUMENT", "URI is required", null)
                    }
                }
                "hasAllFilesAccess" -> {
                    result.success(hasAllFilesAccess())
                }
                "isAllFilesAccessSupported" -> {
                    result.success(isAllFilesAccessSupported())
                }
                "requestAllFilesAccess" -> {
                    result.success(openAllFilesAccessSettings())
                }
                "isIgnoringBatteryOptimizations" -> {
                    result.success(isIgnoringBatteryOptimizations())
                }
                "requestIgnoreBatteryOptimizations" -> {
                    result.success(requestIgnoreBatteryOptimizations())
                }
                "readTextDocument" -> {
                    val uri = call.argument<String>("uri")
                    android.util.Log.d("MainActivity", "[MethodChannel] readTextDocument called with URI: $uri")
                    if (uri != null) {
                        mainScope.launch {
                            try {
                                val text = withContext(Dispatchers.IO) {
                                    readTextDocument(uri)
                                }
                                android.util.Log.d("MainActivity", "[MethodChannel] readTextDocument success, length: ${text.length}")
                                result.success(text)
                            } catch (e: Exception) {
                                android.util.Log.e("MainActivity", "[MethodChannel] readTextDocument error: ${e.message}", e)
                                result.error("READ_TEXT_ERROR", "Failed to read document: ${e.message}", null)
                            }
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "URI is required", null)
                    }
                }
                "writeTextDocument" -> {
                    val uri = call.argument<String>("uri")
                    val content = call.argument<String>("content")
                    android.util.Log.d("MainActivity", "[MethodChannel] writeTextDocument called with URI: $uri, content length: ${content?.length}")
                    if (uri != null && content != null) {
                        mainScope.launch {
                            try {
                                val success = withContext(Dispatchers.IO) {
                                    writeTextDocument(uri, content)
                                }
                                android.util.Log.d("MainActivity", "[MethodChannel] writeTextDocument result: $success")
                                result.success(success)
                            } catch (e: Exception) {
                                android.util.Log.e("MainActivity", "[MethodChannel] writeTextDocument error: ${e.message}", e)
                                result.error("WRITE_TEXT_ERROR", "Failed to write document: ${e.message}", null)
                            }
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "URI and content are required", null)
                    }
                }
                "deleteDocument" -> {
                    val folderTreeUri = call.argument<String>("folderTreeUri")
                    val filePath = call.argument<String>("filePath")
                    if (folderTreeUri != null && filePath != null) {
                        mainScope.launch {
                            try {
                                val success = withContext(Dispatchers.IO) {
                                    deleteDocumentViaSaf(folderTreeUri, filePath)
                                }
                                result.success(success)
                            } catch (e: Exception) {
                                Log.w("MainActivity", "[MethodChannel] deleteDocument error: ${e.message}", e)
                                result.success(false)
                            }
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "folderTreeUri and filePath are required", null)
                    }
                }
                "removeFromMediaStore" -> {
                    val filePath = call.argument<String>("filePath")
                    if (filePath != null) {
                        mainScope.launch {
                            try {
                                val removed = withContext(Dispatchers.IO) {
                                    removeFromMediaStore(filePath)
                                }
                                result.success(removed)
                            } catch (e: Exception) {
                                Log.w("MainActivity", "[MethodChannel] removeFromMediaStore error: ${e.message}", e)
                                result.success(false)
                            }
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "filePath is required", null)
                    }
                }
                "writeFileBytesViaSaf" -> {
                    val folderTreeUri = call.argument<String>("folderTreeUri")
                    val filePath = call.argument<String>("filePath")
                    val tempFilePath = call.argument<String>("tempFilePath")
                    if (folderTreeUri != null && filePath != null && tempFilePath != null) {
                        mainScope.launch {
                            try {
                                val success = withContext(Dispatchers.IO) {
                                    writeFileBytesViaSaf(folderTreeUri, filePath, tempFilePath)
                                }
                                result.success(success)
                            } catch (e: Exception) {
                                Log.w("MainActivity", "[MethodChannel] writeFileBytesViaSaf error: ${e.message}", e)
                                result.success(false)
                            }
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "folderTreeUri, filePath, and tempFilePath are required", null)
                    }
                }
                "stageContentUriForEdit" -> {
                    val uri = call.argument<String>("uri")
                    val extensionHint = call.argument<String>("extensionHint")
                    if (uri != null) {
                        mainScope.launch {
                            val staged = withContext(Dispatchers.IO) {
                                stageContentUriForEdit(uri, extensionHint)
                            }
                            result.success(staged)
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "uri is required", null)
                    }
                }
                "writeBytesToContentUri" -> {
                    val uri = call.argument<String>("uri")
                    val tempFilePath = call.argument<String>("tempFilePath")
                    if (uri != null && tempFilePath != null) {
                        mainScope.launch {
                            val written = withContext(Dispatchers.IO) {
                                writeBytesToContentUri(uri, tempFilePath)
                            }
                            result.success(written)
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "uri and tempFilePath are required", null)
                    }
                }
                "queryMediaStoreAudio" -> {
                    val folderPaths = call.argument<List<String>>("folderPaths")
                    val volumeName = call.argument<String?>("volumeName")
                    mainScope.launch {
                        try {
                            val files = withContext(Dispatchers.IO) {
                                queryMediaStoreAudio(folderPaths ?: emptyList(), volumeName)
                            }
                            result.success(files)
                        } catch (e: Exception) {
                            result.error("MEDIASTORE_ERROR", "Failed to query MediaStore: ${e.message}", null)
                        }
                    }
                }
                "queryMediaStoreNonAudio" -> {
                    val folderPaths = call.argument<List<String>>("folderPaths")
                    val volumeName = call.argument<String?>("volumeName")
                    mainScope.launch {
                        try {
                            val files = withContext(Dispatchers.IO) {
                                queryMediaStoreNonAudio(folderPaths ?: emptyList(), volumeName)
                            }
                            result.success(files)
                        } catch (e: Exception) {
                            result.error("MEDIASTORE_ERROR", "Failed to query MediaStore non-audio: ${e.message}", null)
                        }
                    }
                }
                "queryMediaStoreDeletions" -> {
                    val filePaths = call.argument<List<String>>("filePaths")
                    val volumeName = call.argument<String?>("volumeName")
                    mainScope.launch {
                        try {
                            val deleted = withContext(Dispatchers.IO) {
                                queryMediaStoreDeletions(filePaths ?: emptyList(), volumeName)
                            }
                            result.success(deleted)
                        } catch (e: Exception) {
                            result.error("MEDIASTORE_ERROR", "Failed to check deletions: ${e.message}", null)
                        }
                    }
                }
                else -> result.notImplemented()
            }
        }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, "com.mossapps.flick/mediastore_events").setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    mediaStoreEventSink = events
                    registerMediaStoreObserver()
                }
                override fun onCancel(arguments: Any?) {
                    mediaStoreEventSink = null
                    unregisterMediaStoreObserver()
                }
            }
        )

        // The player (notification) channel is registered by FlickEngine so a
        // headless engine has it too.

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            OVERLAY_CHANNEL,
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "canDrawOverlays" -> {
                    result.success(Settings.canDrawOverlays(this))
                }
                "requestOverlayPermission" -> {
                    pendingOverlayPermissionResult = result
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:$packageName"),
                        )
                        startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
                    } catch (e: Exception) {
                        pendingOverlayPermissionResult = null
                        result.success(false)
                    }
                }
                "showFloatingPlayer" -> {
                    val intent = Intent(this, MusicNotificationService::class.java).apply {
                        putExtra("floating", "show")
                        call.argument<String>("title")?.let { putExtra("title", it) }
                        call.argument<String>("artist")?.let { putExtra("artist", it) }
                        call.argument<String>("albumArtPath")?.let { putExtra("albumArtPath", it) }
                        call.argument<Boolean>("isPlaying")?.let { putExtra("isPlaying", it) }
                    }
                    startService(intent)
                    result.success(null)
                }
                "hideFloatingPlayer" -> {
                    startService(
                        Intent(this, MusicNotificationService::class.java)
                            .putExtra("floating", "hide")
                    )
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }

        integrationChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            INTEGRATION_CHANNEL,
        ).also { channel ->
            channel.setMethodCallHandler { call, result ->
                when (call.method) {
                    "consumePendingExternalPlayback" -> {
                        result.success(pendingExternalPlaybackPayload)
                        pendingExternalPlaybackPayload = null
                    }
                    "returnToLocker" -> {
                        result.success(returnToLocker())
                    }
                    else -> result.notImplemented()
                }
            }
        }

        ensureA2dpProxy()
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, BLUETOOTH_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getConnectedDevices" -> result.success(getBluetoothDevices(includeBonded = false))
                "getBondedDevices" -> result.success(getBluetoothDevices(includeBonded = true))
                "getCodecStatus" -> {
                    val address = call.argument<String>("address")
                    result.success(if (address != null) getBluetoothCodecStatus(address) else null)
                }
                "setCodecConfig" -> {
                    val address = call.argument<String>("address") ?: ""
                    val codecType = call.argument<Int>("codecType") ?: 0
                    val sampleRate = call.argument<Int>("sampleRate") ?: 0
                    val bitsPerSample = call.argument<Int>("bitsPerSample") ?: 0
                    val channelMode = call.argument<Int>("channelMode") ?: 0
                    val ldacBitrate = call.argument<Int>("ldacBitrate") ?: 0
                    result.success(setBluetoothCodecConfigNative(address, codecType, sampleRate, bitsPerSample, channelMode, ldacBitrate))
                }
                "getBatteryLevel" -> {
                    val address = call.argument<String>("address") ?: ""
                    readBluetoothBatteryLevel(address, result)
                }
                "setAbsoluteVolumeEnabled" -> {
                    val address = call.argument<String>("address") ?: ""
                    val enabled = call.argument<Boolean>("enabled") ?: true
                    result.success(setBluetoothAbsoluteVolume(address, enabled))
                }
                "openBluetoothCodecSettings" -> result.success(openDeveloperOptions())
                else -> result.notImplemented()
            }
        }
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, BLUETOOTH_EVENT_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    bluetoothEventSink = events
                }
                override fun onCancel(arguments: Any?) {
                    bluetoothEventSink = null
                }
            }
        )

        // Chromecast: discovery via MediaRouter, control via RemoteMediaClient.
        if (castController == null) {
            castController = CastController(this)
            castController!!.start()
        }
        castController?.onEvent = { event -> emitCastEvent(event) }

        // DLNA renderers as system MediaRouter routes.
        if (dlnaRouteProvider == null) {
            dlnaRouteProvider = DlnaRouteProvider(applicationContext)
            MediaRouter.getInstance(this).addProvider(dlnaRouteProvider!!)
        }
        DlnaRouteRegistry.onRouteSelected = { id ->
            val name = DlnaRouteRegistry.routes.firstOrNull { it.id == id }?.name ?: ""
            emitCastEvent(mapOf("event" to "dlnaRouteSelected", "id" to id, "name" to name))
        }
        DlnaRouteRegistry.onRouteUnselected = { id ->
            emitCastEvent(mapOf("event" to "dlnaRouteUnselected", "id" to id))
        }
        DlnaRouteRegistry.onVolumeSet = { _, volume ->
            emitCastEvent(mapOf("event" to "dlnaVolume", "volume" to volume / 100.0))
        }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CAST_CHANNEL).setMethodCallHandler { call, result ->
            val cc = castController
            when (call.method) {
                "discover" -> result.success(cc?.discover())
                "connect" -> {
                    cc?.connect(call.argument<String>("id") ?: "")
                    result.success(null)
                }
                "disconnect" -> { cc?.disconnect(); result.success(null) }
                "load" -> {
                    cc?.load(
                        call.argument<String>("url") ?: "",
                        call.argument<String>("title"),
                        call.argument<String>("artist"),
                    )
                    result.success(null)
                }
                "play" -> { cc?.play(); result.success(null) }
                "pause" -> { cc?.pause(); result.success(null) }
                "stop" -> { cc?.stop(); result.success(null) }
                "seek" -> { cc?.seek((call.argument<Int>("position") ?: 0).toLong()); result.success(null) }
                "setVolume" -> { cc?.setVolume((call.argument<Double>("volume") ?: 1.0)); result.success(null) }
                "getVolume" -> { cc?.getVolume().let { result.success(it) } }
                "getOutputRoutes" -> result.success(cc?.getOutputRoutes())
                "selectOutputRoute" -> result.success(cc?.selectOutputRoute(call.argument<String>("id") ?: ""))
                else -> result.notImplemented()
            }
        }
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, DLNA_ROUTE_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "publishRoutes" -> {
                    val routes = (call.argument<List<Map<String, Any>>>("routes") ?: emptyList())
                        .mapNotNull { m ->
                            val id = m["id"] as? String ?: return@mapNotNull null
                            DlnaRouteRegistry.Route(id, (m["name"] as? String) ?: id)
                        }
                    DlnaRouteRegistry.publish(routes)
                    result.success(null)
                }
                "selectRoute" -> {
                    val id = call.argument<String>("id") ?: ""
                    trySelectDlnaRoute(id, attempts = 4, result)
                }
                "unselectRoute" -> {
                    if (DlnaRouteRegistry.selectedId != null) {
                        MediaRouter.getInstance(this).selectRoute(MediaRouter.getInstance(this).defaultRoute)
                    }
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, CAST_EVENT_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    castEventSink = events
                }
                override fun onCancel(arguments: Any?) {
                    castEventSink = null
                }
            }
        )

        // Audio processing channel for Android native AudioEffect counterparts.
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, EQUALIZER_CHANNEL).setMethodCallHandler { call, result ->
            if (!justAudioProcessingController.handle(call, result)) {
                result.notImplemented()
            }
        }

        // Audio converter channel
        // NOTE: FLAC to ALAC and M4A to MP3 conversion features are disabled for now
        /*
        audioConverter = AudioConverterFactory.create()
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CONVERTER_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "convertToFlac" -> {
                    val uri = call.argument<String>("uri")
                    if (uri != null) {
                        mainScope.launch {
                            val conversionResult = audioConverter?.convertToFlac(Uri.parse(uri), context) as? ConversionResult
                            handleConversionResult(conversionResult, result)
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "URI is required", null)
                    }
                }
                "convertToMp3" -> {
                    val uri = call.argument<String>("uri")
                    if (uri != null) {
                        mainScope.launch {
                            val conversionResult = audioConverter?.convertToMp3(Uri.parse(uri), context) as? ConversionResult
                            handleConversionResult(conversionResult, result)
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "URI is required", null)
                    }
                }
                "isSupported" -> {
                    val extension = call.argument<String>("extension")
                    if (extension != null) {
                        result.success(audioConverter?.isSupported(extension) ?: false)
                    } else {
                        result.error("INVALID_ARGUMENT", "extension is required", null)
                    }
                }
                "cancel" -> {
                    audioConverter?.cancel()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
        */
        
        // Register USB hot-plug receiver
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, DIAGNOSTICS_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getProcessExitInfo" -> result.success(getProcessExitInfo())
                "getBuildFlavor" -> result.success(BuildConfig.FLAVOR)
                else -> result.notImplemented()
            }
        }
        directUsb.maybeRequestPermissionForConnectedUsbAudioDevices(reason = "flutter engine configured")

        widgetChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, WIDGET_CHANNEL)
        widgetLaunchInbox.detach()
        dispatchWidgetIntent(intent)
        widgetChannel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "widgetHandlerReady" -> result.success(widgetLaunchInbox.attach())
                "widgetHandlerDetached" -> {
                    widgetLaunchInbox.detach()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
        // A cached engine may already have a Dart handler from the old Activity.
        // A fresh engine drains the queued URI when its handler attaches later.
        widgetChannel?.invokeMethod("channelReady", null)
    }

    // private fun handleConversionResult(conversionResult: ConversionResult?, result: MethodChannel.Result) {
//         when (conversionResult) {
//             is ConversionResult.Success -> result.success(mapOf(
//                 "success" to true,
//                 "outputUri" to conversionResult.outputUri,
//                 "outputPath" to conversionResult.outputPath
//             ))
//             is ConversionResult.Error -> result.error("CONVERSION_ERROR", conversionResult.message, null)
//             else -> result.error("CONVERSION_ERROR", "Unknown error", null)
//         }
//     }

    private fun openDocumentTree() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN_DOCUMENT_TREE)
    }

    private fun openDocument(mimeTypes: List<String>) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN_DOCUMENT)
    }

    private fun createDocument(fileName: String, mimeType: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_CREATE_DOCUMENT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_OPEN_DOCUMENT_TREE) {
            if (resultCode == RESULT_OK && data?.data != null) {
                val uri = data.data!!
                pendingDocumentTreeResult?.success(uri.toString())
            } else {
                pendingDocumentTreeResult?.success(null)
            }
            pendingDocumentTreeResult = null
        } else if (requestCode == REQUEST_OPEN_DOCUMENT) {
            if (resultCode == RESULT_OK && data?.data != null) {
                val uri = data.data!!
                pendingOpenDocumentResult?.success(uri.toString())
            } else {
                pendingOpenDocumentResult?.success(null)
            }
            pendingOpenDocumentResult = null
        } else if (requestCode == REQUEST_CREATE_DOCUMENT) {
            if (resultCode == RESULT_OK && data?.data != null) {
                val uri = data.data!!
                pendingCreateDocumentResult?.success(uri.toString())
            } else {
                pendingCreateDocumentResult?.success(null)
            }
            pendingCreateDocumentResult = null
        } else if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            pendingOverlayPermissionResult?.success(Settings.canDrawOverlays(this))
            pendingOverlayPermissionResult = null
        }
    }

    private fun takePersistableUriPermission(uriString: String): Boolean {
        val uri = Uri.parse(uriString)
        // Persist read + write so metadata edits survive restarts. WRITE may not
        // be granted for older (read-only) grants, so fall back to READ only.
        return try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            true
        } catch (e: Exception) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    private fun releasePersistableUriPermission(uriString: String) {
        val uri = Uri.parse(uriString)
        // Release each flag independently; releasing them together fails if one
        // isn't held.
        for (flag in listOf(
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )) {
            try {
                contentResolver.releasePersistableUriPermission(uri, flag)
            } catch (e: Exception) {
                // Ignore if this flag wasn't held
            }
        }
    }

    private fun getPersistedUriPermissions(): List<String> {
        return contentResolver.persistedUriPermissions.map { it.uri.toString() }
    }

    private fun saveImageToGallery(bytes: ByteArray, fileName: String, albumName: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("WRITE_EXTERNAL_STORAGE permission is required on Android 9 and below.")
        }

        val resolver = contentResolver
        val safeFileName = if (fileName.lowercase().endsWith(".png")) {
            fileName
        } else {
            "$fileName.png"
        }
        val safeAlbumName = albumName.ifBlank { "Flick" }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, safeFileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$safeAlbumName")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val itemUri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Unable to create a gallery entry.")

        try {
            resolver.openOutputStream(itemUri)?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: throw IllegalStateException("Unable to open an output stream.")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val publishValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(itemUri, publishValues, null, null)
            }

            return itemUri.toString()
        } catch (e: Exception) {
            resolver.delete(itemUri, null, null)
            throw e
        }
    }

    private fun dispatchWidgetIntent(intent: Intent?) {
        if (intent?.action != HOME_WIDGET_LAUNCH_ACTION) return
        val uri = intent.data?.toString() ?: return
        val dispatch = widgetLaunchInbox.offer(uri) ?: return
        widgetChannel?.invokeMethod("dispatch", dispatch)
    }

    private fun emitCastEvent(event: Map<String, Any?>) {
        runOnUiThread { castEventSink?.success(event) }
    }

    // ponytail: after publish(), MediaRouter assigns provider routes a mangled
    // unique id ("<provider>_<descriptorId>"); routes may not be visible the
    // instant Dart asks us to select, so retry briefly.
    private fun trySelectDlnaRoute(udn: String, attempts: Int, result: MethodChannel.Result) {
        val router = MediaRouter.getInstance(this)
        val route = router.routes.firstOrNull {
            it.playbackType == MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE &&
                (it.id.endsWith("_$udn") || it.id == udn)
        }
        if (route != null) {
            router.selectRoute(route)
            result.success(true)
        } else if (attempts > 1) {
            Handler(mainLooper).postDelayed({ trySelectDlnaRoute(udn, attempts - 1, result) }, 250)
        } else {
            result.success(false)
        }
    }

    private fun handleExternalPlaybackIntent(intent: Intent?) {
        val payload = buildExternalPlaybackPayload(intent) ?: return
        pendingExternalPlaybackPayload = payload
        integrationChannel?.invokeMethod("externalPlaybackIntent", payload)
    }

    private fun buildExternalPlaybackPayload(intent: Intent?): Map<String, Any?>? {
        if (intent?.action != Intent.ACTION_VIEW) {
            return null
        }

        val dataUri = intent.data ?: return null
        if (dataUri.scheme != ContentResolver.SCHEME_CONTENT) {
            return null
        }

        if (!isReadableContentUri(dataUri)) {
            Log.w("Flick", "Ignoring unreadable external playback URI: $dataUri")
            return null
        }

        val sourcePackage = resolveSourcePackage(intent)
        return mapOf(
            "uri" to dataUri.toString(),
            "mimeType" to (intent.type ?: contentResolver.getType(dataUri)),
            "displayName" to getDocumentDisplayName(dataUri.toString()),
            "sourcePackage" to sourcePackage,
            "fromLocker" to (sourcePackage == LOCKER_PACKAGE),
        )
    }

    private fun isReadableContentUri(uri: Uri): Boolean {
        return try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (e: Exception) {
            Log.w("Flick", "Failed to open external playback URI: $uri", e)
            false
        }
    }

    private fun resolveSourcePackage(intent: Intent): String? {
        val referrerUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_REFERRER, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_REFERRER)
        }

        packageNameFromReferrer(referrerUri)?.let { return it }

        val referrerName = intent.getStringExtra(Intent.EXTRA_REFERRER_NAME)
        packageNameFromReferrer(referrerName?.let(Uri::parse))?.let { return it }

        packageNameFromReferrer(referrer)?.let { return it }

        return null
    }

    private fun packageNameFromReferrer(uri: Uri?): String? {
        if (uri == null) return null
        if (uri.scheme == "android-app") {
            return uri.host?.takeIf { it.isNotBlank() }
        }
        return uri.host?.takeIf { it.isNotBlank() }
    }

    private fun returnToLocker(): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(LOCKER_RETURN_URI)).apply {
                `package` = LOCKER_PACKAGE
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w("Flick", "Failed to return to Locker", e)
            false
        }
    }

    private fun getDocumentDisplayName(uriString: String): String? {
        android.util.Log.d("MainActivity", "[getDocumentDisplayName] Input URI: $uriString")
        return try {
            val uri = Uri.parse(uriString)
            android.util.Log.d("MainActivity", "[getDocumentDisplayName] Parsed URI: $uri")
            val fromSingle = DocumentFile.fromSingleUri(this, uri)?.name
            if (!fromSingle.isNullOrBlank()) {
                android.util.Log.d("MainActivity", "[getDocumentDisplayName] From single: $fromSingle")
                return fromSingle
            }

            val fromTree = DocumentFile.fromTreeUri(this, uri)?.name
            if (!fromTree.isNullOrBlank()) {
                android.util.Log.d("MainActivity", "[getDocumentDisplayName] From tree: $fromTree")
                return fromTree
            }

            contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (index >= 0) {
                        val name = cursor.getString(index)
                        android.util.Log.d("MainActivity", "[getDocumentDisplayName] From query: $name")
                        return name
                    }
                }
            }
            android.util.Log.d("MainActivity", "[getDocumentDisplayName] No name found")
            null
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "[getDocumentDisplayName] Error: ${e.message}", e)
            null
        }
    }

    private fun resolveTreeUriToPath(uriString: String): String? {
        return resolveStorageInfo(uriString)["fsPath"] as? String
    }

    // Single source of truth for filesystem path + MediaStore-volume routing, including
    // removable (USB/SD) volumes which queryMediaStoreAudio must target by their own name.
    private fun resolveStorageInfo(uriString: String): Map<String, Any?> {
        val allFiles = mapOf(
            "allFilesAccess" to hasAllFilesAccess(),
            // Dart uses this to decide whether the SAF tree is the only
            // accessible view of the folder (scoped storage, API 30+).
            "sdkInt" to Build.VERSION.SDK_INT,
        )
        val unknown = mapOf<String, Any?>(
            "fsPath" to null,
            "mediaStoreVolume" to null,
            "isRemovable" to false,
            "isPrimary" to false,
            "state" to "unknown",
        ) + allFiles
        return try {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "file") {
                return mapOf(
                    "fsPath" to uri.path,
                    "mediaStoreVolume" to null,
                    "isRemovable" to false,
                    "isPrimary" to true,
                    "state" to "mounted",
                ) + allFiles
            }
            if (uri.scheme != "content" ||
                uri.authority != "com.android.externalstorage.documents"
            ) {
                return unknown
            }

            // getTreeDocumentId already decodes the URI segment. Decoding
            // again would turn literal names like "Mix%20" into another path.
            val documentId = DocumentsContract.getTreeDocumentId(uri)
            val parts = documentId.split(":", limit = 2)
            if (parts.isEmpty()) return unknown
            val volumeId = parts[0]
            val relativePath = parts.getOrNull(1)?.trim('/') ?: ""
            val isHomeDoc = volumeId.equals("home", ignoreCase = true)
            val isPrimaryDoc = volumeId.equals("primary", ignoreCase = true) || isHomeDoc

            val sm = getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            val matched = sm?.storageVolumes?.firstOrNull { vol ->
                if (isPrimaryDoc) vol.isPrimary
                else vol.uuid?.equals(volumeId, ignoreCase = true) == true
            }

            if (matched != null) {
                val basePath = storageVolumePath(matched)?.let {
                    if (isHomeDoc) "$it/${Environment.DIRECTORY_DOCUMENTS}" else it
                }
                val candidate = if (basePath == null) null
                    else if (relativePath.isEmpty()) basePath else "$basePath/$relativePath"
                val readable = candidate != null &&
                    File(candidate).let { it.exists() && it.canRead() }
                return mapOf(
                    "fsPath" to if (readable) candidate else null,
                    "mediaStoreVolume" to mediaStoreVolumeNameFor(volumeId, matched.isPrimary),
                    "isRemovable" to matched.isRemovable,
                    "isPrimary" to matched.isPrimary,
                    "state" to matched.state,
                ) + allFiles
            }

            // Legacy fallback when StorageManager didn't enumerate the volume.
            val basePath = when (volumeId.lowercase()) {
                "primary" -> Environment.getExternalStorageDirectory().absolutePath
                "home" -> Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS
                ).absolutePath
                else -> "/storage/$volumeId"
            }
            val candidate = if (relativePath.isEmpty()) basePath else "$basePath/$relativePath"
            val candidateFile = File(candidate)
            if (!candidateFile.exists() || !candidateFile.canRead()) return unknown

            mapOf(
                "fsPath" to candidateFile.absolutePath,
                "mediaStoreVolume" to mediaStoreVolumeNameFor(volumeId, isPrimaryDoc),
                "isRemovable" to !isPrimaryDoc,
                "isPrimary" to isPrimaryDoc,
                "state" to "mounted",
            ) + allFiles
        } catch (e: Exception) {
            Log.w("MainActivity", "resolveStorageInfo failed: ${e.message}", e)
            unknown
        }
    }

    private fun storageVolumePath(volume: StorageVolume): String? {
        // getDirectory() is API 30+; getPath() is hidden until then.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volume.directory?.absolutePath
        } else {
            try {
                val m = volume.javaClass.getMethod("getPath")
                m.invoke(volume) as? String
            } catch (e: Exception) {
                Log.w("MainActivity", "StorageVolume.getPath() reflection failed: ${e.message}")
                null
            }
        }
    }

    private fun mediaStoreVolumeNameFor(volumeId: String, isPrimary: Boolean): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        // Primary uses VOLUME_EXTERNAL_PRIMARY. For removable storage the
        // MediaStore volume name is the volume's FAT UUID (== volumeId here).
        // If MediaStore hasn't indexed that volume, the query yields 0 rows
        // and the caller falls back to SAF — safe degradation.
        return if (isPrimary) MediaStore.VOLUME_EXTERNAL_PRIMARY else volumeId
    }

    private fun deleteDocumentViaSaf(folderTreeUri: String, filePath: String): Boolean {
        return try {
            val fileUri = Uri.parse(filePath)

            if (fileUri.scheme == "content") {
                return try {
                    DocumentsContract.deleteDocument(contentResolver, fileUri)
                } catch (e: Exception) {
                    Log.w("MainActivity", "content URI deletion failed: ${e.message}")
                    false
                }
            }

            // Delete the physical file first. Removing only the MediaStore row
            // leaves the bytes on disk, so the track reappears on the next scan.
            try {
                val treeUri = Uri.parse(folderTreeUri)
                val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
                val decodedId = Uri.decode(treeDocId)
                val parts = decodedId.split(":", limit = 2)
                if (parts.isNotEmpty()) {
                    val volumeId = parts[0]
                    val relativeFolderPath = parts.getOrNull(1)?.trim('/') ?: ""
                    val basePath = when (volumeId.lowercase()) {
                        "primary" -> Environment.getExternalStorageDirectory().absolutePath
                        "home" -> Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOCUMENTS
                        ).absolutePath
                        else -> "/storage/$volumeId"
                    }

                    val folderBase = if (relativeFolderPath.isEmpty()) {
                        File(basePath)
                    } else {
                        File("$basePath/$relativeFolderPath")
                    }
                    val canonicalBase = folderBase.canonicalPath.trimEnd('/')
                    val canonicalFile = File(filePath).canonicalPath.trimEnd('/')

                    // Only delete descendants of the tree root, never the root.
                    if (canonicalFile != canonicalBase &&
                        canonicalFile.startsWith("$canonicalBase/")) {
                        val relativePath = canonicalFile.removePrefix("$canonicalBase/")
                        val childDocId = "$treeDocId/$relativePath"
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                        if (DocumentsContract.deleteDocument(contentResolver, childUri)) {
                            removeFromMediaStore(filePath)
                            return true
                        }
                    }
                }
            } catch (safEx: Exception) {
                Log.w("MainActivity", "SAF deletion failed, trying File.delete: ${safEx.message}")
            }

            try {
                val file = File(filePath)
                if (!file.exists()) {
                    removeFromMediaStore(filePath)
                    return true
                }
                if (file.delete()) {
                    removeFromMediaStore(filePath)
                    return true
                }
            } catch (fileEx: Exception) {
                Log.w("MainActivity", "File.delete failed: ${fileEx.message}")
            }

            // Last resort: dropping the MediaStore row removes the track from
            // the library even if the file itself could not be deleted.
            if (removeFromMediaStore(filePath)) {
                Log.w("MainActivity", "File not removed, dropped MediaStore entry only: $filePath")
                return true
            }

            false
        } catch (e: Exception) {
            Log.w("MainActivity", "deleteDocumentViaSaf failed: ${e.message}", e)
            false
        }
    }

    private fun removeFromMediaStore(filePath: String): Boolean {
        return try {
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val rowsDeleted = contentResolver.delete(
                uri,
                "${MediaStore.Audio.Media.DATA} = ?",
                arrayOf(filePath)
            )
            rowsDeleted > 0
        } catch (e: Exception) {
            Log.w("MainActivity", "removeFromMediaStore failed: ${e.message}", e)
            false
        }
    }

    private fun writeFileBytesViaSaf(folderTreeUri: String, filePath: String, tempFilePath: String): Boolean {
        return try {
            val treeUri = Uri.parse(folderTreeUri)
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val decodedId = Uri.decode(treeDocId)
            val parts = decodedId.split(":", limit = 2)
            if (parts.isEmpty()) return false

            val volumeId = parts[0]
            val relativeFolderPath = parts.getOrNull(1)?.trim('/') ?: ""
            val basePath = when (volumeId.lowercase()) {
                "primary" -> Environment.getExternalStorageDirectory().absolutePath
                "home" -> Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS
                ).absolutePath
                else -> "/storage/$volumeId"
            }

            val folderBase = if (relativeFolderPath.isEmpty()) {
                File(basePath)
            } else {
                File("$basePath/$relativeFolderPath")
            }
            val canonicalBase = folderBase.canonicalPath.trimEnd('/')
            val canonicalFile = File(filePath).canonicalPath.trimEnd('/')

            if (!canonicalFile.startsWith("$canonicalBase/") && canonicalFile != canonicalBase) {
                Log.w("MainActivity", "writeFileBytesViaSaf: file $filePath is not under tree $folderTreeUri")
                return false
            }

            val relativePath = if (canonicalFile == canonicalBase) {
                ""
            } else {
                canonicalFile.removePrefix("$canonicalBase/")
            }

            val childDocId = if (relativePath.isEmpty()) treeDocId else "$treeDocId/$relativePath"
            val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)

            val tempFile = File(tempFilePath)
            if (!tempFile.exists()) {
                Log.w("MainActivity", "writeFileBytesViaSaf: temp file not found: $tempFilePath")
                return false
            }

            // Stream in fixed-size chunks: hi-res/DSD files can be hundreds of
            // MB, and readBytes() would need the whole file as one contiguous
            // heap array (OutOfMemoryError kills the process — it is an Error,
            // not an Exception, so the catch below must also cover Throwable).
            contentResolver.openOutputStream(childUri, "wt")?.use { output ->
                tempFile.inputStream().use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            } ?: run {
                Log.w("MainActivity", "writeFileBytesViaSaf: failed to open output stream for $childUri")
                return false
            }

            tempFile.delete()
            true
        } catch (e: Throwable) {
            Log.w("MainActivity", "writeFileBytesViaSaf failed: ${e.message}", e)
            false
        }
    }

    /**
     * Copies a content:// document into a private, uniquely named cache file so
     * the Rust tagger can open it by path. The shared playback staging cache is
     * deliberately not reused: editing mutates this copy, and a cached playback
     * file must never be modified in place.
     */
    private fun stageContentUriForEdit(uriString: String, extensionHint: String?): Map<String, Any?> {
        val uri = Uri.parse(uriString)
        val stagingDir = File(cacheDir, "metadata_edits")
        val stagedFile = try {
            stagingDir.mkdirs()
            pruneMetadataEditStaging(stagingDir)
            val extension = resolveStagedExtension(uri, extensionHint)
            File(stagingDir, "${md5(uriString)}-${UUID.randomUUID()}.$extension")
        } catch (e: Exception) {
            Log.w("MainActivity", "stageContentUriForEdit: could not prepare staging: ${e.message}", e)
            return mapOf("path" to null, "reason" to "failed")
        }

        return try {
            val input = contentResolver.openInputStream(uri)
            if (input == null) {
                mapOf("path" to null, "reason" to "not_found")
            } else {
                input.use { source ->
                    stagedFile.outputStream().use { target ->
                        copyStream(source, target)
                    }
                }
                if (stagedFile.length() <= 0L) {
                    stagedFile.delete()
                    mapOf("path" to null, "reason" to "failed")
                } else {
                    mapOf("path" to stagedFile.absolutePath, "reason" to null)
                }
            }
        } catch (e: java.io.FileNotFoundException) {
            stagedFile.delete()
            Log.w("MainActivity", "stageContentUriForEdit: not found: $uriString (${e.message})")
            mapOf("path" to null, "reason" to "not_found")
        } catch (e: SecurityException) {
            stagedFile.delete()
            Log.w("MainActivity", "stageContentUriForEdit: no access: $uriString (${e.message})")
            mapOf("path" to null, "reason" to "no_access")
        } catch (e: Exception) {
            stagedFile.delete()
            Log.w("MainActivity", "stageContentUriForEdit failed: ${e.message}", e)
            mapOf("path" to null, "reason" to "failed")
        }
    }

    /** Streams an edited cache copy back over the original document URI. */
    private fun writeBytesToContentUri(uriString: String, tempFilePath: String): Map<String, Any?> {
        val tempFile = File(tempFilePath)
        if (!tempFile.exists()) {
            return mapOf("success" to false, "reason" to "failed")
        }
        val uri = Uri.parse(uriString)

        return try {
            val output = openContentUriOutputStream(uri)
            if (output == null) {
                mapOf("success" to false, "reason" to "failed")
            } else {
                output.use { target ->
                    tempFile.inputStream().use { source ->
                        copyStream(source, target)
                    }
                }
                mapOf("success" to true, "reason" to "ok")
            }
        } catch (e: java.io.FileNotFoundException) {
            Log.w("MainActivity", "writeBytesToContentUri: not found: $uriString (${e.message})")
            mapOf("success" to false, "reason" to "not_found")
        } catch (e: SecurityException) {
            Log.w("MainActivity", "writeBytesToContentUri: no access: $uriString (${e.message})")
            mapOf("success" to false, "reason" to "no_access")
        } catch (e: Exception) {
            Log.w("MainActivity", "writeBytesToContentUri failed: ${e.message}", e)
            mapOf("success" to false, "reason" to "failed")
        }
    }

    /**
     * "wt" truncates before writing, but a few document providers only accept
     * "w". A genuinely missing document fails again on the retry and is
     * reported as not_found by the caller.
     */
    private fun openContentUriOutputStream(uri: Uri): OutputStream? {
        return try {
            contentResolver.openOutputStream(uri, "wt")
        } catch (e: IllegalArgumentException) {
            contentResolver.openOutputStream(uri, "w")
        } catch (e: java.io.FileNotFoundException) {
            contentResolver.openOutputStream(uri, "w")
        }
    }

    private fun copyStream(source: InputStream, target: OutputStream) {
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            target.write(buffer, 0, read)
        }
        target.flush()
    }

    /** Clear edit copies left behind by crashes; 24h is far beyond one save. */
    private fun pruneMetadataEditStaging(stagingDir: File) {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        stagingDir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isIgnoringBatteryOptimizations(packageName) ?: false
    }

    private fun requestIgnoreBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }

        try {
            val settingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(settingsIntent)
            return true
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to open battery optimization settings", e)
            return false
        }
    }

    // Whether this build declares MANAGE_EXTERNAL_STORAGE at all (the `full`
    // flavor does; the `play` flavor ships without it). UI for granting the
    // permission must be hidden when it can never be granted.
    private fun isAllFilesAccessSupported(): Boolean {
        return try {
            val flags = PackageManager.GET_PERMISSIONS
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, flags)
            }
            info.requestedPermissions?.contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE) == true
        } catch (e: Exception) {
            Log.w("MainActivity", "isAllFilesAccessSupported() failed: ${e.message}")
            false
        }
    }

    // All Files Access (MANAGE_EXTERNAL_STORAGE): raw filesystem reads on every volume,
    // i.e. the UAPP/Neutron-style scanning precondition. Below Android 11 the legacy
    // storage permissions already give equivalent broad read access.
    private fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Environment.isExternalStorageManager()
            } catch (e: Exception) {
                Log.w("MainActivity", "isExternalStorageManager() failed: ${e.message}")
                false
            }
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun openAllFilesAccessSettings(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false
        }
        return try {
            var intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            try {
                startActivity(intent)
                return true
            } catch (e: Exception) {
                Log.w("MainActivity", "App-specific all-files settings screen unavailable, trying global")
            }
            intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to open all-files-access settings", e)
            false
        }
    }

    private fun readTextDocument(uriString: String): String {
        android.util.Log.d("MainActivity", "[readTextDocument] Input URI: $uriString")
        val uri = Uri.parse(uriString)
        android.util.Log.d("MainActivity", "[readTextDocument] Parsed URI: $uri")
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Unable to open input stream")

        android.util.Log.d("MainActivity", "[readTextDocument] Read ${bytes.size} bytes")
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            android.util.Log.d("MainActivity", "[readTextDocument] Detected UTF-8 BOM")
            return String(bytes.copyOfRange(3, bytes.size), Charsets.UTF_8)
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun writeTextDocument(uriString: String, content: String): Boolean {
        android.util.Log.d("MainActivity", "[writeTextDocument] Input URI: $uriString")
        android.util.Log.d("MainActivity", "[writeTextDocument] Content length: ${content.length}")
        val uri = Uri.parse(uriString)
        android.util.Log.d("MainActivity", "[writeTextDocument] Parsed URI: $uri")
        contentResolver.openOutputStream(uri, "wt")?.use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.flush()
            android.util.Log.d("MainActivity", "[writeTextDocument] Write successful")
            return true
        }
        android.util.Log.e("MainActivity", "[writeTextDocument] Failed to open output stream")
        return false
    }

    // Phase 1: Fast Scan (Filesystem only)
    private data class TreeDocEntry(
        val documentId: String,
        val name: String,
        val mimeType: String?,
        val size: Long,
        val lastModified: Long,
        val relativePath: String,
    )

    private data class TreeWalkResult(
        val entries: List<TreeDocEntry>,
        // False when the tree root itself could not be queried (permission
        // revoked, volume unplugged, provider gone). Callers must keep the
        // existing library rows instead of treating this as an empty folder.
        val rootAccessible: Boolean,
        // False when at least one subdirectory could not be listed. The walk is
        // still useful, but absence from the result does not prove deletion.
        val complete: Boolean,
    )

    // Walks a granted SAF tree with one contentResolver.query() per directory
    // (single IPC returning all columns) instead of per-attribute DocumentFile
    // calls. .nomedia short-circuits a subtree when [respectNomedia] is true.
    private fun listTreeDocuments(
        uriString: String,
        respectNomedia: Boolean,
    ): TreeWalkResult {
        val treeUri = Uri.parse(uriString)
        val rootDocId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            Log.w("MainActivity", "SAF tree id unavailable for '$uriString': ${e.message}", e)
            return TreeWalkResult(emptyList(), rootAccessible = false, complete = false)
        }
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val result = mutableListOf<TreeDocEntry>()
        val visited = HashSet<String>()
        var dirCount = 0
        var rootAccessible = true
        var complete = true

        fun scanDir(docId: String, relativePath: String, isRoot: Boolean) {
            if (!visited.add(docId)) return
            dirCount++
            val childrenUri = try {
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            } catch (e: Exception) {
                Log.w("MainActivity", "SAF child URI failed for $docId: ${e.message}", e)
                if (isRoot) rootAccessible = false
                complete = false
                return
            }
            val entries = mutableListOf<TreeDocEntry>()
            var hasNomedia = false
            var dirListed = false
            try {
                val cursor = contentResolver.query(childrenUri, projection, null, null, null)
                if (cursor == null) {
                    Log.w("MainActivity", "SAF query null cursor: $childrenUri")
                } else {
                    cursor.use { c ->
                        // Providers can return an empty or partial cursor without
                        // throwing. It must not be used as evidence of deletion.
                        val extras = c.extras
                        if (extras.getBoolean(DocumentsContract.EXTRA_LOADING) ||
                            extras.containsKey(DocumentsContract.EXTRA_ERROR)
                        ) {
                            complete = false
                        }
                        val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        val mimeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                        val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                        val modIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        while (c.moveToNext()) {
                            val name = c.getString(nameIdx)
                            val childDocId = c.getString(idIdx)
                            val mimeType = c.getString(mimeIdx)
                            if (name.isNullOrEmpty() || childDocId.isNullOrEmpty() || mimeType.isNullOrEmpty()) {
                                complete = false
                                continue
                            }
                            if (respectNomedia && name == ".nomedia") hasNomedia = true
                            entries.add(
                                TreeDocEntry(
                                    documentId = childDocId,
                                    name = name,
                                    mimeType = mimeType,
                                    size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L,
                                    lastModified = if (modIdx >= 0 && !c.isNull(modIdx)) c.getLong(modIdx) else 0L,
                                    relativePath = relativePath,
                                ),
                            )
                        }
                    }
                    dirListed = true
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "listTreeDocuments query failed: ${e.message}", e)
            }
            if (!dirListed) {
                if (isRoot) rootAccessible = false
                complete = false
                return
            }
            if (isRoot && complete && entries.isEmpty()) {
                // Some providers return an empty children cursor for a stale
                // tree URI. Confirm the root still exists before pruning rows.
                rootAccessible = try {
                    val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val rootProjection = arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    contentResolver.query(rootUri, rootProjection, null, null, null)?.use { c ->
                        val extras = c.extras
                        !extras.getBoolean(DocumentsContract.EXTRA_LOADING) &&
                            !extras.containsKey(DocumentsContract.EXTRA_ERROR) &&
                            c.moveToFirst() &&
                            c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)) ==
                            DocumentsContract.Document.MIME_TYPE_DIR
                    } == true
                } catch (e: Exception) {
                    Log.w("MainActivity", "SAF empty-root confirmation failed: ${e.message}", e)
                    false
                }
                if (!rootAccessible) complete = false
                return
            }
            if (hasNomedia) return
            for (e in entries) {
                if (e.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val childPath =
                        if (relativePath.isEmpty()) e.name else "$relativePath/${e.name}"
                    scanDir(e.documentId, childPath, isRoot = false)
                } else {
                    result.add(e)
                }
            }
        }

        scanDir(rootDocId, "", isRoot = true)
        Log.d(
            "MainActivity",
            "SAF tree walk '$uriString' rootDocId=$rootDocId dirs=$dirCount files=${result.size} " +
                "rootAccessible=$rootAccessible complete=$complete",
        )
        return TreeWalkResult(result, rootAccessible, complete)
    }

    private fun fastScanAudioFiles(
        uriString: String,
        filterNonMusicFilesAndFolders: Boolean
    ): Map<String, Any?> {
        val audioExtensions =
            setOf("mp3", "flac", "wav", "aac", "m4a", "ogg", "oga", "ogx", "opus", "wma", "alac", "aif", "aiff", "cue", "wv", "dsf", "dff")
        val treeUri = Uri.parse(uriString)
        val walk = listTreeDocuments(uriString, filterNonMusicFilesAndFolders)

        val files = walk.entries
            .filter { entry ->
                !filterNonMusicFilesAndFolders ||
                    entry.name.substringAfterLast('.', "").lowercase() in audioExtensions
            }
            .map { entry ->
                val extension = entry.name.substringAfterLast('.', "").lowercase()
                mapOf(
                    "uri" to DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.documentId).toString(),
                    "name" to entry.name,
                    "size" to entry.size,
                    "lastModified" to entry.lastModified,
                    "mimeType" to entry.mimeType,
                    "extension" to extension,
                    "relativeFolderPath" to entry.relativePath,
                )
            }

        return mapOf(
            "files" to files,
            "rootAccessible" to walk.rootAccessible,
            "complete" to walk.complete,
        )
    }

    private fun scanPlaylistFiles(
        uriString: String,
        filterNonMusicFilesAndFolders: Boolean
    ): List<Map<String, Any?>> {
        val playlistExtensions = setOf("m3u", "m3u8")
        val treeUri = Uri.parse(uriString)

        return listTreeDocuments(uriString, filterNonMusicFilesAndFolders).entries
            .filter { entry ->
                entry.name.substringAfterLast('.', "").lowercase() in playlistExtensions
            }
            .map { entry ->
                val extension = entry.name.substringAfterLast('.', "").lowercase()
                mapOf(
                    "uri" to DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.documentId).toString(),
                    "name" to entry.name,
                    "size" to entry.size,
                    "lastModified" to entry.lastModified,
                    "extension" to extension,
                )
            }
    }

    // Phase 2: Metadata Extraction (Targeted)
    // Uses coroutine parallelism — each chunk runs on its own MediaMetadataRetriever
    private suspend fun extractMetadataForFiles(uris: List<String>): List<Map<String, Any?>> {
        if (uris.isEmpty()) return emptyList()
        if (uris.size == 1) return listOf(extractSingleMetadata(uris[0]))

        val cores = Runtime.getRuntime().availableProcessors()
        val chunkCount = min(cores * 2, uris.size).coerceAtLeast(1)
        val chunkSize = (uris.size + chunkCount - 1) / chunkCount

        val chunks = uris.chunked(chunkSize)
        Log.d("MainActivity", "SAF metadata: files=${uris.size} parallelism=$chunkCount (cores=$cores)")

        return coroutineScope {
            chunks.map { chunk ->
                async(Dispatchers.IO) {
                    extractMetadataChunk(chunk)
                }
            }.awaitAll().flatten()
        }
    }

    private fun extractSingleMetadata(uriString: String): Map<String, Any?> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(uriString))
            readMetadataFields(retriever, uriString)
        } catch (e: Exception) {
            mapOf("uri" to uriString)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun extractMetadataChunk(uriStrings: List<String>): List<Map<String, Any?>> {
        val retriever = MediaMetadataRetriever()
        val results = mutableListOf<Map<String, Any?>>()
        var perFileMs = 0L

        for (uriString in uriStrings) {
            val t0 = System.currentTimeMillis()
            try {
                retriever.setDataSource(context, Uri.parse(uriString))
                results.add(readMetadataFields(retriever, uriString))
            } catch (e: Exception) {
                results.add(mapOf("uri" to uriString))
            }
            perFileMs += System.currentTimeMillis() - t0
        }

        try { retriever.release() } catch (_: Exception) {}
        if (uriStrings.isNotEmpty()) {
            Log.d("MainActivity", "SAF metadata chunk: files=${uriStrings.size} perFileAvg=${perFileMs / uriStrings.size}ms")
        }
        return results
    }

    private fun readMetadataFields(retriever: MediaMetadataRetriever, uriString: String): Map<String, Any?> {
        val metadata = mutableMapOf<String, Any?>("uri" to uriString)

        metadata["title"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
        metadata["artist"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
        metadata["album"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
        metadata["albumArtist"] = extractMetadataByKeyName(retriever, "METADATA_KEY_ALBUMARTIST")
        val (trackNumber, trackTotal) = parseMetadataNumberPair(
            extractMetadataByKeyName(retriever, "METADATA_KEY_CD_TRACK_NUMBER")
        )
        metadata["trackNumber"] = trackNumber
        metadata["trackTotal"] = trackTotal
        val (discNumber, discTotal) = parseMetadataNumberPair(
            extractMetadataByKeyName(retriever, "METADATA_KEY_DISC_NUMBER")
        )
        metadata["discNumber"] = discNumber
        metadata["discTotal"] = discTotal
        metadata["bitrate"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
        metadata["mimeType"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
        metadata["genre"] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
            ?.takeIf { it.isNotBlank() }
        metadata["year"] = parseMetadataNumber(
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val sampleRateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
            if (sampleRateStr != null) {
                metadata["sampleRate"] = sampleRateStr.toIntOrNull()
            }

            val bitDepthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
            if (bitDepthStr != null) {
                metadata["bitDepth"] = bitDepthStr.toIntOrNull()
            }
        }

        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        if (durationStr != null) {
            metadata["duration"] = durationStr.toLongOrNull()
        }

        metadata["metadataRead"] = true
        return metadata
    }

    private fun extractEmbeddedArtwork(uriString: String): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(uriString))
            retriever.embeddedPicture
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // Ignore
            }
        }
    }

    private fun extractMetadataByKeyName(
        retriever: MediaMetadataRetriever,
        keyName: String
    ): String? {
        return try {
            val key = MediaMetadataRetriever::class.java.getField(keyName).getInt(null)
            retriever.extractMetadata(key)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseMetadataNumber(rawValue: String?): Int? {
        if (rawValue.isNullOrBlank()) return null
        val match = Regex("""\d+""").find(rawValue) ?: return null
        val value = match.value.toIntOrNull() ?: return null
        return if (value > 0) value else null
    }

    private fun queryMediaStoreCursor(
        collection: Uri,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Cursor {
        val cursor = checkNotNull(
            contentResolver.query(collection, projection, selection, selectionArgs, null)
        ) { "MediaStore returned no cursor for $collection" }
        try {
            val extras = cursor.extras
            check(!extras.getBoolean(DocumentsContract.EXTRA_LOADING) &&
                !extras.containsKey(DocumentsContract.EXTRA_ERROR)) {
                "MediaStore returned an incomplete cursor for $collection"
            }
            return cursor
        } catch (e: Exception) {
            cursor.close()
            throw e
        }
    }

    private fun queryMediaStoreAudio(folderPaths: List<String>, volumeName: String? = null): List<Map<String, Any?>> {
        val startedAt = System.nanoTime()
        val result = mutableListOf<Map<String, Any?>>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(volumeName ?: MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.COMPOSER,
        )

        val folderScope = MediaStoreFolderScope(folderPaths)
        val selection = folderScope.selection(MediaStore.Audio.Media.DATA)
        val selectionArgs = selection?.let { folderScope.args.toTypedArray() }

        val seenPaths = HashSet<String>()
        try {
            queryMediaStoreCursor(collection, projection, selection, selectionArgs).use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val albumArtistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dateModCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val data = cursor.getString(dataCol) ?: continue
                    if (!folderScope.contains(data)) continue
                    seenPaths.add(data)
                    val extension = data.substringAfterLast('.', "").lowercase()

                    val id = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(collection, id).toString()

                    val duration = cursor.getLong(durationCol)
                    val size = cursor.getLong(sizeCol)

                    val rawTrack = cursor.getInt(trackCol)
                    val trackNumber = rawTrack % 1000
                    val discNumber = rawTrack / 1000

                    result.add(mapOf(
                        "uri" to contentUri,
                        "filePath" to data,
                        "name" to (data.substringAfterLast('/', "")),
                        "title" to (cursor.getString(titleCol) ?: ""),
                        "artist" to (cursor.getString(artistCol) ?: ""),
                        "album" to (cursor.getString(albumCol) ?: ""),
                        "albumArtist" to (if (albumArtistCol >= 0) cursor.getString(albumArtistCol) else null),
                        "duration" to duration,
                        "trackNumber" to (if (trackNumber > 0) trackNumber else null),
                        "discNumber" to (if (discNumber > 0) discNumber else null),
                        "year" to (cursor.getInt(yearCol).takeIf { it > 0 }),
                        "mimeType" to (cursor.getString(mimeCol)),
                        "extension" to extension,
                        "size" to size,
                        "lastModified" to (cursor.getLong(dateModCol) * 1000L),
                        "dateAdded" to (cursor.getLong(dateAddedCol) * 1000L),
                        "bitrate" to (if (duration > 0 && size > 0)
                            (size * 8_000.0 / duration).toLong().toString() else null),
                    ))
                }
            }

            // DSD (.dsf/.dff/.wv) is not classified as audio by some OEM
            // MediaScanners (ColorOS/Oppo/Realme, HiOS/Tecno/Infinix), so it
            // never reaches MediaStore.Audio.Media and the default scan drops
            // it. Pull these from the Files collection and merge, deduping by
            // filesystem path against the audio rows already collected.
            val dsdExtPatterns = listOf("%.dsf", "%.dff", "%.wv")
            val dsdExtSelection = dsdExtPatterns.joinToString(" OR ") {
                "${MediaStore.Files.FileColumns.DATA} LIKE ?"
            }
            val dsdSelection = folderScope.selection(MediaStore.Files.FileColumns.DATA)
                ?.let { "($it) AND ($dsdExtSelection)" } ?: dsdExtSelection
            val dsdSelectionArgs = (folderScope.args + dsdExtPatterns).toTypedArray()
            val dsdCollection =
                MediaStore.Files.getContentUri(volumeName ?: MediaStore.VOLUME_EXTERNAL)
            val dsdProjection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.DATE_ADDED,
            )
            queryMediaStoreCursor(dsdCollection, dsdProjection, dsdSelection, dsdSelectionArgs).use { c ->
                val dIdCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val dDataCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val dMimeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val dSizeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dModCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val dAddedCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                while (c.moveToNext()) {
                    val data = c.getString(dDataCol) ?: continue
                    if (!folderScope.contains(data)) continue
                    if (!seenPaths.add(data)) continue
                    val contentUri = ContentUris.withAppendedId(
                        dsdCollection, c.getLong(dIdCol)
                    ).toString()
                    result.add(mapOf(
                        "uri" to contentUri,
                        "filePath" to data,
                        "name" to (data.substringAfterLast('/', "")),
                        "title" to "",
                        "artist" to "",
                        "album" to "",
                        "albumArtist" to null,
                        "duration" to 0L,
                        "trackNumber" to null,
                        "discNumber" to null,
                        "year" to null,
                        "mimeType" to (if (!c.isNull(dMimeCol)) c.getString(dMimeCol) else null),
                        "extension" to data.substringAfterLast('.', "").lowercase(),
                        "size" to c.getLong(dSizeCol),
                        "lastModified" to (c.getLong(dModCol) * 1000L),
                        "dateAdded" to (c.getLong(dAddedCol) * 1000L),
                        "bitrate" to null,
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "MediaStore audio query failed: ${e.message}", e)
            // Do not return a partial result or let reconciliation mask a
            // provider failure. The channel forwards this to the SAF fallback.
            throw e
        }

        // Reconciliation walk: some OEM MediaScanners (MIUI/HyperOS, Vivo/
        // Funtouch, Honor) never index DSD into Audio.Media OR the Files
        // collection, so both queries above come back empty for .dsf/.dff/.wv
        // while FLAC scans fine. Stat-only raw walk recovers exactly those
        // files (readable paths only; primary always, removable under All
        // Files Access or a legacy grant) and a fire-and-forget scanFile lets
        // the indexer catch up for future scans.
        try {
            val missing = DsdReconciliation.findUnindexedDsdFiles(folderPaths, seenPaths)
            for (f in missing) {
                val data = f.absolutePath
                if (!seenPaths.add(data)) continue
                result.add(mapOf(
                    "uri" to "file://$data",
                    "filePath" to data,
                    "name" to f.name,
                    "title" to "",
                    "artist" to "",
                    "album" to "",
                    "albumArtist" to null,
                    "duration" to 0L,
                    "trackNumber" to null,
                    "discNumber" to null,
                    "year" to null,
                    "mimeType" to null,
                    "extension" to f.extension.lowercase(),
                    "size" to f.length(),
                    "lastModified" to f.lastModified(),
                    "dateAdded" to f.lastModified(),
                    "bitrate" to null,
                ))
            }
            if (missing.isNotEmpty()) {
                Log.d("MainActivity", "DSD reconciliation walk found ${missing.size} unindexed file(s)")
                healMediaStoreIndex(missing.map { it.absolutePath })
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "DSD reconciliation walk failed: ${e.message}", e)
        }

        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        Log.d("MainActivity", "MediaStore audio query + DSD reconciliation returned ${result.size} rows in ${elapsedMs}ms")
        return result
    }

    /**
     * Best-effort index heal: ask MediaScanner to pick up files it missed.
     * OEMs that hard-skip DSD at the MIME layer will keep skipping — this is
     * a bonus, the reconciliation walk already covers correctness.
     */
    private fun healMediaStoreIndex(paths: List<String>) {
        if (paths.isEmpty()) return
        try {
            MediaScannerConnection.scanFile(
                applicationContext,
                paths.toTypedArray(),
                null,
                null,
            )
        } catch (e: Exception) {
            Log.w("MainActivity", "MediaScanner heal failed: ${e.message}")
        }
    }

    private fun queryMediaStoreNonAudio(folderPaths: List<String>, volumeName: String? = null): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return result
        }

        val collection = MediaStore.Files.getContentUri(volumeName ?: MediaStore.VOLUME_EXTERNAL)

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
        )

        val nonAudioExtensions = setOf("cue", "log", "txt", "m3u", "m3u8")

        val folderScope = MediaStoreFolderScope(folderPaths)
        val extSelection = nonAudioExtensions.joinToString(" OR ") {
            "${MediaStore.Files.FileColumns.DATA} LIKE ?"
        }
        val selection = folderScope.selection(MediaStore.Files.FileColumns.DATA)
            ?.let { "($it) AND ($extSelection)" } ?: extSelection
        val selectionArgs = (folderScope.args + nonAudioExtensions.map { "%.$it" }).toTypedArray()

        try {
            queryMediaStoreCursor(collection, projection, selection, selectionArgs).use { cursor ->
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateModCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val data = cursor.getString(dataCol) ?: continue
                    if (!folderScope.contains(data)) continue
                    val extension = data.substringAfterLast('.', "").lowercase()
                    if (extension !in nonAudioExtensions) continue

                    result.add(mapOf(
                        "filePath" to data,
                        "name" to (cursor.getString(nameCol) ?: ""),
                        "extension" to extension,
                        "size" to cursor.getLong(sizeCol),
                        "lastModified" to (cursor.getLong(dateModCol) * 1000L),
                        "mimeType" to cursor.getString(mimeCol),
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "MediaStore non-audio query failed: ${e.message}", e)
            throw e
        }

        return result
    }

    private fun queryMediaStoreDeletions(filePaths: List<String>, volumeName: String? = null): List<String> {
        if (filePaths.isEmpty()) return emptyList()

        val existingPaths = mutableSetOf<String>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(volumeName ?: MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(MediaStore.Audio.Media.DATA)

        try {
            contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) {
                    cursor.getString(dataCol)?.let { existingPaths.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "MediaStore deletion check failed: ${e.message}", e)
            return emptyList()
        }

        val deleted = filePaths.filter { !existingPaths.contains(it) }
        // DSD isn't indexed in Audio.Media on some OEMs, so it is always absent
        // here. Confirm via the filesystem before declaring a DSD file deleted.
        val dsdExtensions = setOf("dsf", "dff", "wv")
        return deleted.filterNot { path ->
            path.substringAfterLast('.', "").lowercase() in dsdExtensions &&
                java.io.File(path).exists()
        }
    }

    private fun registerMediaStoreObserver() {
        val notify = {
            try {
                mediaStoreEventSink?.success(mapOf("type" to "changed"))
            } catch (_: Exception) {}
        }
        if (mediaStoreContentObserver == null) {
            mediaStoreContentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    notify()
                }
            }
            try {
                contentResolver.registerContentObserver(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    true,
                    mediaStoreContentObserver!!
                )
            } catch (e: Exception) {
                Log.w("MainActivity", "Audio observer registration failed: ${e.message}")
                mediaStoreContentObserver = null
            }
        }
        // Some DSD lands in the Files collection (never in Audio.Media on these
        // OEMs), so watch it too for live-change nudges.
        if (mediaFilesContentObserver == null) {
            val filesUri =
                MediaStore.Files.getContentUri(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaStore.VOLUME_EXTERNAL
                    } else {
                        "external"
                    }
                )
            mediaFilesContentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    notify()
                }
            }
            try {
                contentResolver.registerContentObserver(filesUri, true, mediaFilesContentObserver!!)
            } catch (e: Exception) {
                Log.w("MainActivity", "Files observer registration failed: ${e.message}")
                mediaFilesContentObserver = null
            }
        }
    }

    private fun unregisterMediaStoreObserver() {
        mediaStoreContentObserver?.let {
            contentResolver.unregisterContentObserver(it)
        }
        mediaStoreContentObserver = null
        mediaFilesContentObserver?.let {
            contentResolver.unregisterContentObserver(it)
        }
        mediaFilesContentObserver = null
    }

    private fun cacheUriForPlayback(
        uriString: String,
        extensionHint: String?,
        maxSizeBytes: Long? = null,
        maxStagingBytes: Long? = null,
    ): String? {
        val uri = Uri.parse(uriString)
        val normalizedExt = resolveStagedExtension(uri, extensionHint)
        val stagingDir = java.io.File(cacheDir, "playback_staging").apply { mkdirs() }
        val fileHash = md5(uriString)
        val stagedFile = java.io.File(stagingDir, "$fileHash.$normalizedExt")
        val tempFile = java.io.File(stagingDir, "$fileHash.$normalizedExt.tmp")

        try {
            // Reuse cached file only when non-empty and likely complete.
            val expectedLength = try {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    if (afd.length > 0L) afd.length else null
                }
            } catch (_: Exception) {
                null
            }
            if (stagedFile.exists() && stagedFile.length() > 0L) {
                if (expectedLength == null || stagedFile.length() == expectedLength) {
                    prunePlaybackStaging(maxStagingBytes, stagedFile)
                    return stagedFile.absolutePath
                }
            }

            // Optional caller-supplied cap (metadata enrichment on huge files):
            // bail before copying when the source is larger than allowed.
            if (maxSizeBytes != null && expectedLength != null && expectedLength > maxSizeBytes) {
                return null
            }

            if (tempFile.exists()) {
                tempFile.delete()
            }

            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                // If provider temporarily fails, keep using last known-good staged file.
                if (stagedFile.exists() && stagedFile.length() > 0L) {
                    return stagedFile.absolutePath
                }
                return null
            }

            inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (!tempFile.exists() || tempFile.length() <= 0L) {
                tempFile.delete()
                return null
            }

            if (stagedFile.exists()) {
                stagedFile.delete()
            }
            if (!tempFile.renameTo(stagedFile)) {
                // Fallback: explicit copy and cleanup if rename fails.
                tempFile.inputStream().use { input ->
                    stagedFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile.delete()
            }

            if (stagedFile.length() <= 0L) return null
            prunePlaybackStaging(maxStagingBytes, stagedFile)
            return stagedFile.absolutePath
        } catch (e: Exception) {
            android.util.Log.e("FlickPlayback", "cacheUriForPlayback failed for $uriString: ${e.message}", e)
            return null
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    /// Keeps the SAF staging dir under [maxBytes] by deleting the oldest
    /// staged files (by mtime) until it fits. null or <= 0 disables pruning.
    /// [protect] (the file just staged, or null for a maintenance pass) is
    /// never deleted.
    private fun prunePlaybackStaging(maxBytes: Long?, protect: java.io.File?) {
        if (maxBytes == null || maxBytes <= 0L) return
        try {
            val stagingDir = java.io.File(cacheDir, "playback_staging")
            if (!stagingDir.isDirectory) return
            val files = stagingDir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") }
                ?: return
            var total = files.sumOf { it.length() }
            if (total <= maxBytes) return
            val byOldest = files.sortedBy { it.lastModified() }
            for (file in byOldest) {
                if (total <= maxBytes) break
                if (protect != null && file.absolutePath == protect.absolutePath) continue
                val len = file.length()
                if (file.delete()) {
                    total -= len
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("FlickPlayback", "prunePlaybackStaging failed: ${e.message}")
        }
    }

    private fun readSiblingLyrics(audioUriString: String): Map<String, String>? {
        return try {
            val audioUri = Uri.parse(audioUriString)
            when (audioUri.scheme) {
                "content" -> readSiblingLyricsFromContentUri(audioUri)
                "file" -> readSiblingLyricsFromFilePath(audioUri.path)
                null, "" -> readSiblingLyricsFromFilePath(audioUriString)
                else -> null
            }
        } catch (e: Exception) {
            android.util.Log.w("FlickLyrics", "readSiblingLyrics failed for $audioUriString: ${e.message}")
            null
        }
    }

    private val siblingArtworkStems = listOf("cover", "folder", "album", "albumart", "front")
    private val siblingArtworkExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    private fun readSiblingArtwork(sourceUriString: String): ByteArray? {
        return try {
            val sourceUri = Uri.parse(sourceUriString)
            when (sourceUri.scheme) {
                "content" -> readSiblingArtworkFromContentUri(sourceUri)
                "file" -> readSiblingArtworkFromFilePath(sourceUri.path)
                null, "" -> readSiblingArtworkFromFilePath(sourceUriString)
                else -> null
            }
        } catch (e: Exception) {
            android.util.Log.w("FlickArtwork", "readSiblingArtwork failed for $sourceUriString: ${e.message}")
            null
        }
    }

    private fun readSiblingArtworkFromContentUri(audioUri: Uri): ByteArray? {
        val authority = audioUri.authority ?: return null
        val documentId = try {
            DocumentsContract.getDocumentId(audioUri)
        } catch (e: Exception) {
            return null
        }
        val slashIndex = documentId.lastIndexOf('/')
        if (slashIndex <= 0) return null
        val parentDocumentId = documentId.substring(0, slashIndex)

        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId)
        } catch (e: Exception) {
            return null
        }

        contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (idIndex == -1 || nameIndex == -1) return null

            val matches = mutableListOf<Pair<String, String>>()
            while (cursor.moveToNext()) {
                val displayName = cursor.getString(nameIndex) ?: continue
                if (!isSiblingArtworkName(displayName)) continue
                val childDocumentId = cursor.getString(idIndex) ?: continue
                matches.add(displayName to childDocumentId)
            }
            matches.sortBy { it.first.lowercase() }

            for ((_, childDocumentId) in matches) {
                val childUri = DocumentsContract.buildDocumentUri(authority, childDocumentId)
                val bytes = readBytesFromUri(childUri)
                if (bytes != null && bytes.isNotEmpty()) return bytes
            }
        }

        return null
    }

    private fun readSiblingArtworkFromFilePath(audioPath: String?): ByteArray? {
        if (audioPath.isNullOrBlank()) return null
        val parent = java.io.File(audioPath).parentFile ?: return null
        val covers = parent.listFiles()
            ?.filter { it.isFile && isSiblingArtworkName(it.name) }
            ?.sortedBy { it.name.lowercase() }
            ?: return null

        for (cover in covers) {
            try {
                return cover.readBytes()
            } catch (_: Exception) {
                // try the next candidate
            }
        }
        return null
    }

    private fun isSiblingArtworkName(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return false
        val stem = name.substring(0, dot).lowercase()
        if (!siblingArtworkExtensions.contains(name.substring(dot + 1).lowercase())) {
            return false
        }
        return siblingArtworkStems.any { stem == it || stem.startsWith("$it.") }
    }

    private fun readBytesFromUri(uri: Uri): ByteArray? {
        return try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    private fun readEmbeddedLyrics(audioUriString: String): Map<String, String>? {
        val retriever = MediaMetadataRetriever()
        return try {
            val uri = Uri.parse(audioUriString)

            val handledByUri = when (uri.scheme) {
                "content", "file" -> {
                    retriever.setDataSource(this, uri)
                    true
                }
                else -> false
            }

            if (!handledByUri) {
                retriever.setDataSource(audioUriString)
            }

            val lyricKey = try {
                MediaMetadataRetriever::class.java.getField("METADATA_KEY_LYRIC").getInt(null)
            } catch (_: Exception) {
                null
            }

            val lyricText = lyricKey?.let { retriever.extractMetadata(it) }
            if (lyricText.isNullOrBlank()) {
                val id3Lyrics = parseId3EmbeddedLyrics(audioUriString)
                if (!id3Lyrics.isNullOrBlank()) {
                    mapOf(
                        "content" to id3Lyrics,
                        "source" to "embedded:id3",
                    )
                    } else {
                        val flacVorbisLyrics = parseFlacVorbisLyrics(audioUriString)
                        if (!flacVorbisLyrics.isNullOrBlank()) {
                            mapOf(
                                "content" to flacVorbisLyrics,
                                "source" to "embedded:vorbis",
                            )
                        } else {
                            val mp4Lyrics = parseMp4Lyrics(audioUriString)
                            if (!mp4Lyrics.isNullOrBlank()) {
                                mapOf(
                                    "content" to mp4Lyrics,
                                    "source" to "embedded:mp4",
                                )
                            } else {
                                // ponytail: covers ID3/FLAC/MP4/Ogg; WMA and APE tags not parsed
                                val oggLyrics = parseOggLyrics(audioUriString)
                                if (!oggLyrics.isNullOrBlank()) {
                                    mapOf(
                                        "content" to oggLyrics,
                                        "source" to "embedded:ogg",
                                    )
                                } else {
                                    null
                                }
                            }
                        }
                    }
            } else {
                mapOf(
                    "content" to lyricText,
                    "source" to "embedded",
                )
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun parseId3EmbeddedLyrics(audioUriString: String): String? {
        return openAudioInputStream(audioUriString)?.use { input ->
            val header = ByteArray(10)
            if (!readExact(input, header)) return@use null
            if (header[0] != 'I'.code.toByte() ||
                header[1] != 'D'.code.toByte() ||
                header[2] != '3'.code.toByte()
            ) {
                return@use null
            }

            val version = header[3].toInt() and 0xFF
            val flags = header[5].toInt() and 0xFF
            val tagSize = readSynchsafeInt(header, 6)
            if (tagSize <= 0) return@use null

            val tagBody = ByteArray(tagSize)
            if (!readExact(input, tagBody)) return@use null

            val unsyncFlagSet = (flags and 0x80) != 0
            val data = if (unsyncFlagSet) deUnsynchronize(tagBody) else tagBody

            when (version) {
                2 -> parseId3v22Lyrics(data)
                3, 4 -> parseId3v23Or24Lyrics(data, version, flags)
                else -> null
            }
        }
    }

    private fun parseId3v22Lyrics(data: ByteArray): String? {
        var pos = 0
        var bestSync: String? = null
        var bestUnsync: String? = null

        while (pos + 6 <= data.size) {
            val id = String(data, pos, 3, Charsets.ISO_8859_1)
            if (id.all { it == '\u0000' }) break

            val frameSize =
                ((data[pos + 3].toInt() and 0xFF) shl 16) or
                ((data[pos + 4].toInt() and 0xFF) shl 8) or
                (data[pos + 5].toInt() and 0xFF)
            pos += 6
            if (frameSize <= 0 || pos + frameSize > data.size) break

            val frameData = data.copyOfRange(pos, pos + frameSize)
            when (id) {
                "SLT" -> {
                    val parsed = parseSyltFrame(frameData)
                    if (!parsed.isNullOrBlank()) bestSync = parsed
                }
                "ULT" -> {
                    val parsed = parseUsltFrame(frameData)
                    if (!parsed.isNullOrBlank() && bestUnsync == null) {
                        bestUnsync = parsed
                    }
                }
            }
            pos += frameSize
        }

        return bestSync ?: bestUnsync
    }

    private fun parseId3v23Or24Lyrics(data: ByteArray, version: Int, flags: Int): String? {
        var pos = 0
        val extendedHeaderFlagSet = (flags and 0x40) != 0
        if (extendedHeaderFlagSet && data.size >= 4) {
            val extSize = if (version == 4) {
                readSynchsafeInt(data, 0)
            } else {
                readBigEndianInt(data, 0)
            }
            if (extSize > 0 && extSize < data.size) {
                pos = if (version == 3) 4 + extSize else extSize
            }
        }

        var bestSync: String? = null
        var bestUnsync: String? = null

        while (pos + 10 <= data.size) {
            val id = String(data, pos, 4, Charsets.ISO_8859_1)
            if (id.all { it == '\u0000' }) break

            val frameSize = if (version == 4) {
                readSynchsafeInt(data, pos + 4)
            } else {
                readBigEndianInt(data, pos + 4)
            }
            pos += 10
            if (frameSize <= 0 || pos + frameSize > data.size) break

            val frameData = data.copyOfRange(pos, pos + frameSize)
            when (id) {
                "SYLT" -> {
                    val parsed = parseSyltFrame(frameData)
                    if (!parsed.isNullOrBlank()) bestSync = parsed
                }
                "USLT" -> {
                    val parsed = parseUsltFrame(frameData)
                    if (!parsed.isNullOrBlank() && bestUnsync == null) {
                        bestUnsync = parsed
                    }
                }
            }
            pos += frameSize
        }

        return bestSync ?: bestUnsync
    }

    private fun parseUsltFrame(frameData: ByteArray): String? {
        if (frameData.size < 4) return null

        val encoding = frameData[0].toInt() and 0xFF
        val descriptorStart = 4 // 1-byte encoding + 3-byte language
        val termLen = nullTerminatorLength(encoding)
        val descriptorEnd = findTerminator(frameData, descriptorStart, encoding)
        val textStart = when {
            descriptorEnd >= 0 -> descriptorEnd + termLen
            else -> descriptorStart
        }
        if (textStart >= frameData.size) return null

        val raw = frameData.copyOfRange(textStart, frameData.size)
        val text = decodeId3Text(raw, encoding).trim()
        return text.ifBlank { null }
    }

    private fun parseSyltFrame(frameData: ByteArray): String? {
        if (frameData.size < 7) return null

        val encoding = frameData[0].toInt() and 0xFF
        val timestampFormat = frameData[4].toInt() and 0xFF
        val descriptorStart = 6 // + content type byte
        val termLen = nullTerminatorLength(encoding)
        val descriptorEnd = findTerminator(frameData, descriptorStart, encoding)
        var pos = if (descriptorEnd >= 0) descriptorEnd + termLen else descriptorStart
        if (pos >= frameData.size) return null

        val lines = mutableListOf<String>()
        while (pos < frameData.size) {
            val textEnd = findTerminator(frameData, pos, encoding)
            if (textEnd < 0) break

            val textBytes = frameData.copyOfRange(pos, textEnd)
            val text = decodeId3Text(textBytes, encoding).trim()
            pos = textEnd + termLen
            if (pos + 4 > frameData.size) break

            val timestamp = readBigEndianInt(frameData, pos)
            pos += 4
            if (text.isBlank()) continue

            val timeMs = when (timestampFormat) {
                1 -> timestamp // milliseconds
                else -> timestamp // fallback for MPEG frames or unknown
            }.coerceAtLeast(0)
            lines.add("${formatLrcTime(timeMs)}$text")
        }

        if (lines.isEmpty()) return null
        return lines.joinToString(separator = "\n")
    }

    private fun parseFlacVorbisLyrics(audioUriString: String): String? {
        return openAudioInputStream(audioUriString)?.use { input ->
            val signature = ByteArray(4)
            if (!readExact(input, signature)) return@use null
            if (!signature.contentEquals(byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte()))) {
                return@use null
            }

            var isLastBlock = false
            while (!isLastBlock) {
                val header = input.read()
                if (header < 0) break

                isLastBlock = (header and 0x80) != 0
                val blockType = header and 0x7F
                val lengthBytes = ByteArray(3)
                if (!readExact(input, lengthBytes)) break
                val blockLength =
                    ((lengthBytes[0].toInt() and 0xFF) shl 16) or
                    ((lengthBytes[1].toInt() and 0xFF) shl 8) or
                    (lengthBytes[2].toInt() and 0xFF)

                if (blockLength < 0) break

                if (blockType == 4) {
                    val commentData = ByteArray(blockLength)
                    if (!readExact(input, commentData)) break
                    return@use parseVorbisCommentLyrics(commentData)
                } else {
                    if (!skipFully(input, blockLength.toLong())) break
                }
            }
            null
        }
    }

    private fun parseOggLyrics(audioUriString: String): String? {
        return openAudioInputStream(audioUriString)?.use { input ->
            val magic = ByteArray(4)
            if (!readExact(input, magic) || String(magic, Charsets.ISO_8859_1) != "OggS") {
                return@use null
            }

            var packetIndex = 0
            val packet = ByteArrayOutputStream()
            val pageHeader = ByteArray(23)
            while (true) {
                if (!readExact(input, pageHeader)) return@use null
                val segmentCount = pageHeader[22].toInt() and 0xFF
                if (segmentCount > 0) {
                    val segmentTable = ByteArray(segmentCount)
                    if (!readExact(input, segmentTable)) return@use null

                    for (i in 0 until segmentCount) {
                        val segmentLength = segmentTable[i].toInt() and 0xFF
                        val chunk = ByteArray(segmentLength)
                        if (!readExact(input, chunk)) return@use null
                        packet.write(chunk)

                        if (segmentLength < 255) {
                            // Packet 1 is the Vorbis/Opus comment header
                            if (packetIndex == 1) {
                                return@use parseVorbisCommentLyrics(packet.toByteArray())
                            }
                            packetIndex++
                            packet.reset()
                        }
                    }
                }
            }
            null
        }
    }

    private fun parseMp4Lyrics(audioUriString: String): String? {
        return openAudioInputStream(audioUriString)?.use { input ->
            val knownTopLevel = setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "pnot")
            while (true) {
                val atom = readMp4AtomHeader(input) ?: return@use null
                if (!knownTopLevel.contains(atom.type)) return@use null
                if (atom.type == "moov") {
                    return@use walkMp4Container(input, atom.contentSize, setOf("udta", "meta"))
                }
                if (!skipFully(input, atom.contentSize)) return@use null
            }
            null
        }
    }

    private class Mp4Atom(val type: String, val contentSize: Long)

    private fun readMp4AtomHeader(input: InputStream): Mp4Atom? {
        val header = ByteArray(8)
        if (!readExact(input, header)) return null
        var size = readBigEndianInt(header, 0).toLong() and 0xFFFFFFFFL
        val type = String(header, 4, 4, Charsets.ISO_8859_1)

        if (size == 1L) {
            val ext = ByteArray(8)
            if (!readExact(input, ext)) return null
            var bigSize = 0L
            for (b in ext) bigSize = (bigSize shl 8) or (b.toLong() and 0xFF)
            if (bigSize < 16) return null
            size = bigSize - 16
        } else if (size == 0L) {
            // Atom extends to end of file; treat as huge
            size = Long.MAX_VALUE / 4
        } else {
            size -= 8
        }

        if (size < 0) return null
        return Mp4Atom(type, size)
    }

    // Walks an atom's children, consuming exactly containerSize bytes.
    // Returns lyrics text from a ©lyr item, or null after consuming the container.
    private fun walkMp4Container(input: InputStream, containerSize: Long, childTypes: Set<String>): String? {
        var remaining = containerSize
        while (remaining >= 8) {
            val atom = readMp4AtomHeader(input) ?: return null
            remaining -= 8
            if (atom.contentSize > remaining) return null
            remaining -= atom.contentSize

            if (atom.type == "\u00A9lyr") {
                return readMp4LyricsData(input, atom.contentSize)
            }

            if (childTypes.contains(atom.type)) {
                var payload = atom.contentSize
                if (atom.type == "meta") {
                    // meta is a FullBox: 4 version/flags bytes precede children
                    if (payload < 4) return null
                    val versionFlags = ByteArray(4)
                    if (!readExact(input, versionFlags)) return null
                    payload -= 4
                }
                val next = when (atom.type) {
                    "moov" -> setOf("udta", "meta")
                    "udta" -> setOf("meta")
                    "meta" -> setOf("ilst")
                    else -> emptySet()
                }
                val found = walkMp4Container(input, payload, next)
                if (found != null) return found
            } else {
                if (!skipFully(input, atom.contentSize)) return null
            }
        }
        if (remaining > 0) skipFully(input, remaining)
        return null
    }

    private fun readMp4LyricsData(input: InputStream, itemSize: Long): String? {
        var remaining = itemSize
        while (remaining >= 8) {
            val atom = readMp4AtomHeader(input) ?: return null
            remaining -= 8
            if (atom.contentSize > remaining) return null
            remaining -= atom.contentSize

            if (atom.type == "data" && atom.contentSize >= 8) {
                val header = ByteArray(8)
                if (!readExact(input, header)) return null
                val payloadLength = (atom.contentSize - 8).toInt()
                if (payloadLength <= 0) return null
                val payload = ByteArray(payloadLength)
                if (!readExact(input, payload)) return null

                val typeIndicator =
                    ((header[1].toInt() and 0xFF) shl 16) or
                        ((header[2].toInt() and 0xFF) shl 8) or
                        (header[3].toInt() and 0xFF)
                // ponytail: type 2 is UTF-16; UTF_16 charset honors a BOM when present
                val text = when (typeIndicator) {
                    2 -> payload.toString(Charsets.UTF_16)
                    else -> payload.toString(Charsets.UTF_8)
                }.replace("\u0000", "").trim()
                return text.ifBlank { null }
            }

            if (!skipFully(input, atom.contentSize)) return null
        }
        return null
    }

    private fun parseVorbisCommentLyrics(data: ByteArray): String? {
        if (data.size < 8) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val vendorLength = readLeIntSafe(buffer) ?: return null
        if (vendorLength < 0 || vendorLength > buffer.remaining()) return null
        buffer.position(buffer.position() + vendorLength)

        val commentCount = readLeIntSafe(buffer) ?: return null
        if (commentCount < 0) return null

        val wantedKeys = listOf(
            "LYRICS",
            "UNSYNCEDLYRICS",
            "UNSYNCED_LYRICS",
        )

        repeat(commentCount) {
            val len = readLeIntSafe(buffer) ?: return null
            if (len < 0 || len > buffer.remaining()) return null

            val bytes = ByteArray(len)
            buffer.get(bytes)
            val entry = bytes.toString(Charsets.UTF_8)
            val sep = entry.indexOf('=')
            if (sep <= 0) return@repeat

            val key = entry.substring(0, sep).uppercase()
            val value = entry.substring(sep + 1).trim()
            if (value.isBlank()) return@repeat
            if (wantedKeys.contains(key)) {
                return value
            }
        }

        return null
    }

    private fun openAudioInputStream(audioUriString: String): InputStream? {
        return try {
            val uri = Uri.parse(audioUriString)
            when (uri.scheme) {
                "content" -> contentResolver.openInputStream(uri)
                "file" -> {
                    val path = uri.path
                    if (path.isNullOrBlank()) null else FileInputStream(path)
                }
                null, "" -> FileInputStream(File(audioUriString))
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readExact(input: InputStream, out: ByteArray): Boolean {
        var offset = 0
        while (offset < out.size) {
            val read = input.read(out, offset, out.size - offset)
            if (read <= 0) return false
            offset += read
        }
        return true
    }

    private fun skipFully(input: InputStream, bytesToSkip: Long): Boolean {
        var remaining = bytesToSkip
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() == -1) return false
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
        return true
    }

    private fun readSynchsafeInt(data: ByteArray, offset: Int): Int {
        if (offset + 3 >= data.size) return 0
        return ((data[offset].toInt() and 0x7F) shl 21) or
            ((data[offset + 1].toInt() and 0x7F) shl 14) or
            ((data[offset + 2].toInt() and 0x7F) shl 7) or
            (data[offset + 3].toInt() and 0x7F)
    }

    private fun readBigEndianInt(data: ByteArray, offset: Int): Int {
        if (offset + 3 >= data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun readLeIntSafe(buffer: ByteBuffer): Int? {
        if (buffer.remaining() < 4) return null
        return buffer.int
    }

    private fun deUnsynchronize(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        var i = 0
        while (i < data.size) {
            val current = data[i]
            if (current == 0xFF.toByte() &&
                i + 1 < data.size &&
                data[i + 1] == 0x00.toByte()
            ) {
                out.write(0xFF)
                i += 2
            } else {
                out.write(current.toInt())
                i += 1
            }
        }
        return out.toByteArray()
    }

    private fun decodeId3Text(bytes: ByteArray, encoding: Int): String {
        if (bytes.isEmpty()) return ""
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.UTF_8
        }
        return bytes.toString(charset).replace("\u0000", "")
    }

    private fun findTerminator(data: ByteArray, start: Int, encoding: Int): Int {
        val termLen = nullTerminatorLength(encoding)
        if (termLen == 1) {
            for (i in start until data.size) {
                if (data[i] == 0.toByte()) return i
            }
            return -1
        }

        var i = start
        while (i + 1 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) return i
            i++
        }
        return -1
    }

    private fun nullTerminatorLength(encoding: Int): Int {
        return when (encoding) {
            1, 2 -> 2
            else -> 1
        }
    }

    private fun formatLrcTime(milliseconds: Int): String {
        val clampedMs = milliseconds.coerceAtLeast(0)
        val minutes = clampedMs / 60000
        val seconds = (clampedMs % 60000) / 1000
        val hundredths = (clampedMs % 1000) / 10
        return "[${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}.${hundredths.toString().padStart(2, '0')}]"
    }

    private fun readSiblingLyricsFromContentUri(audioUri: Uri): Map<String, String>? {
        val audioName = DocumentFile.fromSingleUri(this, audioUri)?.name ?: return null
        val stem = audioName.substringBeforeLast('.', audioName)
        val candidateNames = listOf("$stem.lrc", "$stem.txt", "$stem.xml")
        val candidateSet = candidateNames.map { it.lowercase() }.toSet()

        val authority = audioUri.authority ?: return null
        val documentId = try {
            DocumentsContract.getDocumentId(audioUri)
        } catch (e: Exception) {
            return null
        }
        val slashIndex = documentId.lastIndexOf('/')
        if (slashIndex <= 0) return null
        val parentDocumentId = documentId.substring(0, slashIndex)

        // Fast path: build candidate URIs directly using parent document id.
        for (candidateName in candidateNames) {
            val candidateDocumentId = "$parentDocumentId/$candidateName"
            val directUri = DocumentsContract.buildDocumentUri(authority, candidateDocumentId)
            val directContent = readTextFromUri(directUri)
            if (!directContent.isNullOrBlank()) {
                return mapOf(
                    "content" to directContent,
                    "uri" to directUri.toString(),
                    "name" to candidateName,
                )
            }

            val treeUri = try {
                DocumentsContract.buildDocumentUriUsingTree(audioUri, candidateDocumentId)
            } catch (_: Exception) {
                null
            }
            if (treeUri != null) {
                val treeContent = readTextFromUri(treeUri)
                if (!treeContent.isNullOrBlank()) {
                    return mapOf(
                        "content" to treeContent,
                        "uri" to treeUri.toString(),
                        "name" to candidateName,
                    )
                }
            }
        }

        // Fallback: list siblings from parent and match candidate names case-insensitively.
        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId)
        } catch (e: Exception) {
            return null
        }

        contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (idIndex == -1 || nameIndex == -1) {
                return null
            }

            while (cursor.moveToNext()) {
                val displayName = cursor.getString(nameIndex) ?: continue
                if (!candidateSet.contains(displayName.lowercase())) continue

                val childDocumentId = cursor.getString(idIndex) ?: continue
                val childUri = DocumentsContract.buildDocumentUri(authority, childDocumentId)
                val content = readTextFromUri(childUri)
                if (!content.isNullOrBlank()) {
                    return mapOf(
                        "content" to content,
                        "uri" to childUri.toString(),
                        "name" to displayName,
                    )
                }
            }
        }

        return null
    }

    private fun readSiblingLyricsFromFilePath(audioPath: String?): Map<String, String>? {
        if (audioPath.isNullOrBlank()) return null

        val audioFile = java.io.File(audioPath)
        val parent = audioFile.parentFile ?: return null
        val stem = audioFile.name.substringBeforeLast('.', audioFile.name)
        val candidateNames = listOf("$stem.lrc", "$stem.txt", "$stem.xml", "$stem.LRC", "$stem.TXT", "$stem.XML")

        for (candidateName in candidateNames) {
            val candidateFile = java.io.File(parent, candidateName)
            if (!candidateFile.exists() || !candidateFile.isFile) continue

            val text = try {
                candidateFile.readText(Charsets.UTF_8)
            } catch (_: Exception) {
                try {
                    candidateFile.readText(Charsets.ISO_8859_1)
                } catch (_: Exception) {
                    null
                }
            }

            if (!text.isNullOrBlank()) {
                return mapOf(
                    "content" to text,
                    "uri" to candidateFile.absolutePath,
                    "name" to candidateFile.name,
                )
            }
        }

        return null
    }

    private fun readTextFromUri(uri: Uri): String? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeAudioExtension(extensionHint: String?): String {
        val ext = extensionHint
            ?.trim()
            ?.lowercase()
            ?.removePrefix(".")
            ?.ifEmpty { null } ?: return "m4a"

        return when (ext) {
            "aif", "aiff" -> "aiff"
            "m4a", "alac" -> "m4a"
            else -> ext
        }
    }

    // The provider's display name carries the real container extension (e.g.
    // .dsf/.dff for DSD), which generic mime-derived hints (e.g. "dsd") lose.
    private fun resolveStagedExtension(uri: Uri, extensionHint: String?): String {
        val providerExt = try {
            contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && cursor.moveToFirst()) {
                        cursor.getString(nameIndex)
                            ?.substringAfterLast('.', "")
                            ?.lowercase()
                            ?.ifEmpty { null }
                    } else {
                        null
                    }
                }
        } catch (_: Exception) {
            null
        }

        // Only trust the provider name when it is a plausible audio extension.
        return if (providerExt != null && providerExt.length in 2..5 && providerExt.all { it.isLetterOrDigit() }) {
            providerExt
        } else {
            normalizeAudioExtension(extensionHint)
        }
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun getProcessExitInfo(): List<Map<String, Any?>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
        return try {
            activityManager.getHistoricalProcessExitReasons(null, 0, 0).map { info ->
                mapOf(
                    "reason" to info.reason,
                    "timestamp" to info.timestamp,
                    "description" to info.description,
                    "importance" to info.importance,
                    "processName" to info.processName,
                    "pid" to info.pid,
                    "pss" to info.pss,
                    "rss" to info.rss,
                    "status" to info.status,
                    "trace" to readExitTrace(info),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readExitTrace(info: ApplicationExitInfo): String? {
        return try {
            val stream = info.traceInputStream ?: return null
            val text = stream.bufferedReader().use { reader ->
                val buffer = CharArray(16 * 1024)
                val read = reader.read(buffer)
                if (read <= 0) null else String(buffer, 0, read)
            }
            text?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private val btBatteryServiceUuid = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val btBatteryCharUuid = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun ensureA2dpProxy() {
        if (a2dpProxy != null) return
        val adapter = bluetoothAdapter() ?: return
        try {
            adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                    if (profile == BluetoothProfile.A2DP) {
                        a2dpProxy = proxy as? BluetoothA2dp
                    }
                }
                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.A2DP) a2dpProxy = null
                }
            }, BluetoothProfile.A2DP)
        } catch (e: SecurityException) {
            Log.w("Bluetooth", "getProfileProxy blocked (BLUETOOTH_CONNECT not granted?): ${e.message}")
        }
    }

    private fun getBluetoothDevices(includeBonded: Boolean): List<Map<String, Any?>> {
        val a2dp = a2dpProxy
        val connectedAddresses = try {
            a2dp?.connectedDevices?.map { it.address }?.toSet() ?: emptySet()
        } catch (e: SecurityException) {
            Log.w("Bluetooth", "connectedDevices blocked: ${e.message}")
            emptySet()
        }
        val connectedDevices = a2dp?.connectedDevices ?: emptyList()
        fun deviceMap(address: String, name: String): Map<String, Any?> = mapOf(
            "address" to address,
            "name" to name.ifBlank { "Unknown" },
            "isA2dp" to true,
            "isConnected" to (address in connectedAddresses)
        )
        val result = connectedDevices.map { deviceMap(it.address, it.name ?: "Unknown") }.toMutableList()
        if (includeBonded) {
            val adapter = bluetoothAdapter()
            val bonded = try {
                adapter?.bondedDevices ?: emptySet()
            } catch (e: SecurityException) {
                Log.w("Bluetooth", "bondedDevices blocked: ${e.message}")
                emptySet()
            }
            val seen = result.map { it["address"] as String }.toMutableSet()
            for (device in bonded) {
                val address = try { device.address } catch (e: SecurityException) { continue }
                if (address in seen) {
                    // upgrade existing entry's connected flag
                    val idx = result.indexOfFirst { it["address"] == address }
                    if (idx >= 0) result[idx] = deviceMap(address, device.name ?: "Unknown")
                    continue
                }
                val name = try { device.name ?: "Unknown" } catch (e: SecurityException) { "Unknown" }
                result.add(deviceMap(address, name))
                seen.add(address)
            }
        }
        // connected first
        return result.sortedByDescending { (it["isConnected"] as Boolean) }
    }

    private fun btCodecTypeLabel(t: Int): String = when (t) {
        0 -> "SBC"; 1 -> "AAC"; 2 -> "aptX"; 3 -> "aptX HD"; 4 -> "LDAC"; else -> "Codec $t"
    }

    private fun devLogD(msg: String) {
        if (directUsb.developerMode) Log.d("FlickBT", msg)
    }
    private fun btDecodeSampleRate(bits: Int): Int? = when {
        bits and 0x1 != 0 -> 44100
        bits and 0x2 != 0 -> 88200
        bits and 0x4 != 0 -> 48000
        bits and 0x8 != 0 -> 96000
        bits and 0x10 != 0 -> 176400
        bits and 0x20 != 0 -> 192000
        else -> null
    }
    private fun btDecodeBitsPerSample(bits: Int): Int? = when {
        bits and 0x1 != 0 -> 16
        bits and 0x2 != 0 -> 24
        bits and 0x4 != 0 -> 32
        else -> null
    }
    private fun btDecodeChannelMode(bits: Int): String? = when {
        bits and 0x1 != 0 -> "Mono"
        bits and 0x2 != 0 -> "Stereo"
        bits and 0x4 != 0 -> "Dual"
        else -> null
    }

    // Requires API 33+ (BluetoothA2dp.getCodecStatus is @SystemApi → reflection).
    private fun getBluetoothCodecStatus(address: String): Map<String, Any?>? {
        relaxHiddenApiPolicy()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            devLogD("getCodecStatus: skipped (API ${Build.VERSION.SDK_INT} < 33)")
            return null
        }
        val a2dp = a2dpProxy ?: run {
            devLogD("getCodecStatus: no a2dpProxy")
            return null
        }
        val device = a2dp.connectedDevices.firstOrNull { it.address == address } ?: run {
            devLogD("getCodecStatus: device $address not among ${a2dp.connectedDevices.size} connected")
            return null
        }
        return try {
            val status = a2dp.javaClass
                .getMethod("getCodecStatus", BluetoothDevice::class.java)
                .invoke(a2dp, device) ?: return null
            val config = status.javaClass.getMethod("getCodecConfig").invoke(status) ?: return null
            val codecType = config.javaClass.getMethod("getCodecType").invoke(config) as Int
            val sampleRate = config.javaClass.getMethod("getSampleRate").invoke(config) as Int
            val bitsPerSample = config.javaClass.getMethod("getBitsPerSample").invoke(config) as Int
            val channelMode = config.javaClass.getMethod("getChannelMode").invoke(config) as Int
            val statusMap = mapOf(
                "codecName" to btCodecTypeLabel(codecType),
                "sampleRate" to btDecodeSampleRate(sampleRate),
                "bitsPerSample" to btDecodeBitsPerSample(bitsPerSample),
                "channelMode" to btDecodeChannelMode(channelMode)
            )
            devLogD("getCodecStatus: $address -> ${statusMap["codecName"]} ${statusMap["sampleRate"]}Hz ${statusMap["bitsPerSample"]}-bit")
            statusMap
        } catch (e: Exception) {
            devLogD("getCodecStatus: reflection failed: ${e.message}")
            Log.w("Bluetooth", "getCodecStatus failed: ${e.message}")
            null
        }
    }

    private var hiddenApiRelaxed = false

    /// Best-effort lift of the non-SDK API blocklist for this process so the
    /// hidden Bluetooth codec methods (setCodecConfigPreference, getCodecStatus)
    /// are reachable via reflection — same technique apps like UAPP use to
    /// switch A2DP codecs on Android 8+. Device-dependent; no-op if blocked.
    /// ponytail: process-global + idempotent; this is the known one-shot exemption.
    private fun relaxHiddenApiPolicy() {
        if (hiddenApiRelaxed) return
        hiddenApiRelaxed = true
        try {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            // Meta-reflect through Class's own public getDeclaredMethod so the
            // blocklist check on VMRuntime is bypassed.
            val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java
            )
            val setExemptions = getDeclaredMethod.invoke(
                vmRuntimeClass,
                "setHiddenApiExemptions",
                Array<String>::class.java,
            ) as java.lang.reflect.Method
            setExemptions.invoke(null, arrayOf<Any>(arrayOf("L")))
            devLogD("relaxHiddenApiPolicy: hidden-API exemption applied")
        } catch (e: Exception) {
            devLogD("relaxHiddenApiPolicy: unavailable (${e.javaClass.simpleName}: ${e.message})")
        }
    }

    private fun setBluetoothCodecConfigNative(
        address: String, codecType: Int, sampleRate: Int, bitsPerSample: Int, channelMode: Int, ldacBitrate: Int
    ): Map<String, Any?> {
        relaxHiddenApiPolicy()
        if (codecType < 0) {
            devLogD("setCodecConfig: codecType<0 (Automatic) — letting system decide")
            return mapOf("ok" to true, "reason" to "automatic")
        }
        val a2dp = a2dpProxy ?: run {
            devLogD("setCodecConfig: no a2dpProxy")
            return mapOf("ok" to false, "reason" to "no a2dpProxy (BLUETOOTH_CONNECT not granted or profile proxy not ready)")
        }
        val device = a2dp.connectedDevices.firstOrNull { it.address == address } ?: run {
            devLogD("setCodecConfig: device $address not among ${a2dp.connectedDevices.size} connected")
            val connected = a2dp.connectedDevices.joinToString(",") { it.address }
            return mapOf("ok" to false, "reason" to "device not in A2DP connected list (count=${a2dp.connectedDevices.size}; connected=[$connected])")
        }
        devLogD("setCodecConfig: requesting ${btCodecTypeLabel(codecType)} for $address (sr=$sampleRate bps=$bitsPerSample ch=$channelMode ldac=$ldacBitrate)")
        return try {
            val codecConfigClass = Class.forName("android.bluetooth.BluetoothCodecConfig")
            val constructor = codecConfigClass.getConstructor(
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType, Long::class.javaPrimitiveType
            )
            val codecPriorityHighest = 1_000_000
            val cs1 = if (codecType == 4) ldacBitrate.toLong() else 0L
            val config = constructor.newInstance(
                codecType, codecPriorityHighest,
                sampleRate, bitsPerSample, channelMode,
                cs1, 0L, 0L, 0L
            )
            // ponytail: successor to the removed setCodecConfig(device, config[])
            val setMethod = a2dp.javaClass.getMethod(
                "setCodecConfigPreference",
                BluetoothDevice::class.java, codecConfigClass
            )
            val ret = setMethod.invoke(a2dp, device, config)
            // Returns void on newer mainline modules, Boolean on older ones.
            // void + no exception = applied; Dart side verifies via getCodecStatus.
            val ok = if (setMethod.returnType == Boolean::class.javaPrimitiveType) {
                (ret as? Boolean) ?: false
            } else {
                true
            }
            devLogD("setCodecConfigPreference: ${btCodecTypeLabel(codecType)} returnType=${setMethod.returnType.simpleName} -> ok=$ok")
            mapOf("ok" to ok, "reason" to if (ok) "invoke succeeded (setCodecConfigPreference)" else "setCodecConfigPreference.invoke returned false")
        } catch (e: Exception) {
            val verdict = if (e is NoSuchMethodException) {
                val codecMethods = a2dp.javaClass.methods
                    .filter { it.name.contains("Codec", ignoreCase = true) }
                    .joinToString(", ") { m ->
                        "${m.name}(${m.parameterTypes.joinToString(",") { p -> p.simpleName }})"
                    }
                "setCodecConfigPreference(BluetoothDevice, BluetoothCodecConfig) not present on this " +
                    "device's BluetoothA2dp. Available codec methods: [$codecMethods]. " +
                    "App cannot switch A2DP codec here — use Android Developer Options → Bluetooth Audio Codec."
            } else {
                "reflection failed: ${e.message}"
            }
            devLogD("setCodecConfigPreference: $verdict")
            Log.w("Bluetooth", "setCodecConfigPreference failed: $verdict")
            mapOf("ok" to false, "reason" to verdict)
        }
    }

    private fun readBluetoothBatteryLevel(address: String, result: MethodChannel.Result) {
        val adapter = bluetoothAdapter()
        if (adapter == null) { result.success(null); return }
        val device = try { adapter.getRemoteDevice(address) } catch (e: Exception) { null }
        if (device == null) { result.success(null); return }
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        fun finish(value: Int?) {
            if (!finished) { finished = true; result.success(value) }
        }
        val gattCallback = object : BluetoothGattCallback() {
            private fun report(value: ByteArray?) {
                finish(if (value != null && value.isNotEmpty()) value[0].toInt() and 0xFF else null)
            }
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                    BluetoothProfile.STATE_DISCONNECTED -> { try { g.close() } catch (_: Exception) {}; finish(null) }
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val svc = g.getService(btBatteryServiceUuid)
                val ch = svc?.getCharacteristic(btBatteryCharUuid)
                if (ch != null) g.readCharacteristic(ch) else { try { g.close() } catch (_: Exception) {}; finish(null) }
            }
            override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                report(value); try { g.close() } catch (_: Exception) {}
            }
        }
        val gatt = try {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            finish(null); return
        }
        if (gatt == null) { finish(null); return }
        handler.postDelayed({
            if (!finished) {
                finished = true
                try { gatt.close() } catch (_: Exception) {}
                result.success(null)
            }
        }, 4000)
    }

    private fun setBluetoothAbsoluteVolume(address: String, enabled: Boolean): Boolean {
        return try {
            Settings.Global.putInt(contentResolver, "absolute_volume_enabled", if (enabled) 1 else 0)
        } catch (e: SecurityException) {
            Log.w("Bluetooth", "absolute volume blocked (needs WRITE_SECURE_SETTINGS): ${e.message}")
            false
        } catch (e: Exception) {
            Log.w("Bluetooth", "absolute volume failed: ${e.message}")
            false
        }
    }

    /// Opens Developer Options, where "Bluetooth Audio Codec" lives (the only
    /// reliable in-system way to switch the A2DP codec on stock Android).
    /// Returns false if Developer Options is disabled/unavailable.
    private fun openDeveloperOptions(): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w("Bluetooth", "openDeveloperOptions failed: ${e.message}")
            false
        }
    }

    private fun registerBluetoothAclReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                }
                val event = when (action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> "connected"
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> "disconnected"
                    else -> return
                }
                val address = try { device?.address ?: "" } catch (e: SecurityException) { "" }
                val name = try { device?.name ?: "Unknown" } catch (e: SecurityException) { "Unknown" }
                bluetoothEventSink?.success(mapOf("event" to event, "address" to address, "name" to name))
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
            aclReceiver = receiver
        } catch (e: Exception) {
            Log.w("Bluetooth", "ACL receiver registration failed: ${e.message}")
        }
    }

    // Over direct USB, STREAM_MUSIC does not reach the DAC. While Dart says the DAC
    // owns the volume keys, take them here (down, repeats and up) and let Dart
    // step the DAC through its usual volume path; the system volume stays put.
    // Otherwise the keys fall through exactly as before.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val direction = volumeKeyDirection(event.keyCode)
        // The claim, not hasDirectUsbHardwareVolume(): the control is rediscovered
        // with each stream, and a claimed DAC keeps the keys in between.
        if (shouldConsumeDacVolumeKey(direction, FlickEngine.dacVolumeKeysCaptured, directUsb.activeDirectUsbDeviceName != null)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                FlickEngine.adjustDacVolume(direction)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiverSafely(aclReceiver)
        aclReceiver = null
        justAudioProcessingController.release()
        try {
            bluetoothAdapter()?.closeProfileProxy(BluetoothProfile.A2DP, a2dpProxy)
        } catch (e: Exception) {
            Log.w("Bluetooth", "closeProfileProxy failed: ${e.message}")
        }
        a2dpProxy = null
        // The USB hot-plug receiver, the volume observer and the audio-device
        // callback are not torn down here: the engine, and direct USB with no
        // activity, outlive this one.
        directUsb.onActivityDestroyed(this, isFinishing)
    }

    private external fun nativeInitRustAndroidContext(context: Context): Boolean
}
