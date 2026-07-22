package de.pmenke.webkt.lifecycle

import de.pmenke.webkt.Component
import de.pmenke.webkt.koin_interop.ComponentScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.koin.core.scope.Scope
import org.koin.core.scope.ScopeCallback

/**
 * Internal owner for everything created during one successful component render.
 *
 * The coroutine lifetime is deliberately separate from the Koin scope: Koin remains the
 * compatibility adapter used to construct children, while this owner defines when render work
 * ends. Closing either this owner or the exposed Koin scope cancels render coroutines.
 */
internal class RenderLifetime(
    component: Component,
    finalizationCanary: JsAny,
) : AutoCloseable {
    private val lifetime = Lifetime(
        Dispatchers.Default,
        cancellationMessage = "Render of component '${component.id}' closed",
        finalizationCanary = finalizationCanary,
    )
    private var closed = false

    /** Koin compatibility scope used to construct children belonging to this render. */
    val scope: Scope = ComponentScope(component, finalizationCanary).scope

    /** Coroutine scope cancelled when this render is replaced or its Koin scope closes. */
    val coroutineScope: CoroutineScope = lifetime

    init {
        scope.declare(coroutineScope, secondaryTypes = listOf(CoroutineScope::class))
        scope.registerCallback(object : ScopeCallback {
            override fun onScopeClose(scope: Scope) {
                lifetime.close()
            }
        })
    }

    override fun close() {
        if (closed) return
        closed = true

        var failure: Throwable? = null
        try {
            lifetime.close()
        } catch (exception: Throwable) {
            failure = exception
        }
        try {
            scope.close()
        } catch (exception: Throwable) {
            if (failure == null) failure = exception else failure.addSuppressed(exception)
        }
        failure?.let { throw it }
    }
}
