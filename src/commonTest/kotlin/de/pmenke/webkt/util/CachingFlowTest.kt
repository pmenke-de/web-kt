package de.pmenke.webkt.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asPromise
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class CachingFlowTest {
    @Test
    fun readOnlyViewExposesTheAutoRefreshingValueStream(): Promise<JsAny?> =
        CoroutineScope(Dispatchers.Main).async {
            var supplierCalls = 0
            val cache = MutableCachingFlow(supplier = { ++supplierCalls }, validity = 1.minutes)
            val readOnly = cache.asCachingFlow()

            assertEquals(1, readOnly.values.first())
            assertEquals(listOf(1), readOnly.values.replayCache)
            assertEquals(1, supplierCalls)
        }.asPromise()

    @Test
    fun freshCachedValueIsReusedByLaterSubscribers(): Promise<JsAny?> = CoroutineScope(Dispatchers.Main).async {
        var supplierCalls = 0
        val cache = MutableCachingFlow(supplier = { ++supplierCalls }, validity = 1.minutes)

        assertEquals(1, cache.values.first())
        assertEquals(1, cache.values.first())
        assertEquals(1, supplierCalls)
    }.asPromise()

    @Test
    fun subscriptionRefreshEmitsTheCachedValueBeforeItsAsynchronousRefresh(): Promise<JsAny?> =
        CoroutineScope(Dispatchers.Main).async {
            val refreshStarted = CompletableDeferred<Unit>()
            val cachedValueObserved = CompletableDeferred<Unit>()
            val allowRefresh = CompletableDeferred<Unit>()
            val cache = MutableCachingFlow(
                supplier = {
                    refreshStarted.complete(Unit)
                    allowRefresh.await()
                    2
                },
                validity = 1.minutes,
            )
            cache.setValue(1)
            val refreshOwner = CoroutineScope(coroutineContext + Job())

            try {
                val collection = async {
                    cache.onSubscriptionRefreshIn(refreshOwner)
                        .onEach { if (it == 1) cachedValueObserved.complete(Unit) }
                        .take(2)
                        .toList()
                }

                refreshStarted.await()
                cachedValueObserved.await()
                allowRefresh.complete(Unit)

                assertEquals(listOf(1, 2), collection.await())
            } finally {
                refreshOwner.cancel()
            }
        }.asPromise()

    @Test
    fun replaysOnlyTheCurrentCachedValue(): Promise<JsAny?> = CoroutineScope(Dispatchers.Main).async {
        var supplierCalls = 0
        val cache = MutableCachingFlow(supplier = { ++supplierCalls }, validity = 1.minutes)

        cache.setValue(1)
        cache.setValue(2)

        assertEquals(listOf(2), cache.values.replayCache)
        assertEquals(2, cache.values.first())
        assertEquals(0, supplierCalls)
    }.asPromise()

    @Test
    fun concurrentFirstSubscribersShareTheAutomaticRefresh(): Promise<JsAny?> = CoroutineScope(Dispatchers.Main).async {
        var supplierCalls = 0
        val cache = MutableCachingFlow(supplier = {
            supplierCalls++
            delay(5.milliseconds)
            42
        }, validity = 1.minutes)

        val values = coroutineScope {
            listOf(async { cache.values.first() }, async { cache.values.first() }).awaitAll()
        }

        assertEquals(listOf(42, 42), values)
        assertEquals(1, supplierCalls)
    }.asPromise()

    @Test
    fun aFailedResultIsRetriedForTheNextSubscriber(): Promise<JsAny?> = CoroutineScope(Dispatchers.Main).async {
        var supplierCalls = 0
        val cache = MutableCachingFlow<Result<Int>>(supplier = {
            supplierCalls++
            if (supplierCalls == 1) Result.failure(IllegalStateException("first")) else Result.success(2)
        }, validity = 1.minutes)

        assertEquals(true, cache.values.first().isFailure)
        assertEquals(Result.success(2), cache.values.first())
        assertEquals(2, supplierCalls)
    }.asPromise()

    @Test
    fun finiteKeepAliveRequiresAnOwnedScope() {
        val failure = assertFailsWith<IllegalArgumentException> {
            MutableCachingFlowMap<Int, Int>({ it }, 1.minutes, 1.minutes)
        }

        assertTrue(failure.message.orEmpty().contains("caller-owned CoroutineScope"))
    }

    @Test
    fun scopeTakingConstructorRequiresAJob() {
        val joblessScope = object : CoroutineScope {
            override val coroutineContext = Dispatchers.Main
        }

        assertFailsWith<IllegalArgumentException> {
            MutableCachingFlowMap(joblessScope, { key: Int -> key }, keepAlive = 1.minutes)
        }
    }

    @Test
    fun zeroAndInfiniteKeepAliveStartNoMaintenanceJob() {
        val zero = MutableCachingFlowMap<Int, Int>({ it })
        val infinite = MutableCachingFlowMap<Int, Int>({ it }, keepAlive = Duration.INFINITE)

        assertNull(zero.maintenanceJob)
        assertNull(infinite.maintenanceJob)

        zero.close()
        infinite.close()
    }

    @Test
    fun closeCancelsOnlyMaintenanceAndReleasesEntries(): Promise<JsAny?> = CoroutineScope(Dispatchers.Main).async {
        val ownerJob = Job()
        val owner = CoroutineScope(coroutineContext + ownerJob)
        val cache = MutableCachingFlowMap(owner, { key: Int -> key }, keepAlive = 1.minutes)
        cache[1]
        val readOnly = cache.asCachingFlowMap()
        val maintenance = assertNotNull(cache.maintenanceJob)

        cache.close()
        cache.close()
        maintenance.join()

        assertFalse(maintenance.isActive)
        assertTrue(ownerJob.isActive)
        assertEquals(0, cache.entryCountForTesting)
        assertFailsWith<IllegalStateException> { cache[2] }
        assertFailsWith<IllegalStateException> { cache.clearAll() }
        assertFailsWith<IllegalStateException> { cache.asCachingFlowMap() }
        assertFailsWith<IllegalStateException> { readOnly[3] }
        ownerJob.cancel()
    }.asPromise()

    @Test
    fun cancellingTheOwnerStopsMaintenance(): Promise<JsAny?> = CoroutineScope(Dispatchers.Main).async {
        val ownerJob = Job()
        val cache = MutableCachingFlowMap(
            CoroutineScope(coroutineContext + ownerJob),
            { key: Int -> key },
            keepAlive = 1.minutes,
        )
        val maintenance = assertNotNull(cache.maintenanceJob)

        ownerJob.cancel()
        maintenance.join()

        assertFalse(maintenance.isActive)
        assertEquals(0, cache.entryCountForTesting)
        assertFailsWith<IllegalStateException> { cache[1] }
        cache.close()
    }.asPromise()

    @Test
    fun keepAliveExpiresFromTheMostRecentAccess(): Promise<JsAny?> = CoroutineScope(Dispatchers.Main).async {
        val ownerJob = Job()
        var now = Instant.fromEpochMilliseconds(0)
        val cache = MutableCachingFlowMap.createForTesting(
            coroutineScope = CoroutineScope(coroutineContext + ownerJob),
            supplier = { key: Int -> key },
            keepAlive = 10.milliseconds,
            now = { now },
            waitForMaintenance = { CompletableDeferred<Unit>().await() },
        )
        val externallyHeld = cache[1]
        assertEquals(1, cache.retainedEntryCountForTesting)

        now = Instant.fromEpochMilliseconds(5)
        assertTrue(cache[1] === externallyHeld)
        now = Instant.fromEpochMilliseconds(14)
        cache.maintainNowForTesting()
        assertEquals(1, cache.retainedEntryCountForTesting)

        now = Instant.fromEpochMilliseconds(15)
        cache.maintainNowForTesting()
        assertEquals(0, cache.retainedEntryCountForTesting)
        assertTrue(cache[1] === externallyHeld)

        cache.close()
        ownerJob.cancel()
    }.asPromise()

    @Test
    fun closeWinsAgainstAWaitingMaintenanceCycle(): Promise<JsAny?> = CoroutineScope(Dispatchers.Main).async {
        val ownerJob = Job()
        val waiting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = MutableCachingFlowMap.createForTesting(
            coroutineScope = CoroutineScope(coroutineContext + ownerJob),
            supplier = { key: Int -> key },
            keepAlive = 1.milliseconds,
            waitForMaintenance = {
                waiting.complete(Unit)
                release.await()
            },
        )
        cache[1]
        val maintenance = assertNotNull(cache.maintenanceJob)
        waiting.await()

        cache.close()
        release.complete(Unit)
        maintenance.join()

        assertEquals(0, cache.entryCountForTesting)
        assertFalse(maintenance.isActive)
        assertTrue(ownerJob.isActive)
        ownerJob.cancel()
    }.asPromise()

    @Test
    fun maintenanceFailureClosesCacheWithoutCancellingOrdinaryOwnerOrSibling(): Promise<JsAny?> =
        CoroutineScope(Dispatchers.Main).async {
            val failure = IllegalStateException("maintenance failed")
            val reportedFailure = CompletableDeferred<Throwable>()
            val handler = CoroutineExceptionHandler { _, throwable -> reportedFailure.complete(throwable) }
            val ownerJob = Job()
            val owner = CoroutineScope(coroutineContext + ownerJob + handler)
            val siblingRelease = CompletableDeferred<Unit>()
            val sibling = owner.launch { siblingRelease.await() }
            val cache = MutableCachingFlowMap.createForTesting(
                coroutineScope = owner,
                supplier = { key: Int -> key },
                keepAlive = 1.milliseconds,
                waitForMaintenance = { throw failure },
            )
            cache[1]
            val maintenance = assertNotNull(cache.maintenanceJob)

            assertEquals(failure, reportedFailure.await())
            maintenance.join()

            assertFalse(maintenance.isActive)
            assertTrue(ownerJob.isActive)
            assertTrue(sibling.isActive)
            assertEquals(0, cache.entryCountForTesting)
            assertFailsWith<IllegalStateException> { cache[2] }

            siblingRelease.complete(Unit)
            sibling.join()
            ownerJob.cancel()
        }.asPromise()

    @Test
    fun maintenanceLoopDropsExpiredRetentionAfterItsWaitCompletes(): Promise<JsAny?> =
        CoroutineScope(Dispatchers.Main).async {
            val ownerJob = Job()
            var now = Instant.fromEpochMilliseconds(0)
            val firstWaitStarted = CompletableDeferred<Unit>()
            val releaseFirstWait = CompletableDeferred<Unit>()
            val secondWaitStarted = CompletableDeferred<Unit>()
            var waitCount = 0
            val cache = MutableCachingFlowMap.createForTesting(
                coroutineScope = CoroutineScope(coroutineContext + ownerJob),
                supplier = { key: Int -> key },
                keepAlive = 10.milliseconds,
                now = { now },
                waitForMaintenance = {
                    waitCount++
                    if (waitCount == 1) {
                        firstWaitStarted.complete(Unit)
                        releaseFirstWait.await()
                    } else {
                        secondWaitStarted.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    }
                },
            )
            cache[1]
            assertEquals(1, cache.retainedEntryCountForTesting)
            firstWaitStarted.await()

            now = Instant.fromEpochMilliseconds(10)
            releaseFirstWait.complete(Unit)
            secondWaitStarted.await()

            assertEquals(0, cache.retainedEntryCountForTesting)
            cache.close()
            ownerJob.cancel()
        }.asPromise()
}
