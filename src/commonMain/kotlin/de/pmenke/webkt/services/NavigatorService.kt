package de.pmenke.webkt.services

import de.pmenke.webkt.js_interop.JsObject
import de.pmenke.webkt.js_interop.JsUtil.toJsAny
import de.pmenke.webkt.log.Logger
import de.pmenke.webkt.log.LoggingAspect
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.w3c.dom.Element
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.PopStateEvent
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventTarget
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
    private var pathMut = MutableStateFlow(window.location.pathname.removePrefix(basePath))
    private var hashMut = MutableStateFlow(window.location.hash)

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
        window.history.replaceState(mapOf("path" to "$basePath/${path.value}").toJsAny(), "")
        // catch click events on / within a-elements and handle internal navigation
        document.addEventListener("click") {
            val event = it as MouseEvent
            val target = (event.target as Element).closest("a") as? HTMLAnchorElement
            if (target != null) {
                onNavigate(event, target, target.href)
            }
        }
        // handle browser navigation (back/forward)
        window.addEventListener("popstate") { event ->
            if (event !is PopStateEvent) return@addEventListener
            val state = event.state?.unsafeCast<JsObject>()
            if (state != null) {
                val toPath = state["path"].toString()
                LOG.debug("popstate navigated to $toPath")
                pathMut.value = toPath.removePrefix(basePath)
                hashMut.value = state["hash"]?.toString() ?: ""
            } else {
                LOG.debug("popstate event without state", event)
                pathMut.value = window.location.pathname.removePrefix(basePath)
                hashMut.value = window.location.hash
            }
        }
    }

    /**
     * Navigate to the given [path].
     */
    fun navigateTo(path: String, hash: String = "") {
        val fullPath = if (path.startsWith("/")) {
            basePath + path
        } else {
            "$basePath/$path"
        }
        LOG.debug("programmatic navigation to $fullPath")
        navigateToFullPath(fullPath, hash)
    }

    private fun navigateToFullPath(path: String, hash: String) {
        val state = mapOf("path" to path, "hash" to hash).toJsAny()
        window.history.pushState(state, "", path + hash)
        window.location.hash = hash
        pathMut.value = path.removePrefix(basePath)
        hashMut.value = hash
    }

    private fun onNavigate(event: Event, target: EventTarget?, href: String) {
        if (href.startsWith(window.location.origin)) {
            val url = URL(href)
            val targetPath = url.pathname
            event.preventDefault()
            if ("$basePath/${path.value}" != targetPath) {
                LOG.debug("onclick navigation to $targetPath")
                navigateToFullPath(targetPath, url.hash)
            }
        }
    }
}
