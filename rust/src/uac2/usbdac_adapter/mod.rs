//! Flick's direct USB DAC output, on top of the `usbdac-core` library.
//!
//! The library owns the USB side: descriptors, clock programming and
//! verification, isochronous transfers with explicit or implicit feedback timing, loss
//! accounting, the ring, the wire payload audit and the DAC's hardware volume.
//! This module is what is Flick's: it registers the DAC Kotlin has claimed,
//! turns the engine's formats and DSD modes into library requests, runs the
//! render thread that pulls audio from the engine into the stream (`render`),
//! maps the library's events and errors onto Flick's (`events`), keeps the
//! session and fallback semantics Kotlin and Dart rely on (`state`), routes
//! volume (`volume`) and fills the diagnostics (`diagnostics`).
//!
//! The public functions keep the names and signatures the JNI layer, the FRB
//! API and the engine have always called.

mod config;
mod diagnostics;
mod events;
mod ports;
mod position;
mod render;
mod state;
mod volume;

#[cfg(test)]
mod tests;

pub use diagnostics::{android_direct_debug_state, AndroidDirectUsbDebugState};
pub(crate) use position::{android_direct_output_latency, audible_position_secs};
pub use volume::{
    android_direct_cached_hardware_mute, android_direct_cached_hardware_volume,
    android_direct_has_hardware_volume_control, android_direct_queue_hardware_volume_restore,
    android_direct_set_hardware_mute, android_direct_set_hardware_volume,
    android_direct_verify_hardware_volume_health,
};

use crate::audio::commands::AudioEvent;
use crate::audio::engine::AudioCallbackData;
use crate::dev_eprintln;
use config::{clock_policy, dac_config_for_device, skips_clock_validation};
use crossbeam_channel::Sender;
use events::{Effect, EventMapper};
use ports::{DacOpener, DacPort, FormatFacts, WireEncoding};
use render::{run_render_loop, RenderEnd, RenderPlan};
use state::{
    complete_pending_clear_locked, is_session_active, record_refusal, set_direct_mode_refusal_reason,
    set_engine_state, set_last_error, with_registration, ActiveStream, EngineState, Negotiated,
    Registration, SessionGuard, VolumeCache, CLEAR_PENDING, ENABLED, HARDWARE_VOLUME_GENERATION,
    ORIGINAL_VOLUME_ACTION, ORIGINAL_VOLUME_FORGET, SESSION_ACTIVE, SESSION_GATE, STATE,
    STOP_REQUESTED,
};
use std::os::fd::RawFd;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};
use usbdac_core::{DacEvent, DacListener, DeviceInfo, Error, RatePolicy, StreamRequest};

#[cfg(target_os = "android")]
fn set_audio_thread_priority() -> Result<(), std::io::Error> {
    crate::audio::thread_priority::raise_audio_render_priority()
}

/// The registered DAC's identity, as Kotlin hands it over once it has opened
/// the device and claimed the audio interfaces.
#[derive(Debug, Clone)]
pub struct AndroidDirectUsbDevice {
    pub fd: RawFd,
    claimed_interfaces: Arc<[u8]>,
    configuration_id: u8,
    unavailable_feedback_interfaces: Arc<[u8]>,
    pub vendor_id: u16,
    pub product_id: u16,
    pub product_name: String,
    pub device_name: Option<String>,
}

impl AndroidDirectUsbDevice {
    pub fn try_new(
        fd: RawFd,
        vendor_id: u16,
        product_id: u16,
        product_name: String,
        device_name: Option<String>,
        claimed_interfaces: Vec<u8>,
        configuration_id: u8,
        unavailable_feedback_interfaces: Vec<u8>,
    ) -> Result<Self, String> {
        // The library duplicates the descriptor for itself; Kotlin keeps its own
        // open until the DAC has been released.
        if fd < 0 {
            return Err(format!("Invalid Android USB file descriptor: {fd}"));
        }
        if configuration_id == 0 {
            return Err("Invalid USB configuration ID: 0".to_string());
        }
        Ok(Self {
            fd,
            claimed_interfaces: claimed_interfaces.into(),
            configuration_id,
            unavailable_feedback_interfaces: unavailable_feedback_interfaces.into(),
            vendor_id,
            product_id,
            product_name,
            device_name,
        })
    }

    fn info(&self) -> DeviceInfo {
        let mut info = DeviceInfo::new(self.vendor_id, self.product_id, self.product_name.clone())
            .with_claimed_interfaces(self.claimed_interfaces.to_vec());
        info.configuration_id = Some(self.configuration_id);
        info.unavailable_feedback_interfaces = self.unavailable_feedback_interfaces.to_vec();
        if let Some(name) = &self.device_name {
            info = info.with_device_name(name.clone());
        }
        info
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DsdTransportMode {
    None,
    DoP,
    Native,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AndroidDirectUsbPlaybackFormat {
    pub sample_rate: u32,
    pub bit_depth: u8,
    pub channels: u16,
    pub is_dop: bool,
    pub dsd_transport: DsdTransportMode,
    /// DSD 1-bit sample rate (e.g. 2_822_400 for DSD64). Set only when
    /// dsd_transport == Native. The wire rate is computed as
    /// `dsd_bit_rate / (8 * subslot_size)` after the alt-setting is chosen.
    pub dsd_bit_rate: u32,
}

fn sanitize_playback_format(format: AndroidDirectUsbPlaybackFormat) -> AndroidDirectUsbPlaybackFormat {
    let bit_depth = if format.is_dop {
        match format.bit_depth {
            24 | 32 => format.bit_depth,
            _ => 24,
        }
    } else if format.dsd_transport == DsdTransportMode::Native {
        format.bit_depth
    } else {
        match format.bit_depth {
            16 | 24 | 32 => format.bit_depth,
            _ => 16,
        }
    };
    AndroidDirectUsbPlaybackFormat {
        sample_rate: format.sample_rate.max(8_000),
        bit_depth,
        channels: format.channels.max(1),
        is_dop: format.is_dop,
        dsd_transport: format.dsd_transport,
        dsd_bit_rate: format.dsd_bit_rate,
    }
}

/// The library request for a playback format.
fn stream_request(format: &AndroidDirectUsbPlaybackFormat, policy: RatePolicy) -> StreamRequest {
    let request = match format.dsd_transport {
        DsdTransportMode::Native => StreamRequest::dsd_native(format.dsd_bit_rate, format.channels),
        DsdTransportMode::DoP => StreamRequest {
            sample_rate: format.sample_rate,
            channels: format.channels,
            bit_depth: format.bit_depth,
            encoding: usbdac_core::Encoding::DoP,
            rate_policy: RatePolicy::Exact,
        },
        DsdTransportMode::None => {
            StreamRequest::pcm(format.sample_rate, format.channels, format.bit_depth)
        }
    };
    request.with_rate_policy(policy)
}

/// Whether a negotiation answers a request for `want`: same encoding,
/// channels, depth and rate on the wire.
fn negotiation_serves(negotiated: &Negotiated, want: &StreamRequest) -> bool {
    let have = &negotiated.request;
    let format = &negotiated.format;
    let native = matches!(want.encoding, usbdac_core::Encoding::DsdNative { .. });
    have.encoding == want.encoding
        && have.channels == want.channels
        && have.bit_depth == want.bit_depth
        && format.channels == want.channels
        && (native || format.sample_rate == want.sample_rate)
}

/// The format the DAC will run: the requested one at the rate the negotiation
/// settled (for native DSD, the wire rate that follows from the subslot).
fn playback_format_after_negotiation(
    requested: AndroidDirectUsbPlaybackFormat,
    format: &FormatFacts,
) -> AndroidDirectUsbPlaybackFormat {
    AndroidDirectUsbPlaybackFormat {
        sample_rate: format.sample_rate,
        ..requested
    }
}

// ---- switches and session state ---------------------------------------------------

pub fn set_android_direct_usb_enabled(enabled: bool) {
    ENABLED.store(enabled, Ordering::Release);
}

pub fn android_direct_usb_enabled() -> bool {
    ENABLED.load(Ordering::Acquire)
}

pub fn is_usb_session_active() -> bool {
    is_session_active()
}

pub fn mark_android_usb_fallback(reason: Option<String>) {
    set_engine_state(EngineState::Fallback, reason);
}

/// Runs `start` only while the DAC registered under `expected_device` is still
/// owned by a live session that nobody asked to stop: a reconnect must not
/// restart output on a DAC that was released in the meantime.
pub(crate) fn with_live_usb_reconnect<T>(
    expected_device: &str,
    start: impl FnOnce() -> Result<T, String>,
) -> Result<T, String> {
    let _gate = SESSION_GATE.lock();
    let device_matches = STATE
        .lock()
        .as_ref()
        .and_then(|registration| registration.device.device_name.as_deref())
        == Some(expected_device);
    if !device_matches
        || !is_session_active()
        || CLEAR_PENDING.load(Ordering::SeqCst)
        || STOP_REQUESTED.load(Ordering::SeqCst)
    {
        return Err("Direct USB reconnect no longer owns the expected DAC".into());
    }
    start()
}

/// Request teardown without reporting the interfaces idle before the output
/// thread has finished with them.
pub fn force_release_usb_session() {
    let _gate = SESSION_GATE.lock();
    if is_session_active() {
        STOP_REQUESTED.store(true, Ordering::SeqCst);
    } else {
        complete_pending_clear_locked();
    }
}

/// Java's request for the device clear that follows: write the original level
/// back (Android gets the DAC), forget it (the DAC is gone) or keep it (Flick
/// holds on or takes the DAC straight back).
pub fn set_android_usb_original_volume_action(action: u32) -> bool {
    if action > ORIGINAL_VOLUME_FORGET {
        return false;
    }
    ORIGINAL_VOLUME_ACTION.store(action, Ordering::SeqCst);
    true
}

pub fn clear_android_usb_device() {
    let _gate = SESSION_GATE.lock();
    CLEAR_PENDING.store(true, Ordering::SeqCst);
    if is_session_active() {
        // Signal the render loop to stop before Android releases the
        // interfaces and lets the platform driver rebind.
        STOP_REQUESTED.store(true, Ordering::SeqCst);
        dev_eprintln!(
            "Android USB direct: clear requested while session active; signaling the render loop to stop"
        );
        return;
    }
    complete_pending_clear_locked();
}

pub fn wait_for_android_usb_session_stop(timeout: Duration) -> bool {
    let started_at = Instant::now();
    loop {
        if let Some(_gate) = SESSION_GATE.try_lock() {
            if !is_session_active() {
                complete_pending_clear_locked();
                return usbdac_core::Dac::await_usb_retirement(
                    timeout.saturating_sub(started_at.elapsed()),
                );
            }
        }
        if started_at.elapsed() >= timeout {
            return false;
        }
        thread::sleep(Duration::from_millis(10));
    }
}

// ---- registration ---------------------------------------------------------------------

pub fn register_android_usb_device(device: AndroidDirectUsbDevice) -> Result<(), String> {
    register_with(device, &ports::open_real_dac)
}

fn register_with(device: AndroidDirectUsbDevice, open: &DacOpener) -> Result<(), String> {
    let _gate = SESSION_GATE.lock();
    if is_session_active() {
        return Err(format!(
            "Android direct USB session is still active; refusing to replace registration for '{}'",
            device.product_name
        ));
    }
    complete_pending_clear_locked();
    if STATE.lock().is_some() {
        return Err(
            "Release the previous Android direct USB DAC before registering another".to_string(),
        );
    }

    // The library scans the descriptors, checks the host's claims, finds the
    // hardware volume control and keeps the DAC open from here on: the DAC stays
    // held for as long as it is registered, between tracks and while the stream
    // is released.
    let config = dac_config_for_device(device.vendor_id, device.product_id, &device.product_name);
    let dac = open(device.fd, device.info(), config).map_err(|error| error.to_string())?;

    let policy = clock_policy(device.vendor_id, device.product_id, &device.product_name);
    set_engine_state(EngineState::Idle, None);
    *STATE.lock() = Some(Registration {
        device,
        dac,
        requested_playback_format: None,
        playback_format: None,
        negotiated: None,
        lock_requested: true,
        last_error: None,
        direct_mode_refusal_reason: None,
        dac_clock_policy: policy,
        volume: VolumeCache::default(),
        hardware_volume_generation: HARDWARE_VOLUME_GENERATION.fetch_add(1, Ordering::Relaxed),
        stream: None,
    });
    // What the DAC is at, before anything of Flick's touches it.
    volume::refresh_volume_cache();
    Ok(())
}

/// Whether the DAC stays held while no stream is playing. The library keeps it
/// open for as long as it is registered, so this only records what Kotlin asked
/// for, which the diagnostics report.
pub fn set_android_usb_lock_enabled(enabled: bool) -> Result<(), String> {
    let _gate = SESSION_GATE.lock();
    match with_registration(|registration| registration.lock_requested = enabled) {
        Some(()) => Ok(()),
        None if enabled => Err("No Android direct USB DAC is registered".to_string()),
        None => Ok(()),
    }
}

// ---- playback format --------------------------------------------------------------------

fn reset_negotiation(registration: &mut Registration) {
    registration.negotiated = None;
    registration.direct_mode_refusal_reason = None;
}

pub fn set_android_usb_playback_format(
    playback_format: Option<AndroidDirectUsbPlaybackFormat>,
) -> Result<(), String> {
    let mut guard = STATE.lock();
    let Some(registration) = guard.as_mut() else {
        return if playback_format.is_some() {
            Err("No Android direct USB DAC is registered".to_string())
        } else {
            Ok(())
        };
    };
    let sanitized = playback_format.map(sanitize_playback_format);
    if is_session_active()
        && registration.playback_format == sanitized
        && registration.requested_playback_format == sanitized
    {
        dev_eprintln!("[USB] Keeping the active direct USB state for an unchanged playback format");
        return Ok(());
    }
    registration.playback_format = sanitized;
    registration.requested_playback_format = sanitized;
    reset_negotiation(registration);
    drop(guard);
    set_engine_state(EngineState::Idle, None);
    Ok(())
}

pub fn set_android_usb_dop_mode(
    is_dop: bool,
    carrier_rate: u32,
    bit_depth: u8,
) -> Result<(), String> {
    let mut guard = STATE.lock();
    let Some(registration) = guard.as_mut() else {
        return Err("No Android direct USB DAC is registered".to_string());
    };
    let current = registration
        .playback_format
        .unwrap_or(AndroidDirectUsbPlaybackFormat {
            sample_rate: carrier_rate,
            bit_depth,
            channels: 2,
            is_dop: false,
            dsd_transport: DsdTransportMode::None,
            dsd_bit_rate: 0,
        });
    let dop_format = AndroidDirectUsbPlaybackFormat {
        sample_rate: carrier_rate,
        bit_depth: if is_dop { bit_depth } else { current.bit_depth },
        channels: current.channels,
        is_dop,
        dsd_transport: if is_dop {
            DsdTransportMode::DoP
        } else {
            DsdTransportMode::None
        },
        dsd_bit_rate: 0,
    };
    let sanitized = sanitize_playback_format(dop_format);
    registration.playback_format = Some(sanitized);
    registration.requested_playback_format = Some(sanitized);
    registration.negotiated = None;
    Ok(())
}

pub fn set_android_usb_dsd_native_mode(dsd_bit_rate: u32) -> Result<(), String> {
    let mut guard = STATE.lock();
    let Some(registration) = guard.as_mut() else {
        return Err("No Android direct USB DAC is registered".to_string());
    };
    let wire_rate = dsd_bit_rate / (8 * 4); // DSD_U32 default wire rate
    let current = registration
        .playback_format
        .unwrap_or(AndroidDirectUsbPlaybackFormat {
            sample_rate: wire_rate,
            bit_depth: 1,
            channels: 2,
            is_dop: false,
            dsd_transport: DsdTransportMode::None,
            dsd_bit_rate: 0,
        });
    let native_format = AndroidDirectUsbPlaybackFormat {
        sample_rate: wire_rate,
        bit_depth: 1,
        channels: current.channels,
        is_dop: false,
        dsd_transport: DsdTransportMode::Native,
        dsd_bit_rate,
    };
    let sanitized = sanitize_playback_format(native_format);
    registration.playback_format = Some(sanitized);
    registration.requested_playback_format = Some(sanitized);
    registration.negotiated = None;
    Ok(())
}

// ---- what the engine asks before it starts ----------------------------------------------------

pub fn android_direct_output_signature(preferred_sample_rate: Option<u32>) -> Option<String> {
    if !android_direct_usb_enabled() {
        return None;
    }
    let guard = STATE.lock();
    let registration = guard.as_ref()?;
    let playback_format = registration.playback_format?;
    let device = &registration.device;

    // For devices with SkipClockValidation quirk, the stored playback_format
    // rate may be stale (e.g. hardcoded 48000 from initial DAC registration)
    // and not match the track's preferred rate. Don't reject the USB direct
    // signature over this: the engine re-negotiates the clock during stream
    // setup. Returning None here would send it to the shared path.
    let rate = if preferred_sample_rate != Some(playback_format.sample_rate) {
        if !skips_clock_validation(device.vendor_id, device.product_id, &device.product_name) {
            return None;
        }
        preferred_sample_rate.unwrap_or(playback_format.sample_rate)
    } else {
        playback_format.sample_rate
    };
    Some(format!(
        "android-uac2:{}:{}:{}:{}:{}",
        device.fd,
        rate,
        playback_format.bit_depth,
        playback_format.channels,
        device.device_name.as_deref().unwrap_or("usb"),
    ))
}

pub fn validate_android_direct_request(preferred_sample_rate: Option<u32>) -> Result<(), String> {
    if !android_direct_usb_enabled() {
        return Ok(());
    }
    let guard = STATE.lock();
    let Some(registration) = guard.as_ref() else {
        return Ok(());
    };
    let Some(playback_format) = registration.playback_format else {
        return Ok(());
    };
    let device = &registration.device;

    if playback_format.sample_rate == 0 {
        // A DAC with a broken clock readback; the format is prepared at the
        // requested rate and downstream guards handle the real one.
        dev_eprintln!(
            "[USB] validate_android_direct_request: playback_format.sample_rate is 0, allowing validation to pass for preferred {:?} Hz",
            preferred_sample_rate
        );
        return Ok(());
    }
    // For devices with SkipClockValidation quirk, the stored playback_format
    // rate may be stale and not match the track's actual rate; the stream
    // negotiates the clock again, so allow any positive rate.
    if skips_clock_validation(device.vendor_id, device.product_id, &device.product_name) {
        dev_eprintln!(
            "[USB] validate_android_direct_request: SkipClockValidation quirk active, accepting stored {} Hz for preferred {:?} Hz",
            playback_format.sample_rate, preferred_sample_rate
        );
        return Ok(());
    }
    if preferred_sample_rate == Some(playback_format.sample_rate) {
        return Ok(());
    }
    Err(format!(
        "Android direct USB DAC '{}' is prepared for {} Hz, but the track requires {:?} Hz",
        device.product_name, playback_format.sample_rate, preferred_sample_rate
    ))
}

pub fn android_direct_preferred_sample_rate(preferred_sample_rate: Option<u32>) -> Option<u32> {
    STATE
        .lock()
        .as_ref()
        .and_then(|registration| registration.playback_format.map(|format| format.sample_rate))
        .or(preferred_sample_rate)
}

/// Settles the rate the DAC will run for `preferred_sample_rate` and returns it,
/// which differs from the request when the DAC cannot run it (the engine then
/// resamples to it). Programs and verifies the DAC's clock.
pub fn negotiate_android_direct_output_sample_rate(
    preferred_sample_rate: Option<u32>,
) -> Result<Option<u32>, String> {
    if !android_direct_usb_enabled() {
        return Ok(preferred_sample_rate);
    }

    let session_gate = SESSION_GATE.lock();
    if CLEAR_PENDING.load(Ordering::SeqCst) {
        return Err("Android direct USB is being released".to_string());
    }
    let (requested_format, dac) = {
        let mut guard = STATE.lock();
        let Some(registration) = guard.as_mut() else {
            return Ok(preferred_sample_rate);
        };
        let Some(mut requested_format) = registration
            .requested_playback_format
            .or(registration.playback_format)
        else {
            return Ok(preferred_sample_rate);
        };
        if let Some(rate) = preferred_sample_rate {
            requested_format.sample_rate = rate.max(8_000);
            // A caller-supplied rate is a PCM request: don't inherit DSD
            // transport flags or the engine keeps claiming the DSD alt
            // setting. DSD modes re-stamp these at engine create.
            if requested_format.is_dop || requested_format.dsd_transport != DsdTransportMode::None {
                requested_format.is_dop = false;
                requested_format.dsd_transport = DsdTransportMode::None;
                requested_format.dsd_bit_rate = 0;
                if requested_format.bit_depth == 1 {
                    requested_format.bit_depth = 24;
                }
            }
        }

        // A stream that is playing keeps the DAC's clock: only note what the
        // next one will ask for.
        if registration.stream.is_some() || SESSION_ACTIVE.load(Ordering::SeqCst) {
            registration.requested_playback_format = Some(requested_format);
            if registration.playback_format.map(|f| f.sample_rate) != Some(requested_format.sample_rate) {
                registration.playback_format = Some(requested_format);
            }
            return Ok(Some(requested_format.sample_rate));
        }

        registration.requested_playback_format = Some(requested_format);
        registration.playback_format = Some(requested_format);
        reset_negotiation(registration);
        (requested_format, registration.dac.clone())
    };

    let _session = SessionGuard::start_locked();
    drop(session_gate);
    negotiate_playback_format(requested_format, dac.as_ref()).map(|format| {
        // The effective rate the negotiation stored.
        let actual_rate = STATE
            .lock()
            .as_ref()
            .and_then(|registration| registration.playback_format.map(|f| f.sample_rate))
            .filter(|&rate| rate > 0)
            .unwrap_or(format.sample_rate);
        Some(actual_rate)
    })
}

/// Gives the open DAC the native DSD overrides the user has set now. They can
/// change while the DAC stays registered; the library reads them when a format
/// is negotiated and a stream is opened.
fn sync_dsd_overrides(dac: &dyn DacPort) {
    let big_endian = crate::audio::dsd_engine::output::dsd_big_endian_override();
    let subslot = crate::audio::dsd_engine::output::dsd_subslot_override();
    if let Err(error) = dac.set_dsd_overrides(big_endian, subslot) {
        log::warn!("[USB] could not apply the native DSD overrides: {error}");
    }
}

/// Negotiates with the library, taking the nearest rate the DAC runs when it
/// cannot run the exact one, and records the outcome.
fn negotiate_playback_format(
    requested_format: AndroidDirectUsbPlaybackFormat,
    dac: &dyn DacPort,
) -> Result<AndroidDirectUsbPlaybackFormat, String> {
    sync_dsd_overrides(dac);
    let request = stream_request(&requested_format, RatePolicy::NearestSupported);
    match dac.negotiate(&request) {
        Ok(format) => {
            let effective = playback_format_after_negotiation(requested_format, &format);
            with_registration(|registration| {
                registration.playback_format = Some(effective);
                registration.negotiated = Some(Negotiated { request, format });
                registration.last_error = None;
            });
            Ok(effective)
        }
        Err(error) => {
            let message = error.to_string();
            set_last_error(Some(message.clone()));
            set_direct_mode_refusal_reason(Some(message.clone()));
            Err(message)
        }
    }
}

// ---- the backend --------------------------------------------------------------------------------

pub struct AndroidDirectUsbBackend {
    stop: Arc<AtomicBool>,
    render_thread: Option<JoinHandle<()>>,
}

impl AndroidDirectUsbBackend {
    pub fn stop(&mut self) -> Result<(), String> {
        self.stop.store(true, Ordering::Release);
        match self.render_thread.take() {
            Some(handle) => handle.join().map_err(|payload| {
                format!(
                    "Android USB direct render thread panicked: {}",
                    panic_payload_message(payload.as_ref())
                )
            }),
            None => Ok(()),
        }
    }
}

impl Drop for AndroidDirectUsbBackend {
    fn drop(&mut self) {
        let _ = self.stop();
    }
}

fn panic_payload_message(payload: &(dyn std::any::Any + Send)) -> String {
    if let Some(message) = payload.downcast_ref::<&str>() {
        (*message).to_string()
    } else if let Some(message) = payload.downcast_ref::<String>() {
        message.clone()
    } else {
        "unknown panic".to_string()
    }
}

pub fn create_android_usb_backend(
    callback_data: Arc<AudioCallbackData>,
    event_tx: Sender<AudioEvent>,
    preferred_sample_rate: u32,
) -> Result<Option<AndroidDirectUsbBackend>, String> {
    if CLEAR_PENDING.load(Ordering::SeqCst) {
        dev_eprintln!(
            "Android USB direct backend skipped: clear requested before startup for preferred {} Hz",
            preferred_sample_rate
        );
        return Ok(None);
    }

    // Wait for any previous USB session to finish cleanup (up to 1.5s).
    // This prevents "Resource busy" when the engine is recreated for a
    // new track before the old backend's threads have fully stopped.
    for attempt in 0..15 {
        if !SESSION_ACTIVE.load(Ordering::SeqCst) {
            break;
        }
        if attempt == 0 {
            dev_eprintln!("Android USB direct: previous session still active, waiting for cleanup...");
        }
        thread::sleep(Duration::from_millis(100));
    }

    let session = {
        let _gate = SESSION_GATE.lock();
        if CLEAR_PENDING.load(Ordering::SeqCst) {
            return Ok(None);
        }
        if is_session_active() {
            return Err("Previous Android direct USB session is still stopping".to_string());
        }
        Arc::new(SessionGuard::start_locked())
    };
    create_backend(callback_data, event_tx, preferred_sample_rate, &session)
}

/// The format to open: the effective one when its rate is the one the engine
/// starts at, else the requested one if that matches.
fn choose_playback_format(
    registration: &Registration,
    preferred_sample_rate: u32,
) -> Result<(AndroidDirectUsbPlaybackFormat, bool), String> {
    let name = &registration.device.product_name;
    let Some(effective) = registration.playback_format else {
        return Err(format!(
            "No effective Android USB playback format was configured for '{name}'"
        ));
    };
    let requested = registration.requested_playback_format.unwrap_or(effective);
    if effective.sample_rate == preferred_sample_rate {
        Ok((effective, false))
    } else if requested.sample_rate == preferred_sample_rate {
        dev_eprintln!(
            "Android USB direct backend: preferred {} Hz matched the requested playback format for '{}' while the effective one was {} Hz; using the requested format",
            preferred_sample_rate, name, effective.sample_rate,
        );
        Ok((requested, true))
    } else if effective.sample_rate == 0 && requested.sample_rate > 0 {
        dev_eprintln!(
            "Android USB direct backend: effective format had 0 Hz, using the requested format at {} Hz for '{}'",
            requested.sample_rate, name,
        );
        Ok((requested, true))
    } else if effective.sample_rate > 0 && requested.sample_rate == 0 {
        Ok((effective, false))
    } else {
        Err(format!(
            "Prepared playback rate did not match requested startup rate {} Hz",
            preferred_sample_rate
        ))
    }
}

fn create_backend(
    callback_data: Arc<AudioCallbackData>,
    event_tx: Sender<AudioEvent>,
    mut preferred_sample_rate: u32,
    session: &Arc<SessionGuard>,
) -> Result<Option<AndroidDirectUsbBackend>, String> {
    // Some buggy DACs report 0 Hz, and Dart passes it as the preferred rate.
    // Use the requested playback format's rate instead of 0.
    if preferred_sample_rate == 0 {
        if let Some(rate) = STATE.lock().as_ref().and_then(|registration| {
            registration
                .requested_playback_format
                .or(registration.playback_format)
                .map(|format| format.sample_rate)
        }) {
            preferred_sample_rate = rate;
        }
        if preferred_sample_rate == 0 {
            preferred_sample_rate = 44_100;
        }
        dev_eprintln!(
            "[USB] preferred_sample_rate was 0, using {} Hz instead",
            preferred_sample_rate
        );
    }

    let chosen = {
        let guard = STATE.lock();
        match guard.as_ref() {
            None => Err(None),
            Some(registration) => choose_playback_format(registration, preferred_sample_rate)
                .map(|(format, used_requested)| {
                    (
                        format,
                        used_requested,
                        registration.dac.clone(),
                        registration.negotiated.clone(),
                    )
                })
                .map_err(Some),
        }
    };
    let (mut playback_format, use_requested, dac, negotiated) = match chosen {
        Ok(chosen) => chosen,
        Err(None) => {
            dev_eprintln!(
                "Android USB direct backend skipped: no registered DAC state for preferred {} Hz",
                preferred_sample_rate
            );
            set_engine_state(
                EngineState::Error,
                Some("No registered Android USB DAC state was available".to_string()),
            );
            return Ok(None);
        }
        Err(Some(message)) => {
            dev_eprintln!("Android USB direct backend skipped: {message}");
            set_engine_state(EngineState::Error, Some(message));
            return Ok(None);
        }
    };
    if use_requested {
        with_registration(|registration| registration.playback_format = Some(playback_format));
    }
    set_engine_state(EngineState::UsbInit, None);

    // Native DSD needs a bit rate to derive the wire rate from.
    if playback_format.dsd_transport == DsdTransportMode::Native && playback_format.dsd_bit_rate == 0 {
        let message = "USB native DSD refused: invalid subslot or source bit rate".to_string();
        record_refusal(&message);
        return Err(message);
    }

    // The format the previous negotiation settled is reused, so the clock is not
    // programmed a second time for it; the library verifies it again as the
    // stream opens either way.
    sync_dsd_overrides(dac.as_ref());
    let want = stream_request(&playback_format, RatePolicy::Exact);
    let format = match negotiated.filter(|negotiated| negotiation_serves(negotiated, &want)) {
        Some(negotiated) => negotiated.format,
        None => match dac.negotiate(&want) {
            Ok(format) => format,
            Err(error) => return Err(refuse(&error)),
        },
    };
    if matches!(format.encoding, WireEncoding::DsdNative { .. }) {
        // Native DSD clock frames contain subslot_size bytes per channel.
        playback_format.sample_rate = format.sample_rate;
        with_registration(|registration| {
            registration.playback_format = Some(playback_format);
            if let Some(requested) = registration.requested_playback_format.as_mut() {
                requested.sample_rate = format.sample_rate;
            }
        });
    }

    let failed = Arc::new(AtomicBool::new(false));
    let listener = stream_listener(event_tx.clone(), failed.clone());
    let stream = match dac.open_stream(&format, listener) {
        Ok(stream) => stream,
        Err(error) => return Err(refuse(&error)),
    };

    set_last_error(None);
    set_direct_mode_refusal_reason(None);
    let active = Arc::new(ActiveStream::new(
        stream,
        format.clone(),
        callback_data.output_flush_generation(),
        failed,
    ));
    with_registration(|registration| registration.stream = Some(active.clone()));
    set_engine_state(EngineState::UsbReady, None);
    volume::refresh_volume_cache();

    let plan = RenderPlan {
        channels: usize::from(format.channels),
        counter_rate_hz: format.counter_rate_hz,
        dsd_transport: playback_format.dsd_transport,
        bytes_per_ring_sample: match format.encoding {
            WireEncoding::DsdNative { .. } => 1,
            _ => usize::from(format.subslot_bytes).max(1),
        },
    };
    let stop = Arc::new(AtomicBool::new(false));
    let thread_stop = Arc::clone(&stop);
    let thread_active = Arc::clone(&active);
    let thread_session = Arc::clone(session);
    // Publish before spawning: the stream can fail immediately and must not
    // have its error state overwritten by the caller.
    set_engine_state(EngineState::Streaming, None);
    let render_thread = thread::Builder::new()
        .name("android-usb-direct-render".to_string())
        .spawn(move || {
            // Keeps the interfaces alive until the loop and the stream are done.
            let _session = thread_session;
            #[cfg(target_os = "android")]
            if set_audio_thread_priority().is_err() {
                dev_eprintln!("[AudioSched] Render thread running without SCHED_FIFO");
            }
            run_session(callback_data, event_tx, thread_active, plan, thread_stop);
        });
    let render_thread = match render_thread {
        Ok(handle) => handle,
        Err(error) => {
            let message = format!("Failed to spawn Android USB direct render thread: {error}");
            end_stream(&active, false);
            set_engine_state(EngineState::Error, Some(message.clone()));
            return Err(message);
        }
    };

    dev_eprintln!(
        "[USB] Streaming started preferred={}Hz effective={}Hz endpoint=0x{:02x} interface={} alt={} dsd_transport={:?}",
        preferred_sample_rate,
        format.sample_rate,
        format.endpoint_address,
        format.interface_number,
        format.alt_setting,
        playback_format.dsd_transport,
    );
    Ok(Some(AndroidDirectUsbBackend {
        stop,
        render_thread: Some(render_thread),
    }))
}

/// Records a refusal from the library and returns the message the engine gets.
/// An unusable feedback route is refused like any format the DAC cannot carry:
/// the engine falls back to Android-managed output.
fn refuse(error: &Error) -> String {
    let message = if matches!(error, Error::ImplicitFeedbackUnsupported) {
        format!("USB direct refused: {}: no usable compatible implicit feedback source for the requested format", error.code())
    } else {
        format!("USB direct refused: {error}")
    };
    dev_eprintln!("[USB] {message}");
    record_refusal(&message);
    message
}

/// The render thread's whole life: loop, then close the stream and end the
/// session (the guard drops when the closure returns).
fn run_session(
    callback_data: Arc<AudioCallbackData>,
    event_tx: Sender<AudioEvent>,
    active: Arc<ActiveStream>,
    plan: RenderPlan,
    stop: Arc<AtomicBool>,
) {
    let panic_tx = event_tx.clone();
    let panic_stop = Arc::clone(&stop);
    let loop_active = Arc::clone(&active);
    let loop_stop = Arc::clone(&stop);
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
        run_render_loop(callback_data, event_tx, loop_active, plan, loop_stop)
    }));
    match result {
        Ok(RenderEnd::Stopped) => end_stream(&active, true),
        // The library's events have already told the player and set the state.
        Ok(RenderEnd::StreamFailed) => end_stream(&active, false),
        Ok(RenderEnd::WriteFailed(reason)) => {
            // The library has said why through its events; this only covers a
            // write that failed without one.
            if !active.failed.swap(true, Ordering::AcqRel) {
                let message = format!("Android USB direct write failed: {reason}");
                dev_eprintln!("{message}");
                set_last_error(Some(message.clone()));
                set_engine_state(EngineState::Error, Some(message.clone()));
                let _ = panic_tx.try_send(AudioEvent::Error { message });
            }
            end_stream(&active, false);
        }
        Err(payload) => {
            panic_stop.store(true, Ordering::Release);
            let message = format!(
                "Android USB direct render thread panicked: {}",
                panic_payload_message(payload.as_ref())
            );
            dev_eprintln!("{message}");
            set_last_error(Some(message.clone()));
            set_engine_state(EngineState::Error, Some(message.clone()));
            mark_android_usb_fallback(Some(message.clone()));
            let _ = panic_tx.try_send(AudioEvent::Error { message });
            end_stream(&active, false);
        }
    }
}

/// Closes the stream and drops it from the registration. `clean` is a stop the
/// engine or a clear asked for, which leaves the engine state idle.
fn end_stream(active: &Arc<ActiveStream>, clean: bool) {
    with_registration(|registration| registration.stream = None);
    // Outside the state lock: closing joins the library's output thread.
    active.stream.close();
    if clean {
        set_engine_state(EngineState::Idle, None);
    }
}

/// The listener the library calls with a stream's events. It runs on the
/// library's event thread and must stay short.
fn stream_listener(event_tx: Sender<AudioEvent>, failed: Arc<AtomicBool>) -> Arc<dyn DacListener> {
    let mapper = parking_lot::Mutex::new(EventMapper::default());
    Arc::new(move |event: DacEvent| {
        let effects = mapper.lock().map(event);
        apply_effects(effects, &event_tx, &failed);
    })
}

fn apply_effects(effects: Vec<Effect>, event_tx: &Sender<AudioEvent>, failed: &AtomicBool) {
    for effect in effects {
        match effect {
            Effect::Info(line) => log::info!("{line}"),
            Effect::Warn(line) => log::warn!("{line}"),
            Effect::StreamFailed(message) => {
                failed.store(true, Ordering::Release);
                dev_eprintln!("{message}");
                set_last_error(Some(message.clone()));
                set_engine_state(EngineState::Error, Some(message.clone()));
                let _ = event_tx.try_send(AudioEvent::Error { message });
            }
            Effect::Volume(state) => volume::publish(&state),
            Effect::BitPerfect(verified) => log_bit_perfect_transition(verified),
        }
    }
}

/// Transition evidence line for the bit-perfect claim. Both edges are logged
/// unconditionally so field reports can show mode flapping without Developer
/// Mode.
fn log_bit_perfect_transition(verified: bool) {
    let context = STATE.lock().as_ref().and_then(|registration| {
        Some((
            registration.playback_format?,
            registration.device.product_name.clone(),
            registration.stream.clone(),
        ))
    });
    let Some((format, product, stream)) = context else {
        return;
    };
    let transport = diagnostics::transport_label(format.dsd_transport);
    let line = if verified {
        format!(
            "[USB-BITPERFECT] verified: {} bit/{} Hz {}ch {} direct on \"{}\" — no mixer, no resample, no DSP",
            format.bit_depth, format.sample_rate, format.channels, transport, product
        )
    } else {
        let reason = stream
            .map(|active| diagnostics::blocker_reason(active.stream.bit_perfect_blocker()))
            .unwrap_or("stream inactive");
        format!(
            "[USB-BITPERFECT] released: {} bit/{} Hz {}ch {} direct on \"{}\" — {}",
            format.bit_depth, format.sample_rate, format.channels, transport, product, reason
        )
    };
    log::info!("{line}");
    crate::api::logging::forward_to_sink(line);
}
