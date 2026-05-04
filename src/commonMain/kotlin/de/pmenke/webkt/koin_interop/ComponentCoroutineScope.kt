package de.pmenke.webkt.koin_interop

import de.pmenke.webkt.Component
import de.pmenke.webkt.js_interop.WeakReference
import de.pmenke.webkt.log.Logger
import kotlinx.coroutines.*
import org.koin.core.scope.Scope
import org.koin.core.scope.ScopeCallback

private val LOG = Logger("de.pmenke.webkt.koin_interop.ComponentCoroutineScope")

/**
 * A CoroutineScope that is tied to the lifecycle of a [ComponentScope].
 *
 * When the Component's scope is closed, all coroutines launched in this scope are cancelled.
 *
 * Uncaught exceptions from coroutines launched in this scope are logged with a reference to the component's
 * [dom element][Component.currentElement], to allow for easy identification of the source via browser developer tools.
 */
class ComponentCoroutineScope internal constructor(scope: Scope, componentRef: WeakReference<Component>)
    : CoroutineScope by (CoroutineScope(Dispatchers.Default) + SupervisorJob() + exceptionHandler(componentRef)) {
        init {
            scope.registerCallback(object : ScopeCallback {
                override fun onScopeClose(scope: Scope) {
                    cancel("Component scope closed")
                }
            })
        }
}

// Note: We take `ComponentScope` instead of `Component` here to avoid a strong-ref
//       to it, which would inhibit the Component from being garbage collected, because
//       the ComponentCoroutineScope is kept alive by the Scope, which only closes after
//       the Component has been collected.
private fun exceptionHandler(componentRef: WeakReference<Component>) = CoroutineExceptionHandler { _, exception ->
    LOG.error("Unhandled exception in coroutine-scope, owned by",
        componentRef.deref()?.currentElement,
        exception.stackTraceToString())
}