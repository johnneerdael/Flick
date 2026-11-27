//! What Flick tells the usbdac library about a device at open: its own
//! start-up and volume policy, the per-device quirks and the user's native DSD
//! overrides.

use crate::uac2::quirk::{UsbAudioQuirk, QUIRK_DATABASE};
use usbdac_core::{DacConfig, HandBack, Quirk, QuirkOverride, StartupVolume};

/// How strictly the DAC's clock is held to its word, as the diagnostics name it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum DacClockPolicy {
    AllowUnverified,
    RequireVerifiedRate,
    Force48kHzOnly,
}

impl DacClockPolicy {
    pub(crate) fn label(self) -> &'static str {
        match self {
            Self::AllowUnverified => "allowUnverified",
            Self::RequireVerifiedRate => "requireVerifiedRate",
            Self::Force48kHzOnly => "force48kHzOnly",
        }
    }
}

/// The clock policy Flick's quirk table gives a device.
pub(crate) fn clock_policy(vendor_id: u16, product_id: u16, product_name: &str) -> DacClockPolicy {
    let quirks = QUIRK_DATABASE.lookup(vendor_id, product_id, product_name);
    if quirks.contains(&UsbAudioQuirk::RequireVerifiedRate) {
        DacClockPolicy::RequireVerifiedRate
    } else if quirks.contains(&UsbAudioQuirk::Force48kHzOnly) {
        DacClockPolicy::Force48kHzOnly
    } else {
        DacClockPolicy::AllowUnverified
    }
}

/// Whether Flick's quirk table says this device's clock readback cannot be
/// trusted, so a mismatching stored rate is not a reason to refuse.
pub(crate) fn skips_clock_validation(vendor_id: u16, product_id: u16, product_name: &str) -> bool {
    QUIRK_DATABASE.has_quirk(
        vendor_id,
        product_id,
        product_name,
        UsbAudioQuirk::SkipClockValidation,
    )
}

/// The library's name for a Flick quirk, for the ones the transport honours.
/// The others (`ForceAsyncFeedback`, `Force48kHzOnly`, ...) were never acted on
/// by Flick's transport either: they only exist in the enum.
fn library_quirk(quirk: UsbAudioQuirk) -> Option<Quirk> {
    Some(match quirk {
        UsbAudioQuirk::SkipClockValidation => Quirk::SkipClockValidation,
        UsbAudioQuirk::IgnoreInvalidSampleRate => Quirk::IgnoreInvalidSampleRate,
        UsbAudioQuirk::RequireVerifiedRate => Quirk::RequireVerifiedRate,
        UsbAudioQuirk::SettleDelayMs(ms) => Quirk::SettleDelayMs(ms),
        UsbAudioQuirk::PreferPadded24BitTransport => Quirk::PreferPadded24BitTransport,
        UsbAudioQuirk::DsdSubslotSize(size) => Quirk::DsdSubslotSize(size),
        UsbAudioQuirk::DsdBigEndian => Quirk::DsdBigEndian,
        UsbAudioQuirk::DsdBitReverse => Quirk::DsdBitReverse,
        _ => return None,
    })
}

/// Flick's quirks for this device as library overrides. They add to the
/// library's own table, so a device never loses a quirk Flick has: both agree
/// for the two devices both know today, and a device added to Flick's table
/// alone works without a library change.
///
/// Padded 24-bit transport has always been matched by VID/PID alone (the
/// product name Android reports is not always the full string), so it is
/// added for a VID/PID match too.
pub(crate) fn quirk_overrides(
    vendor_id: u16,
    product_id: u16,
    product_name: &str,
) -> Vec<QuirkOverride> {
    let mut quirks: Vec<Quirk> = QUIRK_DATABASE
        .lookup(vendor_id, product_id, product_name)
        .into_iter()
        .filter_map(library_quirk)
        .collect();
    if QUIRK_DATABASE
        .lookup_any_name(vendor_id, product_id)
        .contains(&UsbAudioQuirk::PreferPadded24BitTransport)
        && !quirks.contains(&Quirk::PreferPadded24BitTransport)
    {
        quirks.push(Quirk::PreferPadded24BitTransport);
    }
    if quirks.is_empty() {
        return Vec::new();
    }
    vec![QuirkOverride {
        vendor_id,
        product_id,
        name_contains: None,
        quirks,
    }]
}

/// The library configuration for a device.
///
/// Flick keeps its own start-up safety (the volume it remembers per DAC is
/// restored before the first stream, and without hardware volume it falls back
/// to software gain), so the library neither lowers the level at open nor
/// refuses a DAC without hardware volume: two protections would fight. The
/// original level is handed back at `Dac::close_with`, which Flick calls with
/// what Kotlin decided, so the configured hand-back is only the safe default
/// (keep). Buffer depths, bus queue and flush behaviour are the library's.
pub(crate) fn dac_config(
    vendor_id: u16,
    product_id: u16,
    product_name: &str,
    dsd_big_endian: Option<bool>,
    dsd_subslot: Option<u8>,
) -> DacConfig {
    DacConfig {
        startup_volume: StartupVolume::Keep,
        require_hardware_volume: false,
        realtime_priority: true,
        hand_back: HandBack::Keep,
        dsd_big_endian,
        dsd_subslot,
        quirks: quirk_overrides(vendor_id, product_id, product_name),
        ..DacConfig::default()
    }
}

/// `dac_config` with the native DSD overrides the user has set now.
pub(crate) fn dac_config_for_device(
    vendor_id: u16,
    product_id: u16,
    product_name: &str,
) -> DacConfig {
    dac_config(
        vendor_id,
        product_id,
        product_name,
        crate::audio::dsd_engine::output::dsd_big_endian_override(),
        crate::audio::dsd_engine::output::dsd_subslot_override(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    const DAWN_PRO: (u16, u16, &str) = (12230, 61546, "MOONDROP Dawn Pro");
    const FOSI_DS2: (u16, u16, &str) = (9770, 1, "Fosi Audio DS2");

    fn quirks_of(vendor: u16, product: u16, name: &str) -> Vec<Quirk> {
        quirk_overrides(vendor, product, name)
            .into_iter()
            .flat_map(|entry| entry.quirks)
            .collect()
    }

    #[test]
    fn flick_runs_the_library_with_its_own_volume_policy() {
        let config = dac_config(1, 2, "A DAC", None, None);
        assert_eq!(config.startup_volume, StartupVolume::Keep);
        assert!(!config.require_hardware_volume);
        assert!(config.realtime_priority);
        assert_eq!(config.hand_back, HandBack::Keep);
        assert!(!config.capture_feedback_trace, "support capture is off by default");
        assert!(!config.allow_unsynchronized_async, "nominal asynchronous fallback stays disabled");
        // Everything else is the library's tuning.
        let defaults = DacConfig::default();
        assert_eq!(config.buffer_target_ms, defaults.buffer_target_ms);
        assert_eq!(config.buffer_capacity_ms, defaults.buffer_capacity_ms);
        assert_eq!(config.start_threshold_ms, defaults.start_threshold_ms);
        assert_eq!(config.processing_buffer_target_ms, defaults.processing_buffer_target_ms);
        assert!(config.require_verified_rate);
        config.validate().unwrap();
    }

    #[test]
    fn the_dawn_pro_keeps_every_quirk_it_has() {
        let quirks = quirks_of(DAWN_PRO.0, DAWN_PRO.1, DAWN_PRO.2);
        for expected in [
            Quirk::RequireVerifiedRate,
            Quirk::SettleDelayMs(50),
            Quirk::PreferPadded24BitTransport,
            Quirk::DsdSubslotSize(4),
            Quirk::DsdBigEndian,
        ] {
            assert!(quirks.contains(&expected), "{expected:?} in {quirks:?}");
        }
        assert!(!quirks.contains(&Quirk::DsdBitReverse));
        assert_eq!(
            clock_policy(DAWN_PRO.0, DAWN_PRO.1, DAWN_PRO.2),
            DacClockPolicy::RequireVerifiedRate
        );
    }

    #[test]
    fn the_fosi_ds2_keeps_trusting_its_set_cur() {
        let quirks = quirks_of(FOSI_DS2.0, FOSI_DS2.1, FOSI_DS2.2);
        assert!(quirks.contains(&Quirk::SkipClockValidation));
        assert!(quirks.contains(&Quirk::IgnoreInvalidSampleRate));
        assert!(skips_clock_validation(FOSI_DS2.0, FOSI_DS2.1, FOSI_DS2.2));
        // Matched for any product name, as before.
        assert!(!quirks_of(FOSI_DS2.0, FOSI_DS2.1, "").is_empty());
        assert_eq!(
            clock_policy(FOSI_DS2.0, FOSI_DS2.1, FOSI_DS2.2),
            DacClockPolicy::AllowUnverified
        );
    }

    #[test]
    fn padded_24_bit_is_still_matched_by_vid_pid_alone() {
        // Android's product string for the Dawn Pro is not always the full name.
        let quirks = quirks_of(DAWN_PRO.0, DAWN_PRO.1, "USB DAC");
        assert_eq!(quirks, vec![Quirk::PreferPadded24BitTransport]);
        // Nothing else leaks to an unnamed device: the DSD quirks and the clock
        // policy need the name.
        assert_eq!(
            clock_policy(DAWN_PRO.0, DAWN_PRO.1, "USB DAC"),
            DacClockPolicy::AllowUnverified
        );
    }

    #[test]
    fn an_unknown_device_gets_no_quirks() {
        assert!(quirk_overrides(0x1234, 0x5678, "Some DAC").is_empty());
        assert!(!skips_clock_validation(0x1234, 0x5678, "Some DAC"));
    }

    #[test]
    fn the_dsd_overrides_ride_in_the_config() {
        let config = dac_config(1, 2, "A DAC", Some(true), Some(2));
        assert_eq!(config.dsd_big_endian, Some(true));
        assert_eq!(config.dsd_subslot, Some(2));
        let auto = dac_config(1, 2, "A DAC", None, None);
        assert_eq!((auto.dsd_big_endian, auto.dsd_subslot), (None, None));
    }
}
