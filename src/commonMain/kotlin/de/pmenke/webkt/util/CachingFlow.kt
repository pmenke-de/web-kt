package de.pmenke.webkt.util

import de.pmenke.webkt.js_interop.WeakReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlin.also
import kotlin.collections.forEach
import kotlin.collections.lastOrNull
import kotlin.collections.set
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Kind of an extended middle ground between SharedFlow and StateFlow.
 * Intended for funneling server data to components, caching values between requests.
 * Allows components to explicitly clear the cache or trigger a refresh, which will make
 * the flow emit a new value.
 */
// only for private inheritance in this file
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
sealed interface CachingFlow<T> : SharedFlow<T> {
    // primarily for debugging (delegated from MutableStateFlow)
    val subscriptionCount: StateFlow<Int>

    /**
     * Refreshes the cache value - causing a new `emit` by calling the supplier function.
     */
    suspend fun refresh()

    /**
     * Clears the cache value - causing new subscribers to only receive a value after the next refresh.
     */
    fun clear()
}

/**
 * Extends [CachingFlow] with the mutable interface, needed in server request handlers.
 */
sealed interface MutableCachingFlow<T> : CachingFlow<T> {
    /**
     * Sets the value of the flow, causing all subscribers to receive the new value.
     * This is primarily intended for usage in request-handlers, where we receive a new value
     * as a side effect (e.g. during POST).
     */
    suspend fun setValue(value: T)

    /**
     * Returns a read-only view of this flow.
     */
    fun asCachingFlow(): CachingFlow<T>
}

/**
 * Creates a new [MutableCachingFlow] with the given supplier function.
 * The supplier function will be called to generate a new value when the flow is refreshed.
 * @param supplier The function to call to generate a new value.
 * @param validity The duration for which the cached value is valid.
 */
fun <T> MutableCachingFlow(
    supplier: suspend () -> T,
    validity: Duration ,
): MutableCachingFlow<T> = MutableCachingFlowImpl(supplier, validity)

@OptIn(
    ExperimentalCoroutinesApi::class,
    // we inherit semi-safely, by delegating all known methods to an official implementation of the interface
    ExperimentalForInheritanceCoroutinesApi::class
)
private class MutableCachingFlowImpl<T>(
    private val supplier: suspend () -> T,
    private val validity: Duration = Duration.INFINITE,
) : MutableCachingFlow<T>, SharedFlow<T> {
    private val state = MutableSharedFlow<T>(2, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val flow = state.onSubscription {
        autoRefresh()
    }

    private var lastRefresh: Instant = Instant.DISTANT_PAST
    private var currentRefresh: Job? = null

    private suspend fun autoRefresh() {
        // no need to worry about concurrent entry,
        // as this should only ever run on the browser's event-loop (single-threaded)
        currentRefresh?.join() // if a refresh is ongoing, wait for it to finish
        // then check, if refresh is necessary. refresh if:
        if (state.replayCache.isEmpty() // not fetched yet
            || lastRefresh + validity < Clock.System.now() // expired
            || (state.replayCache.lastOrNull() as? Result<*>)?.isFailure == true) { // last fetch failed
            clear()
            // coroutineScope() only returns, after the inner job is finished.
            coroutineScope { currentRefresh = launch { refresh() } }
            currentRefresh = null
        }
    }

    override val subscriptionCount by state::subscriptionCount

    override suspend fun setValue(value: T) {
        state.emit(value)
    }

    override fun clear() {
        state.resetReplayCache()
    }

    override suspend fun refresh() {
        state.emit(supplier())
        lastRefresh = Clock.System.now()
    }

    override fun asCachingFlow() = ReadOnlyCachingFlow(this)

    override val replayCache = flow.replayCache

    override suspend fun collect(collector: FlowCollector<T>) = flow.collect(collector)
}

private class ReadOnlyCachingFlow<T>(mutable: MutableCachingFlow<T>) : CachingFlow<T> by mutable

/**
 * Uses [SharedFlow.onSubscription] to launch a refresh of the cached value, when the resulting flow is subscribed to.
 * The refresh happens in the given [CoroutineScope] asynchronously, so that a cached values can be observed before
 * the refresh completes.
 */
fun <T> CachingFlow<T>.onSubscriptionRefreshIn(coroutineScope: CoroutineScope) = onSubscription {
    coroutineScope.launch { refresh() }
}

/**
 * A map of [CachingFlow]s, indexed by a key of type K.
 * The map is mutable and creates new flows on demand.
 * Useful for objects, which are requested by a key/id.
 */
interface CachingFlowMap<K, T> {
    /**
     * Returns a [CachingFlow] for the given key.
     * If the flow does not exist yet, it will be created using the given supplier function.
     * The returned [CachingFlow] is internally held by a weak reference only and thus will be garbage collected,
     * if it isn't held onto by the caller.
     */
    operator fun get(key: K): CachingFlow<T>
}

/**
 * Mutable implementation of [CachingFlowMap], intended for use in server request handlers.
 * Frontend components should be handed out a read-only view of this map via [asCachingFlowMap].
 */
class MutableCachingFlowMap<K, T>(
    private val supplier: suspend (K) -> T,
    private val validity: Duration = Duration.INFINITE,
) : CachingFlowMap<K, T> {
    private val state = mutableMapOf<K, WeakReference<MutableCachingFlow<T>>>()
    private val deadKeys = mutableSetOf<K>()
    private fun makeFlow(key: K) = MutableCachingFlowImpl({ supplier(key) }, validity)

    override fun get(key: K): MutableCachingFlow<T> {
        val ref = state[key]
        return if (ref != null) {
            ref.deref() ?: makeFlow(key).also { state[key] = WeakReference(it) }
        } else {
            makeFlow(key).also { state[key] = WeakReference(it) }
        }.also { removeDeadKeys() }
    }

    /**
     * Clears all cached values, but keeps the underlying [CachingFlow]s,
     * so that future [CachingFlow.refresh]s on them will still work.
     */
    fun clearAll() {
        state.keys.forEach { key ->
            getOrNull(key)?.clear()
        }
        removeDeadKeys()
    }

    /**
     * Creates a read-only view of this map, which can be handed out to frontend components.
     */
    fun asCachingFlowMap(): CachingFlowMap<K, T> = ReadOnlyCachingFlowMap(this)

    private fun removeDeadKeys() {
        deadKeys.forEach { key -> state.remove(key) }
        deadKeys.clear()
    }

    private fun getOrNull(key: K): MutableCachingFlow<T>? {
        val ref = state[key] ?: return null
        return ref.deref() ?: run {
            // schedule key for removal, if reference is dead.
            // can't delete immediately, as we might be in concurrent access from #clearAll.
            deadKeys.add(key)
            null
        }
    }
}

private class ReadOnlyCachingFlowMap<K, T>(private val mutable: MutableCachingFlowMap<K, T>) : CachingFlowMap<K, T> {
    override fun get(key: K): CachingFlow<T> = mutable[key].asCachingFlow()
}