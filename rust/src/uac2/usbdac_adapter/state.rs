//! The adapter's process-wide state: the registered DAC, its lifecycle label and
//! the session that serialises negotiation and output against clearing the
//! device.
//!
//! Lock discipline: `STATE` is never held across a call into the library. The
//! library's `Dac::close` joins its event thread, and that thread delivers into
//! listeners that take `STATE`, so a call made while holding it could deadlock.
//! Clone what a call needs out of the lock, release it, then call.

use super::config::DacClockPolicy;
use super::ports::{DacPort, FormatFacts, StreamPort};
use super::{AndroidDirectUsbDevice, AndroidDirectUsbPlaybackFormat};
use crate::dev_eprintln;
use once_cell::sync::Lazy;
use parking_lot::Mutex;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::Arc;
use usbdac_core::{CloseVolume, StreamRequest};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum EngineState {
    Idle,
    UsbInit,
    UsbReady,
    Streaming,
    Error,
    Fallback,
}

impl EngineState {
    pub(crate) fn label(self) -> &'static str {
        match self {
            Self::Idle => "idle",
            Self::UsbInit => "usbInit",
            Self::UsbReady => "usbReady",
            Self::Streaming => "streaming",
            Self::Error => "error",
            Self::Fallback => "fallback",
        }
    }
}

#[derive(Debug, Clone)]
pub(crate) struct Lifecycle {
    pub state: EngineState,
    pub reason: Option<String>,
}

pub(crate) static LIFECYCLE: Lazy<Mutex<Lifecycle>> = Lazy::new(|| {
    Mutex::new(Lifecycle {
        state: EngineState::Idle,
        reason: None,
    })
});

pub(crate) fn set_engine_state(state: EngineState, reason: Option<String>) {
    let mut lifecycle = LIFECYCLE.lock();
    let previous = lifecycle.state;
    lifecycle.state = state;
    lifecycle.reason = reason;
    if previous != state {
        dev_eprintln!(
            "Android USB direct engine state: {:?} -> {:?}",
            previous, state
        );
    }
    dev_eprintln!(
        "[USB][STATE] {}{}",
        state.label(),
        lifecycle
            .reason
            .as_ref()
            .map(|reason| format!(" ({})", reason))
            .unwrap_or_default()
    );
}

/// The DAC's volume as the player last saw it. The DAC owns the level; this is
/// what was last read from or written to it.
#[derive(Debug, Clone, Default)]
pub(crate) struct VolumeCache {
    pub normalized: Option<f64>,
    pub muted: Option<bool>,
    pub error: Option<String>,
}

/// The stream that is playing.
pub(crate) struct ActiveStream {
    pub stream: Arc<dyn StreamPort>,
    pub format: FormatFacts,
    /// The engine's latest output flush request the render thread has carried
    /// out. While it lags the request, the ring still holds audio that is about
    /// to be dropped.
    applied_flush_generation: AtomicU64,
    /// The stream failed: the library said so, or the writer could not write.
    pub failed: Arc<AtomicBool>,
}

impl ActiveStream {
    pub(crate) fn new(
        stream: Arc<dyn StreamPort>,
        format: FormatFacts,
        flush_generation: u64,
        failed: Arc<AtomicBool>,
    ) -> Self {
        Self {
            stream,
            format,
            applied_flush_generation: AtomicU64::new(flush_generation),
            failed,
        }
    }

    pub(crate) fn applied_flush_generation(&self) -> u64 {
        self.applied_flush_generation.load(Ordering::Acquire)
    }

    pub(crate) fn set_applied_flush_generation(&self, generation: u64) {
        self.applied_flush_generation.store(generation, Ordering::Release);
    }
}

/// The last negotiation, kept so the stream that follows it does not program
/// the DAC's clock a second time for the same format.
#[derive(Clone)]
pub(crate) struct Negotiated {
    pub request: StreamRequest,
    pub format: FormatFacts,
}

/// The registered DAC.
pub(crate) struct Registration {
    pub device: AndroidDirectUsbDevice,
    pub dac: Arc<dyn DacPort>,
    pub requested_playback_format: Option<AndroidDirectUsbPlaybackFormat>,
    pub playback_format: Option<AndroidDirectUsbPlaybackFormat>,
    pub negotiated: Option<Negotiated>,
    pub lock_requested: bool,
    pub last_error: Option<String>,
    pub direct_mode_refusal_reason: Option<String>,
    pub dac_clock_policy: DacClockPolicy,
    pub volume: VolumeCache,
    pub hardware_volume_generation: u64,
    pub stream: Option<Arc<ActiveStream>>,
}

pub(crate) static STATE: Lazy<Mutex<Option<Registration>>> = Lazy::new(|| Mutex::new(None));

/// Runs `change` on the registration, if there is one.
pub(crate) fn with_registration<T>(change: impl FnOnce(&mut Registration) -> T) -> Option<T> {
    STATE.lock().as_mut().map(change)
}

pub(crate) fn set_last_error(error: Option<String>) {
    with_registration(|registration| registration.last_error = error);
}

pub(crate) fn set_direct_mode_refusal_reason(reason: Option<String>) {
    with_registration(|registration| registration.direct_mode_refusal_reason = reason);
}

/// Records why direct output was refused: the diagnostics show it, and the
/// engine state goes to `Error`.
pub(crate) fn record_refusal(message: &str) {
    set_last_error(Some(message.to_string()));
    set_direct_mode_refusal_reason(Some(message.to_string()));
    set_engine_state(EngineState::Error, Some(message.to_string()));
}

pub(crate) static ENABLED: AtomicBool = AtomicBool::new(true);
pub(crate) static HARDWARE_VOLUME_GENERATION: AtomicU64 = AtomicU64::new(0);

pub(crate) const ORIGINAL_VOLUME_KEEP: u32 = 0;
pub(crate) const ORIGINAL_VOLUME_RESTORE: u32 = 1;
pub(crate) const ORIGINAL_VOLUME_FORGET: u32 = 2;
/// What the next device clear does with the level the DAC had before Flick
/// first changed it; set by Java just before it.
pub(crate) static ORIGINAL_VOLUME_ACTION: AtomicU32 = AtomicU32::new(ORIGINAL_VOLUME_KEEP);

pub(crate) fn close_volume(action: u32) -> CloseVolume {
    match action {
        ORIGINAL_VOLUME_RESTORE => CloseVolume::Restore,
        ORIGINAL_VOLUME_FORGET => CloseVolume::Forget,
        _ => CloseVolume::Keep,
    }
}

/// Only one negotiation or output session can use the claimed interfaces.
/// Ownership transitions are serialised with device clear before Android
/// releases the interfaces.
pub(crate) static SESSION_ACTIVE: AtomicBool = AtomicBool::new(false);
pub(crate) static SESSION_GATE: Mutex<()> = Mutex::new(());
pub(crate) static CLEAR_PENDING: AtomicBool = AtomicBool::new(false);
/// Set by a clear that arrives while a session is active, so the render loop
/// stops before Android releases the interfaces. Reset at session start.
pub(crate) static STOP_REQUESTED: AtomicBool = AtomicBool::new(false);

pub(crate) struct SessionGuard;

impl SessionGuard {
    /// The caller holds `SESSION_GATE` and has checked that no session is
    /// active.
    pub(crate) fn start_locked() -> Self {
        STOP_REQUESTED.store(false, Ordering::SeqCst);
        SESSION_ACTIVE.store(true, Ordering::SeqCst);
        Self
    }
}

impl Drop for SessionGuard {
    fn drop(&mut self) {
        let _gate = SESSION_GATE.lock();
        SESSION_ACTIVE.store(false, Ordering::SeqCst);
        complete_pending_clear_locked();
    }
}

/// The render loop stops when either its backend's flag or the global
/// clear-requested flag is set.
pub(crate) fn stop_requested(stop: &AtomicBool) -> bool {
    stop.load(Ordering::Acquire) || STOP_REQUESTED.load(Ordering::Acquire)
}

pub(crate) fn is_session_active() -> bool {
    SESSION_ACTIVE.load(Ordering::SeqCst)
}

/// Drops the registration and closes the DAC, handing the volume back as Java
/// asked. The caller holds `SESSION_GATE`, so the wait API cannot report
/// success while a clear is still closing the DAC.
pub(crate) fn complete_pending_clear_locked() {
    if SESSION_ACTIVE.load(Ordering::SeqCst) {
        return;
    }
    if !CLEAR_PENDING.swap(false, Ordering::SeqCst) {
        return;
    }
    dev_eprintln!("Android USB direct: clearing registration after USB I/O stopped");
    clear_registration();
}

fn clear_registration() {
    let registration = STATE.lock().take();
    let action = ORIGINAL_VOLUME_ACTION.swap(ORIGINAL_VOLUME_KEEP, Ordering::SeqCst);
    if LIFECYCLE.lock().state != EngineState::Fallback {
        set_engine_state(EngineState::Idle, None);
    }
    let Some(registration) = registration else {
        return;
    };
    // Normally the stream is long gone; this is for a clear that raced one.
    if let Some(active) = registration.stream {
        active.stream.close();
    }
    // Waits for control calls in flight, then restores, keeps or forgets the
    // DAC's original level, and releases the device.
    if let Err(error) = registration.dac.close(close_volume(action)) {
        log::warn!("[USB] closing the DAC: {error}");
    }
}
