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

    /**
     * The current path, starting with a '/' and without the base path (if any).
     * Updates on browser navigation (a-click, back/forward buttons) as well as programmatic navigation via [navigateTo].
     */
    val path get() = pathMut.asStateFlow()

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
            val state = (event as PopStateEvent).state?.unsafeCast<JsObject>()
            if (state != null) {
                val toPath = state["path"].toString()
                LOG.debug("popstate navigated to $toPath")
                pathMut.value = toPath.removePrefix(basePath)
            } else {
                LOG.warn("popstate event without state", event)
            }
        }
    }

    /**
     * Navigate to the given [path].
     */
    fun navigateTo(path: String) {
        val fullPath = if (path.startsWith("/")) {
            basePath + path
        } else {
            "$basePath/$path"
        }
        LOG.debug("programmatic navigation to $fullPath")
        navigateToFullPath(fullPath)
    }

    private fun navigateToFullPath(path: String) {
        val state = mapOf("path" to path).toJsAny()
        window.history.pushState(state, "", path)
        pathMut.value = path.removePrefix(basePath)
    }

    private fun onNavigate(event: Event, target: EventTarget?, href: String) {
        if (href.startsWith(window.location.origin)) {
            val targetPath = href.substring(window.location.origin.length)
            event.preventDefault()
            if ("$basePath/${path.value}" != targetPath) {
                LOG.debug("onclick navigation to $targetPath")
                navigateToFullPath(targetPath)
            }
        }
    }
}
