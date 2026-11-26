//! The direct-USB diagnostics the Dart side reads: what is registered, what
//! stream is open, what the library reports about it.
//!
//! The shape (every field name and type) is the one the player and the shared
//! debug JSON have always had. Fields come from the registration, the library's
//! capabilities and the open stream's stats; the few the library does not have
//! are derived, or report nothing (see the notes on each).

use super::config::DacClockPolicy;
use super::ports::{AltFacts, DacFacts, FormatFacts, StreamSnapshot, WireEncoding};
use super::state::{ActiveStream, Lifecycle, Registration, LIFECYCLE, STATE};
use super::{AndroidDirectUsbPlaybackFormat, DsdTransportMode};
use serde::Serialize;
use std::sync::Arc;
use usbdac_core::BitPerfectBlocker;

#[derive(Debug, Clone, Serialize, Default)]
pub struct AndroidDirectUsbDebugState {
    pub registered: bool,
    pub engine_state: Option<String>,
    pub engine_state_reason: Option<String>,
    pub requested_playback_sample_rate: Option<u32>,
    pub requested_playback_bit_depth: Option<u8>,
    pub requested_playback_channels: Option<u16>,
    pub device_name: Option<String>,
    pub product_name: Option<String>,
    pub playback_format_sample_rate: Option<u32>,
    pub playback_format_bit_depth: Option<u8>,
    pub playback_format_channels: Option<u16>,
    pub lock_requested: bool,
    pub stream_active: bool,
    /// The DAC is open and no stream is playing on it. This replaces the old
    /// idle lock: the library keeps the DAC open for as long as it is
    /// registered.
    pub idle_lock_held: bool,
    pub active_interface_number: Option<u8>,
    pub active_alt_setting: Option<u8>,
    pub active_endpoint_address: Option<u8>,
    pub active_endpoint_interval: Option<u8>,
    pub active_service_interval_us: Option<u32>,
    pub active_max_packet_bytes: Option<usize>,
    pub transport_format: Option<String>,
    pub transport_channels: Option<u16>,
    pub transport_subslot_size: Option<u8>,
    pub transport_bit_resolution: Option<u8>,
    pub advertised_sample_rates: Vec<u32>,
    pub active_sync_type: Option<String>,
    pub active_usage_type: Option<String>,
    pub active_refresh: Option<u8>,
    pub active_synch_address: Option<u8>,
    /// The AudioControl interface the open stream's sample rate was programmed
    /// through (the library's `StreamFormat::clock`); `None` without a stream or
    /// when the DAC describes no clock entities.
    pub clock_interface_number: Option<u8>,
    /// The Clock Source the open stream's sample rate was set and verified on.
    pub clock_id: Option<u8>,
    pub clock_requested_sample_rate: Option<u32>,
    pub clock_reported_sample_rate: Option<u32>,
    pub clock_control_attempted: bool,
    pub clock_control_succeeded: bool,
    pub clock_verification_passed: bool,
    pub require_verified_rate: bool,
    pub dac_clock_policy: Option<String>,
    pub dac_mode: Option<String>,
    pub resampling_active: bool,
    pub last_error: Option<String>,
    pub direct_mode_refusal_reason: Option<String>,
    pub usb_stream_stable: bool,
    pub bit_perfect_verified: bool,
    pub pcm_payload_verified: bool,
    pub dsd_payload_verified: bool,
    pub payload_constant_for_ms: Option<u64>,
    pub software_volume_active: bool,
    pub hardware_volume_supported: bool,
    pub hardware_mute_supported: bool,
    pub hardware_volume_normalized: Option<f64>,
    pub hardware_mute_active: Option<bool>,
    pub hardware_volume_min_raw: Option<i16>,
    pub hardware_volume_max_raw: Option<i16>,
    pub hardware_volume_resolution_raw: Option<i16>,
    pub hardware_volume_error: Option<String>,
    pub hardware_volume_generation: u64,
    pub feedback_endpoint_present: bool,
    pub feedback_endpoint_address: Option<u8>,
    /// How the open stream is paced, as the library labels it: `explicit_feedback`, `implicit_feedback`,
    /// `adaptive`, `synchronous` or `unsynchronized`. The library does not report
    /// the USB transfer type of the feedback endpoint (`isochronous` or
    /// `interrupt`), which the in-tree driver put here; nothing reads this key.
    pub feedback_transfer_type: Option<String>,
    /// Not reported by the library: always empty.
    pub packet_schedule_frames_preview: Vec<u32>,
    pub supported_sample_rates: Vec<u32>,
    pub supported_bit_depths: Vec<u8>,
    pub supported_channels: Vec<u16>,
    pub available_alt_settings: Vec<AndroidDirectUsbAltCapability>,
    pub buffer_fill_ms: Option<u32>,
    pub buffer_capacity_ms: Option<u32>,
    /// The depth the ring is being kept at now.
    pub buffer_target_ms: Option<u32>,
    /// The nominal frames in one packet (the rate times the service interval);
    /// the library does not report the feedback-adjusted size of a packet.
    pub frames_per_packet: Option<u32>,
    pub underrun_count: Option<u64>,
    pub producer_frames: Option<u64>,
    pub consumer_frames: Option<u64>,
    /// Ring frames lost on the USB transport this stream; any non-zero value
    /// has cleared `bit_perfect_verified` and `usb_stream_stable`.
    pub lost_frames: Option<u64>,
    /// Music frames whose isochronous transfers the DAC has completed since the
    /// stream started or the ring was last flushed. Excludes held silence,
    /// underrun padding and lost frames; monotonic between flushes.
    pub played_music_frames: Option<u64>,
    /// Music frames between the decoder and the DAC: in the ring plus
    /// submitted to the bus and not yet completed.
    pub output_latency_frames: Option<u64>,
    /// `output_latency_frames` in milliseconds at the ring rate.
    pub output_latency_ms: Option<f64>,
    pub drift_ms_from_target: Option<i32>,
    pub transfer_queue_depth: Option<u32>,
    pub completed_transfers: Option<u64>,
    pub last_transfer_turnaround_us: Option<u64>,
    pub max_transfer_turnaround_us: Option<u64>,
    pub last_submit_gap_us: Option<u64>,
    pub max_submit_gap_us: Option<u64>,
    pub effective_consumer_rate_milli_hz: Option<u64>,
}

#[derive(Debug, Clone, Serialize)]
pub struct AndroidDirectUsbAltCapability {
    pub interface_number: u8,
    pub alt_setting: u8,
    pub endpoint_address: u8,
    pub endpoint_interval: u8,
    pub service_interval_us: u32,
    pub max_packet_bytes: usize,
    pub format_tag: String,
    pub channels: u16,
    pub subslot_size: u8,
    pub bit_resolution: u8,
    pub sample_rates: Vec<u32>,
    pub sync_type: String,
    pub usage_type: String,
    pub refresh: u8,
    pub synch_address: u8,
}

impl From<&AltFacts> for AndroidDirectUsbAltCapability {
    fn from(alt: &AltFacts) -> Self {
        Self {
            interface_number: alt.interface_number,
            alt_setting: alt.alt_setting,
            endpoint_address: alt.endpoint_address,
            endpoint_interval: alt.endpoint_interval,
            service_interval_us: alt.service_interval_us,
            max_packet_bytes: alt.max_packet_bytes,
            format_tag: alt.format_tag.to_string(),
            channels: alt.channels,
            subslot_size: alt.subslot_size,
            bit_resolution: alt.bit_resolution,
            sample_rates: alt.sample_rates.clone(),
            sync_type: alt.sync_type.to_string(),
            usage_type: alt.usage_type.to_string(),
            refresh: alt.refresh,
            synch_address: alt.synch_address,
        }
    }
}

/// What the registration says, cloned out so the library can be asked without
/// holding the state lock.
struct Snapshot {
    registration: RegistrationView,
    lifecycle: Lifecycle,
    facts: DacFacts,
    stream: Option<(Arc<ActiveStream>, StreamSnapshot)>,
}

struct RegistrationView {
    device_name: Option<String>,
    product_name: String,
    requested: Option<AndroidDirectUsbPlaybackFormat>,
    playback: Option<AndroidDirectUsbPlaybackFormat>,
    lock_requested: bool,
    last_error: Option<String>,
    refusal: Option<String>,
    clock_policy: DacClockPolicy,
    volume_normalized: Option<f64>,
    volume_muted: Option<bool>,
    volume_error: Option<String>,
    volume_generation: u64,
}

pub fn android_direct_debug_state() -> AndroidDirectUsbDebugState {
    let lifecycle = LIFECYCLE.lock().clone();
    let cloned = STATE.lock().as_ref().map(|registration| {
        (
            RegistrationView::of(registration),
            registration.dac.clone(),
            registration.stream.clone(),
        )
    });
    let Some((registration, dac, stream)) = cloned else {
        return AndroidDirectUsbDebugState {
            idle_lock_held: false,
            engine_state: Some(lifecycle.state.label().to_string()),
            engine_state_reason: lifecycle.reason,
            ..Default::default()
        };
    };
    let facts = dac.facts();
    let stream = stream.map(|active| {
        let snapshot = active.stream.snapshot();
        (active, snapshot)
    });
    build(Snapshot {
        registration,
        lifecycle,
        facts,
        stream,
    })
}

impl RegistrationView {
    fn of(registration: &Registration) -> Self {
        Self {
            device_name: registration.device.device_name.clone(),
            product_name: registration.device.product_name.clone(),
            requested: registration.requested_playback_format,
            playback: registration.playback_format,
            lock_requested: registration.lock_requested,
            last_error: registration.last_error.clone(),
            refusal: registration.direct_mode_refusal_reason.clone(),
            clock_policy: registration.dac_clock_policy,
            volume_normalized: registration.volume.normalized,
            volume_muted: registration.volume.muted,
            volume_error: registration.volume.error.clone(),
            volume_generation: registration.hardware_volume_generation,
        }
    }
}

fn union<T: Ord + Copy>(values: impl Iterator<Item = T>) -> Vec<T> {
    let mut all: Vec<T> = values.collect();
    all.sort_unstable();
    all.dedup();
    all
}

/// The alternate setting a format runs on, from what the DAC offers.
fn alt_of<'a>(facts: &'a DacFacts, format: &FormatFacts) -> Option<&'a AltFacts> {
    facts.alts.iter().find(|alt| {
        alt.interface_number == format.interface_number
            && alt.alt_setting == format.alt_setting
            && alt.endpoint_address == format.endpoint_address
    })
}

fn build(snapshot: Snapshot) -> AndroidDirectUsbDebugState {
    let Snapshot {
        registration,
        lifecycle,
        facts,
        stream,
    } = snapshot;
    let requested = registration.requested;
    let playback = registration.playback;
    let resampling_active = requested
        .zip(playback)
        .is_some_and(|(requested, effective)| requested.sample_rate != effective.sample_rate);
    let hardware_volume_supported = facts.volume_raw.is_some();
    let mut state = AndroidDirectUsbDebugState {
        registered: true,
        engine_state: Some(lifecycle.state.label().to_string()),
        engine_state_reason: lifecycle.reason,
        requested_playback_sample_rate: requested.map(|format| format.sample_rate),
        requested_playback_bit_depth: requested.map(|format| format.bit_depth),
        requested_playback_channels: requested.map(|format| format.channels),
        device_name: registration.device_name,
        product_name: Some(registration.product_name),
        playback_format_sample_rate: playback.map(|format| format.sample_rate),
        playback_format_bit_depth: playback.map(|format| format.bit_depth),
        playback_format_channels: playback.map(|format| format.channels),
        lock_requested: registration.lock_requested,
        stream_active: stream.is_some(),
        idle_lock_held: registration.lock_requested && stream.is_none(),
        require_verified_rate: true,
        dac_clock_policy: Some(registration.clock_policy.label().to_string()),
        resampling_active,
        last_error: registration.last_error,
        direct_mode_refusal_reason: registration.refusal,
        hardware_volume_supported,
        hardware_mute_supported: facts.has_mute && registration.volume_muted.is_some(),
        hardware_volume_normalized: registration.volume_normalized,
        hardware_mute_active: registration.volume_muted,
        hardware_volume_min_raw: facts.volume_raw.map(|(min, _, _)| min),
        hardware_volume_max_raw: facts.volume_raw.map(|(_, max, _)| max),
        hardware_volume_resolution_raw: facts.volume_raw.map(|(_, _, res)| res),
        hardware_volume_error: registration.volume_error,
        hardware_volume_generation: registration.volume_generation,
        supported_sample_rates: union(facts.alts.iter().flat_map(|alt| alt.sample_rates.iter().copied())),
        supported_bit_depths: union(facts.alts.iter().map(|alt| alt.bit_resolution)),
        supported_channels: union(facts.alts.iter().map(|alt| alt.channels)),
        available_alt_settings: facts.alts.iter().map(Into::into).collect(),
        ..Default::default()
    };

    let Some((active, snapshot)) = stream else {
        return state;
    };
    let format = &active.format;
    let dsd = !matches!(format.encoding, WireEncoding::Pcm);
    // A stream that plays at a rate other than the one asked for is not
    // bit-perfect, whatever the library saw: the engine resamples to it.
    state.bit_perfect_verified = snapshot.bit_perfect && !resampling_active;
    state.usb_stream_stable = snapshot.stable;
    state.clock_control_attempted = true;
    state.clock_control_succeeded = snapshot.clock_verified;
    state.clock_verification_passed = snapshot.clock_verified;
    state.clock_requested_sample_rate = Some(format.sample_rate);
    state.clock_reported_sample_rate = snapshot.clock_verified.then_some(format.sample_rate);
    state.dac_mode = Some(
        if format.rate_differs_from_request || resampling_active {
            "adaptiveStreaming"
        } else {
            "fullControl"
        }
        .to_string(),
    );
    state.pcm_payload_verified = !dsd && snapshot.payload_verified;
    state.dsd_payload_verified = dsd && snapshot.payload_verified;
    state.payload_constant_for_ms = snapshot.payload_constant_for_ms;
    state.software_volume_active = snapshot.software_gain != 1.0;

    state.active_interface_number = Some(format.interface_number);
    state.active_alt_setting = Some(format.alt_setting);
    state.active_endpoint_address = Some(format.endpoint_address);
    state.active_service_interval_us = Some(format.service_interval_us);
    state.transport_channels = Some(format.channels);
    state.transport_subslot_size = Some(format.subslot_bytes);
    state.transport_bit_resolution = Some(format.valid_bits);
    state.active_sync_type = Some(format.sync_type.to_string());
    state.feedback_endpoint_present = format.feedback_endpoint.is_some();
    state.feedback_endpoint_address = format.feedback_endpoint;
    state.clock_interface_number = format.clock_interface_number;
    state.clock_id = format.clock_id;
    state.feedback_transfer_type = Some(format.pacing.to_string());
    if let Some(alt) = alt_of(&facts, format) {
        state.active_endpoint_interval = Some(alt.endpoint_interval);
        state.active_max_packet_bytes = Some(alt.max_packet_bytes);
        state.transport_format = Some(alt.format_tag.to_string());
        state.transport_bit_resolution = Some(alt.bit_resolution);
        state.advertised_sample_rates = alt.sample_rates.clone();
        state.active_usage_type = Some(alt.usage_type.to_string());
        state.active_refresh = Some(alt.refresh);
        state.active_synch_address = Some(alt.synch_address);
    }

    let counter_rate = u64::from(format.counter_rate_hz.max(1));
    state.buffer_fill_ms = Some(snapshot.buffer_ms);
    state.buffer_capacity_ms = Some(snapshot.buffer_capacity_ms);
    state.buffer_target_ms = Some(snapshot.active_target_ms);
    state.frames_per_packet = Some(
        (u64::from(format.sample_rate) * u64::from(format.service_interval_us) / 1_000_000) as u32,
    )
    .filter(|frames| *frames > 0);
    state.underrun_count = Some(snapshot.underruns);
    state.producer_frames = Some(snapshot.frames_written);
    state.consumer_frames = Some(snapshot.frames_completed);
    state.lost_frames = Some(snapshot.lost_frames);
    state.played_music_frames = Some(snapshot.played_frames);
    state.output_latency_frames = Some(snapshot.latency_frames);
    state.output_latency_ms = Some(snapshot.latency_ms);
    let queued_ms = snapshot.queued_frames * 1_000 / counter_rate;
    state.drift_ms_from_target = Some(queued_ms as i32 - snapshot.active_target_ms as i32);
    state.transfer_queue_depth = Some(snapshot.transfer_queue_depth).filter(|depth| *depth > 0);
    state.completed_transfers = Some(snapshot.completed_transfers);
    state.last_transfer_turnaround_us = Some(snapshot.last_turnaround_us).filter(|v| *v > 0);
    state.max_transfer_turnaround_us = Some(snapshot.max_turnaround_us).filter(|v| *v > 0);
    state.last_submit_gap_us = Some(snapshot.last_submit_gap_us).filter(|v| *v > 0);
    state.max_submit_gap_us = Some(snapshot.max_submit_gap_us).filter(|v| *v > 0);
    state.effective_consumer_rate_milli_hz =
        Some((snapshot.effective_rate_hz * 1_000.0) as u64).filter(|v| *v > 0);
    state
}

/// The blocker's reason, as the bit-perfect log lines have always worded it.
pub(crate) fn blocker_reason(blocker: Option<BitPerfectBlocker>) -> &'static str {
    match blocker {
        Some(BitPerfectBlocker::NoStream) => "stream inactive",
        Some(BitPerfectBlocker::LostFrames) => "audio lost on the USB transport",
        Some(BitPerfectBlocker::FormatChanged) => "requested/effective format mismatch",
        Some(BitPerfectBlocker::ClockUnverified) => "clock verification reset",
        Some(BitPerfectBlocker::FeedbackUnlocked) => "feedback pacing has not locked",
        Some(BitPerfectBlocker::SoftwareGain) => "software gain active",
        Some(BitPerfectBlocker::UnsynchronizedAsync) => {
            "asynchronous endpoint without feedback, host-paced at the nominal rate"
        }
        Some(BitPerfectBlocker::PayloadUnverified) | None => "wire payload audit failed",
        Some(_) => "not verified",
    }
}

pub(crate) fn transport_label(transport: DsdTransportMode) -> &'static str {
    match transport {
        DsdTransportMode::DoP => "dop",
        DsdTransportMode::Native => "dsd-native",
        DsdTransportMode::None => "pcm",
    }
}
