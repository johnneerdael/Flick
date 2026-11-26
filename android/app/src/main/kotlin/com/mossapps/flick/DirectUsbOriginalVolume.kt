package com.mossapps.flick

/**
 * What happens to the level a DAC had before Flick first changed it when Flick
 * lets go of the DAC. Native keeps that level for the whole attachment and
 * applies the chosen action as it clears the registration, after the stream has
 * stopped and before Android's own USB audio driver gets the interfaces back.
 */
internal enum class OriginalVolumeAction(val code: Int) {
    /** Flick still holds the DAC, or takes it straight back: keep the level. */
    KEEP(0),

    /** Android gets the DAC: write the level back first. */
    RESTORE(1),

    /** The DAC is gone: there is nothing to write, and a later attachment starts fresh. */
    FORGET(2),
}

/**
 * The action for a release. [handBack] is true when Android is meant to get the
 * DAC (direct USB turned off, another engine, app quit) and false when Flick
 * takes it straight back. A DAC that is no longer attached is never written to.
 */
internal fun originalVolumeActionOnRelease(handBack: Boolean, dacAttached: Boolean): OriginalVolumeAction =
    when {
        !dacAttached -> OriginalVolumeAction.FORGET
        handBack -> OriginalVolumeAction.RESTORE
        else -> OriginalVolumeAction.KEEP
    }
