package com.mossapps.flick

import android.media.AudioManager
import android.view.KeyEvent

/** Who the media session's remote volume (system panel, background keys) drives. */
internal enum class SessionVolumeRoute { LOCAL, CAST, DAC }

/**
 * One MediaSession has one volume provider. Cast wins: while casting the audio
 * is on the cast target, not the DAC. The DAC only takes over when nothing is
 * being cast, and everything else stays on the system (STREAM_MUSIC) volume.
 */
internal fun sessionVolumeRoute(isCasting: Boolean, dacActive: Boolean): SessionVolumeRoute =
    when {
        isCasting -> SessionVolumeRoute.CAST
        dacActive -> SessionVolumeRoute.DAC
        else -> SessionVolumeRoute.LOCAL
    }

/** +1 / -1 for the phone's volume up / down keys, 0 for every other key. */
internal fun volumeKeyDirection(keyCode: Int): Int = when (keyCode) {
    KeyEvent.KEYCODE_VOLUME_UP -> 1
    KeyEvent.KEYCODE_VOLUME_DOWN -> -1
    else -> 0
}

/**
 * Whether the activity consumes a volume key for the DAC. Dart's flag says the
 * DAC owns the keys; the claim says the DAC is still Flick's. The claim, not the
 * DAC's volume control, because that control only exists while a stream is open
 * and a stream closed by the idle release must keep the keys on the DAC rather
 * than let them fall through to the system volume.
 */
internal fun shouldConsumeDacVolumeKey(
    direction: Int,
    dacKeysCaptured: Boolean,
    dacClaimed: Boolean,
): Boolean = direction != 0 && dacKeysCaptured && dacClaimed

/**
 * Step for a remote-volume adjust request. Mute, unmute, toggle-mute and
 * ADJUST_SAME are not a level change, so the DAC volume is left alone.
 */
internal fun remoteVolumeAdjustStep(direction: Int): Int = when (direction) {
    AudioManager.ADJUST_RAISE -> 1
    AudioManager.ADJUST_LOWER -> -1
    else -> 0
}
