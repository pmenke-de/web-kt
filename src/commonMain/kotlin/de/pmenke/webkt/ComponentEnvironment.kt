package de.pmenke.webkt

import kotlinx.coroutines.CoroutineScope

/**
 * Supplies integration resources for components without making the component kernel depend on a
 * dependency-injection framework.
 *
 * A root component receives an environment explicitly. Child components inherit the same
 * environment from their parent. Integrations may observe component creation through
 * [attachComponent] and create one [RenderEnvironment] for every isolated render attempt.
 */
interface ComponentEnvironment {
    /**
     * Attaches integration-specific ownership during the root [component]'s first [Component.renderTo]
     * call, after its initial contents and DOM element have been created.
     *
     * Implementations may arrange for an external owner to close [lifetime]. They must not retain
     * [component] more strongly or longer than that owner requires.
     */
    fun attachComponent(component: Component, lifetime: ResourceLifetime) = Unit

    /** Creates the integration resource owned by one render [lifetime]. */
    fun createRenderEnvironment(component: Component, lifetime: ResourceLifetime): RenderEnvironment =
        EmptyRenderEnvironment

    companion object {
        /** Environment for applications that do not need a framework integration. */
        val Empty: ComponentEnvironment = object : ComponentEnvironment {}
    }
}

/**
 * DI-framework-neutral lifetime contract offered to component-environment integrations.
 *
 * The lifetime owns its coroutine job. Closing it cancels that job before executing registered
 * cleanup actions in reverse registration order.
 */
interface ResourceLifetime : CoroutineScope, AutoCloseable {
    /** Whether closure has started. */
    val isClosed: Boolean

    /** Registers cleanup, or runs it immediately when this lifetime is already closing. */
    fun onClose(cleanup: () -> Unit)
}

/** Integration-specific resource belonging to one render attempt. */
interface RenderEnvironment : AutoCloseable

private object EmptyRenderEnvironment : RenderEnvironment {
    override fun close() = Unit
}
