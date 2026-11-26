import 'package:flutter_test/flutter_test.dart';
import 'package:flick/l10n/l10n.dart';
import 'package:flick/services/player_service.dart';
import 'package:flick/services/uac2_service.dart';

const device = Uac2DeviceInfo(
  vendorId: 1,
  productId: 2,
  productName: 'Test USB DAC',
  manufacturer: 'Test',
  deviceName: '/dev/bus/usb/001/002',
);

Uac2DeviceStatus statusFromRoute(Map<String, dynamic> overrides) =>
    Uac2DeviceStatus.fromAndroidRoute(
      device: device,
      state: Uac2State.streaming,
      routeStatus: {
        'routeType': 'usb',
        'directUsbRegistered': true,
        'isExternal': true,
        'volumeMode': 'unavailable',
        'hasVolumeControl': false,
        'volumeControlWritable': false,
        ...overrides,
      },
    );

VolumeTier effectiveTier(Uac2DeviceStatus status, {bool passthrough = true}) =>
    determineVolumeTier(
      isBitPerfectVolumePath: true,
      volumeMode: status.volumeMode,
      hwVolumeFailed: false,
      isDoP: false,
      autoSwitchDsdForVolume: false,
      isPassthrough: passthrough,
      usingRustBackend: true,
    );

void main() {
  test(
    'direct USB unavailable stays unavailable, without stale system volume',
    () {
      final status = statusFromRoute({'volume': 0.13, 'muted': false});
      expect(status.directUsbRegistered, isTrue);
      expect(status.volumeMode, Uac2VolumeMode.unavailable);
      expect(status.hasVolumeControl, isFalse);
      expect(status.volumeControlWritable, isFalse);
      expect(status.volume, isNull);
      expect(status.muted, isNull);
      expect(effectiveTier(status), VolumeTier.unavailable);
      expect(status.compatibilityNotice, l10n.usbHardwareVolumeUnavailable);
      expect(
        status.compatibilityNotice,
        isNot(contains('Software volume control is active')),
      );
    },
  );

  test(
    'missing native USB controls still allow explicit DSP software volume',
    () {
      final status = statusFromRoute({});
      expect(effectiveTier(status, passthrough: false), VolumeTier.software);
      expect(status.hasVolumeControl, isFalse);
    },
  );

  test('hardware volume remains available without a usable mute control', () {
    final status = statusFromRoute({
      'volumeMode': 'hardware',
      'hasVolumeControl': true,
      'volumeControlWritable': true,
      'volume': 0.4,
      'muted': null,
    });
    expect(status.hasVolumeControl, isTrue);
    expect(status.volumeControlWritable, isTrue);
    expect(status.volume, 0.4);
    expect(status.muted, isNull);
    expect(effectiveTier(status), VolumeTier.hardware);
    expect(status.compatibilityNotice, isNull);
  });

  test(
    'unavailable native mode cannot acquire writable controls from stale flags',
    () {
      final status = statusFromRoute({
        'hasVolumeControl': true,
        'volumeControlWritable': true,
        'volume': 0.9,
      });
      expect(status.hasVolumeControl, isFalse);
      expect(status.volumeControlWritable, isFalse);
      expect(status.volume, isNull);
    },
  );

  test('shared Android system volume is preserved', () {
    final status = statusFromRoute({
      'directUsbRegistered': false,
      'routeType': 'internal',
      'isExternal': false,
      'volumeMode': 'system',
      'hasVolumeControl': true,
      'volumeControlWritable': true,
      'volume': 0.6,
      'muted': false,
    });
    expect(status.volumeMode, Uac2VolumeMode.system);
    expect(status.volume, 0.6);
    expect(status.muted, isFalse);
    expect(status.isExternalRoute, isFalse);
    expect(status.warningMessage, isNull);
  });

  test(
    'native read-only flag is respected and nonfinite volume is discarded',
    () {
      final status = statusFromRoute({
        'volumeMode': 'hardware',
        'hasVolumeControl': true,
        'volumeControlWritable': false,
        'volume': double.nan,
      });
      expect(status.volumeControlWritable, isFalse);
      expect(status.volume, isNull);
    },
  );

  test('direct USB does not display the shared Android resampling warning', () {
    final direct = statusFromRoute({});
    final shared = statusFromRoute({'directUsbRegistered': false});
    expect(direct.warningMessage, l10n.directUsbFormatNotice);
    expect(direct.warningMessage, isNot(contains('resample the stream')));
    expect(shared.warningMessage, contains('resample the stream'));
  });

  test('registration is not misrepresented as a verified active USB route', () {
    final status = statusFromRoute({'routeType': 'internal'});
    expect(
      status.warningMessage,
      contains('Android still reports internalDac'),
    );
    expect(status.warningMessage, contains('not verified hardware output'));
  });

  group('holding the hardware volume view while the stream is closed', () {
    final hardware = statusFromRoute({
      'volumeMode': 'hardware',
      'hasVolumeControl': true,
      'volumeControlWritable': true,
      'volume': 0.4,
      'muted': true,
    });
    // What native reports once the stream, and with it the control, is gone.
    final lost = statusFromRoute({});

    test(
      'a refresh without the control keeps the last level, mute and notice',
      () {
        expect(lost.volumeMode, Uac2VolumeMode.unavailable);
        expect(lost.compatibilityNotice, l10n.usbHardwareVolumeUnavailable);

        final held = holdHardwareVolumeView(hardware, lost)!;

        expect(held.volumeMode, Uac2VolumeMode.hardware);
        expect(held.hasVolumeControl, isTrue);
        expect(held.volumeControlWritable, isTrue);
        expect(held.volume, 0.4);
        expect(held.muted, isTrue);
        expect(held.compatibilityNotice, isNull);
        // Everything else is the fresh route report.
        expect(held.state, lost.state);
        expect(held.routeLabel, lost.routeLabel);
      },
    );

    test('the reopened stream rediscovers the control and wins', () {
      final reopened = statusFromRoute({
        'volumeMode': 'hardware',
        'hasVolumeControl': true,
        'volumeControlWritable': true,
        'volume': 0.55,
        'muted': false,
      });
      expect(holdHardwareVolumeView(hardware, reopened), same(reopened));
    });

    test('a different, unregistered or vanished DAC is never papered over', () {
      const other = Uac2DeviceInfo(
        vendorId: 1,
        productId: 2,
        productName: 'Other DAC',
        manufacturer: 'Test',
        deviceName: '/dev/bus/usb/001/003',
      );
      final otherLost = Uac2DeviceStatus.fromAndroidRoute(
        device: other,
        state: Uac2State.connected,
        routeStatus: const {
          'routeType': 'usb',
          'directUsbRegistered': true,
          'isExternal': true,
          'volumeMode': 'unavailable',
        },
      );
      expect(holdHardwareVolumeView(hardware, otherLost), same(otherLost));

      final unregistered = statusFromRoute({'directUsbRegistered': false});
      expect(
        holdHardwareVolumeView(hardware, unregistered),
        same(unregistered),
      );
      expect(holdHardwareVolumeView(hardware, null), isNull);
    });

    test('nothing is held without a previous hardware view', () {
      expect(holdHardwareVolumeView(null, lost), same(lost));
      expect(holdHardwareVolumeView(lost, lost), same(lost));
      final system = statusFromRoute({
        'volumeMode': 'system',
        'hasVolumeControl': true,
        'volume': 0.6,
      });
      expect(holdHardwareVolumeView(system, lost), same(lost));
    });
  });
}
