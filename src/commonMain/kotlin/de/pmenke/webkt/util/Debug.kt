package de.pmenke.webkt.util

/** Compile-time switch for code that should disappear from optimized non-debug builds. */
object Debug {
    /** Whether debug-only blocks are enabled in this artifact. */
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
