//! Where the audio is audible: the decoder position corrected by what sits
//! between the decoder and the DAC.

use super::state::STATE;

/// The audible position: the decoder position minus the source time that still
/// sits between the decoder and the DAC, never below zero.
///
/// The output latency is in output time. A playback speed other than 1.0 makes
/// the decoder cover `speed` seconds of source per second of output, so the
/// source time inside the buffer is `latency * speed`. A non-finite or
/// non-positive input yields the decoder position unchanged rather than a
/// wrong one.
pub(crate) fn audible_position_secs(
    decoder_position_secs: f64,
    latency_frames: u64,
    ring_rate_hz: u32,
    speed: f32,
) -> f64 {
    if !decoder_position_secs.is_finite() || ring_rate_hz == 0 {
        return decoder_position_secs;
    }
    let speed = if speed.is_finite() && speed > 0.0 {
        f64::from(speed)
    } else {
        1.0
    };
    let latency_secs = latency_frames as f64 / f64::from(ring_rate_hz) * speed;
    (decoder_position_secs - latency_secs).max(0.0)
}

/// Music frames between the decoder and the DAC, and the rate they are counted
/// at, while a direct-USB stream is running.
///
/// `requested_flush_generation` is the engine's latest output flush request. A
/// seek publishes the new decoder position before the render thread has dropped
/// what is still queued; until it has, nothing in the ring or on the bus is
/// ahead of that position, so the lead is zero rather than a quarter of a
/// second that is about to vanish.
pub(crate) fn android_direct_output_latency(requested_flush_generation: u64) -> Option<(u64, u32)> {
    let active = STATE.lock().as_ref()?.stream.clone()?;
    let frames = if active.applied_flush_generation() != requested_flush_generation {
        0
    } else {
        active.stream.latency_frames()
    };
    Some((frames, active.format.counter_rate_hz))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn secs(frames: u64, rate: u32) -> f64 {
        frames as f64 / f64::from(rate)
    }

    #[test]
    fn the_audible_position_trails_the_decoder_by_the_latency() {
        // 250 ms in the ring and 16 ms on the bus at 44.1 kHz.
        let latency = 11_025 + 705;
        let audible = audible_position_secs(10.0, latency, 44_100, 1.0);
        assert!((audible - (10.0 - secs(latency, 44_100))).abs() < 1e-9);
        assert!(audible < 10.0 && audible > 9.7);
    }

    #[test]
    fn the_audible_position_is_never_negative() {
        // A seek to the start, or the first moments of a track.
        assert_eq!(audible_position_secs(0.05, 11_025, 44_100, 1.0), 0.0);
        assert_eq!(audible_position_secs(0.0, 11_025, 44_100, 1.0), 0.0);
    }

    #[test]
    fn a_playback_speed_scales_the_source_time_inside_the_buffer() {
        // 0.25 s of output at 2x speed holds 0.5 s of source.
        let latency = 11_025;
        let audible = audible_position_secs(10.0, latency, 44_100, 2.0);
        assert!((audible - 9.5).abs() < 1e-9);
        let slow = audible_position_secs(10.0, latency, 44_100, 0.5);
        assert!((slow - 9.875).abs() < 1e-9);
    }

    #[test]
    fn dsd_ring_rates_work_in_ring_frames() {
        // Native DSD64 counts one byte per channel: 352.8 kHz. A quarter second
        // of audio is 88 200 of those frames.
        let latency = 88_200;
        let audible = audible_position_secs(5.0, latency, 352_800, 1.0);
        assert!((audible - 4.75).abs() < 1e-9);
    }

    #[test]
    fn bad_inputs_leave_the_decoder_position_alone() {
        assert_eq!(audible_position_secs(3.0, 100, 0, 1.0), 3.0);
        assert!(audible_position_secs(f64::NAN, 100, 44_100, 1.0).is_nan());
        // A speed that makes no sense counts as 1.0.
        let audible = audible_position_secs(10.0, 44_100, 44_100, f32::NAN);
        assert!((audible - 9.0).abs() < 1e-9);
        let audible = audible_position_secs(10.0, 44_100, 44_100, -1.0);
        assert!((audible - 9.0).abs() < 1e-9);
    }
}
