package de.pmenke.webkt

/**
 * A simple routing tree, which can match paths with parameters and tags.
 * Built using a DSL from the package-level `route` method.
 *
 * @param T The type of the result produced when a route is selected.
 */
class Route<T : Any> private constructor(private val routeSegments: List<String>) {
    constructor(path: String) : this(path.toRouteSegments())
    internal constructor() : this(emptyList())
    private val children = mutableListOf<Route<T>>()
    private var onSelect: ((params: Map<String, String>, tags: Set<String>) -> T)? = null
    private val tags = mutableSetOf<String>()

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
        return enter(path.toRouteSegments(), emptyMap(), tags)
    }

    private fun enter(segments: List<String>, inheritedParams: Map<String, String>, tags: Set<String>): T? {
        val matchedParams = match(segments) ?: return null
        val params = inheritedParams + matchedParams
        val remainingSegments = segments.drop(routeSegments.size)

        if (remainingSegments.isNotEmpty()) {
            for (child in children) {
                child.enter(remainingSegments, params, tags + child.tags)?.let { return it }
            }
            return null
        }

        return onSelect?.invoke(params, tags)
    }

    private fun match(pathSegments: List<String>): Map<String, String>? {
        if (pathSegments.size < routeSegments.size) return null
        val params = mutableMapOf<String, String>()
        for ((pathSegment, routeSegment) in pathSegments.zip(routeSegments)) {
            when {
                routeSegment.isParameter() -> {
                    params[routeSegment.substring(1, routeSegment.lastIndex)] = pathSegment
                }
                routeSegment != pathSegment -> return null
            }
        }
        return params
    }
}

/**
 * Creates a root [Route] and initializes it using [init].
 */
fun <T : Any> route(init: Route<T>.() -> Unit): Route<T> {
    return Route<T>().apply(init)
}

private fun String.toRouteSegments(): List<String> = trimStart('/').split('/').also { segments ->
    segments.filter { it.startsWith('{') || it.endsWith('}') }.forEach { segment ->
        require(segment.isParameter()) { "Invalid route parameter segment '$segment'" }
    }
}

private fun String.isParameter(): Boolean =
    length > 2 && startsWith('{') && endsWith('}') && substring(1, lastIndex).none { it == '{' || it == '}' }
