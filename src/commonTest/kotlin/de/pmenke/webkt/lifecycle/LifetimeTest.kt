package de.pmenke.webkt.lifecycle

import de.pmenke.webkt.js_interop.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asPromise
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LifetimeTest {
    @Test
    fun rejectsAParentJob() {
        val parent = Job()

        val failure = assertFailsWith<IllegalArgumentException> { Lifetime(parent) }

        assertTrue(failure.message.orEmpty().contains("unparented Job"))
        parent.cancel()
    }

    @Test
    fun closesOnceInReverseRegistrationOrder() {
        val lifetime = Lifetime()
        val events = mutableListOf<String>()
        lifetime.onClose { events += "first" }
        lifetime.onClose { events += "second" }

        lifetime.close()
        lifetime.close()

        assertTrue(lifetime.isClosed)
        assertEquals(listOf("second", "first"), events)
    }

    @Test
    fun cancelsCoroutinesBeforeCleaningResources(): Promise<JsAny?> = CoroutineScope(Dispatchers.Main).async {
        val lifetime = Lifetime(cancellationMessage = "test lifetime closed")
        var coroutineCompleted = false
        var completionCause: Throwable? = null
        val child = lifetime.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                coroutineCompleted = true
            }
        }
        child.invokeOnCompletion { completionCause = it }
        lifetime.onClose { assertTrue(child.isCancelled) }

        lifetime.close()
        child.join()

        assertTrue(coroutineCompleted)
        assertTrue(child.isCancelled)
        assertIs<CancellationException>(completionCause)
        assertEquals("test lifetime closed", completionCause?.message)
    }.asPromise()

    @Test
    fun registrationAfterClosureIsCleanedImmediately() {
        val lifetime = Lifetime()
        var cleanupCount = 0
        lifetime.close()

        lifetime.onClose { cleanupCount++ }

        assertEquals(1, cleanupCount)
    }

    @Test
    fun removableCleanupCanBeDetachedBeforeClosure() {
        val lifetime = Lifetime()
        var cleanupCount = 0
        val registration = lifetime.onCloseRemovable { cleanupCount++ }

        registration.close()
        registration.close()
        lifetime.close()

        assertEquals(0, cleanupCount)
    }

    @Test
    fun ownerDrivenCleanupCanCloseItsOwnRegistration() {
        val lifetime = Lifetime()
        val events = mutableListOf<String>()
        lateinit var registration: AutoCloseable
        registration = lifetime.onCloseRemovable {
            events += "cleanup-start"
            registration.close()
            events += "cleanup-end"
        }

        lifetime.close()
        lifetime.close()

        assertEquals(listOf("cleanup-start", "cleanup-end"), events)
    }

    @Test
    fun cleanupCanDetachAnotherEntryWhileClosureIsInProgress() {
        val lifetime = Lifetime()
        val events = mutableListOf<String>()
        val detached = lifetime.onCloseRemovable { events += "detached" }
        lifetime.onClose {
            events += "detaching"
            detached.close()
        }

        lifetime.close()

        assertEquals(listOf("detaching"), events)
    }

    @Test
    fun finalizationFallbackCancelsCoroutinesWithoutRunningResourceCleanup(): Promise<JsAny?> =
        CoroutineScope(Dispatchers.Main).async {
            val lifetime = Lifetime()
            var cleanupCount = 0
            lifetime.onClose { cleanupCount++ }
            val lifetimeJob = requireNotNull(lifetime.coroutineContext[Job])
            val child = lifetime.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }

            cancelLifetimeAfterOwnerFinalization(WeakReference(lifetimeJob))
            child.join()

            assertTrue(child.isCancelled)
            assertEquals(0, cleanupCount)

            lifetime.close()
            assertEquals(1, cleanupCount)
        }.asPromise()

    @Test
    fun recursivelyClosingDoesNotRepeatOrInterruptCleanup() {
        val lifetime = Lifetime()
        val events = mutableListOf<String>()
        lifetime.onClose { events += "first" }
        lifetime.onClose {
            events += "second"
            lifetime.close()
        }

        lifetime.close()

        assertEquals(listOf("second", "first"), events)
    }

    @Test
    fun cleanupRegisteredDuringClosureRunsImmediately() {
        val lifetime = Lifetime()
        val events = mutableListOf<String>()
        lifetime.onClose { events += "first" }
        lifetime.onClose {
            events += "second-start"
            lifetime.onClose { events += "nested" }
            events += "second-end"
        }

        lifetime.close()

        assertEquals(listOf("second-start", "nested", "second-end", "first"), events)
    }

    @Test
    fun attemptsEveryCleanupAndAggregatesFailures() {
        val lifetime = Lifetime()
        val events = mutableListOf<String>()
        lifetime.onClose {
            events += "first"
            error("first failed")
        }
        lifetime.onClose {
            events += "second"
            error("second failed")
        }
        lifetime.onClose { events += "third" }

        val failure = assertFailsWith<IllegalStateException> { lifetime.close() }

        assertEquals(listOf("third", "second", "first"), events)
        assertEquals("second failed", failure.message)
        assertEquals(listOf("first failed"), failure.suppressedExceptions.map(Throwable::message))
    }
}
