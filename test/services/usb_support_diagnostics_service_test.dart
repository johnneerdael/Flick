import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flick/services/usb_support_diagnostics_service.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('flick.test/usb_support');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late Directory directory;
  late UsbSupportDiagnosticsService service;
  late int retirements;
  late int nextId;
  late int? wrongNativeId;
  late bool permissionDenied;

  setUp(() async {
    directory = await Directory.systemTemp.createTemp('flick-usb-support-');
    retirements = 0;
    nextId = 10;
    wrongNativeId = null;
    permissionDenied = false;
    service = UsbSupportDiagnosticsService(
      channel: channel,
      reportDirectory: () async => directory,
      retireOutput: () async {
        retirements++;
      },
    );
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'begin') {
        return {
          'attemptId': ++nextId,
          'host': {'appVersion': '0.24.4'},
        };
      }
      if (call.method == 'finish') {
        final id = (call.arguments as Map)['attemptId'];
        return {
          'attemptId': id,
          'androidEvents': [],
          if (permissionDenied)
            'androidFirstFailure': {
              'elapsedUs': 150,
              'stage': 'permission',
              'code': 'denied',
            },
          'nativeJson': permissionDenied
              ? null
              : jsonEncode({
                  'attemptId': wrongNativeId ?? id,
                  'firstFailure': {
                    'elapsedUs': 100,
                    'stage': 'stream',
                    'code': 'transfer',
                  },
                  'legs': [
                    {'number': 1, 'feedback': 'typed trace'},
                  ],
                }),
        };
      }
      return null;
    });
  });

  tearDown(() async {
    if (service.isRecording) await service.stop();
    service.dispose();
    channel.setMethodCallHandler(null);
    messenger.setMockMethodCallHandler(channel, null);
    await directory.delete(recursive: true);
  });

  test(
    'records only the opted-in attempt and retains a saved report',
    () async {
      service.recordPlayback(UsbSupportPlaybackEvent.play);
      expect(service.isRecording, isFalse);
      await service.start();
      service.recordPlayback(
        UsbSupportPlaybackEvent.seek,
        position: const Duration(seconds: 15),
      );
      service.recordRoute(
        path: 'directUsbExperimental',
        selected: 'usbDacExperimental',
        initialized: 'usbDacExperimental',
        mixerManaged: false,
        resampling: false,
        directRegistered: true,
        requestedRate: 44100,
        reportedRate: 44100,
      );
      await service.stop();
      expect(retirements, 2);
      expect(service.isRecording, isFalse);
      expect(service.report!.failingStage, 'stream');
      expect(service.report!.lastRoute, 'directUsbExperimental');
      final data = jsonDecode(service.report!.toExportJson()) as Map;
      expect(data['host'], {'appVersion': '0.24.4'});
      expect(data['native']['legs'].single['feedback'], 'typed trace');
      final events = (data['appTimeline'] as List).cast<Map>();
      expect(events.where((event) => event['stage'] == 'play'), isEmpty);
      expect(
        events.singleWhere((event) => event['stage'] == 'seek')['positionMs'],
        15000,
      );

      await service.updateNotes(
        expected: 'Clear audio',
        observed: 'Stopped',
        steps: 'Play',
        setup: 'USB DAC',
      );
      final reopened = UsbSupportDiagnosticsService(
        reportDirectory: () async => directory,
      );
      addTearDown(reopened.dispose);
      await reopened.load();
      expect(reopened.report!.expected, 'Clear audio');
      expect(reopened.report!.setup, 'USB DAC');
      await reopened.deleteReport();
      expect(reopened.report, isNull);
      expect(await directory.list().toList(), isEmpty);
    },
  );

  test(
    'automatic failure saves the attempt after the fallback route',
    () async {
      await service.start();
      final finished = Completer<void>();
      void check() {
        if (!service.isBusy && !service.isRecording && !finished.isCompleted)
          finished.complete();
      }

      service.addListener(check);
      service.recordPlayback(UsbSupportPlaybackEvent.transportFailure);
      service.recordRoute(
        path: 'androidManagedShared',
        selected: 'normalAndroid',
        initialized: 'normalAndroid',
        mixerManaged: true,
        resampling: false,
        directRegistered: false,
        requestedRate: 44100,
        reportedRate: null,
      );
      await finished.future.timeout(const Duration(seconds: 5));
      service.removeListener(check);
      expect(service.report!.lastRoute, 'androidManagedShared');
      expect(
        (jsonDecode(service.report!.toExportJson()) as Map)['endedBecause'],
        'transport_failure',
      );
      expect(retirements, 2);
    },
  );

  test(
    'keeps the previous report while armed and ignores an unrelated native trace',
    () async {
      await service.start();
      await service.stop();
      final previous = service.report;
      await service.start();
      expect(service.report, same(previous));
      wrongNativeId = 11;
      await service.stop();
      final data = jsonDecode(service.report!.toExportJson()) as Map;
      expect(data['attemptId'], 12);
      expect(data['native'], isNull);
      expect(service.report!.failingStage, isNull);
    },
  );

  test('saves a permission failure with no stream trace', () async {
    permissionDenied = true;
    await service.start();
    final finished = Completer<void>();
    void check() {
      if (!service.isBusy && !service.isRecording && !finished.isCompleted)
        finished.complete();
    }

    service.addListener(check);
    await messenger.handlePlatformMessage(
      channel.name,
      const StandardMethodCodec().encodeMethodCall(
        const MethodCall('failure', {'attemptId': 11, 'stage': 'permission'}),
      ),
      (_) {},
    );
    await finished.future.timeout(const Duration(seconds: 5));
    service.removeListener(check);
    expect(service.report!.failingStage, 'permission');
    expect(
      (jsonDecode(service.report!.toExportJson()) as Map)['native'],
      isNull,
    );
  });
}
