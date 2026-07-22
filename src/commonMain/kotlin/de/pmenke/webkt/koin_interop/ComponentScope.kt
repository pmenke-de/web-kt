package de.pmenke.webkt.koin_interop

import de.pmenke.webkt.Component
import de.pmenke.webkt.js_interop.JsObject
import de.pmenke.webkt.js_interop.WeakReference
import de.pmenke.webkt.log.Logger
import de.pmenke.webkt.log.LoggingAspect
import de.pmenke.webkt.util.IdGenerator
import js.memory.FinalizationRegistry
import org.koin.core.Koin
import org.koin.core.scope.Scope
import org.koin.core.scope.ScopeCallback

private val LOG = Logger("de.pmenke.webkt.koin_interop.ComponentScope")

/**
 * Owns the short-lived Koin scope used for one component render.
 *
 * The scope closes explicitly on the next render and defensively through a JavaScript
 * finalization registry if the owning component becomes unreachable first.
 */
internal class ComponentScope private constructor(
    component: Component,
) {
    private val componentRef = WeakReference(component)

    /** The Koin scope exposed to children created during the render. */
    lateinit var scope: Scope
        private set

    /** Owning component while it remains reachable. */
    val component: Component
        get() = componentRef.deref()
            // Note: As this should normally only ever be accessed from the scope's defining component and its
            //       descendants (initialization-) code, this case should never happen, as descendant components
            //       have a strong reference to their parent, keeping it from being garbage collected.
            ?: error("ComponentScope's component was garbage collected")

    private fun initialize(scope: Scope, finalizationCanary: JsAny) {
        this.scope = scope
        // if we created our own scope, we need to close it, when this component gets garbage collected,
        // as scopes are kept alive by koin's internal scope-registry forever (until they're closed).
        val unregisterToken = JsObject()
        var finalizerRegistered = false
        try {
            componentScopeFinalizationRegistry.register(finalizationCanary, scope.toJsReference(), unregisterToken)
            finalizerRegistered = true
            scope.registerCallback(object : ScopeCallback {
                override fun onScopeClose(scope: Scope) {
                    componentScopeFinalizationRegistry.unregister(unregisterToken)
                }
            })
        } catch (exception: Throwable) {
            if (finalizerRegistered) {
                try {
                    componentScopeFinalizationRegistry.unregister(unregisterToken)
                } catch (cleanupFailure: Throwable) {
                    exception.addSuppressed(cleanupFailure)
                }
            }
            throw exception
        }
    }

    companion object {
        /** Creates and fully initializes a render scope, rolling back every partial step on failure. */
        internal fun create(
            component: Component,
            finalizationCanary: JsAny,
            koin: Koin,
            parentScope: Scope,
        ): ComponentScope {
            val owner = ComponentScope(component)
            var createdScope: Scope? = null
            try {
                createdScope = koin.createScope<ComponentScope>(
                    "ComponentScope-${component.id}-${IdGenerator.next}",
                    owner,
                )
                createdScope.linkTo(parentScope)
                ComponentScopeHooks.afterScopeLinked(createdScope)
                owner.initialize(createdScope, finalizationCanary)
                return owner
            } catch (exception: Throwable) {
                createdScope?.let { scope ->
                    try {
                        if (!scope.closed) scope.close()
                    } catch (cleanupFailure: Throwable) {
                        exception.addSuppressed(cleanupFailure)
                    }
                }
                throw exception
            }
        }
    }
}

/** Failure-injection seam for render-scope construction rollback tests. */
internal object ComponentScopeHooks {
    var afterScopeLinked: (Scope) -> Unit = {}

    fun reset() {
        afterScopeLinked = {}
    }
}

private val componentScopeFinalizationRegistry = FinalizationRegistry<JsReference<Scope>> {
    LOG.debug(aspect = LoggingAspect.LIFECYCLE) {
        "Component instance was garbage collected, closing its scope ${it.get().id}"
    }
    it.get().close()
}
