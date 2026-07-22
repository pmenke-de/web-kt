package de.pmenke.webkt.lifecycle

import de.pmenke.webkt.Component
import de.pmenke.webkt.ComponentEnvironment
import de.pmenke.webkt.RenderEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Internal owner for everything created during one successful component render.
 *
 * The coroutine lifetime and cleanup order belong to the component kernel. The component's
 * [ComponentEnvironment] may add one integration-specific [RenderEnvironment], but the kernel
 * remains its owner and closes it together with all other render resources.
 */
internal class RenderLifetime(
    component: Component,
    finalizationCanary: JsAny,
    componentEnvironment: ComponentEnvironment,
) : AutoCloseable {
    private val lifetime = Lifetime(
        Dispatchers.Default,
        cancellationMessage = "Render of component '${component.id}' closed",
        finalizationCanary = finalizationCanary,
    )
    /** Coroutine scope cancelled when this render is replaced. */
    val coroutineScope: CoroutineScope = lifetime

    /** Integration-specific context exposed while this render is being built. */
    val environment: RenderEnvironment

    init {
        environment = try {
            componentEnvironment.createRenderEnvironment(component, lifetime)
                .also { resource -> lifetime.onClose(resource::close) }
        } catch (exception: Throwable) {
            try {
                lifetime.close()
            } catch (cleanupFailure: Throwable) {
                exception.addSuppressed(cleanupFailure)
            }
            throw exception
        }
    }

    /** Makes [component] a child resource of this render. */
    fun own(component: Component): AutoCloseable =
        lifetime.onCloseRemovable(component::close)

    override fun close() = lifetime.close()
}
