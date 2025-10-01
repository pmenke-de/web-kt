package de.pmenke.webkt.util

import de.pmenke.webkt.Component
import de.pmenke.webkt.log.Logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.plus
import org.koin.core.scope.Scope
import org.koin.core.scope.ScopeCallback

private val LOG = Logger("de.pmenke.webkt.util.ComponentCoroutineScope")

/**
 * A CoroutineScope that is tied to the lifecycle of a Component's scope.
 * It can be obtained via koin dependency injection / `get<ComponentCoroutineScope>()` within any [Component] instance.
 *
 * When the Component's scope is closed, all coroutines launched in this scope are cancelled.
 *
 * Uncaught exceptions from coroutines launched in this scope are logged with a reference to the component's
 * [dom element][Component.currentElement], to allow for easy identification of the source via browser developer tools.
 */
class ComponentCoroutineScope internal constructor(component: Component)
    : CoroutineScope by (CoroutineScope(Dispatchers.Default) + exceptionHandler(component)) {
        init {
            component.scope.registerCallback(object : ScopeCallback {
                override fun onScopeClose(scope: Scope) {
                    cancel("Component scope closed")
                }
            })
        }
}

private fun exceptionHandler(component: Component) = CoroutineExceptionHandler { _, exception ->
    LOG.error("Unhandled exception in coroutine-scope, owned by",
        component.currentElement,
        exception.stackTraceToString())
}