//! The seam between the adapter and the usbdac library.
//!
//! The adapter talks to three small traits instead of the library's handles, so
//! its behaviour (pause and flush mapping, gain routing, event mapping, device
//! lifecycle, volume routing, negotiation and fallback) can be tested on the
//! host with scripted fakes. The library's own types are `#[non_exhaustive]`
//! and a `StreamFormat` cannot be built by hand, so what crosses the seam on
//! the way back is a plain Flick-owned copy of the facts the adapter uses
//! (`DacFacts`, `FormatFacts`, `StreamSnapshot`); the real implementations here
//! convert.

use parking_lot::{Mutex, RwLock};
use super::support::{self, Capture};
use serde_json::json;
use std::os::fd::RawFd;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use usbdac_core::{
    AltFormat, BitPerfectBlocker, Capabilities, CloseVolume, Dac, DacConfig, DacListener,
    DacStream, DeviceInfo, EndpointUsage, Encoding, Error, LinkSpeed, Pacing, StreamFormat, StreamRequest,
    StreamStats, SyncKind, VolumeControl, VolumeState,
};

/// One isochronous OUT alternate setting, as the diagnostics show it.
#[derive(Debug, Clone, PartialEq, serde::Serialize)]
pub(crate) struct AltFacts {
    pub interface_number: u8,
    pub alt_setting: u8,
    pub endpoint_address: u8,
    pub endpoint_interval: u8,
    pub service_interval_us: u32,
    pub max_packet_bytes: usize,
    /// `PCM`, `PCM8`, `IEEE_FLOAT`, `DOP`, `DSD` or `OTHER`.
    pub format_tag: &'static str,
    pub channels: u16,
    pub subslot_size: u8,
    pub bit_resolution: u8,
    pub sample_rates: Vec<u32>,
    pub sync_type: &'static str,
    pub usage_type: &'static str,
    pub refresh: u8,
    pub synch_address: u8,
}

/// What the DAC offers, from the descriptors scanned when it was opened.
#[derive(Debug, Clone, Default, PartialEq, serde::Serialize)]
pub(crate) struct DacFacts {
    pub alts: Vec<AltFacts>,
    pub has_mute: bool,
    /// Raw UAC units (1/256 dB): minimum, maximum, finest step.
    pub volume_raw: Option<(i16, i16, i16)>,
}

/// How the audio is carried on the wire.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum WireEncoding {
    Pcm,
    /// Type I unsigned 8-bit PCM. Flick never requests it.
    PcmU8,
    /// Type I IEEE-754 binary32 PCM. Flick never requests it.
    PcmFloat32,
    Dop,
    DsdNative { bit_rate: u32 },
}

/// The wire format a negotiation settled on.
#[derive(Debug, Clone)]
pub(crate) struct FormatFacts {
    pub encoding: WireEncoding,
    /// Frame rate on the wire: the PCM rate, the DoP carrier, or for native DSD
    /// the rate of subslot-sized words.
    pub sample_rate: u32,
    pub channels: u16,
    pub valid_bits: u8,
    pub subslot_bytes: u8,
    pub interface_number: u8,
    pub alt_setting: u8,
    pub endpoint_address: u8,
    pub sync_type: &'static str,
    pub feedback_endpoint: Option<u8>,
    pub service_interval_us: u32,
    /// The unit of the stream's frame counters (`bit_rate / 8` for native DSD).
    pub counter_rate_hz: u32,
    /// The DAC runs another rate than the one asked for (nearest-rate policy).
    pub rate_differs_from_request: bool,
    /// The AudioControl interface the sample rate was programmed through
    /// (`StreamFormat::clock`). `None` when the DAC describes no clock entities.
    pub clock_interface_number: Option<u8>,
    /// The Clock Source entity the sample rate was set and verified on
    /// (`StreamFormat::clock`).
    pub clock_id: Option<u8>,
    /// How the library paces the endpoint: the stable code of
    /// `StreamFormat::pacing` (`explicit_feedback`, `implicit_feedback`, `adaptive`, `synchronous`,
    /// `unsynchronized`).
    pub pacing: &'static str,
    /// The library's own format, which `open_stream` needs. Absent in fakes.
    pub native: Option<StreamFormat>,
}

impl FormatFacts {
    pub(crate) fn from_native(format: &StreamFormat) -> Self {
        Self {
            encoding: match format.encoding {
                Encoding::Pcm => WireEncoding::Pcm,
                Encoding::PcmU8 => WireEncoding::PcmU8,
                Encoding::PcmFloat32 => WireEncoding::PcmFloat32,
                Encoding::DoP => WireEncoding::Dop,
                Encoding::DsdNative { bit_rate } => WireEncoding::DsdNative { bit_rate },
            },
            sample_rate: format.sample_rate,
            channels: format.channels,
            valid_bits: format.valid_bits,
            subslot_bytes: format.subslot_bytes,
            interface_number: format.interface_number,
            alt_setting: format.alt_setting,
            endpoint_address: format.endpoint_address,
            sync_type: sync_label(format.sync_type),
            feedback_endpoint: selected_feedback_endpoint(format.pacing),
            service_interval_us: format.service_interval_us,
            counter_rate_hz: format.counter_rate_hz(),
            rate_differs_from_request: format.rate_differs_from_request(),
            clock_interface_number: format.clock.as_ref().map(|clock| clock.control_interface),
            clock_id: format.clock.as_ref().map(|clock| clock.source_id),
            pacing: format.pacing.code(),
            native: Some(format.clone()),
        }
    }
}

/// What a stream reports, as plain numbers.
#[derive(Debug, Clone, Default, serde::Serialize)]
pub(crate) struct StreamSnapshot {
    pub underruns: u64,
    pub lost_frames: u64,
    pub frames_written: u64,
    pub frames_completed: u64,
    pub played_frames: u64,
    pub latency_frames: u64,
    pub latency_ms: f64,
    pub queued_frames: u64,
    pub buffer_ms: u32,
    pub buffer_capacity_ms: u32,
    /// The depth the ring is being kept at now.
    pub active_target_ms: u32,
    pub transfer_queue_depth: u32,
    pub completed_transfers: u64,
    pub last_turnaround_us: u64,
    pub max_turnaround_us: u64,
    pub last_submit_gap_us: u64,
    pub max_submit_gap_us: u64,
    pub effective_rate_hz: f64,
    pub software_gain: f32,
    pub bit_perfect: bool,
    pub stable: bool,
    pub clock_verified: bool,
    pub payload_verified: bool,
    pub payload_constant_for_ms: Option<u64>,
}

impl StreamSnapshot {
    fn from_stats(stats: &StreamStats) -> Self {
        Self {
            underruns: stats.underruns,
            lost_frames: stats.lost_frames,
            frames_written: stats.frames_written,
            frames_completed: stats.frames_completed,
            played_frames: stats.played_frames,
            latency_frames: stats.latency_frames,
            latency_ms: stats.latency_ms,
            queued_frames: stats.queued_frames,
            buffer_ms: stats.buffer_ms,
            buffer_capacity_ms: stats.buffer_capacity_ms,
            active_target_ms: stats.active_target_ms,
            transfer_queue_depth: stats.transfer_queue_depth,
            completed_transfers: stats.completed_transfers,
            last_turnaround_us: stats.last_turnaround_us,
            max_turnaround_us: stats.max_turnaround_us,
            last_submit_gap_us: stats.last_submit_gap_us,
            max_submit_gap_us: stats.max_submit_gap_us,
            effective_rate_hz: stats.effective_rate_hz,
            software_gain: stats.software_gain,
            bit_perfect: stats.bit_perfect,
            stable: stats.stable,
            clock_verified: stats.clock_verified,
            payload_verified: stats.payload_verified,
            payload_constant_for_ms: stats.payload_constant_for_ms,
        }
    }
}

/// The open DAC.
pub(crate) trait DacPort: Send + Sync {
    fn facts(&self) -> DacFacts;
    fn negotiate(&self, request: &StreamRequest) -> Result<FormatFacts, Error>;
    fn open_stream(
        &self,
        format: &FormatFacts,
        listener: Arc<dyn DacListener>,
    ) -> Result<Arc<dyn StreamPort>, Error>;
    fn volume(&self) -> Option<Box<dyn VolumePort>>;
    fn set_dsd_overrides(&self, big_endian: Option<bool>, subslot: Option<u8>) -> Result<(), Error>;
    fn set_listener(&self, listener: Option<Arc<dyn DacListener>>);
    /// Closes the DAC (and a stream still open on it). Later calls fail.
    fn close(&self, volume: CloseVolume) -> Result<(), Error>;
}

/// The output stream.
pub(crate) trait StreamPort: Send + Sync {
    fn writable_bytes(&self) -> usize;
    fn queued_frames(&self) -> u64;
    fn write_i32(&self, samples: &[i32]) -> Result<usize, Error>;
    fn play(&self) -> Result<(), Error>;
    fn pause(&self) -> Result<(), Error>;
    fn flush(&self) -> Result<(), Error>;
    fn set_software_gain(&self, gain: f32) -> Result<(), Error>;
    fn set_host_processing(&self, processing: bool);
    fn played_frames(&self) -> u64;
    fn latency_frames(&self) -> u64;
    fn is_bit_perfect(&self) -> bool;
    fn bit_perfect_blocker(&self) -> Option<BitPerfectBlocker>;
    fn snapshot(&self) -> StreamSnapshot;
    /// Stops the stream and waits for its output thread. Later calls fail.
    fn close(&self);
}

/// The DAC's hardware volume.
pub(crate) trait VolumePort: Send {
    fn get(&self) -> Result<VolumeState, Error>;
    fn set_fraction(&self, fraction: f64) -> Result<VolumeState, Error>;
    fn set_mute(&self, muted: bool) -> Result<VolumeState, Error>;
}

/// Opens a DAC. Replaced in tests.
pub(crate) type DacOpener =
    dyn Fn(RawFd, DeviceInfo, DacConfig) -> Result<Arc<dyn DacPort>, Error> + Send + Sync;

/// Opens the real DAC through the library.
pub(crate) fn open_real_dac(
    fd: RawFd,
    info: DeviceInfo,
    mut config: DacConfig,
) -> Result<Arc<dyn DacPort>, Error> {
    let capture = support::prepare(&info, &mut config);
    let dac = Dac::open(fd, info, config).map_err(|error| {
        if let Some(c) = &capture { c.failure("dac_open", &error); }
        error
    })?;
    if let Some(c) = &capture { c.event("dac_opened", json!({})); }
    Ok(Arc::new(RealDac { handle: Arc::new(RwLock::new(Some(dac))), capture, leg: Mutex::new(None), pending_format: Mutex::new(PendingStreamLeg::default()), capabilities_recorded: AtomicBool::new(false) }))
}

struct RealDac {
    handle: Arc<RwLock<Option<Dac>>>,
    capture: Option<Capture>,
    leg: Mutex<Option<usize>>,
    // An unopened negotiation can become a stream leg exactly once. Cached
    // formats reopened later need a fresh leg so their predecessor is retained.
    pending_format: Mutex<PendingStreamLeg>,
    capabilities_recorded: AtomicBool,
}

#[derive(Default)]
struct PendingStreamLeg {
    format: Option<String>,
    number: Option<usize>,
}

impl PendingStreamLeg {
    fn take_for(&mut self, format: &str) -> Option<usize> {
        let pending = self.format.take();
        let number = self.number.take();
        if pending.as_deref() == Some(format) { number } else { None }
    }
}

const CLOSED: &str = "the DAC is closed";

fn closed() -> Error {
    Error::InvalidState(CLOSED.to_string())
}

impl DacPort for RealDac {
    fn facts(&self) -> DacFacts {
        let guard = self.handle.read();
        let facts = guard
            .as_ref()
            .and_then(|dac| dac.capabilities().ok())
            .map(|capabilities| facts_from_capabilities(&capabilities))
            .unwrap_or_default();
        if let Some(c) = &self.capture {
            if c.active() && !self.capabilities_recorded.swap(true, Ordering::AcqRel) {
                c.event("capabilities",json!({"capabilities":facts}));
            }
        }
        facts
    }

    fn negotiate(&self, request: &StreamRequest) -> Result<FormatFacts, Error> {
        let guard = self.handle.read();
        let dac = guard.as_ref().ok_or_else(closed)?;
        if let Some(c) = &self.capture {
            *self.leg.lock() = c.leg();
            *self.pending_format.lock() = PendingStreamLeg::default();
            let request = json!({"leg":*self.leg.lock(),"encoding":format!("{:?}",request.encoding),"sampleRate":request.sample_rate,"validBits":request.bit_depth,"channels":request.channels,"ratePolicy":format!("{:?}",request.rate_policy)});
            c.update_leg(*self.leg.lock(),"request",request.clone());
            c.event("negotiate_requested", request);
        }
        match dac.negotiate(request) {
            Ok(format) => {
                if let Some(c) = &self.capture {
                    *self.pending_format.lock() = PendingStreamLeg { format: Some(format!("{format:?}")), number: *self.leg.lock() };
                    c.update_leg(*self.leg.lock(), "format", format_report(&format));
                    c.event("negotiated",json!({"leg":*self.leg.lock()}));
                }
                Ok(FormatFacts::from_native(&format))
            }
            Err(e) => { if let Some(c) = &self.capture { c.leg_failure(*self.leg.lock(),"negotiate",&e); } Err(e) }
        }
    }

    fn open_stream(
        &self,
        format: &FormatFacts,
        listener: Arc<dyn DacListener>,
    ) -> Result<Arc<dyn StreamPort>, Error> {
        let guard = self.handle.read();
        let dac = guard.as_ref().ok_or_else(closed)?;
        let native = format
            .native
            .as_ref()
            .ok_or_else(|| Error::InvalidState("the format was not negotiated".to_string()))?;
        let capture = self.capture.clone();
        let event_capture = capture.clone();
        let leg = capture.as_ref().and_then(|c| {
            let leg = self.pending_format.lock().take_for(&format!("{native:?}")).or_else(|| c.leg());
            c.update_leg(leg,"format",format_report(native));
            leg
        });
        let wrapped: Arc<dyn DacListener> = if event_capture.is_some() {
            Arc::new(move |event: usbdac_core::DacEvent| {
                if let Some(c) = &event_capture {
                    match &event {
                        usbdac_core::DacEvent::Error(e) => c.leg_failure(leg,"stream_error",e),
                        usbdac_core::DacEvent::DeviceLost => c.leg_failure(leg,"device_lost",&Error::DeviceLost),
                        _ => c.sdk_event(&event),
                    }
                }
                listener.on_event(event);
            })
        } else { listener };
        let stream = dac.open_stream(native, wrapped).map_err(|e| {
            if let Some(c) = &capture { c.leg_failure(leg,"stream_open",&e); }
            e
        })?;
        if let Some(c) = &capture { c.event("stream_opened",json!({"leg":leg})); }
        Ok(Arc::new(RealStream { handle: RwLock::new(Some(stream)), dac: self.handle.clone(), capture, leg }))
    }

    fn volume(&self) -> Option<Box<dyn VolumePort>> {
        let guard = self.handle.read();
        guard
            .as_ref()
            .and_then(Dac::volume)
            .map(|volume| Box::new(RealVolume(volume)) as Box<dyn VolumePort>)
    }

    fn set_dsd_overrides(&self, big_endian: Option<bool>, subslot: Option<u8>) -> Result<(), Error> {
        let guard = self.handle.read();
        guard
            .as_ref()
            .ok_or_else(closed)?
            .set_dsd_overrides(big_endian, subslot)
            .map(|()| { if let Some(c) = &self.capture { c.event("dsd_overrides",json!({"bigEndian":big_endian,"subslot":subslot})); } })
    }

    fn set_listener(&self, listener: Option<Arc<dyn DacListener>>) {
        if let Some(dac) = self.handle.read().as_ref() {
            let capture = self.capture.clone();
            let listener = if capture.is_some() {
                listener.map(|listener| Arc::new(move |event: usbdac_core::DacEvent| {
                    if let Some(c) = &capture { c.sdk_event(&event); }
                    listener.on_event(event);
                }) as Arc<dyn DacListener>)
            } else { listener };
            dac.set_listener(listener);
        }
    }

    fn close(&self, volume: CloseVolume) -> Result<(), Error> {
        // The write lock waits for control and negotiation calls in flight, so
        // the host never releases its claims under one.
        let dac = self.handle.write().take();
        match dac {
            Some(dac) => {
                if let Some(c) = &self.capture {
                    c.event("dac_close_requested",json!({}));
                }
                let result = dac.close_with(volume);
                if let Some(c) = &self.capture { match &result {
                    Ok(()) => c.event("dac_closed",json!({})), Err(e) => c.failure("dac_close",e),
                } }
                result
            },
            None => Ok(()),
        }
    }
}

struct RealStream {
    handle: RwLock<Option<DacStream>>,
    dac: Arc<RwLock<Option<Dac>>>,
    capture: Option<Capture>,
    leg: Option<usize>,
}

impl RealStream {
    fn control(&self, stage: &str, call: impl FnOnce(&DacStream) -> Result<(),Error>) -> Result<(),Error> {
        let result = self.with(call);
        if let Some(c) = &self.capture { match &result {
            Ok(()) => c.event(stage,json!({"leg":self.leg})), Err(e) => c.leg_failure(self.leg,stage,e),
        } }
        result
    }

    fn with<T>(&self, call: impl FnOnce(&DacStream) -> Result<T, Error>) -> Result<T, Error> {
        match self.handle.read().as_ref() {
            Some(stream) => call(stream),
            None => Err(Error::InvalidState("the stream is closed".to_string())),
        }
    }

    fn peek<T>(&self, default: T, read: impl FnOnce(&DacStream) -> T) -> T {
        match self.handle.read().as_ref() {
            Some(stream) => read(stream),
            None => default,
        }
    }
}

impl StreamPort for RealStream {
    fn writable_bytes(&self) -> usize {
        self.peek(0, DacStream::writable_bytes)
    }

    fn queued_frames(&self) -> u64 {
        self.peek(0, DacStream::queued_frames)
    }

    fn write_i32(&self, samples: &[i32]) -> Result<usize, Error> {
        self.with(|stream| stream.write_i32(samples))
    }

    fn play(&self) -> Result<(), Error> {
        self.control("play", DacStream::play)
    }

    fn pause(&self) -> Result<(), Error> {
        self.control("pause", DacStream::pause)
    }

    fn flush(&self) -> Result<(), Error> {
        self.control("flush", DacStream::flush)
    }

    fn set_software_gain(&self, gain: f32) -> Result<(), Error> {
        self.with(|stream| stream.set_software_gain(gain))
    }

    fn set_host_processing(&self, processing: bool) {
        self.peek((), |stream| stream.set_host_processing(processing));
    }

    fn played_frames(&self) -> u64 {
        self.peek(0, DacStream::played_frames)
    }

    fn latency_frames(&self) -> u64 {
        self.peek(0, DacStream::latency_frames)
    }

    fn is_bit_perfect(&self) -> bool {
        self.peek(false, DacStream::is_bit_perfect)
    }

    fn bit_perfect_blocker(&self) -> Option<BitPerfectBlocker> {
        self.peek(Some(BitPerfectBlocker::NoStream), DacStream::bit_perfect_blocker)
    }

    fn snapshot(&self) -> StreamSnapshot {
        self.peek(StreamSnapshot::default(), |stream| {
            StreamSnapshot::from_stats(&stream.stats())
        })
    }

    fn close(&self) {
        let stream = self.handle.write().take();
        if let Some(stream) = stream {
            if let Some(c) = &self.capture {
                if c.active() {
                    let stats = stream.stats();
                    c.update_leg(self.leg,"stats",json!(StreamSnapshot::from_stats(&stats)));
                    c.update_leg(self.leg,"feedbackStats",json!({
                        "state":format!("{:?}",stats.state),"feedbackActive":stats.feedback_active,
                        "feedbackLocked":stats.feedback_locked,"feedbackStale":stats.feedback_stale,
                        "feedbackAgeMs":stats.feedback_age_ms,"feedbackRateHz":stats.feedback_rate_hz,
                        "feedbackReports":stats.feedback_reports,"feedbackEmpty":stats.feedback_empty,
                        "feedbackRejected":stats.feedback_rejected,"feedbackErrors":stats.feedback_errors,
                        "feedbackTimeouts":stats.feedback_timeouts,"urbTimeouts":stats.urb_timeouts,
                        "shortPackets":stats.short_packets,"zeroPackets":stats.zero_packets,
                        "implicitFifoDepth":stats.implicit_fifo_depth,"implicitFifoHighWater":stats.implicit_fifo_high_water,
                        "implicitWaits":stats.implicit_waits,"implicitEmptyBus":stats.implicit_empty_bus,
                        "implicitOverruns":stats.implicit_overruns,"implicitInTransfers":stats.implicit_in_transfers,
                        "implicitOutOfOrder":stats.implicit_out_of_order,"implicitInvalidPackets":stats.implicit_invalid_packets,
                        "implicitMeasuredFrames":stats.implicit_measured_frames,"implicitScheduledFrames":stats.implicit_scheduled_frames
                    }));
                    c.update_leg(self.leg,"explicitFeedbackDiagnostics",json!(format!("{:#?}",stream.explicit_feedback_diagnostics())));
                    c.update_leg(self.leg,"bitPerfectBlocker",json!(format!("{:?}",stream.bit_perfect_blocker())));
                    c.event("stream_close_requested",json!({"leg":self.leg}));
                }
            }
            if let Err(error) = stream.close() {
                if let Some(c) = &self.capture { c.leg_failure(self.leg,"stream_close",&error); }
                log::warn!("[USB] closing the stream: {error}");
            }
            if let Some(c) = &self.capture {
                if c.active() {
                    if let Some(trace) = self.dac.read().as_ref().and_then(Dac::feedback_trace) { c.feedback(self.leg,trace); }
                    c.event("stream_closed",json!({"leg":self.leg}));
                }
            }
        }
    }
}

struct RealVolume(VolumeControl);

impl VolumePort for RealVolume {
    fn get(&self) -> Result<VolumeState, Error> {
        self.0.get()
    }

    fn set_fraction(&self, fraction: f64) -> Result<VolumeState, Error> {
        self.0.set_fraction(fraction)
    }

    fn set_mute(&self, muted: bool) -> Result<VolumeState, Error> {
        self.0.set_mute(muted)
    }
}

pub(crate) fn sync_label(kind: SyncKind) -> &'static str {
    match kind {
        SyncKind::Asynchronous => "asynchronous",
        SyncKind::Adaptive => "adaptive",
        SyncKind::Synchronous => "synchronous",
        _ => "none",
    }
}

fn usage_label(usage: EndpointUsage) -> &'static str {
    match usage {
        EndpointUsage::Data => "data",
        EndpointUsage::Feedback => "feedback",
        EndpointUsage::FeedbackData => "feedback-data",
        _ => "reserved",
    }
}

fn format_tag(format: AltFormat) -> &'static str {
    match format {
        AltFormat::Pcm => "PCM",
        AltFormat::PcmU8 => "PCM8",
        AltFormat::PcmFloat32 => "IEEE_FLOAT",
        AltFormat::Dop => "DOP",
        AltFormat::Dsd => "DSD",
        _ => "OTHER",
    }
}

pub(crate) fn link_speed_label(speed: LinkSpeed) -> &'static str {
    match speed {
        LinkSpeed::Low => "low",
        LinkSpeed::Full => "full",
        LinkSpeed::High => "high",
        LinkSpeed::Super => "super",
        LinkSpeed::SuperPlus => "super-plus",
        _ => "unknown",
    }
}

fn facts_from_capabilities(capabilities: &Capabilities) -> DacFacts {
    DacFacts {
        alts: capabilities
            .alt_settings
            .iter()
            .map(|alt| AltFacts {
                interface_number: alt.interface_number,
                alt_setting: alt.alt_setting,
                endpoint_address: alt.endpoint_address,
                endpoint_interval: alt.endpoint_interval,
                service_interval_us: alt.service_interval_us,
                max_packet_bytes: alt.max_packet_bytes,
                format_tag: format_tag(alt.format),
                channels: alt.channels,
                subslot_size: alt.subslot_bytes,
                bit_resolution: alt.valid_bits,
                sample_rates: alt.sample_rates.clone(),
                sync_type: sync_label(alt.sync_type),
                usage_type: usage_label(alt.usage),
                refresh: alt.refresh,
                synch_address: alt.synch_address,
            })
            .collect(),
        has_mute: capabilities.has_mute,
        volume_raw: capabilities.volume.map(|range| {
            let raw = |db: f64| (db * 256.0).round().clamp(f64::from(i16::MIN), f64::from(i16::MAX)) as i16;
            (raw(range.min_db), raw(range.max_db), raw(range.res_db))
        }),
    }
}

/// Allowlisted negotiated wire, clock and pacing facts; never device identity strings.
fn format_report(format: &StreamFormat) -> serde_json::Value {
    json!({"encoding":format!("{:?}",format.encoding),"sampleRate":format.sample_rate,
        "channels":format.channels,"validBits":format.valid_bits,"subslotBytes":format.subslot_bytes,
        "interfaceNumber":format.interface_number,"altSetting":format.alt_setting,
        "endpointAddress":format.endpoint_address,"serviceIntervalUs":format.service_interval_us,
        "clock":format!("{:?}",format.clock),"pacing":format!("{:?}",format.pacing),
        "syncType":sync_label(format.sync_type),"counterRateHz":format.counter_rate_hz(),
        "rateDiffersFromRequest":format.rate_differs_from_request()})
}

/// Use the selected pacing source, rather than an advertised but unused endpoint.
pub(crate) fn selected_feedback_endpoint(pacing: Pacing) -> Option<u8> {
    match pacing {
        Pacing::ExplicitFeedback { endpoint } | Pacing::ImplicitFeedback { endpoint, .. } => {
            Some(endpoint)
        }
        _ => None,
    }
}

#[cfg(test)]
mod support_leg_tests {
    use super::PendingStreamLeg;

    #[test]
    fn a_negotiation_labels_only_one_matching_stream() {
        let mut pending = PendingStreamLeg { format: Some("pcm-44100".into()), number: Some(3) };
        assert_eq!(pending.take_for("pcm-44100"), Some(3));
        // A cached reopen must allocate another leg, retaining the old trace.
        assert_eq!(pending.take_for("pcm-44100"), None);
        pending = PendingStreamLeg { format: Some("dsd-native".into()), number: Some(4) };
        // Reusing an earlier cached format cannot inherit another request's leg.
        assert_eq!(pending.take_for("pcm-44100"), None);
        assert_eq!(pending.take_for("dsd-native"), None);
        // Failed negotiation leaves no pending format to mislabel the next open.
        assert_eq!(PendingStreamLeg::default().take_for("pcm-44100"), None);
    }
}
