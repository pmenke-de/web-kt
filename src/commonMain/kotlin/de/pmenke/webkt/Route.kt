package de.pmenke.webkt

import kotlin.apply
import kotlin.collections.forEach
import kotlin.collections.isNotEmpty
import kotlin.collections.plus
import kotlin.collections.set
import kotlin.collections.zip
import kotlin.let
import kotlin.text.endsWith
import kotlin.text.split
import kotlin.text.startsWith
import kotlin.text.trim
import kotlin.text.trimStart
import kotlin.to

/**
 * A simple routing tree, which can match paths with parameters and tags.
 * Built using a DSL from the package-level `route` method.
 *
 * @param T The type of the result produced when a route is selected.
 */
class Route<T: Any> private constructor(private val routeSegments: List<String>) {
    constructor(path: String) : this(path.trimStart('/').split('/'))
    internal constructor() : this(emptyList())
    private val children = mutableListOf<Route<T>>()
    private var onSelect: ((params: Map<String, String>, tags: Set<String>) -> T)? = null
    private var tags = mutableSetOf<String>()

    /**
     * Adds a child route with the given [path] and initializes it using [init].
     * Prefer to use the operator-invoke syntax instead:
     * ```kotlin
     * "/path" { ... }
     * ```
     */
    fun route(path: String, init: Route<T>.() -> Unit): Route<T> {
        val child = Route<T>(path).apply(init)
        children.add(child)
        return child
    }

    /**
     * Adds a child route and initializes it using [init].
     */
    operator fun String.invoke(init: Route<T>.() -> Unit) = route(this, init)

    /**
     * Sets the [resultSelector] function to be called when this route is selected.
     * The function receives the extracted path parameters and the accumulated tags.
     */
    fun onSelect(resultSelector: (params: Map<String, String>, tags: Set<String>) -> T) {
        onSelect = resultSelector
    }

    /**
     * Adds a [tag] to this route, which will be accumulated and passed to the [onSelect] function.
     */
    fun tag(tag: String) {
        tags.add(tag)
    }

    /**
     * Attempts to enter the routing tree with the given [path].
     * If a matching route is found, the associated [onSelect] function is called and its result returned.
     * If no matching route is found, `null` is returned.
     */
    fun enter(path: String): T? {
        val segments = path.trimStart('/').split('/')
        val params = mutableMapOf<String, String>()
        return enter(segments, params, tags)
    }

    private fun enter(segments: List<String>, params: MutableMap<String, String>, tags: Set<String>): T? {
        if (match(segments, params)) {
            val remainingSegments = segments.subList(routeSegments.size, segments.size)
            if (remainingSegments.isNotEmpty()) {
                for (child in children) {
                    child.enter(remainingSegments, params, tags + child.tags)?.let { return it }
                }
                return null
            } else {
                return onSelect?.invoke(params, tags)
            }
        } else {
            return null
        }
    }

    private fun match(pathSegments: List<String>, params: MutableMap<String, String>): Boolean {
        if (pathSegments.size < routeSegments.size) return false
        val newParams = mutableListOf<Pair<String, String>>()
        for ((pathSegment, routeSegment) in pathSegments.zip(routeSegments)) {
            when {
                routeSegment.startsWith("{") && routeSegment.endsWith("}") -> {
                    val paramName = routeSegment.trim('{', '}')
                    newParams.add(paramName to pathSegment)
                }
                routeSegment != pathSegment -> return false
            }
        }
        newParams.forEach { (name, value) -> params[name] = value }
        return true
    }
}

/**
 * Creates a root [Route] and initializes it using [init].
 */
fun <T: Any> route(init: Route<T>.() -> Unit): Route<T> {
    return Route<T>().apply(init)
}