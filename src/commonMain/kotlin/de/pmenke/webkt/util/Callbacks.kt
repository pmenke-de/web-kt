package de.pmenke.webkt.util

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement

internal typealias CallbackId = Long

/**
 * A simple callback registry that allows subscribing to and notifying callbacks identified by a [CallbackKey].
 * Callbacks can be unsubscribed using the returned [CallbackHandle].
 */
// Note: this class is not thread-safe atm. (mutable-maps aren't thread-safe).
//       for web / wasm this is not a problem, as it's single-threaded anyway.
class Callbacks {
    @OptIn(ExperimentalAtomicApi::class)
    private val idGen = AtomicLong(0L).let { { it.fetchAndIncrement() } }
    // Note: type-coupling between key and callback cannot be represented in the map type
    private val callbacks = mutableMapOf<CallbackKey<*>, MutableMap<CallbackId, (Nothing) -> Unit>>()

    // keep a list of deferred removals to avoid concurrent modification on removal during notification
    private val deferredRemovals = mutableListOf<CallbackHandle>()
    private var notifying = false

    /**
     * Subscribes a [callback] under the given [key].
     * Returns a [CallbackHandle] that can be used to unsubscribe the callback.
     */
    fun <T> subscribe(key: CallbackKey<T>, callback: (T) -> Unit): CallbackHandle {
        val callbackId = idGen()
        callbacks.getOrPut(key) { LinkedHashMap() }.put(callbackId, callback)
        return CallbackHandle(this, key, callbackId)
    }

    /**
     * Notifies all callbacks registered under the given [key] with the provided [payload].
     * If an [errorHandler] is provided, it will be called for each exception thrown by a callback.
     * If no [errorHandler] is provided, exceptions will be propagated.
     */
    fun <T> notify(key: CallbackKey<T>, payload: T, errorHandler: ((Throwable) -> Unit)? = null) {
        notifying = true
        try {
            callbacks[key]?.values?.forEach {
                try {
                    @Suppress("UNCHECKED_CAST")
                    (it as (T) -> Unit)(payload) // may cause deferred removals
                } catch (t: Throwable) {
                    errorHandler?.invoke(t) ?: throw t
                }
            }
        } finally {
            notifying = false
            // process deferred removals
            deferredRemovals.forEach { it.unsubscribe() }
            deferredRemovals.clear()
        }
    }

    /**
     * Notifies all callbacks registered under the given [key].
     * If an [errorHandler] is provided, it will be called for each exception thrown by a callback.
     * If no [errorHandler] is provided, exceptions will be propagated.
     */
    // shorthand for parameterless callbacks
    fun notify(key: CallbackKey<Unit>, errorHandler: ((Throwable) -> Unit)? = null) = notify(key, Unit, errorHandler)

    internal fun remove(handle: CallbackHandle) {
        if (notifying) {
            deferredRemovals.add(handle)
            return
        }
        callbacks[handle.key]?.remove(handle.id)
    }
}

/* non-data*/ class CallbackKey<T>(val name: String)
fun CallbackKey(name: String) = CallbackKey<Unit>(name)

class CallbackHandle(private val registry: Callbacks, internal val key: CallbackKey<*>, internal val id: CallbackId) {
    /**
     * Unsubscribes the callback associated with this handle.
     */
    fun unsubscribe() {
        registry.remove(this)
    }
}