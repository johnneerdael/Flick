package com.mossapps.flick

import java.lang.ref.WeakReference

/**
 * Whether an activity currently exists, for code that outlives it. Holds the
 * activity weakly, so a destroyed activity that leaks nowhere else is not kept
 * alive by the process-lifetime owner.
 *
 * Main thread only.
 */
internal class ActivityPresence {
    private var current: WeakReference<Any>? = null

    val isPresent: Boolean
        get() = current?.get() != null

    fun attach(activity: Any) {
        current = WeakReference(activity)
    }

    /**
     * The activity is destroyed. Returns true when it was the current one (or
     * none was recorded), so the caller should end whatever depended on it. A
     * newer activity that has already attached stays the current one, and a
     * late destroy of its predecessor returns false.
     */
    fun detach(activity: Any): Boolean {
        val held = current?.get()
        if (held != null && held !== activity) return false
        current = null
        return true
    }
}
