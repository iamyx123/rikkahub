package me.rerere.rikkahub.data.power

import androidx.lifecycle.Lifecycle

/** System shades and transient dialogs can pause a visible page or take window focus. */
internal fun readingPowerPageVisible(state: Lifecycle.State): Boolean =
    state.isAtLeast(Lifecycle.State.STARTED)
