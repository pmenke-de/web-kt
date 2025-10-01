package de.pmenke.webkt.util

object Debug {
    const val ENABLED = true

    /**
     * Allows to eliminate debug code in production builds.
     */
    inline fun ifDebugEnabled(block: () -> Unit) {
        if (ENABLED) {
            block()
        }
    }
}