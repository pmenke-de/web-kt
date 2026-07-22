package de.pmenke.webkt.util

import de.pmenke.webkt.js_interop.WeakReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A refreshable value cache backed by [values].
 *
 * The observation stream replays the current cached value and automatically loads a value for a subscriber when the
 * cache is empty or stale. [refresh] and [clear] provide explicit cache control without exposing mutation of the
 * observation stream.
 */
// only for private inheritance in this file
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
sealed interface CachingFlow<T> : SharedFlow<T> {
    /** The read-only stream of cached values, including automatic refresh when subscribers arrive. */
    val values: SharedFlow<T>

    // primarily for debugging (delegated from MutableSharedFlow)
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
 * @param validity The non-negative duration for which the cached value is valid. Infinite by default.
 */
fun <T> MutableCachingFlow(
    supplier: suspend () -> T,
    validity: Duration = Duration.INFINITE,
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
    private val state = MutableSharedFlow<T>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val values: SharedFlow<T> = state.onSubscription {
        autoRefresh()
    }

    private var lastRefresh: Instant = Instant.DISTANT_PAST
    private val refreshMutex = Mutex()

    init {
        require(validity >= Duration.ZERO) { "validity must be non-negative" }
    }

    private suspend fun autoRefresh() {
        refreshMutex.withLock {
            if (needsRefresh()) {
                clear()
                refreshUnlocked()
            }
        }
    }

    private fun needsRefresh(): Boolean =
        state.replayCache.isEmpty() ||
            lastRefresh + validity < Clock.System.now() ||
            (state.replayCache.lastOrNull() as? Result<*>)?.isFailure == true

    override val subscriptionCount by state::subscriptionCount

    override suspend fun setValue(value: T) {
        refreshMutex.withLock {
            state.emit(value)
            lastRefresh = Clock.System.now()
        }
    }

    override fun clear() {
        state.resetReplayCache()
    }

    override suspend fun refresh() {
        refreshMutex.withLock { refreshUnlocked() }
    }

    private suspend fun refreshUnlocked() {
        state.emit(supplier())
        lastRefresh = Clock.System.now()
    }

    override fun asCachingFlow() = ReadOnlyCachingFlow(this)

    override val replayCache: List<T>
        get() = values.replayCache

    override suspend fun collect(collector: FlowCollector<T>) = values.collect(collector)
}

private class ReadOnlyCachingFlow<T>(mutable: MutableCachingFlow<T>) : CachingFlow<T> by mutable

/**
 * Uses [SharedFlow.onSubscription] to launch a refresh of the cached value, when the resulting flow is subscribed to.
 * The refresh happens in the given [CoroutineScope] asynchronously, so that a cached value can be observed before
 * the refresh completes.
 */
fun <T> CachingFlow<T>.onSubscriptionRefreshIn(coroutineScope: CoroutineScope) = values.onSubscription {
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
 * @implementation If [keepAlive] is finite and non-zero, a maintenance job runs every [keepAlive] duration and drops
 *                 strong references to entries, which haven't been accessed for at least [keepAlive] duration.
 *                 Infinite keep-alive retains entries for the lifetime of this map without starting a job.
 */
class MutableCachingFlowMap<K, T> private constructor(
    private val supplier: suspend (K) -> T,
    private val validity: Duration,
    private val keepAlive: Duration,
    maintenanceOwner: CoroutineScope?,
    private val now: () -> Instant,
    private val waitForMaintenance: suspend (Duration) -> Unit,
) : CachingFlowMap<K, T>, AutoCloseable {
    private val state = mutableMapOf<K, CacheFlowMapEntry<T>>()
    private val deadKeys = mutableSetOf<K>()
    private var closed = false
    private var maintenanceLifetimeJob: Job? = null
    internal var maintenanceJob: Job? = null
        private set

    init {
        validateDurations(validity, keepAlive)
        require(!requiresMaintenance(keepAlive) || maintenanceOwner != null) {
            "finite, non-zero keepAlive requires a caller-owned CoroutineScope; " +
                "use MutableCachingFlowMap(coroutineScope, supplier, validity, keepAlive)"
        }
        require(maintenanceOwner == null || maintenanceOwner.coroutineContext[Job] != null) {
            "coroutineScope must contain a Job so cache maintenance has an explicit owner"
        }
        if (requiresMaintenance(keepAlive)) {
            val weakThis = WeakReference<MutableCachingFlowMap<*, *>>(this)
            // Snapshot constructor properties before creating the worker. Its closure must retain only these
            // values and the weak map reference, otherwise the owner job would keep the cache alive forever.
            val maintenanceInterval = keepAlive
            val maintenanceWait = waitForMaintenance
            val ownerJob = maintenanceOwner!!.coroutineContext[Job]!!
            val lifetimeJob = SupervisorJob(ownerJob)
            val maintenanceScope = CoroutineScope(maintenanceOwner.coroutineContext + lifetimeJob)
            val job = maintenanceScope.launch {
                runCacheMaintenance(weakThis, maintenanceInterval, maintenanceWait)
            }
            maintenanceLifetimeJob = lifetimeJob
            maintenanceJob = job
            job.invokeOnCompletion {
                weakThis.deref()?.close()
                // Detach the private supervisor even when the cache has already been collected.
                lifetimeJob.cancel()
            }
        }
    }

    /**
     * Creates a cache with no maintenance owner.
     *
     * This constructor is valid only when [keepAlive] is zero or infinite, because those modes do not run a
     * maintenance coroutine. Use the scope-taking constructor for a finite, non-zero keep-alive.
     */
    constructor(
        supplier: suspend (K) -> T,
        validity: Duration = Duration.INFINITE,
        keepAlive: Duration = Duration.ZERO,
    ) : this(supplier, validity, keepAlive, null, Clock.System::now, { delay(it) })

    /**
     * Creates a keyed cache whose finite keep-alive maintenance is owned by [coroutineScope].
     *
     * The scope must contain a [Job]. For a finite, non-zero [keepAlive], cancelling that job stops maintenance
     * and closes the map. Maintenance is isolated behind a private supervisor, so its failure and explicit map
     * closure never cancel the caller's job or its other children.
     *
     * Zero and infinite keep-alive modes start no maintenance and therefore are not closed by owner cancellation;
     * their owner must still call [close] to release retained entries deterministically.
     */
    constructor(
        coroutineScope: CoroutineScope,
        supplier: suspend (K) -> T,
        validity: Duration = Duration.INFINITE,
        keepAlive: Duration = Duration.ZERO,
    ) : this(
        supplier = supplier,
        validity = validity,
        keepAlive = keepAlive,
        maintenanceOwner = coroutineScope,
        now = Clock.System::now,
        waitForMaintenance = { delay(it) },
    )

    private fun makeFlow(key: K): CacheFlowMapEntry<T> {
        val cachingFlow = MutableCachingFlowImpl({ supplier(key) }, validity)
        return if (keepAlive > Duration.ZERO) StrongCacheFlowMapEntry(cachingFlow, now)
               else CacheFlowMapEntry(WeakReference(cachingFlow))
    }

    override fun get(key: K): MutableCachingFlow<T> {
        checkOpen()
        return (state[key]?.deref() ?: makeFlow(key).also { state[key] = it }.deref()!!).also { removeDeadKeys() }
    }

    /**
     * Clears all cached values, but keeps the underlying [CachingFlow]s,
     * so that future [CachingFlow.refresh]s on them will still work.
     */
    fun clearAll() {
        checkOpen()
        state.keys.forEach { key ->
            getOrNull(key)?.clear()
        }
        removeDeadKeys()
    }

    /**
     * Creates a read-only view of this map, which can be handed out to frontend components.
     */
    fun asCachingFlowMap(): CachingFlowMap<K, T> {
        checkOpen()
        return ReadOnlyCachingFlowMap(this)
    }

    /** Stops private maintenance, detaches it from its owner, and releases all entries. Repeated calls are safe. */
    override fun close() {
        if (closed) return
        closed = true
        maintenanceJob?.cancel()
        maintenanceJob = null
        maintenanceLifetimeJob?.cancel()
        maintenanceLifetimeJob = null
        state.clear()
        deadKeys.clear()
    }

    private fun checkOpen() {
        check(!closed) { "MutableCachingFlowMap is closed" }
    }

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
        if (closed) return
        val now = now()
        state.values.forEach { entry ->
            (entry as? StrongCacheFlowMapEntry<T>)?.clearExpired(now, keepAlive)
        }
    }

    companion object {
        internal fun <K, T> createForTesting(
            coroutineScope: CoroutineScope?,
            supplier: suspend (K) -> T,
            validity: Duration = Duration.INFINITE,
            keepAlive: Duration = Duration.ZERO,
            now: () -> Instant = Clock.System::now,
            waitForMaintenance: suspend (Duration) -> Unit = { delay(it) },
        ) = MutableCachingFlowMap(supplier, validity, keepAlive, coroutineScope, now, waitForMaintenance)

        private fun validateDurations(validity: Duration, keepAlive: Duration) {
            require(validity >= Duration.ZERO) { "validity must be non-negative" }
            require(keepAlive >= Duration.ZERO) { "keepAlive must be non-negative" }
        }

        private fun requiresMaintenance(keepAlive: Duration) =
            keepAlive > Duration.ZERO && keepAlive.isFinite()

        private suspend fun runCacheMaintenance(
            instance: WeakReference<MutableCachingFlowMap<*, *>>,
            interval: Duration,
            waitForMaintenance: suspend (Duration) -> Unit,
        ) {
            while (true) {
                waitForMaintenance(interval)
                instance.deref()?.maintain() ?: break
            }
        }
    }

    internal fun maintainNowForTesting() = maintain()

    internal val entryCountForTesting: Int
        get() = state.size

    internal val retainedEntryCountForTesting: Int
        get() = state.values.count { (it as? StrongCacheFlowMapEntry<T>)?.isRetained == true }
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

private class StrongCacheFlowMapEntry<T>(
    cachingFlow: MutableCachingFlow<T>,
    private val now: () -> Instant,
) : CacheFlowMapEntry<T>(WeakReference(cachingFlow)) {
    private var strongEntry: MutableCachingFlow<T>? = cachingFlow
    private var lastAccess: Instant = now()

    override fun deref(): MutableCachingFlow<T>? {
        lastAccess = now()
        return strongEntry ?: (super.deref()?.also { strongEntry = it })
    }

    fun clearExpired(now: Instant, keepAlive: Duration) {
        if (lastAccess + keepAlive <= now) {
            strongEntry = null
        }
    }

    val isRetained: Boolean
        get() = strongEntry != null
}
