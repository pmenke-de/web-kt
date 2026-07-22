package de.pmenke.webkt.services

import de.pmenke.webkt.js_interop.JsUtil.toJsAny
import de.pmenke.webkt.log.Logger
import de.pmenke.webkt.log.LoggingAspect
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.w3c.dom.Element
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.Node
import org.w3c.dom.PopStateEvent
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.url.URL

private val LOG = Logger("de.pmenke.webkt.services.NavigatorService")

/**
 * A simple client-side navigator service that uses the History API to manage navigation within a single-page application (SPA).
 * If the document contains a `<base href="...">` element, the base path is automatically added / trimmed from the paths,
 * so that the application doesn't need to care about it.
 */
class NavigatorService {
    private val basePath = URL(document.baseURI).pathname.trimEnd('/')
    private val pathMut = MutableStateFlow(toApplicationPath(window.location.pathname))
    private val hashMut = MutableStateFlow(window.location.hash)

    /**
     * The current path, starting with a '/' and without the base path (if any).
     * Updates on browser navigation (a-click, back/forward buttons) as well as programmatic navigation via [navigateTo].
     */
    val path get() = pathMut.asStateFlow()

    /**
     * The current URL hash (including the leading '#'), or an empty string if no hash is present.
      * Updates on browser navigation (a-click, back/forward buttons) as well as programmatic navigation via [navigateTo].
      */
    val hash get() = hashMut.asStateFlow()

    init {
        LOG.debug("NavigatorService created", aspect = LoggingAspect.LIFECYCLE)
        replaceCurrentHistoryState()
        // catch click events on / within a-elements and handle internal navigation
        document.addEventListener("click") { rawEvent ->
            val event = rawEvent as? MouseEvent ?: return@addEventListener
            val eventElement = when (val target = event.target) {
                is Element -> target
                is Node -> target.parentElement
                else -> null
            }
            val anchor = eventElement?.closest("a") as? HTMLAnchorElement ?: return@addEventListener
            onNavigate(event, anchor)
        }
        // handle browser navigation (back/forward)
        window.addEventListener("popstate") { event ->
            if (event !is PopStateEvent) return@addEventListener
            LOG.debug("popstate navigated to ${window.location.href}")
            updateLocationState()
        }
    }

    /**
     * Navigates to [path] without reloading the document.
     *
     * Relative paths are resolved from the application's `<base>` path. [hash] may be empty,
     * include its leading `#`, or omit it.
     */
    fun navigateTo(path: String, hash: String = "") {
        val applicationPath = path.ensureLeadingSlash()
        val fullPath = basePath + applicationPath
        LOG.debug("programmatic navigation to $fullPath")
        navigateToFullPath(fullPath, hash.normalizeHash())
    }

    private fun navigateToFullPath(path: String, hash: String) {
        val state = mapOf("path" to path, "hash" to hash).toJsAny()
        window.history.pushState(state, "", path + hash)
        pathMut.value = toApplicationPath(path)
        hashMut.value = hash
    }

    private fun onNavigate(event: MouseEvent, anchor: HTMLAnchorElement) {
        if (event.defaultPrevented || event.button.toInt() != 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
        if (!anchor.hasAttribute("href")) return
        if (anchor.hasAttribute("download")) return
        if (anchor.target.isNotBlank() && anchor.target != "_self") return

        val url = URL(anchor.href)
        if (url.origin != window.location.origin || !url.pathname.isWithinBasePath()) return

        event.preventDefault()
        if (window.location.pathname != url.pathname || window.location.hash != url.hash) {
            LOG.debug("onclick navigation to ${url.pathname}${url.hash}")
            navigateToFullPath(url.pathname, url.hash)
        }
    }

    private fun replaceCurrentHistoryState() {
        val state = mapOf("path" to window.location.pathname, "hash" to window.location.hash).toJsAny()
        window.history.replaceState(state, "", window.location.href)
    }

    private fun updateLocationState() {
        pathMut.value = toApplicationPath(window.location.pathname)
        hashMut.value = window.location.hash
    }

    private fun toApplicationPath(fullPath: String): String {
        if (!fullPath.isWithinBasePath()) return fullPath.ensureLeadingSlash()
        return fullPath.removePrefix(basePath).ensureLeadingSlash()
    }

    private fun String.isWithinBasePath(): Boolean =
        basePath.isEmpty() || this == basePath || startsWith("$basePath/")

    private fun String.ensureLeadingSlash(): String = when {
        isEmpty() -> "/"
        startsWith('/') -> this
        else -> "/$this"
    }

    private fun String.normalizeHash(): String = when {
        isEmpty() || startsWith('#') -> this
        else -> "#$this"
    }
}
