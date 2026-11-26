package com.mossapps.flick

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityPresenceTest {
    @Test
    fun noActivityUntilOneAttaches() {
        assertFalse(ActivityPresence().isPresent)
    }

    @Test
    fun attachedActivityIsPresentUntilItIsDestroyed() {
        val presence = ActivityPresence()
        val activity = Any()
        presence.attach(activity)
        assertTrue(presence.isPresent)

        assertTrue(presence.detach(activity))
        assertFalse(presence.isPresent)
    }

    @Test
    fun destroyOfAnOlderActivityLeavesTheNewerOnePresent() {
        val presence = ActivityPresence()
        val older = Any()
        val newer = Any()
        presence.attach(older)
        presence.attach(newer)

        assertFalse(presence.detach(older))
        assertTrue(presence.isPresent)

        assertTrue(presence.detach(newer))
        assertFalse(presence.isPresent)
    }

    @Test
    fun destroyWithNothingRecordedStillEndsWhatDependedOnIt() {
        assertTrue(ActivityPresence().detach(Any()))
    }
}
