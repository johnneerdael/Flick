import 'dart:async';
import 'dart:collection';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';

import 'player_service.dart';

enum UsbSupportPlaybackEvent { play, resume, seek, transportFailure }

Map<String, Object?>? _object(Object? value) =>
    value is Map ? Map<String, Object?>.from(value) : null;

class UsbSupportReport {
  UsbSupportReport._(this._data);

  final Map<String, Object?> _data;

  DateTime get startedAt => DateTime.parse(_data['startedAt']! as String);
  String? get failingStage =>
      _object(_data['firstFailure'])?['stage'] as String?;
  String? get lastRoute => _data['lastRoute'] as String?;

  Map<String, Object?> get _notes => _object(_data['notes']) ?? const {};
  String get expected => _notes['expected'] as String? ?? '';
  String get observed => _notes['observed'] as String? ?? '';
  String get steps => _notes['steps'] as String? ?? '';
  String get setup => _notes['setup'] as String? ?? '';

  String toExportJson() => const JsonEncoder.withIndent('  ').convert(_data);
}

class _UsbSupportAttempt {
  _UsbSupportAttempt(this.id, this.startedAt, this.host, this.devices);

  final int id;
  final DateTime startedAt;
  final Object? host;
  final Object? devices;
  final Stopwatch elapsed = Stopwatch()..start();
  final Queue<Map<String, Object?>> events = Queue();
  int droppedEvents = 0;
  String? lastRoute;
  String? lastRouteSignature;
  Map<String, Object?>? firstFailure;

  void record(
    String stage, {
    String? code,
    Map<String, Object?> fields = const {},
  }) {
    if (events.length >= 256) {
      events.removeFirst();
      droppedEvents++;
    }
    events.addLast({
      'elapsedUs': elapsed.elapsedMicroseconds,
      'stage': stage,
      if (code != null) 'code': code,
      ...fields,
    });
  }
}

/// A single opt-in attempt and one local saved report. No general logs are read.
class UsbSupportDiagnosticsService extends ChangeNotifier {
  UsbSupportDiagnosticsService({
    MethodChannel? channel,
    Future<Directory> Function()? reportDirectory,
    Future<void> Function()? retireOutput,
  }) : _channel =
           channel ??
           const MethodChannel('com.mossapps.flick/usb_support_diagnostics'),
       _reportDirectory = reportDirectory ?? getApplicationSupportDirectory,
       _retireOutput =
           retireOutput ??
           (() => PlayerService().retireUsbForSupportDiagnostics());

  static final instance = UsbSupportDiagnosticsService();
  static const _captureDuration = Duration(minutes: 3);
  static const _outputTimeout = Duration(seconds: 8);
  static const _maxReportBytes = 12 * 1024 * 1024;

  final MethodChannel _channel;
  final Future<Directory> Function() _reportDirectory;
  final Future<void> Function() _retireOutput;
  Future<void>? _loadTask;
  UsbSupportReport? _report;
  _UsbSupportAttempt? _attempt;
  bool _busy = false;
  Timer? _expiry;
  Timer? _automaticStop;
  String? _pendingStopReason;

  bool get isRecording => _attempt != null;
  bool get isBusy => _busy;
  UsbSupportReport? get report => _report;

  Future<File> _reportFile() async {
    final directory = await _reportDirectory();
    return File('${directory.path}/usb_playback_support.json');
  }

  Future<void> load() => _loadTask ??= _readSavedReport();

  Future<void> _readSavedReport() async {
    try {
      final file = await _reportFile();
      if (!await file.exists() || await file.length() > _maxReportBytes) return;
      final parsed = _object(jsonDecode(await file.readAsString()));
      if (parsed?['schemaVersion'] != 1 ||
          DateTime.tryParse(parsed?['startedAt']?.toString() ?? '') == null) {
        return;
      }
      _report = UsbSupportReport._(parsed!);
      notifyListeners();
    } on FormatException {
      // A partial or obsolete file cannot be shared as a valid report.
    } on FileSystemException {
      // The capture flow can still save a new report if storage is available.
    }
  }

  Future<void> _save(UsbSupportReport report) async {
    final file = await _reportFile();
    final temporary = File('${file.path}.tmp');
    try {
      await temporary.writeAsString(report.toExportJson(), flush: true);
      await temporary.rename(file.path);
      _report = report;
    } finally {
      if (await temporary.exists()) await temporary.delete();
    }
  }

  Future<void> _operation(Future<void> Function() operation) async {
    if (_busy) return;
    _busy = true;
    notifyListeners();
    try {
      await operation();
    } finally {
      _busy = false;
      notifyListeners();
      _scheduleFinish();
    }
  }

  Future<void> start() => _operation(() async {
    if (isRecording) return;
    await load();
    _channel.setMethodCallHandler(_onNativeEvent);
    final startedAt = DateTime.now();
    final result = await _channel.invokeMapMethod<String, Object?>('begin');
    final id = result?['attemptId'];
    if (id is! int || id <= 0)
      throw PlatformException(code: 'CAPTURE_UNAVAILABLE');

    final attempt = _UsbSupportAttempt(
      id,
      startedAt,
      result?['host'],
      result?['attachedDevices'],
    );
    _attempt = attempt;
    attempt.record('capture_started');
    _expiry = Timer(_captureDuration, () => _requestFinish('time_limit'));
    try {
      await _retireOutput().timeout(_outputTimeout);
      attempt.record('output_prepared');
    } catch (_) {
      attempt.record('output_restart', code: 'restart_failed');
      attempt.firstFailure = attempt.events.last;
      await _finishAttempt(attempt, 'restart_failed');
      rethrow;
    }
  });

  Future<void> _onNativeEvent(MethodCall call) async {
    final fields = _object(call.arguments);
    if (call.method == 'failure' && _attempt?.id == fields?['attemptId']) {
      _requestFinish('usb_failure');
    }
  }

  void recordPlayback(UsbSupportPlaybackEvent event, {Duration? position}) {
    final attempt = _attempt;
    if (attempt == null) return;
    attempt.record(
      event.name,
      fields: {
        if (event == UsbSupportPlaybackEvent.seek && position != null)
          'positionMs': position.inMilliseconds,
      },
    );
    if (event == UsbSupportPlaybackEvent.transportFailure) {
      attempt.firstFailure ??= attempt.events.last;
      _requestFinish('transport_failure');
    }
  }

  void recordRequestedFormat({
    required int sampleRate,
    required int bits,
    required int channels,
    required bool nativeDsd,
    required bool dop,
  }) {
    _attempt?.record(
      'requested_format',
      fields: {
        'sampleRateHz': sampleRate,
        'bitDepth': bits,
        'channels': channels,
        'encoding': nativeDsd
            ? 'native_dsd'
            : dop
            ? 'dop'
            : 'pcm',
        if (nativeDsd || dop) 'dsdBitRateHz': sampleRate * (nativeDsd ? 8 : 16),
      },
    );
  }

  void recordRoute({
    required String path,
    required String selected,
    required String? initialized,
    required bool mixerManaged,
    required bool resampling,
    required bool directRegistered,
    required int? requestedRate,
    required int? reportedRate,
  }) {
    final attempt = _attempt;
    if (attempt == null) return;
    final fields = <String, Object?>{
      'path': path,
      'selectedMode': selected,
      'initializedMode': initialized,
      'mixerManaged': mixerManaged,
      'resampling': resampling,
      'directRegistered': directRegistered,
      'requestedRateHz': requestedRate,
      'reportedRateHz': reportedRate,
    };
    final signature = jsonEncode(fields);
    if (signature == attempt.lastRouteSignature) return;
    attempt.lastRouteSignature = signature;
    attempt.lastRoute = path;
    attempt.record('route', fields: fields);
  }

  void _requestFinish(String reason) {
    if (!isRecording) return;
    _pendingStopReason ??= reason;
    _scheduleFinish();
  }

  void _scheduleFinish() {
    if (!isRecording || _busy || _pendingStopReason == null) return;
    // Let the normal playback error/fallback handlers record their route first.
    _automaticStop ??= Timer(const Duration(milliseconds: 400), () {
      _automaticStop = null;
      unawaited(
        _stop(_pendingStopReason ?? 'usb_failure').catchError((Object _) {}),
      );
    });
  }

  Future<void> stop() => _stop(_pendingStopReason ?? 'stopped');

  Future<void> _stop(String reason) => _operation(() async {
    final attempt = _attempt;
    if (attempt == null) return;
    try {
      // Capture is an option on Dac::open, so retire the opted-in DAC before
      // finishing. A later play will open it with the normal configuration.
      await _retireOutput().timeout(_outputTimeout);
    } catch (_) {
      attempt.record('output_close', code: 'close_incomplete');
    }
    await _finishAttempt(attempt, reason);
  });

  Future<void> _finishAttempt(_UsbSupportAttempt attempt, String reason) async {
    _expiry?.cancel();
    _automaticStop?.cancel();
    _automaticStop = null;
    _pendingStopReason = null;
    Map<String, Object?>? android;
    Map<String, Object?>? native;
    try {
      final result = await _channel.invokeMapMethod<String, Object?>('finish', {
        'attemptId': attempt.id,
      });
      if (result != null && result['attemptId'] == attempt.id) {
        android = Map.of(result)..remove('nativeJson');
        final nativeJson = result['nativeJson'];
        if (nativeJson is String) {
          final decoded = _object(jsonDecode(nativeJson));
          if (decoded?['attemptId'] == attempt.id) native = decoded;
        }
      }
    } catch (_) {
      attempt.record('native_export', code: 'unavailable');
    } finally {
      _attempt = null;
    }
    attempt.record('capture_finished', code: reason);
    attempt.elapsed.stop();
    final failures =
        [
          if (_object(native?['firstFailure']) case final failure?) failure,
          if (_object(android?['androidFirstFailure']) case final failure?)
            failure,
          if (attempt.firstFailure case final failure?) failure,
        ]..sort(
          (a, b) => ((a['elapsedUs'] as num?) ?? 0).compareTo(
            (b['elapsedUs'] as num?) ?? 0,
          ),
        );

    await _save(
      UsbSupportReport._({
        'schemaVersion': 1,
        'attemptId': attempt.id,
        'startedAt': attempt.startedAt.toUtc().toIso8601String(),
        'startTimeZoneOffsetMinutes':
            attempt.startedAt.timeZoneOffset.inMinutes,
        'elapsedUs': attempt.elapsed.elapsedMicroseconds,
        'endedBecause': reason,
        'host': attempt.host,
        'attachedDevices': attempt.devices,
        'lastRoute': attempt.lastRoute,
        if (failures.isNotEmpty) 'firstFailure': failures.first,
        'appTimeline': attempt.events.toList(),
        'appDroppedEvents': attempt.droppedEvents,
        'android': android,
        'native': native,
      }),
    );
  }

  Future<void> updateNotes({
    required String expected,
    required String observed,
    required String steps,
    required String setup,
  }) => _operation(() async {
    final current = _report;
    if (current == null || isRecording) return;
    String bounded(String value) =>
        String.fromCharCodes(value.trim().runes.take(2000));
    await _save(
      UsbSupportReport._({
        ...current._data,
        'notes': {
          'expected': bounded(expected),
          'observed': bounded(observed),
          'steps': bounded(steps),
          'setup': bounded(setup),
        },
      }),
    );
  });

  Future<void> deleteReport() => _operation(() async {
    if (isRecording) return;
    final file = await _reportFile();
    if (await file.exists()) await file.delete();
    _report = null;
  });
}
