package de.pmenke.webkt.koin_interop

import de.pmenke.webkt.Component
import de.pmenke.webkt.js_interop.JsObject
import de.pmenke.webkt.js_interop.WeakReference
import de.pmenke.webkt.log.Logger
import de.pmenke.webkt.log.LoggingAspect
import js.memory.FinalizationRegistry
import org.koin.core.component.KoinComponent
import org.koin.core.scope.Scope
import org.koin.core.scope.ScopeCallback

private val LOG = Logger("de.pmenke.webkt.koin_interop.ComponentScope")

/**
 * Owns the short-lived Koin scope used for one component render.
 *
 * The scope closes explicitly on the next render and defensively through a JavaScript
 * finalization registry if the owning component becomes unreachable first.
 */
class ComponentScope(component: Component, finalizationCanary: JsAny) : KoinComponent {
    private val componentRef = WeakReference(component)

    /** The Koin scope exposed to children created during the render. */
    val scope: Scope = getKoin().createScope<ComponentScope>("ComponentScope-${component.id}", this)

    /** Owning component while it remains reachable. */
    val component: Component
        get() = componentRef.deref()
            // Note: As this should normally only ever be accessed from the scope's defining component and its
            //       descendants (initialization-) code, this case should never happen, as descendant components
            //       have a strong reference to their parent, keeping it from being garbage collected.
            ?: error("ComponentScope's component was garbage collected")

    init {
        // if we created our own scope, we need to close it, when this component gets garbage collected,
        // as scopes are kept alive by koin's internal scope-registry forever (until they're closed).
        val unregisterToken = JsObject()
        componentScopeFinalizationRegistry.register(finalizationCanary, scope.toJsReference(), unregisterToken)
        scope.registerCallback(object : ScopeCallback {
            override fun onScopeClose(scope: Scope) {
                componentScopeFinalizationRegistry.unregister(unregisterToken)
            }
        })
    }
}

private val componentScopeFinalizationRegistry = FinalizationRegistry<JsReference<Scope>> {
    LOG.debug(aspect = LoggingAspect.LIFECYCLE) {
        "Component instance was garbage collected, closing its scope ${it.get().id}"
    }
    it.get().close()
}
