package com.ketvoice.app.stems

/**
 * The phone's library preparation (separating downloaded titles ahead, on the charger) is not on the desktop yet:
 * a title is separated when its voice level is first touched, or when Ketmix mixes it. Only the rule the shared
 * tests read is kept, unchanged.
 */
internal object StemLibrary {
    internal fun allowed(ketmix: Boolean, library: Boolean, charging: Boolean): Boolean = ketmix && library && charging
}
