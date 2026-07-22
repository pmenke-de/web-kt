package de.pmenke.webkt.services

import de.pmenke.webkt.js_interop.JsUtil.toJsAny
import de.pmenke.webkt.log.Logger
import de.pmenke.webkt.log.LoggingAspect
import js.objects.unsafeJso
import js.reflect.unsafeCast
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.w3c.dom.Element
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.Node
import org.w3c.dom.PopStateEvent
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.url.URL
import web.abort.AbortController
import web.dom.clickEvent
import web.dom.document as webDocument
import web.events.AddEventListenerOptions
import web.events.addHandler
import web.window.popStateEvent
import web.window.window as webWindow

private val LOG = Logger("de.pmenke.webkt.services.NavigatorService")

/** A browser location snapshot used by the navigation policy and state holder. */
internal data class NavigationLocation(
    val origin: String,
    val pathname: String,
    val search: String,
    val hash: String,
    val href: String,
)

/** The browser-normalized properties of a clicked link and its mouse event. */
internal data class NavigationClick(
    val defaultPrevented: Boolean,
    val button: Int,
    val metaKey: Boolean,
    val ctrlKey: Boolean,
    val shiftKey: Boolean,
    val altKey: Boolean,
    val hasHref: Boolean,
    val download: Boolean,
    val target: String,
    val origin: String,
    val pathname: String,
    val search: String,
    val hash: String,
    val preventDefault: () -> Unit,
)

/** The result of applying SPA link-interception rules without touching browser globals. */
internal sealed interface LinkNavigationDecision {
    data object BrowserDefault : LinkNavigationDecision
    data object ConsumeCurrentLocation : LinkNavigationDecision
    data class Navigate(val pathname: String, val search: String, val hash: String) : LinkNavigationDecision
}

internal data class NavigationTarget(val pathname: String, val search: String)

/** Browser-independent URL and click policy for [NavigatorService]. */
internal class NavigationPolicy(basePath: String) {
    val basePath: String = basePath.trimEnd('/')

    fun applicationPath(fullPath: String): String {
        if (!isWithinBasePath(fullPath)) return fullPath.ensureLeadingSlash()
        return fullPath.removePrefix(basePath).ensureLeadingSlash()
    }

    fun target(applicationPath: String): NavigationTarget {
        val queryStart = applicationPath.indexOf('?')
        val pathname = if (queryStart < 0) applicationPath else applicationPath.substring(0, queryStart)
        val search = if (queryStart < 0) "" else applicationPath.substring(queryStart)
        return NavigationTarget(basePath + pathname.ensureLeadingSlash(), search)
    }

    fun normalizeHash(hash: String): String = when {
        hash.isEmpty() || hash.startsWith('#') -> hash
        else -> "#$hash"
    }

    fun decide(click: NavigationClick, current: NavigationLocation): LinkNavigationDecision {
        if (
            click.defaultPrevented || click.button != 0 || click.metaKey || click.ctrlKey ||
            click.shiftKey || click.altKey
        ) return LinkNavigationDecision.BrowserDefault
        if (!click.hasHref || click.download) return LinkNavigationDecision.BrowserDefault
        if (click.target.isNotBlank() && click.target != "_self") return LinkNavigationDecision.BrowserDefault
        if (click.origin != current.origin || !isWithinBasePath(click.pathname)) {
            return LinkNavigationDecision.BrowserDefault
        }
        if (
            click.pathname == current.pathname && click.search == current.search &&
            click.hash == current.hash
        ) {
            return LinkNavigationDecision.ConsumeCurrentLocation
        }
        return LinkNavigationDecision.Navigate(click.pathname, click.search, click.hash)
    }

    private fun isWithinBasePath(path: String): Boolean =
        basePath.isEmpty() || path == basePath || path.startsWith("$basePath/")

    private fun String.ensureLeadingSlash(): String = when {
        isEmpty() -> "/"
        startsWith('/') -> this
        else -> "/$this"
    }
}

/** Browser effects needed by [NavigatorService], isolated for deterministic tests. */
internal interface NavigatorBrowser : AutoCloseable {
    val basePath: String
    fun location(): NavigationLocation
    fun replaceCurrentHistoryState()
    fun pushHistoryState(pathname: String, search: String, hash: String)
    fun onClick(listener: (NavigationClick) -> Unit)
    fun onPopState(listener: () -> Unit)
}

/** Runs cleanup steps once, retries only failed steps, and never skips later cleanup after a failure. */
internal class RetryableCleanup(private vararg val steps: () -> Unit) : AutoCloseable {
    private val completed = BooleanArray(steps.size)

    val isComplete: Boolean
        get() = completed.all { it }

    override fun close() {
        var failure: Throwable? = null
        steps.forEachIndexed { index, step ->
            if (completed[index]) return@forEachIndexed
            try {
                step()
                completed[index] = true
            } catch (exception: Throwable) {
                if (failure == null) failure = exception else failure.addSuppressed(exception)
            }
        }
        failure?.let { throw it }
    }
}

/**
 * A small client-side navigator that uses the History API for single-page application navigation.
 *
 * If the document contains a `<base href="...">` element, its path is automatically added to and
 * removed from application paths. The service installs document `click` and window `popstate`
 * listeners and therefore must be [closed][close] by its owner. It is normally an
 * application-lifetime singleton; closing it is idempotent and removes both global listeners.
 */
class NavigatorService internal constructor(
    private val browser: NavigatorBrowser,
) : AutoCloseable {
    /** Creates a navigator bound to the current browser document and window. */
    constructor() : this(DomNavigatorBrowser())

    private val policy = NavigationPolicy(browser.basePath)
    private val initialLocation = browser.location()
    private val pathMut = MutableStateFlow(policy.applicationPath(initialLocation.pathname))
    private val hashMut = MutableStateFlow(initialLocation.hash)
    private var shutdownRequested = false
    private var cleanupComplete = false

    /**
     * The current pathname, beginning with `/` and excluding the document base path, if present.
     *
     * Query strings are intentionally not included. Updates after intercepted link clicks,
     * back/forward navigation, and [navigateTo].
     */
    val path get() = pathMut.asStateFlow()

    /**
     * The current URL hash including `#`, or an empty string when no hash is present.
     *
     * Updates after intercepted link clicks, back/forward navigation, and [navigateTo].
     */
    val hash get() = hashMut.asStateFlow()

    init {
        LOG.debug("NavigatorService created", aspect = LoggingAspect.LIFECYCLE)
        try {
            browser.replaceCurrentHistoryState()
            browser.onClick(::onNavigate)
            browser.onPopState(::updateLocationState)
        } catch (exception: Throwable) {
            try {
                browser.close()
            } catch (closeFailure: Throwable) {
                exception.addSuppressed(closeFailure)
            }
            throw exception
        }
    }

    /**
     * Navigates to [path] without reloading the document.
     *
     * Relative paths are resolved from the application's `<base>` path. [path] may include a
     * query string; it never becomes part of [this service's pathname state][this.path]. [hash]
     * may be empty, include its leading `#`, or omit it.
     */
    fun navigateTo(path: String, hash: String = "") {
        check(!shutdownRequested) { "NavigatorService is shutting down or closed" }
        val target = policy.target(path)
        LOG.debug("programmatic navigation to ${target.pathname}${target.search}")
        navigateToFullPath(target.pathname, target.search, policy.normalizeHash(hash))
    }

    /**
     * Removes this service's document and window listeners.
     *
     * The first call immediately and permanently disables navigation and event handling. If
     * adapter cleanup fails, the exception is propagated and a later call retries the unfinished
     * cleanup. Repeated calls after cleanup succeeds have no effect.
     */
    override fun close() {
        if (cleanupComplete) return
        shutdownRequested = true
        browser.close()
        cleanupComplete = true
    }

    private fun onNavigate(click: NavigationClick) {
        if (shutdownRequested) return
        when (val decision = policy.decide(click, browser.location())) {
            LinkNavigationDecision.BrowserDefault -> Unit
            LinkNavigationDecision.ConsumeCurrentLocation -> click.preventDefault()
            is LinkNavigationDecision.Navigate -> {
                click.preventDefault()
                LOG.debug("onclick navigation to ${decision.pathname}${decision.search}${decision.hash}")
                navigateToFullPath(decision.pathname, decision.search, decision.hash)
            }
        }
    }

    private fun navigateToFullPath(pathname: String, search: String, hash: String) {
        browser.pushHistoryState(pathname, search, hash)
        pathMut.value = policy.applicationPath(pathname)
        hashMut.value = hash
    }

    private fun updateLocationState() {
        if (shutdownRequested) return
        val location = browser.location()
        LOG.debug("popstate navigated to ${location.href}")
        pathMut.value = policy.applicationPath(location.pathname)
        hashMut.value = location.hash
    }
}

/** DOM implementation that owns stable listener instances required by removeEventListener. */
private class DomNavigatorBrowser : NavigatorBrowser {
    override val basePath: String = URL(document.baseURI).pathname.trimEnd('/')

    private var removeClickListener: (() -> Unit)? = null
    private var removePopStateListener: (() -> Unit)? = null
    private var clickDelegate: ((NavigationClick) -> Unit)? = null
    private var popStateDelegate: (() -> Unit)? = null
    private val listenerController = AbortController()
    private val listenerOptions: AddEventListenerOptions = unsafeJso<AddEventListenerOptions>().apply {
        signal = listenerController.signal
    }
    private val listenerCleanup = RetryableCleanup(
        { listenerController.abort() },
        {
            removeClickListener?.invoke()
            removeClickListener = null
        },
        {
            removePopStateListener?.invoke()
            removePopStateListener = null
        },
    )
    private var closed = false

    override fun location(): NavigationLocation = NavigationLocation(
        origin = window.location.origin,
        pathname = window.location.pathname,
        search = window.location.search,
        hash = window.location.hash,
        href = window.location.href,
    )

    override fun replaceCurrentHistoryState() {
        val current = location()
        val state = mapOf(
            "path" to current.pathname,
            "search" to current.search,
            "hash" to current.hash,
        ).toJsAny()
        window.history.replaceState(state, "", current.href)
    }

    override fun pushHistoryState(pathname: String, search: String, hash: String) {
        val state = mapOf("path" to pathname, "search" to search, "hash" to hash).toJsAny()
        window.history.pushState(state, "", pathname + search + hash)
    }

    override fun onClick(listener: (NavigationClick) -> Unit) {
        check(removeClickListener == null) { "A click listener is already registered" }
        clickDelegate = listener
        val domListener: (Event) -> Unit = eventHandler@{ rawEvent ->
            val event = rawEvent as? MouseEvent ?: return@eventHandler
            val eventElement = when (val target = event.target) {
                is Element -> target
                is Node -> target.parentElement
                else -> null
            }
            val anchor = eventElement?.closest("a") as? HTMLAnchorElement ?: return@eventHandler
            val url = URL(anchor.href)
            clickDelegate?.invoke(
                NavigationClick(
                    defaultPrevented = event.defaultPrevented,
                    button = event.button.toInt(),
                    metaKey = event.metaKey,
                    ctrlKey = event.ctrlKey,
                    shiftKey = event.shiftKey,
                    altKey = event.altKey,
                    hasHref = anchor.hasAttribute("href"),
                    download = anchor.hasAttribute("download"),
                    target = anchor.target,
                    origin = url.origin,
                    pathname = url.pathname,
                    search = url.search,
                    hash = url.hash,
                    preventDefault = event::preventDefault,
                ),
            )
        }
        removeClickListener = webDocument.clickEvent.addHandler(listenerOptions) { rawEvent ->
            domListener(rawEvent.unsafeCast<Event>())
        }
    }

    override fun onPopState(listener: () -> Unit) {
        check(removePopStateListener == null) { "A popstate listener is already registered" }
        popStateDelegate = listener
        val domListener: (Event) -> Unit = eventHandler@{ rawEvent ->
            if (rawEvent !is PopStateEvent) return@eventHandler
            popStateDelegate?.invoke()
        }
        removePopStateListener = webWindow.popStateEvent.addHandler(listenerOptions) { rawEvent ->
            domListener(rawEvent.unsafeCast<Event>())
        }
    }

    override fun close() {
        if (closed) return
        clickDelegate = null
        popStateDelegate = null
        try {
            listenerCleanup.close()
        } finally {
            closed = listenerCleanup.isComplete
        }
    }
}
