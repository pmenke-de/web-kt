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

    /**
     * Subscribes a [callback] under the given [key].
     * Returns a [CallbackHandle] that can be used to unsubscribe the callback.
     */
    fun <T> subscribe(key: CallbackKey<T>, callback: (T) -> Unit): CallbackHandle {
        val callbackId = idGen()
        callbacks.getOrPut(key) { LinkedHashMap() }[callbackId] = callback
        return CallbackHandle(this, key, callbackId)
    }

    /**
     * Notifies all callbacks registered under the given [key] with the provided [payload].
     *
     * The first exception thrown by a callback is propagated to the caller. Use [notifyCatching] when
     * notification should continue after a callback fails.
     */
    fun <T> notify(key: CallbackKey<T>, payload: T) {
        // Snapshotting makes notification deterministic and safe when callbacks subscribe,
        // unsubscribe, clear the key, or recursively notify from inside a callback.
        callbacks[key]?.values?.toList()?.forEach {
            @Suppress("UNCHECKED_CAST")
            (it as (T) -> Unit)(payload)
        }
    }

    /** Notifies all callbacks registered under the given parameterless [key]. */
    fun notify(key: CallbackKey<Unit>) = notify(key, Unit)

    /**
     * Notifies all callbacks registered under the given [key] with the provided [payload], invoking
     * [onError] for every callback exception and continuing with the remaining callbacks.
     *
     * Exceptions thrown by [onError] are propagated and stop notification.
     */
    fun <T> notifyCatching(key: CallbackKey<T>, payload: T, onError: (Throwable) -> Unit) {
        // Take the same snapshot as notify so mutation and recursive notification have identical semantics.
        callbacks[key]?.values?.toList()?.forEach {
            try {
                @Suppress("UNCHECKED_CAST")
                (it as (T) -> Unit)(payload)
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    /**
     * Notifies all callbacks registered under the given parameterless [key], invoking [onError] for
     * every callback exception and continuing with the remaining callbacks.
     */
    fun notifyCatching(key: CallbackKey<Unit>, onError: (Throwable) -> Unit) = notifyCatching(key, Unit, onError)

    /**
     * Removes all callbacks registered under the given [key].
     */
    fun clear(key: CallbackKey<*>) {
        callbacks.remove(key)
    }

    /** Removes every callback from this registry. */
    fun clear() {
        callbacks.clear()
    }

    internal fun remove(handle: CallbackHandle) {
        callbacks[handle.key]?.let { callbacksForKey ->
            callbacksForKey.remove(handle.id)
            if (callbacksForKey.isEmpty()) callbacks.remove(handle.key)
        }
    }
}

/** Type-safe identity key for a family of callbacks carrying [T]. Keys use reference identity. */
/* non-data*/ class CallbackKey<T>(val name: String)
/** Creates a callback key with no payload. */
fun CallbackKey(name: String) = CallbackKey<Unit>(name)

/** Idempotent subscription handle returned by [Callbacks.subscribe]. */
class CallbackHandle(private val registry: Callbacks, internal val key: CallbackKey<*>, internal val id: CallbackId) {
    private var subscribed = true

    /**
     * Unsubscribes the callback associated with this handle.
     */
    fun unsubscribe() {
        if (subscribed) {
            subscribed = false
            registry.remove(this)
        }
    }
}
