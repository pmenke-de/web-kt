package de.pmenke.webkt.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NavigatorServiceTest {
    @Test
    fun pathAndHashNormalizationRespectBasePathBoundaries() {
        val policy = NavigationPolicy("/application/")

        assertEquals("/application", policy.basePath)
        assertEquals("/", policy.applicationPath("/application"))
        assertEquals("/customers/42", policy.applicationPath("/application/customers/42"))
        assertEquals("/application-other/customers", policy.applicationPath("/application-other/customers"))
        assertEquals("/outside", policy.applicationPath("outside"))
        assertEquals(NavigationTarget("/application/customers", ""), policy.target("customers"))
        assertEquals(NavigationTarget("/application/", "?active=true"), policy.target("?active=true"))
        assertEquals(
            NavigationTarget("/application/customers", "?active=true&sort=name"),
            policy.target("customers?active=true&sort=name"),
        )
        assertEquals("", policy.normalizeHash(""))
        assertEquals("#details", policy.normalizeHash("details"))
        assertEquals("#details", policy.normalizeHash("#details"))
    }

    @Test
    fun linkDecisionLeavesBrowserSpecificClicksAlone() {
        val policy = NavigationPolicy("/application")
        val current = location(pathname = "/application/current")
        val browserDefaultCases = listOf(
            click(defaultPrevented = true),
            click(button = 1),
            click(metaKey = true),
            click(ctrlKey = true),
            click(shiftKey = true),
            click(altKey = true),
            click(hasHref = false),
            click(download = true),
            click(target = "_blank"),
            click(origin = "https://other.example"),
            click(pathname = "/application-other/page"),
        )

        browserDefaultCases.forEach { candidate ->
            assertSame(LinkNavigationDecision.BrowserDefault, policy.decide(candidate, current))
        }
    }

    @Test
    fun linkDecisionConsumesEligibleLinksAndAvoidsDuplicateHistoryEntries() {
        val policy = NavigationPolicy("/application")
        val current = location(pathname = "/application/current", search = "?page=1", hash = "#section")

        assertEquals(
            LinkNavigationDecision.Navigate("/application/next", "?page=2", "#details"),
            policy.decide(click(pathname = "/application/next", search = "?page=2", hash = "#details"), current),
        )
        assertSame(
            LinkNavigationDecision.ConsumeCurrentLocation,
            policy.decide(
                click(pathname = current.pathname, search = current.search, hash = current.hash),
                current,
            ),
        )
        assertEquals(
            LinkNavigationDecision.Navigate("/application/current", "?page=2", "#section"),
            policy.decide(
                click(pathname = current.pathname, search = "?page=2", hash = current.hash),
                current,
            ),
        )
    }

    @Test
    fun serviceOwnsHistoryAndLocationStateTransitions() {
        val browser = FakeNavigatorBrowser(
            basePath = "/application",
            currentLocation = location(pathname = "/application/start", hash = "#top"),
        )
        val service = NavigatorService(browser)

        assertEquals(1, browser.replaceCount)
        assertEquals("/start", service.path.value)
        assertEquals("#top", service.hash.value)

        service.navigateTo("customers/42?tab=appointments", "details")

        assertEquals(
            listOf(Triple("/application/customers/42", "?tab=appointments", "#details")),
            browser.pushes,
        )
        assertEquals("/customers/42", service.path.value)
        assertEquals("#details", service.hash.value)
        assertEquals("?tab=appointments", browser.currentLocation.search)

        browser.currentLocation = location(pathname = "/application/back", search = "?restored=true", hash = "")
        browser.firePopState()

        assertEquals("/back", service.path.value)
        assertEquals("", service.hash.value)
    }

    @Test
    fun interceptedAndDuplicateClicksPreserveExactBrowserSemantics() {
        val browser = FakeNavigatorBrowser(
            basePath = "/application",
            currentLocation = location(pathname = "/application/current", hash = "#same"),
        )
        NavigatorService(browser)
        var prevented = false

        browser.fireClick(
            click(
                pathname = "/application/current",
                search = "",
                hash = "#same",
                preventDefault = { prevented = true },
            ),
        )
        assertTrue(prevented)
        assertTrue(browser.pushes.isEmpty())

        prevented = false
        browser.fireClick(
            click(
                pathname = "/application/next",
                hash = "#section",
                preventDefault = { prevented = true },
            ),
        )
        assertTrue(prevented)
        assertEquals(listOf(Triple("/application/next", "", "#section")), browser.pushes)

        prevented = false
        browser.currentLocation = location(pathname = "/application/next", search = "", hash = "#section")
        browser.fireClick(
            click(
                pathname = "/application/next",
                search = "?view=compact",
                hash = "#section",
                preventDefault = { prevented = true },
            ),
        )
        assertTrue(prevented)
        assertEquals(Triple("/application/next", "?view=compact", "#section"), browser.pushes.last())

        prevented = false
        browser.fireClick(click(ctrlKey = true, preventDefault = { prevented = true }))
        assertFalse(prevented)
        assertEquals(2, browser.pushes.size)
    }

    @Test
    fun closeIsIdempotentAndEachInstanceReleasesBothListeners() {
        val firstBrowser = FakeNavigatorBrowser()
        val secondBrowser = FakeNavigatorBrowser()
        val first = NavigatorService(firstBrowser)
        val second = NavigatorService(secondBrowser)

        assertTrue(firstBrowser.hasClickListener)
        assertTrue(firstBrowser.hasPopStateListener)
        assertTrue(secondBrowser.hasClickListener)
        assertTrue(secondBrowser.hasPopStateListener)

        first.close()
        first.close()
        second.close()

        assertEquals(1, firstBrowser.closeCount)
        assertEquals(1, secondBrowser.closeCount)
        assertFalse(firstBrowser.hasClickListener)
        assertFalse(firstBrowser.hasPopStateListener)
        assertFalse(secondBrowser.hasClickListener)
        assertFalse(secondBrowser.hasPopStateListener)
    }

    @Test
    fun initializationFailureClosesPartiallyRegisteredBrowserAdapter() {
        val failure = IllegalStateException("popstate registration failed")
        val browser = FakeNavigatorBrowser(popStateFailure = failure)

        val actual = kotlin.runCatching { NavigatorService(browser) }.exceptionOrNull()

        assertSame(failure, actual)
        assertEquals(1, browser.closeCount)
        assertFalse(browser.hasClickListener)
    }

    @Test
    fun closeFailureDisablesServiceImmediatelyWhileCleanupRemainsRetryable() {
        val cleanupFailure = IllegalStateException("cleanup failed")
        val browser = FakeNavigatorBrowser(closeFailures = ArrayDeque(listOf(cleanupFailure)))
        val service = NavigatorService(browser)
        val pathBeforeClose = service.path.value
        val hashBeforeClose = service.hash.value

        assertSame(cleanupFailure, kotlin.runCatching { service.close() }.exceptionOrNull())
        assertFailsWith<IllegalStateException> { service.navigateTo("after-failed-close?retry=true") }

        var prevented = false
        browser.fireClick(click(preventDefault = { prevented = true }))
        browser.currentLocation = location(pathname = "/after-failed-close", hash = "#ignored")
        browser.firePopState()

        assertFalse(prevented)
        assertEquals(pathBeforeClose, service.path.value)
        assertEquals(hashBeforeClose, service.hash.value)

        service.close()
        service.close()

        assertEquals(2, browser.closeCount)
        assertFailsWith<IllegalStateException> { service.navigateTo("after-successful-close") }
    }

    @Test
    fun retryableCleanupAttemptsEveryStepAggregatesFailuresAndRetriesOnlyFailures() {
        val abortFailure = IllegalStateException("abort failed")
        val clickFailure = IllegalArgumentException("click removal failed")
        val events = mutableListOf<String>()
        var failAbort = true
        var failClick = true
        val cleanup = RetryableCleanup(
            {
                events += "abort"
                if (failAbort) throw abortFailure
            },
            {
                events += "click"
                if (failClick) throw clickFailure
            },
            { events += "popstate" },
        )

        val actual = kotlin.runCatching { cleanup.close() }.exceptionOrNull()

        assertSame(abortFailure, actual)
        assertEquals(listOf(clickFailure), actual?.suppressedExceptions)
        assertEquals(listOf("abort", "click", "popstate"), events)
        assertFalse(cleanup.isComplete)

        failAbort = false
        failClick = false
        cleanup.close()
        cleanup.close()

        assertEquals(listOf("abort", "click", "popstate", "abort", "click"), events)
        assertTrue(cleanup.isComplete)
    }

    private fun location(
        origin: String = "https://example.test",
        pathname: String = "/application/current",
        search: String = "",
        hash: String = "",
    ) = NavigationLocation(origin, pathname, search, hash, "$origin$pathname$search$hash")

    private fun click(
        defaultPrevented: Boolean = false,
        button: Int = 0,
        metaKey: Boolean = false,
        ctrlKey: Boolean = false,
        shiftKey: Boolean = false,
        altKey: Boolean = false,
        hasHref: Boolean = true,
        download: Boolean = false,
        target: String = "",
        origin: String = "https://example.test",
        pathname: String = "/application/next",
        search: String = "",
        hash: String = "",
        preventDefault: () -> Unit = {},
    ) = NavigationClick(
        defaultPrevented,
        button,
        metaKey,
        ctrlKey,
        shiftKey,
        altKey,
        hasHref,
        download,
        target,
        origin,
        pathname,
        search,
        hash,
        preventDefault,
    )
}

private class FakeNavigatorBrowser(
    override val basePath: String = "",
    var currentLocation: NavigationLocation = NavigationLocation(
        "https://example.test",
        "/",
        "",
        "",
        "https://example.test/",
    ),
    private val popStateFailure: Throwable? = null,
    private val closeFailures: ArrayDeque<Throwable> = ArrayDeque(),
) : NavigatorBrowser {
    var replaceCount = 0
    val pushes = mutableListOf<Triple<String, String, String>>()
    var closeCount = 0
    private var clickListener: ((NavigationClick) -> Unit)? = null
    private var popStateListener: (() -> Unit)? = null

    val hasClickListener get() = clickListener != null
    val hasPopStateListener get() = popStateListener != null

    override fun location(): NavigationLocation = currentLocation

    override fun replaceCurrentHistoryState() {
        replaceCount++
    }

    override fun pushHistoryState(pathname: String, search: String, hash: String) {
        pushes += Triple(pathname, search, hash)
        currentLocation = currentLocation.copy(
            pathname = pathname,
            search = search,
            hash = hash,
            href = currentLocation.origin + pathname + search + hash,
        )
    }

    override fun onClick(listener: (NavigationClick) -> Unit) {
        clickListener = listener
    }

    override fun onPopState(listener: () -> Unit) {
        popStateFailure?.let { throw it }
        popStateListener = listener
    }

    fun fireClick(click: NavigationClick) = requireNotNull(clickListener)(click)

    fun firePopState() = requireNotNull(popStateListener)()

    override fun close() {
        closeCount++
        closeFailures.removeFirstOrNull()?.let { throw it }
        clickListener = null
        popStateListener = null
    }
}
