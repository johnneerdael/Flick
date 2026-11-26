//! How deep the render thread keeps the PCM ring ahead of the USB output.
//!
//! A deep ring absorbs an upstream stall (slow storage, a network source,
//! scheduling) that a shallow one would let through as a dropout. It also delays
//! everything the render thread applies to the audio *as it renders it*: EQ, DSP,
//! crossfade, pitch and software gain are heard only after the audio already
//! queued, which is the ring's fill. A change to the audio that is already in the
//! ring is impossible, so the depth is a trade.
//!
//! Bit-perfect passthrough has no live processing to delay, so it keeps the deep
//! ring. While anything live is applied the ring is kept shallow, so a change is
//! heard after the previous 100 ms instead of a quarter second.
//!
//! The choice is made on every render cycle, because the mode can change during
//! playback. Nothing is flushed when it does: a ring that is now deeper than its
//! target is simply not refilled until the output has drained it below the
//! target, and one that is shallower than its target is filled on. Either way
//! the audio is continuous. This module is the decision, kept apart from libusb
//! and the engine so it can be tested on the host; the ring and its fill are the
//! caller's. Sizes here are ring samples (frames times channels).

/// The two depths the render thread keeps the ring at.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct RingTargets {
    /// Bit-perfect passthrough: nothing is applied live.
    deep_samples: usize,
    /// Something is applied live; never above the deep target.
    live_samples: usize,
}

impl RingTargets {
    pub(crate) fn new(deep_samples: usize, live_samples: usize) -> Self {
        Self {
            deep_samples,
            live_samples: live_samples.min(deep_samples),
        }
    }

    /// The fill the render thread keeps the ring at right now.
    pub(crate) fn select(self, live_processing: bool) -> usize {
        if live_processing {
            self.live_samples
        } else {
            self.deep_samples
        }
    }
}

/// Whether anything the listener can change live is applied to the audio as it
/// is rendered.
///
/// Raw DSD and DoP are never processed (the render thread skips the software
/// gain for them and the engine ignores every DSP stage), so a bitstream is
/// never live whatever the engine's pipeline mode says; DoP in particular is a
/// pipeline mode of its own, not passthrough. For PCM it is live when the engine
/// is not in pure passthrough (EQ, DSP, crossfade, pitch, 432 Hz tuning or
/// ReplayGain forces its DSP chain), or when the software gain is anything but
/// exactly 1.0, which the render thread then multiplies in.
pub(crate) fn live_processing(
    raw_bitstream: bool,
    engine_passthrough: bool,
    software_gain: f32,
) -> bool {
    !raw_bitstream && (!engine_passthrough || software_gain != 1.0)
}

/// Whether the render thread renders another chunk into a ring holding
/// `buffered_samples` while it is kept at `target_samples`.
pub(crate) fn needs_refill(buffered_samples: usize, target_samples: usize) -> bool {
    buffered_samples < target_samples
}
