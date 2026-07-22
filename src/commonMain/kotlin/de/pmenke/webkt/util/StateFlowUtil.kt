package de.pmenke.webkt.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

/** Utilities for explicitly lifecycle-owned hot flow state. */
object StateFlowUtil {

    /** Convert and eagerly launch this flow as nullable state in the caller-owned [coroutineScope]. */
    fun <T> Flow<T>.launchStateIn(coroutineScope: CoroutineScope) =
        stateIn(coroutineScope, SharingStarted.Eagerly, null)
}
