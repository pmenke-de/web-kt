package de.pmenke.webkt.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asPromise
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class CachingFlowTest {
    @Test
    fun replaysOnlyTheCurrentCachedValue(): Promise<JsAny?> = CoroutineScope(Dispatchers.Main).async {
        var supplierCalls = 0
        val cache = MutableCachingFlow(supplier = { ++supplierCalls }, validity = 1.minutes)

        cache.setValue(1)
        cache.setValue(2)

        assertEquals(listOf(2), cache.replayCache)
        assertEquals(2, cache.first())
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
            listOf(async { cache.first() }, async { cache.first() }).awaitAll()
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

        assertEquals(true, cache.first().isFailure)
        assertEquals(Result.success(2), cache.first())
        assertEquals(2, supplierCalls)
    }.asPromise()
}
