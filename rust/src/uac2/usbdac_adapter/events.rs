//! What the usbdac library's events mean to Flick.
//!
//! The library delivers `DacEvent`s on its own thread. This module turns each
//! into the effects Flick has always had for the same happenings: log lines,
//! the last error and the engine's error state, an `AudioEvent::Error` for the
//! player, and the cached DAC volume. It is a pure function of the event and a
//! little history, so it is tested without a DAC; applying the effects is the
//! adapter's job.

use usbdac_core::{DacEvent, Error, StreamState, VolumeState};

/// One thing to do about an event.
#[derive(Debug, Clone, PartialEq)]
pub(crate) enum Effect {
    /// A line for logcat (and the user-shareable log when it is a state change).
    Info(String),
    Warn(String),
    /// The stream failed: record it, put the engine in its error state and tell
    /// the player.
    StreamFailed(String),
    /// The hardware volume or mute changed through the library.
    Volume(VolumeState),
    /// The bit-perfect verdict changed.
    BitPerfect(bool),
}

/// The history the mapping needs: the failure is reported once, and underruns
/// are logged the way they always were (the first few, then every 64th).
#[derive(Debug, Default)]
pub(crate) struct EventMapper {
    failure_reported: bool,
    underrun_runs: u64,
}

const UNDERRUN_LOG_BURST: u64 = 4;
const UNDERRUN_LOG_EVERY: u64 = 64;

impl EventMapper {
    pub(crate) fn map(&mut self, event: DacEvent) -> Vec<Effect> {
        match event {
            DacEvent::StateChanged(state) => vec![Effect::Info(format!(
                "[USB] stream state: {}",
                state_label(state)
            ))],
            DacEvent::Underrun => {
                self.underrun_runs += 1;
                if self.underrun_runs <= UNDERRUN_LOG_BURST
                    || self.underrun_runs % UNDERRUN_LOG_EVERY == 0
                {
                    vec![Effect::Info(format!(
                        "[USB] underrun count={} (the ring ran dry and the wire was padded)",
                        self.underrun_runs
                    ))]
                } else {
                    Vec::new()
                }
            }
            DacEvent::LostFrames { frames } => vec![Effect::Warn(format!(
                "[USB] the DAC did not take {frames} frame(s) of queued audio; it is lost and not re-sent"
            ))],
            DacEvent::BitPerfectChanged(verified) => vec![Effect::BitPerfect(verified)],
            DacEvent::VolumeChanged(state) => vec![Effect::Volume(state)],
            DacEvent::FeedbackStale => vec![Effect::Warn(
                "[USB] the feedback endpoint stopped producing valid reports; \
                 feedback pacing is stale and the transport may stop if it cannot recover"
                    .to_string(),
            )],
            DacEvent::Error(error) => self.failure(&error),
            DacEvent::DeviceLost => self.failure(&Error::DeviceLost),
            // Events the library may add: log them, never act on them.
            other => vec![Effect::Info(format!("[USB] {other:?}"))],
        }
    }

    /// A failed stream is reported once, however many events say so (the
    /// library sends `Error(DeviceLost)` and `DeviceLost` for one unplug).
    fn failure(&mut self, error: &Error) -> Vec<Effect> {
        if self.failure_reported {
            return Vec::new();
        }
        self.failure_reported = true;
        vec![Effect::StreamFailed(failure_message(error))]
    }
}

pub(crate) fn failure_message(error: &Error) -> String {
    match error {
        Error::DeviceLost => "USB DAC disconnected".to_string(),
        other => format!("Android USB direct transfer failed: {other}"),
    }
}

fn state_label(state: StreamState) -> &'static str {
    match state {
        StreamState::Buffering => "buffering",
        StreamState::Playing => "playing",
        StreamState::Paused => "paused",
        StreamState::Draining => "draining",
        StreamState::Drained => "drained",
        StreamState::Closed => "closed",
        StreamState::Failed => "failed",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn volume(db: f64) -> VolumeState {
        VolumeState {
            db,
            fraction: 0.5,
            muted: Some(false),
        }
    }

    #[test]
    fn a_failed_stream_is_reported_to_the_player_once() {
        let mut mapper = EventMapper::default();
        let first = mapper.map(DacEvent::Error(Error::Transfer("stalled".into())));
        assert_eq!(first.len(), 1);
        let Effect::StreamFailed(message) = &first[0] else {
            panic!("{first:?}");
        };
        assert!(message.contains("stalled"), "{message}");
        // The library follows an error with a Failed state; neither repeats it.
        assert!(mapper.map(DacEvent::StateChanged(StreamState::Failed)).iter().all(|e| matches!(e, Effect::Info(_))));
        assert!(mapper.map(DacEvent::Error(Error::Transfer("again".into()))).is_empty());
    }

    #[test]
    fn an_unplug_is_one_failure_whichever_event_arrives_first() {
        for order in [
            [DacEvent::Error(Error::DeviceLost), DacEvent::DeviceLost],
            [DacEvent::DeviceLost, DacEvent::Error(Error::DeviceLost)],
        ] {
            let mut mapper = EventMapper::default();
            let effects: Vec<_> = order.into_iter().flat_map(|event| mapper.map(event)).collect();
            assert_eq!(effects, vec![Effect::StreamFailed("USB DAC disconnected".to_string())]);
        }
    }

    #[test]
    fn underruns_are_logged_the_first_few_times_and_then_every_sixty_fourth() {
        let mut mapper = EventMapper::default();
        let logged: Vec<u64> = (1..=130u64)
            .filter(|_| !mapper.map(DacEvent::Underrun).is_empty())
            .collect();
        assert_eq!(logged, vec![1, 2, 3, 4, 64, 128]);
    }

    #[test]
    fn lost_audio_is_a_warning_and_never_a_failure() {
        let mut mapper = EventMapper::default();
        let effects = mapper.map(DacEvent::LostFrames { frames: 96 });
        assert!(matches!(&effects[..], [Effect::Warn(text)] if text.contains("96")));
        // The stream may still fail later and that is then reported.
        assert!(!mapper.map(DacEvent::Error(Error::Transfer("x".into()))).is_empty());
    }

    #[test]
    fn bit_perfect_and_volume_changes_are_passed_on_as_they_are() {
        let mut mapper = EventMapper::default();
        assert_eq!(mapper.map(DacEvent::BitPerfectChanged(true)), vec![Effect::BitPerfect(true)]);
        assert_eq!(mapper.map(DacEvent::BitPerfectChanged(false)), vec![Effect::BitPerfect(false)]);
        let state = volume(-30.0);
        assert_eq!(mapper.map(DacEvent::VolumeChanged(state)), vec![Effect::Volume(state)]);
    }

    #[test]
    fn a_stale_feedback_endpoint_is_a_warning() {
        let mut mapper = EventMapper::default();
        assert!(matches!(&mapper.map(DacEvent::FeedbackStale)[..], [Effect::Warn(_)]));
    }
}
