package de.pmenke.webkt.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ApplicationResourcesTest {
    @Test
    fun closesEveryResourceInUiToContainerOrderOnlyOnce() {
        val closed = mutableListOf<String>()
        val resources = ApplicationResources()

        resources.ownApplication(closeable("application", closed)) { it.close() }
        resources.ownRootScope(closeable("scope", closed)) { it.close() }
        resources.ownRepository(closeable("repository", closed))
        resources.ownNavigator(closeable("navigator", closed))
        resources.ownRoot(closeable("root", closed))
        resources.ownPageHideListener { closed += "listener" }

        resources.close()
        resources.close()

        assertEquals(
            listOf("listener", "root", "navigator", "repository", "scope", "application"),
            closed,
        )
    }

    @Test
    fun partialAcquisitionIsCleanedWhenTheNextAcquisitionFails() {
        val closed = mutableListOf<String>()
        val resources = ApplicationResources()
        val startupFailure = IllegalStateException("navigator acquisition failed")

        val thrown = assertFailsWith<IllegalStateException> {
            try {
                resources.ownApplication(closeable("application", closed)) { it.close() }
                resources.ownRootScope(closeable("scope", closed)) { it.close() }
                resources.ownRepository(closeable("repository", closed))
                throw startupFailure
            } catch (failure: Throwable) {
                try {
                    resources.close()
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
                throw failure
            }
        }

        assertSame(startupFailure, thrown)
        assertEquals(listOf("repository", "scope", "application"), closed)
    }

    @Test
    fun cleanupContinuesAfterFailureAndOnlyRetriesTheFailedResource() {
        val closed = mutableListOf<String>()
        val resources = ApplicationResources()
        var navigatorAttempts = 0

        resources.ownApplication(closeable("application", closed)) { it.close() }
        resources.ownRootScope(closeable("scope", closed)) { it.close() }
        resources.ownRepository(closeable("repository", closed))
        resources.ownNavigator(
            AutoCloseable {
                navigatorAttempts++
                if (navigatorAttempts == 1) error("temporary navigator cleanup failure")
                closed += "navigator"
            },
        )
        resources.ownRoot(closeable("root", closed))
        resources.ownPageHideListener { closed += "listener" }

        assertFailsWith<IllegalStateException> { resources.close() }
        assertEquals(
            listOf("listener", "root", "repository", "scope", "application"),
            closed,
        )

        resources.close()

        assertEquals(
            listOf("listener", "root", "repository", "scope", "application", "navigator"),
            closed,
        )
        assertEquals(2, navigatorAttempts)
    }

    private fun closeable(label: String, closed: MutableList<String>) =
        AutoCloseable { closed += label }
}
