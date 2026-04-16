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
 * @param supplier The function to call to generate a new value for a given key.
 * @param validity The duration for which the cached value is valid.
 * @param keepAlive The duration for which the map should ensure that an entry ([CachingFlow]) is kept alive,
 *                  even if there are no external references to it.
 * @implementation If [keepAlive] is non-zero, a maintenance job will run every [keepAlive] duration, which drops
 *                 strong references to entries, which haven't been accessed for at least [keepAlive] duration.
 */
class MutableCachingFlowMap<K, T>(
    private val supplier: suspend (K) -> T,
    private val validity: Duration = Duration.INFINITE,
    private val keepAlive: Duration = Duration.ZERO,
) : CachingFlowMap<K, T> {
    private val state = mutableMapOf<K, CacheFlowMapEntry<T>>()
    private val deadKeys = mutableSetOf<K>()

    init {
        require(keepAlive >= Duration.ZERO) { "keepAlive must be non-negative" }
        if (keepAlive > Duration.ZERO) {
            val weakThis = WeakReference<MutableCachingFlowMap<*, *>>(this)
            @OptIn(DelicateCoroutinesApi::class)
            GlobalScope.launch { maintenanceJob(weakThis, keepAlive) }
        }
    }

    private fun makeFlow(key: K): CacheFlowMapEntry<T> {
        val cachingFlow = MutableCachingFlowImpl({ supplier(key) }, validity)
        return if (keepAlive > Duration.ZERO) StrongCacheFlowMapEntry(cachingFlow)
               else CacheFlowMapEntry(WeakReference(cachingFlow))
    }

    override fun get(key: K): MutableCachingFlow<T> {
        return (state[key]?.deref() ?: makeFlow(key).also { state[key] = it }.deref()!!).also { removeDeadKeys() }
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

    private fun maintain() {
        val now = Clock.System.now()
        state.values.forEach { entry ->
            (entry as? StrongCacheFlowMapEntry<T>)?.clearExpired(now, keepAlive)
        }
    }

    companion object {
        private suspend fun maintenanceJob(instance: WeakReference<MutableCachingFlowMap<*, *>>, interval: Duration) {
            while(true) {
                delay(interval)
                instance.deref()?.maintain() ?: break
            }
        }
    }
}

private class ReadOnlyCachingFlowMap<K, T>(private val mutable: MutableCachingFlowMap<K, T>) : CachingFlowMap<K, T> {
    override fun get(key: K): CachingFlow<T> = mutable[key].asCachingFlow()
}

private open class CacheFlowMapEntry<T>(
    private val weakEntry: WeakReference<MutableCachingFlow<T>>,
) {
    open fun deref(): MutableCachingFlow<T>? {
        return weakEntry.deref()
    }
}

private class StrongCacheFlowMapEntry<T>(cachingFlow: MutableCachingFlow<T>) : CacheFlowMapEntry<T>(WeakReference(cachingFlow)) {
    private var strongEntry: MutableCachingFlow<T>? = cachingFlow
    private var lastAccess: Instant = Clock.System.now()

    override fun deref(): MutableCachingFlow<T>? {
        lastAccess = Clock.System.now()
        return strongEntry ?: (super.deref()?.also { strongEntry = it })
    }

    fun clearExpired(now: Instant, keepAlive: Duration) {
        if (lastAccess + keepAlive < now) {
            strongEntry = null
        }
    }
}
