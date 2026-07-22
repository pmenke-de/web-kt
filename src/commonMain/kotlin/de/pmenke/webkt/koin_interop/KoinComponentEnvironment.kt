package de.pmenke.webkt.koin_interop

import de.pmenke.webkt.Component
import de.pmenke.webkt.ComponentEnvironment
import de.pmenke.webkt.RenderEnvironment
import de.pmenke.webkt.ResourceLifetime
import de.pmenke.webkt.js_interop.WeakReference
import kotlinx.coroutines.CoroutineScope
import org.koin.core.scope.Scope
import org.koin.core.scope.ScopeCallback

/**
 * Adapts a caller-owned Koin [scope] to WebKt's DI-neutral [ComponentEnvironment].
 *
 * Components inherit this environment without receiving a Koin scope in their constructors.
 * Every render still receives a fresh child-resolution scope through [KoinRenderEnvironment].
 */
class KoinComponentEnvironment(val scope: Scope) : ComponentEnvironment {
    override fun attachComponent(component: Component, lifetime: ResourceLifetime) {
        val lifetimeRef = WeakReference(lifetime)
        scope.registerCallback(object : ScopeCallback {
            override fun onScopeClose(scope: Scope) {
                lifetimeRef.deref()?.close()
            }
        })
    }

    override fun createRenderEnvironment(
        component: Component,
        lifetime: ResourceLifetime,
    ): RenderEnvironment = KoinRenderEnvironment(component, lifetime, scope)
}

/** Koin resource owned by one component render attempt. */
class KoinRenderEnvironment internal constructor(
    component: Component,
    lifetime: ResourceLifetime,
    parentScope: Scope,
) : RenderEnvironment {
    /** Fresh scope used to resolve children belonging to this render. */
    val scope: Scope = ComponentScope.create(
        component,
        component.finalizationCanary,
        parentScope = parentScope,
        koin = parentScope.getKoin(),
    ).scope

    init {
        try {
            scope.declare(lifetime, secondaryTypes = listOf(CoroutineScope::class))
            val lifetimeRef = WeakReference(lifetime)
            scope.registerCallback(object : ScopeCallback {
                override fun onScopeClose(scope: Scope) {
                    lifetimeRef.deref()?.close()
                }
            })
        } catch (exception: Throwable) {
            try {
                scope.close()
            } catch (cleanupFailure: Throwable) {
                exception.addSuppressed(cleanupFailure)
            }
            throw exception
        }
    }

    override fun close() = scope.close()
}
