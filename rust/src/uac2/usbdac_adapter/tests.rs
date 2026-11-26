//! Tests of the adapter against a scripted stand-in for the library.
//!
//! The library has its own tests for the transport; these cover what Flick adds:
//! the device lifecycle and session semantics, how formats are negotiated and a
//! DAC that cannot carry them falls back, the mapping of the engine's pause,
//! flush and gain onto the stream, the library's events and errors, volume
//! routing, the diagnostics shape and the quirks and overrides handed to the
//! library. No USB device is involved.

use super::config::DacClockPolicy;
use super::ports::{AltFacts, DacFacts, DacPort, FormatFacts, StreamPort, StreamSnapshot, VolumePort, WireEncoding};
use super::state::{
    EngineState, Lifecycle, CLEAR_PENDING, ENABLED, LIFECYCLE, ORIGINAL_VOLUME_ACTION,
    ORIGINAL_VOLUME_KEEP, SESSION_ACTIVE, STATE, STOP_REQUESTED,
};
use super::*;
use crate::audio::engine::{EngineDsdMode, PipelineMode};
use crate::audio::source::{AudioSource, PlaybackRange, SourceInfo};
use parking_lot::Mutex;
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex as StdMutex, MutexGuard};
use usbdac_core::{
    BitPerfectBlocker, CloseVolume, DacConfig, DacEvent, DacListener, Error, HandBack,
    StartupVolume, StreamRequest, VolumeState,
};

// ---- the scripted library -------------------------------------------------------------

#[derive(Debug, Clone, PartialEq)]
enum Call {
    Play,
    Pause,
    Flush,
    Gain(f32),
    HostProcessing(bool),
    Write(usize),
}

struct FakeStream {
    calls: Mutex<Vec<Call>>,
    queued: AtomicUsize,
    gain_bits: AtomicU32,
    host_processing: AtomicBool,
    closed: AtomicBool,
    fail_writes: AtomicBool,
    /// How many samples one write may take, to exercise a full ring.
    write_limit: AtomicUsize,
    rate: u32,
    channels: usize,
    bytes_per_sample: usize,
    latency: AtomicUsize,
    snapshot: Mutex<StreamSnapshot>,
    blocker: Mutex<Option<BitPerfectBlocker>>,
}

impl FakeStream {
    fn new(rate: u32, channels: usize, bytes_per_sample: usize) -> Arc<Self> {
        Arc::new(Self {
            calls: Mutex::new(Vec::new()),
            queued: AtomicUsize::new(0),
            gain_bits: AtomicU32::new(1.0f32.to_bits()),
            host_processing: AtomicBool::new(false),
            closed: AtomicBool::new(false),
            fail_writes: AtomicBool::new(false),
            write_limit: AtomicUsize::new(usize::MAX),
            rate,
            channels,
            bytes_per_sample,
            latency: AtomicUsize::new(0),
            snapshot: Mutex::new(StreamSnapshot::default()),
            blocker: Mutex::new(None),
        })
    }

    fn calls(&self) -> Vec<Call> {
        self.calls.lock().clone()
    }

    fn count(&self, wanted: &Call) -> usize {
        self.calls.lock().iter().filter(|call| *call == wanted).count()
    }

    fn gain(&self) -> f32 {
        f32::from_bits(self.gain_bits.load(Ordering::Relaxed))
    }

    fn target_samples(&self) -> usize {
        // The library's two depths: deep, or shallow while something is live.
        let live = self.gain() != 1.0 || self.host_processing.load(Ordering::Relaxed);
        let ms = if live { 100 } else { 250 };
        (self.rate as usize / 1_000) * ms * self.channels
    }

    /// What the output thread does: takes audio out of the ring.
    fn consume(&self, samples: usize) {
        let queued = self.queued.load(Ordering::Relaxed);
        self.queued.store(queued.saturating_sub(samples), Ordering::Relaxed);
    }
}

impl StreamPort for FakeStream {
    fn writable_bytes(&self) -> usize {
        let queued = self.queued.load(Ordering::Relaxed);
        self.target_samples().saturating_sub(queued) * self.bytes_per_sample
    }

    fn queued_frames(&self) -> u64 {
        (self.queued.load(Ordering::Relaxed) / self.channels) as u64
    }

    fn write_i32(&self, samples: &[i32]) -> Result<usize, Error> {
        if self.fail_writes.load(Ordering::Relaxed) || self.closed.load(Ordering::Relaxed) {
            return Err(Error::InvalidState("the stream has failed".to_string()));
        }
        let taken = samples.len().min(self.write_limit.load(Ordering::Relaxed));
        self.queued.fetch_add(taken, Ordering::Relaxed);
        self.calls.lock().push(Call::Write(taken));
        Ok(taken)
    }

    fn play(&self) -> Result<(), Error> {
        self.calls.lock().push(Call::Play);
        Ok(())
    }

    fn pause(&self) -> Result<(), Error> {
        self.calls.lock().push(Call::Pause);
        Ok(())
    }

    fn flush(&self) -> Result<(), Error> {
        self.queued.store(0, Ordering::Relaxed);
        self.calls.lock().push(Call::Flush);
        Ok(())
    }

    fn set_software_gain(&self, gain: f32) -> Result<(), Error> {
        self.gain_bits.store(gain.to_bits(), Ordering::Relaxed);
        self.calls.lock().push(Call::Gain(gain));
        Ok(())
    }

    fn set_host_processing(&self, processing: bool) {
        self.host_processing.store(processing, Ordering::Relaxed);
        self.calls.lock().push(Call::HostProcessing(processing));
    }

    fn played_frames(&self) -> u64 {
        0
    }

    fn latency_frames(&self) -> u64 {
        self.latency.load(Ordering::Relaxed) as u64
    }

    fn is_bit_perfect(&self) -> bool {
        self.snapshot.lock().bit_perfect
    }

    fn bit_perfect_blocker(&self) -> Option<BitPerfectBlocker> {
        *self.blocker.lock()
    }

    fn snapshot(&self) -> StreamSnapshot {
        self.snapshot.lock().clone()
    }

    fn close(&self) {
        self.closed.store(true, Ordering::Relaxed);
    }
}

#[derive(Clone)]
struct VolumeScript {
    fraction: f64,
    muted: Option<bool>,
    fail: bool,
    /// What the DAC reads back instead of what was written.
    readback_offset: f64,
    writes: Vec<f64>,
    mute_writes: Vec<bool>,
}

struct FakeVolume(Arc<Mutex<VolumeScript>>);

impl FakeVolume {
    fn state(script: &VolumeScript) -> VolumeState {
        VolumeState {
            db: -80.0 * (1.0 - script.fraction),
            fraction: script.fraction,
            muted: script.muted,
        }
    }
}

impl VolumePort for FakeVolume {
    fn get(&self) -> Result<VolumeState, Error> {
        let script = self.0.lock();
        if script.fail {
            return Err(Error::Control("the DAC does not answer".to_string()));
        }
        Ok(Self::state(&script))
    }

    fn set_fraction(&self, fraction: f64) -> Result<VolumeState, Error> {
        let mut script = self.0.lock();
        if script.fail {
            return Err(Error::Control("the DAC does not answer".to_string()));
        }
        script.writes.push(fraction);
        script.fraction = fraction + script.readback_offset;
        Ok(Self::state(&script))
    }

    fn set_mute(&self, muted: bool) -> Result<VolumeState, Error> {
        let mut script = self.0.lock();
        if script.fail {
            return Err(Error::Control("the DAC does not answer".to_string()));
        }
        if script.muted.is_none() {
            return Err(Error::Control("the DAC has no writable mute".to_string()));
        }
        script.mute_writes.push(muted);
        script.muted = Some(muted);
        Ok(Self::state(&script))
    }
}

struct FakeDac {
    stream: Arc<FakeStream>,
    volume: Arc<Mutex<VolumeScript>>,
    has_volume: AtomicBool,
    facts: Mutex<DacFacts>,
    negotiated: Mutex<Vec<StreamRequest>>,
    /// The rate the DAC runs instead of the requested one.
    rate_override: Mutex<Option<u32>>,
    negotiate_error: Mutex<Option<Error>>,
    open_error: Mutex<Option<Error>>,
    opened: AtomicUsize,
    listener: Mutex<Option<Arc<dyn DacListener>>>,
    overrides: Mutex<Vec<(Option<bool>, Option<u8>)>>,
    closed_with: Mutex<VecDeque<CloseVolume>>,
}

impl FakeDac {
    fn new() -> Arc<Self> {
        Arc::new(Self {
            stream: FakeStream::new(48_000, 2, 3),
            volume: Arc::new(Mutex::new(VolumeScript {
                fraction: 0.25,
                muted: Some(false),
                fail: false,
                readback_offset: 0.0,
                writes: Vec::new(),
                mute_writes: Vec::new(),
            })),
            has_volume: AtomicBool::new(true),
            facts: Mutex::new(DacFacts::default()),
            negotiated: Mutex::new(Vec::new()),
            rate_override: Mutex::new(None),
            negotiate_error: Mutex::new(None),
            open_error: Mutex::new(None),
            opened: AtomicUsize::new(0),
            listener: Mutex::new(None),
            overrides: Mutex::new(Vec::new()),
            closed_with: Mutex::new(VecDeque::new()),
        })
    }

    fn emit(&self, event: DacEvent) {
        let listener = self.listener.lock().clone().expect("a stream is open");
        listener.on_event(event);
    }
}

fn wire_bytes(bits: u8) -> u8 {
    match bits {
        16 => 2,
        24 => 3,
        _ => 4,
    }
}

impl DacPort for FakeDac {
    fn facts(&self) -> DacFacts {
        self.facts.lock().clone()
    }

    fn negotiate(&self, request: &StreamRequest) -> Result<FormatFacts, Error> {
        self.negotiated.lock().push(*request);
        if let Some(error) = self.negotiate_error.lock().clone() {
            return Err(error);
        }
        let native = matches!(request.encoding, usbdac_core::Encoding::DsdNative { .. });
        let rate = if native {
            match request.encoding {
                usbdac_core::Encoding::DsdNative { bit_rate } => bit_rate / 32,
                _ => 0,
            }
        } else {
            self.rate_override.lock().unwrap_or(request.sample_rate)
        };
        if request.rate_policy == usbdac_core::RatePolicy::Exact
            && !native
            && rate != request.sample_rate
        {
            return Err(Error::Unsupported {
                reason: format!("the DAC cannot run {} Hz exactly", request.sample_rate),
                offered: Vec::new(),
            });
        }
        let (encoding, valid_bits, subslot, counter) = match request.encoding {
            usbdac_core::Encoding::Pcm => (
                WireEncoding::Pcm,
                request.bit_depth,
                wire_bytes(request.bit_depth),
                rate,
            ),
            usbdac_core::Encoding::DoP => (WireEncoding::Dop, 24, 3, rate),
            usbdac_core::Encoding::DsdNative { bit_rate } => {
                (WireEncoding::DsdNative { bit_rate }, 1, 4, bit_rate / 8)
            }
        };
        Ok(FormatFacts {
            encoding,
            sample_rate: rate,
            channels: request.channels,
            valid_bits,
            subslot_bytes: subslot,
            interface_number: 1,
            alt_setting: 2,
            endpoint_address: 0x01,
            sync_type: "asynchronous",
            explicit_feedback: true,
            service_interval_us: 125,
            counter_rate_hz: counter,
            rate_differs_from_request: !native && rate != request.sample_rate,
            native: None,
        })
    }

    fn open_stream(
        &self,
        _format: &FormatFacts,
        listener: Arc<dyn DacListener>,
    ) -> Result<Arc<dyn StreamPort>, Error> {
        if let Some(error) = self.open_error.lock().clone() {
            return Err(error);
        }
        self.opened.fetch_add(1, Ordering::SeqCst);
        *self.listener.lock() = Some(listener);
        Ok(self.stream.clone())
    }

    fn volume(&self) -> Option<Box<dyn VolumePort>> {
        self.has_volume
            .load(Ordering::Relaxed)
            .then(|| Box::new(FakeVolume(self.volume.clone())) as Box<dyn VolumePort>)
    }

    fn set_dsd_overrides(&self, big_endian: Option<bool>, subslot: Option<u8>) -> Result<(), Error> {
        self.overrides.lock().push((big_endian, subslot));
        Ok(())
    }

    fn set_listener(&self, _listener: Option<Arc<dyn DacListener>>) {}

    fn close(&self, volume: CloseVolume) -> Result<(), Error> {
        self.closed_with.lock().push_back(volume);
        Ok(())
    }
}

// ---- helpers -----------------------------------------------------------------------------

static TEST_LOCK: StdMutex<()> = StdMutex::new(());

fn reset() {
    *STATE.lock() = None;
    *LIFECYCLE.lock() = Lifecycle {
        state: EngineState::Idle,
        reason: None,
    };
    ENABLED.store(true, Ordering::SeqCst);
    CLEAR_PENDING.store(false, Ordering::SeqCst);
    STOP_REQUESTED.store(false, Ordering::SeqCst);
    SESSION_ACTIVE.store(false, Ordering::SeqCst);
    ORIGINAL_VOLUME_ACTION.store(ORIGINAL_VOLUME_KEEP, Ordering::SeqCst);
    crate::audio::dsd_engine::output::set_dsd_big_endian_override(None);
    crate::audio::dsd_engine::output::set_dsd_subslot_override(None);
}

/// The adapter's state is process-wide: tests that touch it run one at a time,
/// starting from nothing registered.
fn serial() -> MutexGuard<'static, ()> {
    let guard = TEST_LOCK.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    reset();
    guard
}

fn device() -> AndroidDirectUsbDevice {
    named_device(0x1234, 0x5678, "Test DAC")
}

fn named_device(vendor: u16, product: u16, name: &str) -> AndroidDirectUsbDevice {
    AndroidDirectUsbDevice::try_new(
        7,
        vendor,
        product,
        name.to_string(),
        Some("/dev/bus/usb/001/002".to_string()),
        vec![0, 1, 2],
    )
    .unwrap()
}

fn opener(fake: &Arc<FakeDac>) -> impl Fn(RawFd, DeviceInfo, DacConfig) -> Result<Arc<dyn DacPort>, Error> {
    let fake = fake.clone();
    move |_, _, _| Ok(fake.clone() as Arc<dyn DacPort>)
}

fn register(fake: &Arc<FakeDac>) {
    register_with(device(), &opener(fake)).unwrap();
}

fn pcm_format(rate: u32, bits: u8) -> AndroidDirectUsbPlaybackFormat {
    AndroidDirectUsbPlaybackFormat {
        sample_rate: rate,
        bit_depth: bits,
        channels: 2,
        is_dop: false,
        dsd_transport: DsdTransportMode::None,
        dsd_bit_rate: 0,
    }
}

fn registered_pcm(fake: &Arc<FakeDac>, rate: u32, bits: u8) {
    register(fake);
    set_android_usb_playback_format(Some(pcm_format(rate, bits))).unwrap();
}

fn callback_data(rate: u32) -> Arc<AudioCallbackData> {
    let (finished_tx, _finished_rx) = crossbeam_channel::bounded(8);
    Arc::new(AudioCallbackData::new(
        rate,
        2,
        finished_tx,
        PipelineMode::Passthrough,
        EngineDsdMode::Pcm,
    ))
}

/// Gives the engine something to play, so it is not idle.
fn with_music(data: &Arc<AudioCallbackData>, rate: u32) -> crate::audio::source::SourceProducer {
    let samples = vec![0.1f32; 4 * rate as usize * 2];
    let info = SourceInfo {
        path: "adapter-test.wav".into(),
        range: PlaybackRange::default(),
        original_sample_rate: rate,
        output_sample_rate: rate,
        channels: 2,
        total_samples: samples.len() as u64,
        duration_secs: 4.0,
        http_origin: None,
    };
    let (mut source, mut producer) = AudioSource::new_with_capacity(info, samples.len());
    assert_eq!(producer.write(&samples), samples.len());
    source.set_ready();
    source.set_playing();
    data.set_current_source_for_test(source);
    producer
}

fn wait_for(what: &str, condition: impl Fn() -> bool) {
    let deadline = Instant::now() + Duration::from_secs(10);
    while !condition() {
        assert!(Instant::now() < deadline, "timed out waiting for {what}");
        thread::sleep(Duration::from_millis(2));
    }
}

fn started(fake: &Arc<FakeDac>, data: &Arc<AudioCallbackData>, rate: u32) -> (AndroidDirectUsbBackend, crossbeam_channel::Receiver<AudioEvent>) {
    let (event_tx, event_rx) = crossbeam_channel::bounded(64);
    registered_pcm(fake, rate, 24);
    let backend = create_android_usb_backend(data.clone(), event_tx, rate)
        .unwrap()
        .expect("a backend");
    (backend, event_rx)
}

// ---- device lifecycle ---------------------------------------------------------------

#[test]
fn registering_opens_the_dac_with_flicks_policy_and_quirks() {
    let _serial = serial();
    let seen = Arc::new(Mutex::new(None));
    let fake = FakeDac::new();
    let capture = seen.clone();
    let fake_for_opener = fake.clone();
    let opener = move |fd: RawFd, info: DeviceInfo, config: DacConfig| {
        *capture.lock() = Some((fd, info, config));
        Ok(fake_for_opener.clone() as Arc<dyn DacPort>)
    };
    register_with(named_device(12230, 61546, "MOONDROP Dawn Pro"), &opener).unwrap();

    let (fd, info, config) = seen.lock().take().unwrap();
    assert_eq!(fd, 7);
    assert_eq!((info.vendor_id, info.product_id), (12230, 61546));
    assert_eq!(info.product_name, "MOONDROP Dawn Pro");
    assert_eq!(info.device_name.as_deref(), Some("/dev/bus/usb/001/002"));
    assert_eq!(info.claimed_interfaces, vec![0, 1, 2], "exactly what Kotlin claimed");
    assert_eq!(config.startup_volume, StartupVolume::Keep);
    assert!(!config.require_hardware_volume);
    assert_eq!(config.hand_back, HandBack::Keep);
    assert!(config.realtime_priority);
    // The quirks Flick has for this DAC ride in the configuration.
    let quirks: Vec<_> = config.quirks.iter().flat_map(|entry| entry.quirks.clone()).collect();
    assert!(quirks.contains(&usbdac_core::Quirk::DsdBigEndian));
    assert!(quirks.contains(&usbdac_core::Quirk::PreferPadded24BitTransport));

    let state = android_direct_debug_state();
    assert!(state.registered);
    assert_eq!(state.dac_clock_policy.as_deref(), Some("requireVerifiedRate"));
    assert!(!state.stream_active);
    assert!(state.idle_lock_held, "the DAC is open while it is registered");
    assert_eq!(state.hardware_volume_normalized, Some(0.25), "what the DAC is at");
}

#[test]
fn the_users_dsd_overrides_are_in_the_configuration_at_registration() {
    let _serial = serial();
    crate::audio::dsd_engine::output::set_dsd_big_endian_override(Some(true));
    crate::audio::dsd_engine::output::set_dsd_subslot_override(Some(2));
    let seen = Arc::new(Mutex::new(None));
    let fake = FakeDac::new();
    let capture = seen.clone();
    let opener = move |_: RawFd, _: DeviceInfo, config: DacConfig| {
        *capture.lock() = Some(config);
        Ok(fake.clone() as Arc<dyn DacPort>)
    };
    register_with(device(), &opener).unwrap();
    let config = seen.lock().take().unwrap();
    assert_eq!(config.dsd_big_endian, Some(true));
    assert_eq!(config.dsd_subslot, Some(2));
}

#[test]
fn a_dac_the_library_refuses_is_not_registered() {
    let _serial = serial();
    let refuse = |_: RawFd, _: DeviceInfo, _: DacConfig| -> Result<Arc<dyn DacPort>, Error> {
        Err(Error::Claim("interface 3 was not claimed".to_string()))
    };
    let error = register_with(device(), &refuse).unwrap_err();
    assert!(error.contains("claim") && error.contains("interface 3"), "{error}");
    assert!(!android_direct_debug_state().registered);
    assert!(STATE.lock().is_none());
}

#[test]
fn a_second_dac_is_refused_until_the_first_is_released() {
    let _serial = serial();
    let first = FakeDac::new();
    register(&first);
    let second = FakeDac::new();
    let error = register_with(device(), &opener(&second)).unwrap_err();
    assert!(error.contains("Release the previous"), "{error}");
    assert!(second.closed_with.lock().is_empty());
    // Released, the next one registers.
    clear_android_usb_device();
    assert!(wait_for_android_usb_session_stop(Duration::from_millis(100)));
    register_with(device(), &opener(&second)).unwrap();
}

#[test]
fn nothing_is_registered_over_an_active_session() {
    let _serial = serial();
    SESSION_ACTIVE.store(true, Ordering::SeqCst);
    let fake = FakeDac::new();
    let error = register_with(device(), &opener(&fake)).unwrap_err();
    assert!(error.contains("still active"), "{error}");
}

#[test]
fn the_original_volume_action_decides_how_the_dac_is_closed() {
    let _serial = serial();
    for (action, expected) in [
        (0, CloseVolume::Keep),
        (1, CloseVolume::Restore),
        (2, CloseVolume::Forget),
    ] {
        let fake = FakeDac::new();
        register(&fake);
        assert!(set_android_usb_original_volume_action(action));
        clear_android_usb_device();
        assert_eq!(fake.closed_with.lock().clone(), vec![expected], "action {action}");
        assert!(!android_direct_debug_state().registered);
        assert_eq!(
            ORIGINAL_VOLUME_ACTION.load(Ordering::SeqCst),
            ORIGINAL_VOLUME_KEEP,
            "the action applies to one clear"
        );
    }
    assert!(!set_android_usb_original_volume_action(3), "an unknown action is refused");
}

#[test]
fn a_clear_without_an_action_keeps_the_original_volume() {
    let _serial = serial();
    let fake = FakeDac::new();
    register(&fake);
    clear_android_usb_device();
    assert_eq!(fake.closed_with.lock().clone(), vec![CloseVolume::Keep]);
}

#[test]
fn a_clear_during_a_session_waits_for_it_to_end() {
    let _serial = serial();
    let fake = FakeDac::new();
    register(&fake);
    let session = {
        let _gate = SESSION_GATE.lock();
        SessionGuard::start_locked()
    };
    assert!(is_usb_session_active());
    set_android_usb_original_volume_action(1);

    clear_android_usb_device();
    assert!(
        fake.closed_with.lock().is_empty(),
        "the DAC stays open while the stream may still be using it"
    );
    assert!(STOP_REQUESTED.load(Ordering::SeqCst), "the render loop is told to stop");
    assert!(android_direct_debug_state().registered);
    assert!(!wait_for_android_usb_session_stop(Duration::from_millis(30)));

    drop(session);
    assert!(wait_for_android_usb_session_stop(Duration::from_millis(100)));
    assert_eq!(fake.closed_with.lock().clone(), vec![CloseVolume::Restore]);
    assert!(!android_direct_debug_state().registered);
}

#[test]
fn a_fallback_mark_survives_a_clear() {
    let _serial = serial();
    let fake = FakeDac::new();
    register(&fake);
    mark_android_usb_fallback(Some("Android took over".to_string()));
    let state = android_direct_debug_state();
    assert_eq!(state.engine_state.as_deref(), Some("fallback"));
    assert_eq!(state.engine_state_reason.as_deref(), Some("Android took over"));
    clear_android_usb_device();
    assert_eq!(android_direct_debug_state().engine_state.as_deref(), Some("fallback"));
}

#[test]
fn a_reconnect_only_runs_while_the_same_dac_is_owned_by_a_live_session() {
    let _serial = serial();
    let fake = FakeDac::new();
    register(&fake);
    let mut ran = false;
    // No session: not owned.
    assert!(with_live_usb_reconnect("/dev/bus/usb/001/002", || {
        ran = true;
        Ok(())
    })
    .is_err());
    assert!(!ran);

    SESSION_ACTIVE.store(true, Ordering::SeqCst);
    // Another DAC.
    assert!(with_live_usb_reconnect("/dev/bus/usb/001/009", || Ok(())).is_err());
    // The expected one.
    assert_eq!(with_live_usb_reconnect("/dev/bus/usb/001/002", || Ok(5)), Ok(5));
    // A clear is under way.
    STOP_REQUESTED.store(true, Ordering::SeqCst);
    assert!(with_live_usb_reconnect("/dev/bus/usb/001/002", || Ok(())).is_err());
}

#[test]
fn the_lock_request_is_recorded_and_needs_a_dac_to_be_enabled() {
    let _serial = serial();
    assert!(set_android_usb_lock_enabled(true).is_err());
    assert!(set_android_usb_lock_enabled(false).is_ok());
    let fake = FakeDac::new();
    register(&fake);
    set_android_usb_lock_enabled(false).unwrap();
    let state = android_direct_debug_state();
    assert!(!state.lock_requested);
    set_android_usb_lock_enabled(true).unwrap();
    assert!(android_direct_debug_state().lock_requested);
}

// ---- formats and negotiation -------------------------------------------------------

#[test]
fn a_rate_the_dac_cannot_run_is_negotiated_to_the_nearest_it_can() {
    let _serial = serial();
    let fake = FakeDac::new();
    registered_pcm(&fake, 44_100, 24);
    *fake.rate_override.lock() = Some(48_000);

    let rate = negotiate_android_direct_output_sample_rate(Some(44_100)).unwrap();
    assert_eq!(rate, Some(48_000), "the engine resamples to what the DAC runs");
    let request = fake.negotiated.lock().last().copied().unwrap();
    assert_eq!(request.sample_rate, 44_100);
    assert_eq!(request.bit_depth, 24);
    assert_eq!(request.rate_policy, RatePolicy::NearestSupported);

    let state = android_direct_debug_state();
    assert_eq!(state.requested_playback_sample_rate, Some(44_100));
    assert_eq!(state.playback_format_sample_rate, Some(48_000));
    assert!(state.resampling_active);
    assert!(!is_usb_session_active(), "the negotiation's session has ended");
    assert_eq!(
        android_direct_preferred_sample_rate(Some(96_000)),
        Some(48_000),
        "the prepared rate wins over a track's"
    );
}

#[test]
fn an_exact_rate_is_negotiated_as_it_was_asked() {
    let _serial = serial();
    let fake = FakeDac::new();
    registered_pcm(&fake, 44_100, 16);
    assert_eq!(negotiate_android_direct_output_sample_rate(Some(96_000)), Ok(Some(96_000)));
    let state = android_direct_debug_state();
    assert!(!state.resampling_active);
    assert_eq!(state.requested_playback_bit_depth, Some(16));
    assert_eq!(state.last_error, None);
}

#[test]
fn a_dac_that_would_need_implicit_feedback_falls_back_like_one_that_cannot_carry_the_format() {
    let _serial = serial();
    let fake = FakeDac::new();
    registered_pcm(&fake, 44_100, 24);
    *fake.negotiate_error.lock() = Some(Error::ImplicitFeedbackUnsupported);

    // The engine's first question: the rate. The refusal comes back as an error
    // the player answers by using Android's output.
    let error = negotiate_android_direct_output_sample_rate(Some(44_100)).unwrap_err();
    assert!(error.contains("implicit_feedback_unsupported"), "{error}");
    let state = android_direct_debug_state();
    assert_eq!(state.direct_mode_refusal_reason.as_deref(), Some(error.as_str()));
    assert_eq!(state.last_error.as_deref(), Some(error.as_str()));
    assert!(!is_usb_session_active());

    // And at stream creation: no backend, an error the engine falls back on, and
    // the engine state says why.
    let (event_tx, _event_rx) = crossbeam_channel::bounded(8);
    let error = create_android_usb_backend(callback_data(44_100), event_tx, 44_100)
        .err()
        .expect("a refusal");
    assert!(error.contains("implicit_feedback_unsupported"), "{error}");
    assert_eq!(fake.opened.load(Ordering::SeqCst), 0, "no stream was opened");
    let state = android_direct_debug_state();
    assert_eq!(state.engine_state.as_deref(), Some("error"));
    assert!(!state.stream_active);
    assert!(!is_usb_session_active(), "a refused start releases the session");

    // A DAC the library cannot carry the format on is refused the same way.
    *fake.negotiate_error.lock() = Some(Error::Unsupported {
        reason: "no alternate setting carries 24-bit 44.1 kHz".to_string(),
        offered: Vec::new(),
    });
    let (event_tx, _event_rx) = crossbeam_channel::bounded(8);
    assert!(create_android_usb_backend(callback_data(44_100), event_tx, 44_100).is_err());
}

#[test]
fn the_clock_the_dac_would_not_confirm_refuses_the_stream() {
    let _serial = serial();
    let fake = FakeDac::new();
    registered_pcm(&fake, 44_100, 24);
    *fake.open_error.lock() = Some(Error::ClockUnverified {
        requested_hz: 44_100,
        reported_hz: Some(48_000),
    });
    let (event_tx, _event_rx) = crossbeam_channel::bounded(8);
    let error = create_android_usb_backend(callback_data(44_100), event_tx, 44_100)
        .err()
        .expect("a refusal");
    assert!(error.contains("clock") && error.contains("48000"), "{error}");
    assert_eq!(android_direct_debug_state().engine_state.as_deref(), Some("error"));
}

#[test]
fn a_caller_supplied_rate_is_a_pcm_request_and_drops_the_dsd_flags() {
    let _serial = serial();
    let fake = FakeDac::new();
    register(&fake);
    set_android_usb_dsd_native_mode(2_822_400).unwrap();
    assert_eq!(negotiate_android_direct_output_sample_rate(Some(352_800)), Ok(Some(352_800)));
    let request = fake.negotiated.lock().last().copied().unwrap();
    assert_eq!(request.encoding, usbdac_core::Encoding::Pcm);
    assert_eq!(request.bit_depth, 24, "a one-bit DSD depth becomes 24-bit PCM");
}

#[test]
fn dop_and_native_dsd_become_the_requests_the_library_knows() {
    let dop = AndroidDirectUsbPlaybackFormat {
        sample_rate: 176_400,
        bit_depth: 24,
        channels: 2,
        is_dop: true,
        dsd_transport: DsdTransportMode::DoP,
        dsd_bit_rate: 0,
    };
    let request = stream_request(&dop, RatePolicy::Exact);
    assert_eq!(request.encoding, usbdac_core::Encoding::DoP);
    assert_eq!((request.sample_rate, request.bit_depth, request.channels), (176_400, 24, 2));

    let native = AndroidDirectUsbPlaybackFormat {
        sample_rate: 88_200,
        bit_depth: 1,
        channels: 2,
        is_dop: false,
        dsd_transport: DsdTransportMode::Native,
        dsd_bit_rate: 2_822_400,
    };
    let request = stream_request(&native, RatePolicy::Exact);
    assert_eq!(
        request.encoding,
        usbdac_core::Encoding::DsdNative { bit_rate: 2_822_400 }
    );
    assert_eq!(
        stream_request(&pcm_format(44_100, 16), RatePolicy::NearestSupported).rate_policy,
        RatePolicy::NearestSupported
    );
}

#[test]
fn a_negotiation_while_a_stream_plays_only_notes_the_next_format() {
    let _serial = serial();
    let fake = FakeDac::new();
    registered_pcm(&fake, 48_000, 24);
    SESSION_ACTIVE.store(true, Ordering::SeqCst);
    let rate = negotiate_android_direct_output_sample_rate(Some(96_000)).unwrap();
    assert_eq!(rate, Some(96_000));
    assert!(fake.negotiated.lock().is_empty(), "the DAC's clock is left alone");
    assert_eq!(android_direct_debug_state().requested_playback_sample_rate, Some(96_000));
}

#[test]
fn nothing_is_negotiated_without_a_dac_or_a_format_or_when_direct_output_is_off() {
    let _serial = serial();
    assert_eq!(negotiate_android_direct_output_sample_rate(Some(44_100)), Ok(Some(44_100)));
    let fake = FakeDac::new();
    register(&fake);
    // Registered, but no format prepared yet.
    assert_eq!(negotiate_android_direct_output_sample_rate(Some(44_100)), Ok(Some(44_100)));
    assert!(fake.negotiated.lock().is_empty());
    set_android_usb_playback_format(Some(pcm_format(44_100, 24))).unwrap();
    set_android_direct_usb_enabled(false);
    assert_eq!(negotiate_android_direct_output_sample_rate(Some(96_000)), Ok(Some(96_000)));
    assert!(fake.negotiated.lock().is_empty());
    assert_eq!(android_direct_output_signature(Some(44_100)), None);
    assert_eq!(validate_android_direct_request(Some(96_000)), Ok(()));
}

#[test]
fn a_clear_in_flight_refuses_a_negotiation() {
    let _serial = serial();
    let fake = FakeDac::new();
    registered_pcm(&fake, 44_100, 24);
    CLEAR_PENDING.store(true, Ordering::SeqCst);
    let error = negotiate_android_direct_output_sample_rate(Some(44_100)).unwrap_err();
    assert!(error.contains("being released"), "{error}");
    let (event_tx, _event_rx) = crossbeam_channel::bounded(8);
    assert!(create_android_usb_backend(callback_data(44_100), event_tx, 44_100)
        .unwrap()
        .is_none());
}

#[test]
fn the_output_signature_and_the_request_check_follow_the_prepared_format() {
    let _serial = serial();
    assert_eq!(android_direct_output_signature(Some(44_100)), None, "nothing registered");
    assert_eq!(validate_android_direct_request(Some(44_100)), Ok(()));

    let fake = FakeDac::new();
    registered_pcm(&fake, 44_100, 24);
    assert_eq!(
        android_direct_output_signature(Some(44_100)).as_deref(),
        Some("android-uac2:7:44100:24:2:/dev/bus/usb/001/002")
    );
    // Another rate is not the prepared one.
    assert_eq!(android_direct_output_signature(Some(96_000)), None);
    let error = validate_android_direct_request(Some(96_000)).unwrap_err();
    assert!(error.contains("44100") && error.contains("96000"), "{error}");
    assert_eq!(validate_android_direct_request(Some(44_100)), Ok(()));
}

#[test]
fn a_dac_whose_clock_readback_cannot_be_trusted_accepts_any_rate() {
    let _serial = serial();
    let fake = FakeDac::new();
    register_with(named_device(9770, 1, "Fosi Audio DS2"), &opener(&fake)).unwrap();
    set_android_usb_playback_format(Some(pcm_format(48_000, 24))).unwrap();
    assert_eq!(validate_android_direct_request(Some(96_000)), Ok(()));
    assert_eq!(
        android_direct_output_signature(Some(96_000)).as_deref(),
        Some("android-uac2:7:96000:24:2:/dev/bus/usb/001/002"),
        "the signature carries the rate the engine will start at"
    );
}

#[test]
fn the_dsd_modes_stamp_the_prepared_format() {
    let _serial = serial();
    let fake = FakeDac::new();
    registered_pcm(&fake, 44_100, 24);
    set_android_usb_dop_mode(true, 176_400, 24).unwrap();
    let state = android_direct_debug_state();
    assert_eq!(state.playback_format_sample_rate, Some(176_400));
    assert_eq!(state.playback_format_bit_depth, Some(24));

    set_android_usb_dsd_native_mode(2_822_400).unwrap();
    let state = android_direct_debug_state();
    assert_eq!(state.playback_format_sample_rate, Some(88_200), "the U32 wire rate until the DAC says");
    assert_eq!(state.playback_format_bit_depth, Some(1));

    set_android_usb_dop_mode(false, 96_000, 32).unwrap();
    let state = android_direct_debug_state();
    assert_eq!(state.playback_format_sample_rate, Some(96_000));
    assert_eq!(state.playback_format_bit_depth, Some(16), "a one-bit depth is not PCM: the default one");
    assert!(set_android_usb_dsd_native_mode(5_644_800).is_ok());

    clear_android_usb_device();
    assert!(set_android_usb_dop_mode(true, 176_400, 24).is_err());
    assert!(set_android_usb_dsd_native_mode(2_822_400).is_err());
    assert!(set_android_usb_playback_format(None).is_ok());
    assert!(set_android_usb_playback_format(Some(pcm_format(44_100, 16))).is_err());
}

#[test]
fn the_dsd_overrides_reach_the_library_before_it_negotiates() {
    let _serial = serial();
    let fake = FakeDac::new();
    registered_pcm(&fake, 44_100, 24);
    crate::audio::dsd_engine::output::set_dsd_big_endian_override(Some(false));
    crate::audio::dsd_engine::output::set_dsd_subslot_override(Some(4));
    negotiate_android_direct_output_sample_rate(Some(44_100)).unwrap();
    assert_eq!(fake.overrides.lock().last().copied(), Some((Some(false), Some(4))));
    crate::audio::dsd_engine::output::set_dsd_big_endian_override(None);
    crate::audio::dsd_engine::output::set_dsd_subslot_override(None);
    negotiate_android_direct_output_sample_rate(Some(44_100)).unwrap();
    assert_eq!(fake.overrides.lock().last().copied(), Some((None, None)), "back to the quirk");
}

// ---- the stream: creation, the render thread, stopping ---------------------------

#[test]
fn a_stream_is_opened_on_the_negotiated_format_without_programming_the_clock_again() {
    let _serial = serial();
    let fake = FakeDac::new();
    registered_pcm(&fake, 44_100, 24);
    negotiate_android_direct_output_sample_rate(Some(44_100)).unwrap();
    assert_eq!(fake.negotiated.lock().len(), 1);

    let data = callback_data(44_100);
    let (event_tx, _event_rx) = crossbeam_channel::bounded(8);
    let mut backend = create_android_usb_backend(data, event_tx, 44_100).unwrap().expect("a backend");
    assert_eq!(fake.negotiated.lock().len(), 1, "the negotiation is reused for the same format");
    assert_eq!(fake.opened.load(Ordering::SeqCst), 1);
    let state = android_direct_debug_state();
    assert!(state.stream_active && !state.idle_lock_held);
    assert_eq!(state.engine_state.as_deref(), Some("streaming"));
    assert!(is_usb_session_active());
    backend.stop().unwrap();
}

#[test]
fn a_stream_for_another_encoding_negotiates_again() {
    let _serial = serial();
    let fake = FakeDac::new();
    registered_pcm(&fake, 44_100, 24);
    negotiate_android_direct_output_sample_rate(Some(44_100)).unwrap();
    set_android_usb_dop_mode(true, 176_400, 24).unwrap();

    let data = callback_data(176_400);
    let (event_tx, _event_rx) = crossbeam_channel::bounded(8);
    let mut backend = create_android_usb_backend(data, event_tx, 176_400).unwrap().expect("a backend");
    let requests = fake.negotiated.lock().clone();
    assert_eq!(requests.len(), 2);
    assert_eq!(requests[1].encoding, usbdac_core::Encoding::DoP);
    assert_eq!(requests[1].rate_policy, RatePolicy::Exact, "the stream opens at exactly the prepared rate");
    backend.stop().unwrap();
}

#[test]
fn stopping_closes_the_stream_ends_the_session_and_leaves_the_dac_open() {
    let _serial = serial();
    let fake = FakeDac::new();
    let data = callback_data(48_000);
    let (mut backend, _events) = started(&fake, &data, 48_000);
    assert!(android_direct_debug_state().stream_active);

    backend.stop().unwrap();
    assert!(fake.stream.closed.load(Ordering::Relaxed));
    assert!(!is_usb_session_active());
    let state = android_direct_debug_state();
    assert!(!state.stream_active);
    assert!(state.registered, "only a clear releases the DAC");
    assert!(state.idle_lock_held);
    assert_eq!(state.engine_state.as_deref(), Some("idle"));
    assert!(fake.closed_with.lock().is_empty());
    // And the volume keeps working with no stream.
    assert_eq!(android_direct_set_hardware_volume(0.5), Ok(()));
}

#[test]
fn a_clear_during_playback_stops_the_render_thread_and_then_releases_the_dac() {
    let _serial = serial();
    let fake = FakeDac::new();
    let data = callback_data(48_000);
    let (mut backend, _events) = started(&fake, &data, 48_000);
    set_android_usb_original_volume_action(1);

    clear_android_usb_device();
    assert!(wait_for_android_usb_session_stop(Duration::from_secs(10)));
    assert!(fake.stream.closed.load(Ordering::Relaxed));
    assert_eq!(fake.closed_with.lock().clone(), vec![CloseVolume::Restore]);
    assert!(!android_direct_debug_state().registered);
    backend.stop().unwrap();
}

#[test]
fn the_render_thread_fills_the_ring_and_asks_for_no_more_than_it_wants() {
    let _serial = serial();
    let fake = FakeDac::new();
    let data = callback_data(48_000);
    let _music = with_music(&data, 48_000);
    let (mut backend, _events) = started(&fake, &data, 48_000);

    let deep = fake.stream.target_samples();
    wait_for("the deep fill", || fake.stream.queued.load(Ordering::Relaxed) >= deep);
    thread::sleep(Duration::from_millis(80));
    let chunk = 480 * 2;
    let steady = fake.stream.queued.load(Ordering::Relaxed);
    assert!((deep..deep + chunk).contains(&steady), "{steady} vs {deep}");
    // Nothing the library did not ask for.
    assert!(fake.stream.calls().iter().all(|call| matches!(call, Call::Write(n) if *n == chunk)
        || matches!(call, Call::Gain(_) | Call::HostProcessing(_))));
    backend.stop().unwrap();
}

#[test]
fn idle_silence_keeps_only_a_start_threshold_in_the_ring() {
    let _serial = serial();
    let fake = FakeDac::new();
    let data = callback_data(48_000);
    // No source: the engine renders idle silence.
    let (mut backend, _events) = started(&fake, &data, 48_000);
    let threshold = 48_000 / 10 * 2;
    wait_for("the start threshold", || fake.stream.queued.load(Ordering::Relaxed) >= threshold);
    thread::sleep(Duration::from_millis(80));
    let queued = fake.stream.queued.load(Ordering::Relaxed);
    assert!(queued < threshold + 480 * 2, "{queued}");
    backend.stop().unwrap();
}

#[test]
fn pause_and_resume_reach_the_stream_once_each() {
    let _serial = serial();
    let fake = FakeDac::new();
    let data = callback_data(48_000);
    let _music = with_music(&data, 48_000);
    let (mut backend, _events) = started(&fake, &data, 48_000);
    wait_for("the first audio", || fake.stream.queued.load(Ordering::Relaxed) > 0);

    data.set_paused(true);
    wait_for("the pause", || fake.stream.count(&Call::Pause) == 1);
    // While paused nothing is rendered or written.
    thread::sleep(Duration::from_millis(30));
    let writes = fake.stream.calls().iter().filter(|c| matches!(c, Call::Write(_))).count();
    thread::sleep(Duration::from_millis(60));
    assert_eq!(
        fake.stream.calls().iter().filter(|c| matches!(c, Call::Write(_))).count(),
        writes
    );
    assert_eq!(fake.stream.count(&Call::Pause), 1);

    data.set_paused(false);
    wait_for("the resume", || fake.stream.count(&Call::Play) == 1);
    thread::sleep(Duration::from_millis(30));
    assert_eq!((fake.stream.count(&Call::Pause), fake.stream.count(&Call::Play)), (1, 1));
    backend.stop().unwrap();
}

#[test]
fn an_engine_flush_flushes_the_stream_and_the_position_ignores_what_it_dropped() {
    let _serial = serial();
    let fake = FakeDac::new();
    let data = callback_data(48_000);
    let _music = with_music(&data, 48_000);
    let (mut backend, _events) = started(&fake, &data, 48_000);
    wait_for("the first audio", || fake.stream.queued.load(Ordering::Relaxed) > 0);
    fake.stream.latency.store(12_000, Ordering::Relaxed);
    assert_eq!(
        android_direct_output_latency(data.output_flush_generation()),
        Some((12_000, 48_000))
    );

    // A seek: the engine asks for a flush. Until the stream has done it, the
    // latency it reports is not ahead of the new position.
    let before = fake.stream.count(&Call::Flush);
    data.request_output_flush();
    wait_for("the flush", || fake.stream.count(&Call::Flush) > before);
    wait_for("the flush to be applied", || {
        android_direct_output_latency(data.output_flush_generation()) == Some((12_000, 48_000))
    });
    assert_eq!(
        android_direct_output_latency(data.output_flush_generation() + 1),
        Some((0, 48_000)),
        "a request the stream has not carried out yet"
    );
    backend.stop().unwrap();
}

#[test]
fn a_flush_while_paused_still_reaches_the_stream() {
    let _serial = serial();
    let fake = FakeDac::new();
    let data = callback_data(48_000);
    let _music = with_music(&data, 48_000);
    let (mut backend, _events) = started(&fake, &data, 48_000);
    data.set_paused(true);
    wait_for("the pause", || fake.stream.count(&Call::Pause) == 1);
    data.request_output_flush();
    wait_for("the flush", || fake.stream.count(&Call::Flush) == 1);
    assert_eq!(fake.stream.count(&Call::Play), 0, "still paused");
    backend.stop().unwrap();
}

#[test]
fn the_engines_gain_goes_to_the_library_and_not_into_the_samples() {
    let _serial = serial();
    let fake = FakeDac::new();
    let data = callback_data(48_000);
    let _music = with_music(&data, 48_000);
    let (mut backend, _events) = started(&fake, &data, 48_000);
    wait_for("the first audio", || fake.stream.queued.load(Ordering::Relaxed) > 0);
    assert!(
        fake.stream.calls().iter().all(|call| !matches!(call, Call::Gain(_))),
        "unity gain sets nothing"
    );

    data.set_volume(0.5);
    let gain = data.get_gain();
    assert!(gain < 1.0);
    wait_for("the gain", || fake.stream.gain() == gain);
    thread::sleep(Duration::from_millis(30));
    assert_eq!(fake.stream.count(&Call::Gain(gain)), 1, "set once, not on every cycle");
    // The library shortens the ring for gain by itself; the host says nothing.
    assert!(!fake.stream.host_processing.load(Ordering::Relaxed));

    data.set_volume(1.0);
    wait_for("unity", || fake.stream.gain() == 1.0);
    backend.stop().unwrap();
}

#[test]
fn the_dsp_chain_makes_the_ring_shallow_and_applies_no_second_gain() {
    let _serial = serial();
    let fake = FakeDac::new();
    let data = callback_data(48_000);
    let _music = with_music(&data, 48_000);
    let (mut backend, _events) = started(&fake, &data, 48_000);
    wait_for("the first audio", || fake.stream.queued.load(Ordering::Relaxed) > 0);

    // In passthrough the engine's gain is the library's to apply...
    data.set_volume(0.5);
    let gain = data.get_gain();
    wait_for("the gain", || fake.stream.gain() == gain);
    // ...in the DSP chain the engine has applied it already, so the library's goes
    // back to unity and the ring is kept shallow instead.
    data.set_pipeline_mode(PipelineMode::Dsp);
    wait_for("host processing", || fake.stream.host_processing.load(Ordering::Relaxed));
    wait_for("no gain of the library's", || fake.stream.gain() == 1.0);

    data.set_pipeline_mode(PipelineMode::Passthrough);
    wait_for("the end of host processing", || !fake.stream.host_processing.load(Ordering::Relaxed));
    backend.stop().unwrap();
}

#[test]
fn a_dsd_bitstream_is_never_scaled_or_called_processed() {
    let _serial = serial();
    let fake = FakeDac::new();
    register(&fake);
    set_android_usb_dop_mode(true, 176_400, 24).unwrap();
    let data = callback_data(176_400);
    data.set_pipeline_mode(PipelineMode::Dop);
    data.set_volume(0.5);
    let (event_tx, _event_rx) = crossbeam_channel::bounded(8);
    let mut backend = create_android_usb_backend(data.clone(), event_tx, 176_400)
        .unwrap()
        .expect("a backend");
    wait_for("some audio", || fake.stream.queued.load(Ordering::Relaxed) > 0);
    thread::sleep(Duration::from_millis(50));
    assert!(
        fake.stream.calls().iter().all(|c| matches!(c, Call::Write(_))),
        "{:?}",
        fake.stream.calls().iter().filter(|c| !matches!(c, Call::Write(_))).collect::<Vec<_>>()
    );
    backend.stop().unwrap();
}

#[test]
fn what_the_ring_does_not_take_is_kept_and_written_next() {
    let _serial = serial();
    let fake = FakeDac::new();
    let data = callback_data(48_000);
    let _music = with_music(&data, 48_000);
    // The ring takes a few samples at a time.
    fake.stream.write_limit.store(100, Ordering::Relaxed);
    let (mut backend, _events) = started(&fake, &data, 48_000);
    wait_for("several writes", || {
        fake.stream.calls().iter().filter(|c| matches!(c, Call::Write(_))).count() >= 20
    });
    // The first chunk (960 samples) goes out in pieces before the next is rendered.
    let writes: Vec<usize> = fake
        .stream
        .calls()
        .iter()
        .filter_map(|call| match call {
            Call::Write(n) => Some(*n),
            _ => None,
        })
        .collect();
    // 960 samples to a chunk: nine pieces of 100 and the 60 left.
    assert!(writes[..9].iter().all(|n| *n == 100), "{writes:?}");
    assert_eq!(writes[9], 60, "{writes:?}");
    backend.stop().unwrap();
}

#[test]
fn a_stream_that_refuses_writes_ends_the_session_and_tells_the_player_once() {
    let _serial = serial();
    let fake = FakeDac::new();
    let data = callback_data(48_000);
    let _music = with_music(&data, 48_000);
    let (mut backend, events) = started(&fake, &data, 48_000);
    wait_for("the first audio", || fake.stream.queued.load(Ordering::Relaxed) > 0);

    fake.stream.fail_writes.store(true, Ordering::Relaxed);
    // The output takes audio out, the ring wants more and the write is refused.
    fake.stream.consume(usize::MAX);
    wait_for("the session to end", || !is_usb_session_active());
    let errors: Vec<_> = events
        .try_iter()
        .filter_map(|event| match event {
            AudioEvent::Error { message } => Some(message),
            _ => None,
        })
        .collect();
    assert_eq!(errors.len(), 1, "{errors:?}");
    let state = android_direct_debug_state();
    assert_eq!(state.engine_state.as_deref(), Some("error"));
    assert!(!state.stream_active);
    assert!(state.last_error.is_some());
    backend.stop().unwrap();
}

// ---- the library's events --------------------------------------------------------------

#[test]
fn a_failed_stream_is_one_error_for_the_player_and_the_engine_state() {
    let _serial = serial();
    let fake = FakeDac::new();
    let data = callback_data(48_000);
    let (mut backend, events) = started(&fake, &data, 48_000);

    fake.emit(DacEvent::Error(Error::DeviceLost));
    fake.emit(DacEvent::DeviceLost);
    fake.emit(DacEvent::StateChanged(usbdac_core::StreamState::Failed));
    let message = match events.recv_timeout(Duration::from_secs(5)).unwrap() {
        AudioEvent::Error { message } => message,
        other => panic!("{other:?}"),
    };
    assert_eq!(message, "USB DAC disconnected");
    // A failed stream asks for nothing more (the ring is "full" for it), so the
    // render loop learns of the failure from the event and ends the session.
    fake.stream.queued.store(usize::MAX / 4, Ordering::Relaxed);
    wait_for("the session to end", || !is_usb_session_active());
    assert!(events.try_recv().is_err(), "the unplug is reported once");
    let state = android_direct_debug_state();
    assert_eq!(state.engine_state.as_deref(), Some("error"));
    assert_eq!(state.last_error.as_deref(), Some("USB DAC disconnected"));
    assert!(!state.stream_active);
    assert!(fake.stream.closed.load(Ordering::Relaxed));
    backend.stop().unwrap();
}

#[test]
fn lost_audio_and_underruns_are_not_errors() {
    let _serial = serial();
    let fake = FakeDac::new();
    let data = callback_data(48_000);
    let (mut backend, events) = started(&fake, &data, 48_000);
    fake.emit(DacEvent::LostFrames { frames: 96 });
    fake.emit(DacEvent::Underrun);
    fake.emit(DacEvent::FeedbackStale);
    fake.emit(DacEvent::BitPerfectChanged(false));
    assert!(events.try_recv().is_err());
    assert_eq!(android_direct_debug_state().engine_state.as_deref(), Some("streaming"));
    backend.stop().unwrap();
}

#[test]
fn a_volume_change_the_library_reports_updates_what_the_player_reads() {
    let _serial = serial();
    let fake = FakeDac::new();
    let data = callback_data(48_000);
    let (mut backend, _events) = started(&fake, &data, 48_000);
    fake.emit(DacEvent::VolumeChanged(VolumeState {
        db: -20.0,
        fraction: 0.75,
        muted: Some(true),
    }));
    assert_eq!(android_direct_cached_hardware_volume(), Some(0.75));
    assert_eq!(android_direct_cached_hardware_mute(), Some(true));
    backend.stop().unwrap();
}

// ---- volume ----------------------------------------------------------------------------------

#[test]
fn the_volume_is_set_through_the_library_and_read_back() {
    let _serial = serial();
    let fake = FakeDac::new();
    register(&fake);
    assert!(android_direct_has_hardware_volume_control());
    assert_eq!(android_direct_cached_hardware_volume(), Some(0.25));
    assert_eq!(android_direct_cached_hardware_mute(), Some(false));

    assert_eq!(android_direct_set_hardware_volume(0.6), Ok(()));
    assert_eq!(fake.volume.lock().writes, vec![0.6]);
    assert_eq!(android_direct_cached_hardware_volume(), Some(0.6), "what the DAC read back");

    // The DAC lands on a nearby step: the cache holds what it says.
    fake.volume.lock().readback_offset = 0.01;
    android_direct_set_hardware_volume(0.5).unwrap();
    assert_eq!(android_direct_cached_hardware_volume(), Some(0.51));

    assert!(android_direct_set_hardware_volume(f64::NAN).is_err());
    assert_eq!(fake.volume.lock().writes.len(), 2, "nothing was written for a bad value");
}

#[test]
fn a_volume_the_dac_refuses_is_an_error_with_a_reason() {
    let _serial = serial();
    let fake = FakeDac::new();
    register(&fake);
    fake.volume.lock().fail = true;
    let error = android_direct_set_hardware_volume(0.4).unwrap_err();
    assert!(error.contains("does not answer"), "{error}");
    assert_eq!(
        android_direct_debug_state().hardware_volume_error.as_deref(),
        Some(error.as_str())
    );
    // Reachable again: the error clears with the next good answer.
    fake.volume.lock().fail = false;
    assert_eq!(android_direct_verify_hardware_volume_health(), Ok(true));
    assert_eq!(android_direct_debug_state().hardware_volume_error, None);
}

#[test]
fn the_health_check_reports_what_the_dac_is_at_now() {
    let _serial = serial();
    let fake = FakeDac::new();
    register(&fake);
    // A knob on the DAC.
    fake.volume.lock().fraction = 0.9;
    fake.volume.lock().muted = Some(true);
    assert_eq!(android_direct_verify_hardware_volume_health(), Ok(true));
    assert_eq!(android_direct_cached_hardware_volume(), Some(0.9));
    assert_eq!(android_direct_cached_hardware_mute(), Some(true));
}

#[test]
fn mute_is_written_and_checked_against_the_read_back() {
    let _serial = serial();
    let fake = FakeDac::new();
    register(&fake);
    assert_eq!(android_direct_set_hardware_mute(true), Ok(()));
    assert_eq!(fake.volume.lock().mute_writes, vec![true]);
    assert_eq!(android_direct_cached_hardware_mute(), Some(true));
    assert_eq!(android_direct_set_hardware_mute(false), Ok(()));

    // A DAC without a writable mute.
    fake.volume.lock().muted = None;
    assert!(android_direct_set_hardware_mute(true).is_err());
}

#[test]
fn a_remembered_level_is_restored_before_any_stream() {
    let _serial = serial();
    let fake = FakeDac::new();
    register(&fake);
    assert_eq!(android_direct_queue_hardware_volume_restore(0.35), Ok(()));
    assert_eq!(fake.volume.lock().writes, vec![0.35], "written now, not at the first stream");
    assert_eq!(fake.opened.load(Ordering::SeqCst), 0);
    assert_eq!(android_direct_cached_hardware_volume(), Some(0.35));

    for bad in [-0.1, 1.1, f64::NAN, f64::INFINITY] {
        assert!(android_direct_queue_hardware_volume_restore(bad).is_err(), "{bad}");
    }
    assert_eq!(fake.volume.lock().writes.len(), 1);

    // A refusal by the DAC stops the activation: Kotlin deactivates on false.
    fake.volume.lock().fail = true;
    assert!(android_direct_queue_hardware_volume_restore(0.2).is_err());
}

#[test]
fn a_dac_without_a_hardware_volume_has_nothing_to_restore_or_set() {
    let _serial = serial();
    let fake = FakeDac::new();
    fake.has_volume.store(false, Ordering::Relaxed);
    register(&fake);
    assert!(!android_direct_has_hardware_volume_control());
    assert_eq!(android_direct_cached_hardware_volume(), None);
    assert_eq!(android_direct_queue_hardware_volume_restore(0.5), Ok(()));
    assert!(android_direct_set_hardware_volume(0.5).is_err());
    assert!(android_direct_set_hardware_mute(true).is_err());
    assert!(android_direct_verify_hardware_volume_health().is_err());
}

#[test]
fn volume_calls_without_a_dac_fail_cleanly() {
    let _serial = serial();
    assert!(!android_direct_has_hardware_volume_control());
    assert_eq!(android_direct_cached_hardware_volume(), None);
    assert_eq!(android_direct_cached_hardware_mute(), None);
    assert!(android_direct_set_hardware_volume(0.5).is_err());
    assert!(android_direct_queue_hardware_volume_restore(0.5).is_err());
    assert!(android_direct_set_hardware_mute(true).is_err());
    assert!(android_direct_verify_hardware_volume_health().is_err());
}

#[test]
fn a_new_registration_is_a_new_volume_route() {
    let _serial = serial();
    let first = FakeDac::new();
    register(&first);
    let generation = android_direct_debug_state().hardware_volume_generation;
    clear_android_usb_device();
    let second = FakeDac::new();
    register(&second);
    assert!(android_direct_debug_state().hardware_volume_generation > generation);
}

// ---- diagnostics -------------------------------------------------------------------------------

fn alt(format: &'static str, bits: u8, subslot: u8, rates: &[u32]) -> AltFacts {
    AltFacts {
        interface_number: 1,
        alt_setting: 2,
        endpoint_address: 0x01,
        endpoint_interval: 4,
        service_interval_us: 125,
        max_packet_bytes: 1024,
        format_tag: format,
        channels: 2,
        subslot_size: subslot,
        bit_resolution: bits,
        sample_rates: rates.to_vec(),
        sync_type: "asynchronous",
        usage_type: "data",
        refresh: 0,
        synch_address: 0,
        feedback_endpoint: Some(0x81),
    }
}

fn facts() -> DacFacts {
    DacFacts {
        alts: vec![
            alt("PCM", 24, 3, &[44_100, 48_000, 96_000]),
            AltFacts {
                alt_setting: 3,
                ..alt("DSD", 1, 4, &[88_200])
            },
        ],
        has_mute: true,
        volume_raw: Some((-20_480, 0, 256)),
    }
}

#[test]
fn the_diagnostics_describe_the_dac_the_stream_and_the_library_stats() {
    let _serial = serial();
    let fake = FakeDac::new();
    *fake.facts.lock() = facts();
    *fake.stream.snapshot.lock() = StreamSnapshot {
        underruns: 3,
        lost_frames: 0,
        frames_written: 96_000,
        frames_completed: 90_000,
        played_frames: 88_000,
        latency_frames: 12_000,
        latency_ms: 250.0,
        queued_frames: 9_600,
        buffer_ms: 200,
        buffer_capacity_ms: 500,
        active_target_ms: 250,
        transfer_queue_depth: 8,
        completed_transfers: 400,
        last_turnaround_us: 2_100,
        max_turnaround_us: 3_000,
        last_submit_gap_us: 90,
        max_submit_gap_us: 400,
        effective_rate_hz: 48_000.5,
        software_gain: 1.0,
        bit_perfect: true,
        stable: true,
        clock_verified: true,
        payload_verified: true,
        payload_constant_for_ms: None,
    };
    let data = callback_data(48_000);
    let (mut backend, _events) = started(&fake, &data, 48_000);
    let state = android_direct_debug_state();

    assert!(state.registered && state.stream_active);
    assert_eq!(state.product_name.as_deref(), Some("Test DAC"));
    assert_eq!(state.playback_format_sample_rate, Some(48_000));
    assert_eq!(state.active_interface_number, Some(1));
    assert_eq!(state.active_alt_setting, Some(2));
    assert_eq!(state.active_endpoint_address, Some(1));
    assert_eq!(state.active_endpoint_interval, Some(4));
    assert_eq!(state.active_max_packet_bytes, Some(1024));
    assert_eq!(state.transport_format.as_deref(), Some("PCM"));
    assert_eq!(state.transport_subslot_size, Some(3));
    assert_eq!(state.transport_bit_resolution, Some(24));
    assert_eq!(state.active_sync_type.as_deref(), Some("asynchronous"));
    assert_eq!(state.active_usage_type.as_deref(), Some("data"));
    assert!(state.feedback_endpoint_present);
    assert_eq!(state.feedback_endpoint_address, Some(0x81));
    assert_eq!(state.advertised_sample_rates, vec![44_100, 48_000, 96_000]);
    assert_eq!(state.supported_sample_rates, vec![44_100, 48_000, 88_200, 96_000]);
    assert_eq!(state.supported_bit_depths, vec![1, 24]);
    assert_eq!(state.supported_channels, vec![2]);
    assert_eq!(state.available_alt_settings.len(), 2);
    assert_eq!(state.available_alt_settings[1].format_tag, "DSD");

    assert!(state.bit_perfect_verified);
    assert!(state.usb_stream_stable);
    assert!(state.clock_verification_passed);
    assert_eq!(state.clock_reported_sample_rate, Some(48_000));
    assert!(state.pcm_payload_verified && !state.dsd_payload_verified);
    assert!(!state.resampling_active);
    assert_eq!(state.dac_mode.as_deref(), Some("fullControl"));
    assert!(!state.software_volume_active);

    assert_eq!(state.buffer_fill_ms, Some(200));
    assert_eq!(state.buffer_target_ms, Some(250));
    assert_eq!(state.buffer_capacity_ms, Some(500));
    assert_eq!(state.drift_ms_from_target, Some(-50), "9600 frames at 48 kHz is 200 ms");
    assert_eq!(state.frames_per_packet, Some(6), "48 kHz at a 125 us interval, nominal");
    assert_eq!(state.underrun_count, Some(3));
    assert_eq!(state.producer_frames, Some(96_000));
    assert_eq!(state.consumer_frames, Some(90_000));
    assert_eq!(state.played_music_frames, Some(88_000));
    assert_eq!(state.output_latency_frames, Some(12_000));
    assert_eq!(state.transfer_queue_depth, Some(8));
    assert_eq!(state.effective_consumer_rate_milli_hz, Some(48_000_500));

    assert!(state.hardware_volume_supported && state.hardware_mute_supported);
    assert_eq!(state.hardware_volume_min_raw, Some(-20_480));
    assert_eq!(state.hardware_volume_max_raw, Some(0));
    assert_eq!(state.hardware_volume_resolution_raw, Some(256));
    backend.stop().unwrap();
}

#[test]
fn a_stream_that_runs_another_rate_than_asked_is_not_reported_bit_perfect() {
    let _serial = serial();
    let fake = FakeDac::new();
    *fake.facts.lock() = facts();
    fake.stream.snapshot.lock().bit_perfect = true;
    registered_pcm(&fake, 44_100, 24);
    *fake.rate_override.lock() = Some(48_000);
    negotiate_android_direct_output_sample_rate(Some(44_100)).unwrap();
    let data = callback_data(48_000);
    let (event_tx, _event_rx) = crossbeam_channel::bounded(8);
    let mut backend = create_android_usb_backend(data, event_tx, 48_000).unwrap().expect("a backend");
    let state = android_direct_debug_state();
    assert!(state.resampling_active);
    assert!(!state.bit_perfect_verified, "the engine resamples 44.1 kHz to 48 kHz");
    assert_eq!(state.dac_mode.as_deref(), Some("adaptiveStreaming"));
    backend.stop().unwrap();
}

#[test]
fn diagnostics_without_a_dac_say_only_what_state_the_engine_is_in() {
    let _serial = serial();
    let state = android_direct_debug_state();
    assert!(!state.registered && !state.idle_lock_held && !state.stream_active);
    assert_eq!(state.engine_state.as_deref(), Some("idle"));
    assert!(state.available_alt_settings.is_empty());
}

#[test]
fn the_diagnostics_keep_every_field_the_player_and_the_debug_export_read() {
    let _serial = serial();
    let fake = FakeDac::new();
    *fake.facts.lock() = facts();
    register(&fake);
    let json = serde_json::to_value(android_direct_debug_state()).unwrap();
    let object = json.as_object().unwrap();
    for key in [
        "registered", "engine_state", "engine_state_reason", "requested_playback_sample_rate",
        "requested_playback_bit_depth", "requested_playback_channels", "device_name", "product_name",
        "playback_format_sample_rate", "playback_format_bit_depth", "playback_format_channels",
        "lock_requested", "stream_active", "idle_lock_held", "active_interface_number",
        "active_alt_setting", "active_endpoint_address", "active_endpoint_interval",
        "active_service_interval_us", "active_max_packet_bytes", "transport_format",
        "transport_channels", "transport_subslot_size", "transport_bit_resolution",
        "advertised_sample_rates", "active_sync_type", "active_usage_type", "active_refresh",
        "active_synch_address", "clock_interface_number", "clock_id", "clock_requested_sample_rate",
        "clock_reported_sample_rate", "clock_control_attempted", "clock_control_succeeded",
        "clock_verification_passed", "require_verified_rate", "dac_clock_policy", "dac_mode",
        "resampling_active", "last_error", "direct_mode_refusal_reason", "usb_stream_stable",
        "bit_perfect_verified", "pcm_payload_verified", "dsd_payload_verified",
        "payload_constant_for_ms", "software_volume_active", "hardware_volume_supported",
        "hardware_mute_supported", "hardware_volume_normalized", "hardware_mute_active",
        "hardware_volume_min_raw", "hardware_volume_max_raw", "hardware_volume_resolution_raw",
        "hardware_volume_error", "hardware_volume_generation", "feedback_endpoint_present",
        "feedback_endpoint_address", "feedback_transfer_type", "packet_schedule_frames_preview",
        "supported_sample_rates", "supported_bit_depths", "supported_channels",
        "available_alt_settings", "buffer_fill_ms", "buffer_capacity_ms", "buffer_target_ms",
        "frames_per_packet", "underrun_count", "producer_frames", "consumer_frames", "lost_frames",
        "played_music_frames", "output_latency_frames", "output_latency_ms", "drift_ms_from_target",
        "transfer_queue_depth", "completed_transfers", "last_transfer_turnaround_us",
        "max_transfer_turnaround_us", "last_submit_gap_us", "max_submit_gap_us",
        "effective_consumer_rate_milli_hz",
    ] {
        assert!(object.contains_key(key), "the diagnostics lost {key}");
    }
    assert_eq!(object.len(), 84, "no field was added or dropped");
    let alt = &json["available_alt_settings"][0];
    for key in [
        "interface_number", "alt_setting", "endpoint_address", "endpoint_interval",
        "service_interval_us", "max_packet_bytes", "format_tag", "channels", "subslot_size",
        "bit_resolution", "sample_rates", "sync_type", "usage_type", "refresh", "synch_address",
    ] {
        assert!(alt.get(key).is_some(), "an alternate setting lost {key}");
    }
}

#[test]
fn the_clock_policy_comes_from_flicks_quirk_table() {
    let _serial = serial();
    let fake = FakeDac::new();
    register_with(named_device(9770, 1, "Fosi Audio DS2"), &opener(&fake)).unwrap();
    assert_eq!(
        android_direct_debug_state().dac_clock_policy.as_deref(),
        Some(DacClockPolicy::AllowUnverified.label())
    );
}
