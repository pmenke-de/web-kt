package de.pmenke.webkt

import de.pmenke.webkt.js_interop.JsObject
import de.pmenke.webkt.js_interop.WeakReference
import de.pmenke.webkt.koin_interop.ComponentCoroutineScope
import de.pmenke.webkt.koin_interop.ComponentScope
import de.pmenke.webkt.lifecycle.Lifetime
import de.pmenke.webkt.lifecycle.RenderLifetime
import de.pmenke.webkt.log.Logger
import de.pmenke.webkt.log.LoggingAspect
import de.pmenke.webkt.util.*
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.html.TagConsumer
import kotlinx.html.dom.append
import org.koin.core.component.KoinScopeComponent
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.core.scope.Scope
import org.koin.core.scope.ScopeCallback
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

private val LOG = Logger("de.pmenke.webkt.Component")


/**
 * # Components
 * Components are the core building block of a webkt application.
 * They encapsulate a piece of UI, defined by their [renderContents] function.
 *
 * Each component is represented in the DOM by its own element of type [tagName], normally a custom name such as `app-$componentName`.
 *
 * Components can be stateful, i.e. they can hold data which influences their rendering.
 * When the state changes, the component can ask to be re-rendered via [requestUpdate].
 *
 * ## Scope: Dependency-Injection & Lifecycle
 * Components implement the [KoinScopeComponent] interface. They can use dependency injection to obtain their dependencies and child components.
 * Dependencies (like services, repositories, ...) generally should be [singletons][org.koin.core.module.Module.single],
 * while components should be [factory][org.koin.core.module.Module.factory] definitions, as the same component-class
 * can be used multiple times in the component-hierarchy.
 *
 * Components bind their lifecycle to the given [scope] by listening for the scope's close callback and calling [dispose]
 * in return (which also fires the [LifecycleCallbacks.Dispose] callback) to free any non-automatic resources.
 *
 * ## Child-Components
 * Components can have child-components, which are rendered within the parent's [renderContents] function.
 * Thus, they form a tree-hierarchy which mirrors the DOM-tree.
 *
 * To retrieve child-components in [renderContents] use [getComponent][RenderReceiver.getComponent], which will automatically pass the
 * current component as its parent and the current render's [RenderReceiver.scope] as the child-components scope.
 * Internally each call to [updateContents] or [renderTo] prepares a new render lifetime. It becomes active only
 * after rendering succeeds; the last successful lifetime and its child-components are then disposed automatically.
 * You can also use [getComponent][Component.getComponent] from outside [renderContents] / a [RenderReceiver] and bind
 * them to an instance-field (for example) which will inherit the components scope to the child-component directly
 * (sharing the same lifecycle), which is useful, if you want some child-components to persist state across [renderContents] calls.
 *
 * Components can access their parent component via the nullable [parent] property. A root has no parent.
 * [de.pmenke.webkt.util.ComponentUtil.parents] and
 * [de.pmenke.webkt.util.ComponentUtil.findAncestor] provide safe traversal of the component hierarchy.
 *
 * ## Utilities
 * For a component-type independent way to pass values from parent to child components, [Component] contains an [AttributeStore],
 * which supports [hierarchical lookup][HierarchicalAttributeStore] through the component-hierarchy.
 *
 * Components also contain a [Callbacks] registry, which can be used to subscribe to lifecycle-events of the component,
 * which also can be used for custom events within component implementations.
 *
 * @param parent The parent component of this component. `null` is only valid for the root component of a component tree.
 *
 * When constructing via koin (e.g. [Module.factoryOf]) and instantiating via [getComponent][RenderReceiver.getComponent],
 * the parent component is automatically passed as the first parameter.
 * @param scope The [Scope] to bind this component's lifecycle to.
 * When constructing via koin (e.g. [Module.factoryOf]) and instantiating via [getComponent][RenderReceiver.getComponent],
 * the scope is automatically passed as the second parameter.
 * @param tagName The HTML tag name to use for this component's root element in the DOM.
 * @param initialAttributes Attributes to set on this component's element in the DOM (e.g. `class` or `data-something`).
 */
abstract class Component(
    parent: Component?,
    override val scope: Scope,
    private val tagName: String,
    private val initialAttributes: Map<String, String> = emptyMap(),
) : KoinScopeComponent {

    /** Convenience constructor for a root component, which has no semantic parent. */
    protected constructor(
        scope: Scope,
        tagName: String,
        initialAttributes: Map<String, String> = emptyMap(),
    ) : this(null, scope, tagName, initialAttributes)

    /**
     * A unique identifier for this component instance.
     * Primarily useful for logging and debugging, but also used internally to identify [ComponentScope]s.
     */
    val id = "$tagName-${IdGenerator.next}"

    // An opaque JsAny kept alive only by this instance. Finalization registries use it to close
    // the render Koin scope and cancel any independently reachable component coroutine job.
    private val finalizationCanary = JsObject()

    /** Internal owner for this component's coroutines and deterministic cleanup. */
    private val componentLifetime = Lifetime(
        Dispatchers.Default,
        cancellationMessage = "Component '$id' closed",
        finalizationCanary = finalizationCanary,
    )

    /**
     * A [CoroutineScope] tied to this component's lifecycle.
     *
     * Initialized on first access.
     */
    protected val coroutineScope by lazy { ComponentCoroutineScope(componentLifetime, WeakReference(this)) }

    /**
     * The parent component of this component.
     *
     * Root components have no parent. Child component constructors can continue to require a non-null
     * parent; only root constructors need to select the parentless base constructor.
     */
    val parent: Component? = parent

    /**
     * A store for attributes, which can be used to pass values from parent to child components.
     */
    protected val componentContext: HierarchicalAttributeStore = HierarchicalAttributeStore(parent?.componentContext)

    /**
     * The lifetime of the last successfully rendered child tree.
     *
     * A replacement is first prepared in isolation. A failed attempt closes only its own resources, while a
     * successful attempt becomes current and then closes this previous lifetime.
     */
    private var currentRenderLifetime: RenderLifetime? = null

    // reference to the result of the last render.
    private var element: HTMLElement? = null
    private var disposed = false

    /**
     * A reference to the DOM element representing this component.
     * Can be null, if the component hasn't been rendered yet, or has been disposed.
     *
     * The element may also be recreated, if the component is re-rendered by its parent.
     */
    val currentElement get() = element
    // remember if we have an animationFrame-request pending, to avoid concurrent requests
    private var animationRequest: Int? = null

    val callbacks = Callbacks()

    init {
        LOG.debug { "[$id] component-init in scope ${scope.id} with parent ${parent?.id}" }
        // only take a weak reference to ourselves in the scope-callback, as the scope might outlive us,
        // which would stop garbage collection of this component, if the scope had a strong reference.
        // Finalization handles only cycle-safe fallback work after the component becomes unreachable;
        // deterministic disposal always follows closure of this owning scope/lifetime.
        val weakThis = WeakReference(this)
        // Note: We cannot unregister the callback in case we get disposed before the scope closes.
        //       This will lead to "empty" (`weakThis` being empty) callbacks being called when the scope finally closes.
        //       It should remain small because the parent normally supplies its short-lived render scope.
        //       Performance-problems could arise, if someone uses a long-lived scope for short-lived many components.
        componentLifetime.onClose { weakThis.deref()?.dispose() }
        scope.registerCallback(object : ScopeCallback {
            override fun onScopeClose(scope: Scope) {
                weakThis.deref()?.componentLifetime?.close()
            }
        })
    }

    /**
     * renders the contents of this component into the element created for this component
     * (don't render the [tagName] element in the implementation).
     */
    protected abstract fun RenderReceiver.renderContents()

    /**
     * Handles an exception thrown while building this component's contents.
     *
     * Override this at an application error boundary and render fallback contents into the receiver. Return
     * `true` only when the failure has been handled. The failed update attempt is discarded and its render
     * lifetime is closed before this method runs. Initial render failures are never offered to a boundary: they
     * always clean up and throw synchronously. If this method returns `false` (the default), the original update
     * exception is rethrown. An exception from the fallback is also propagated.
     *
     * A boundary also sees unhandled failures from descendants, because they propagate through the parent's
     * render attempt. During an update, successfully rendered fallback contents are committed normally;
     * otherwise the component retains its last successful contents and render lifetime.
     */
    protected open fun RenderReceiver.renderFailure(exception: Throwable): Boolean = false

    private fun TagConsumer<Element>.renderReceiver(
        renderLifetime: RenderLifetime,
        transaction: RenderTransaction,
    ): RenderReceiver {
        return object : RenderReceiver, TransactionalRenderConsumer, TagConsumer<Element> by this {
            override val scope: Scope = renderLifetime.scope
            override val component: Component = this@Component
            override val coroutineScope: CoroutineScope = renderLifetime.coroutineScope
            override val componentContext: HierarchicalAttributeStore = this@Component.componentContext
            override val renderTransaction: RenderTransaction = transaction
        }
    }

    /** Result of building one complete set of contents away from the live component element. */
    private data class RenderAttempt(
        val contents: HTMLElement,
        val lifetime: RenderLifetime,
        val transactionCheckpoint: Int,
    )

    /**
     * Builds replacement children in a detached staging element.
     *
     * The current render lifetime is deliberately not changed here. A failed attempt owns all children and
     * coroutines that it created and closes them before the failure is propagated or offered to the boundary.
     */
    private fun buildRenderAttempt(
        transaction: RenderTransaction,
        allowFailureBoundary: Boolean,
    ): RenderAttempt {
        fun attempt(block: RenderReceiver.() -> Unit): RenderAttempt {
            val callbackCheckpoint = transaction.checkpoint()
            // A neutral staging element avoids invoking a registered custom-element constructor for `tagName`.
            val contents = kotlinx.browser.document.createElement("div") as HTMLElement
            val lifetime = RenderLifetime(this, finalizationCanary)
            try {
                contents.append {
                    renderReceiver(lifetime, transaction).block()
                    finalizeAllowingEmptyContents()
                }
                return RenderAttempt(contents, lifetime, callbackCheckpoint)
            } catch (exception: Throwable) {
                try {
                    transaction.rollbackTo(callbackCheckpoint)
                } catch (rollbackFailure: Throwable) {
                    exception.addSuppressed(rollbackFailure)
                }
                try {
                    lifetime.close()
                } catch (cleanupFailure: Throwable) {
                    exception.addSuppressed(cleanupFailure)
                }
                throw exception
            }
        }

        return try {
            attempt { renderContents() }
        } catch (failure: Throwable) {
            if (!allowFailureBoundary) throw failure
            try {
                var handled = false
                val fallback = attempt { handled = renderFailure(failure) }
                if (handled) {
                    fallback
                } else {
                    discardRenderAttempt(transaction, fallback, failure)
                    throw failure
                }
            } catch (boundaryFailure: Throwable) {
                if (boundaryFailure !== failure) boundaryFailure.addSuppressed(failure)
                throw boundaryFailure
            }
        }
    }

    private fun discardRenderAttempt(
        transaction: RenderTransaction,
        attempt: RenderAttempt,
        failure: Throwable,
    ) {
        try {
            transaction.rollbackTo(attempt.transactionCheckpoint)
        } catch (rollbackFailure: Throwable) {
            failure.addSuppressed(rollbackFailure)
        }
        try {
            attempt.lifetime.close()
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
        }
    }

    private fun ensureActiveForCommit() {
        check(!disposed) { "Component '$id' was disposed while rendering" }
    }

    /**
     * renders this component into the given [TagConsumer], which usually is the receiver of [renderContents] of the parent component.
     *
     * Note: Use [render] instead of `component.renderTo(this@renderContents)` within [renderContents],
     *       as the former is more concise.
     *
     * During detached construction, a nested component exposes its candidate [currentElement] so established
     * render-time DOM setup remains possible. That state is tentative: if the containing transaction is discarded,
     * its previous element, back-reference, and render lifetime are restored before the failure is propagated.
     */
    fun renderTo(consumer: TagConsumer<Element>): HTMLElement {
        check(!disposed) { "Component '$id' cannot be rendered after its lifecycle scope was closed" }
        LOG.debug(LoggingAspect.RENDERING) { "[$id] renderTo" }
        val inheritedTransaction = (consumer as? TransactionalRenderConsumer)?.renderTransaction
        val transaction = inheritedTransaction ?: RenderTransaction()
        val attempt = buildRenderAttempt(transaction, allowFailureBoundary = false)
        val element = try {
            ensureActiveForCommit()
            val materializeRoot = ComponentRenderHooks.materializeRoot
            // Materialization is the standalone render's DOM commit point.
            ensureActiveForCommit()
            materializeRoot(tagName, consumer, initialAttributes).also { newElement ->
                while (attempt.contents.firstChild != null) {
                    newElement.appendChild(attempt.contents.firstChild!!)
                }
                ensureActiveForCommit()
            }
        } catch (exception: Throwable) {
            discardRenderAttempt(transaction, attempt, exception)
            throw exception
        }
        val oldRenderLifetime = currentRenderLifetime
        val oldElement = this.element
        currentRenderLifetime = attempt.lifetime
        // back-reference to us, so we can find our component from the DOM element
        // and keep us from being garbage collected, as long as the element is reachable.
        this.element?.takeIf { it !== element }?.componentKt = null
        element.componentKt = this
        this.element = element
        transaction.onCommit(
            commit = {
                runAllRenderActions(
                    { oldRenderLifetime?.close() },
                    { callbacks.notify(LifecycleCallbacks.AfterRender) },
                )
            },
            rollback = {
                if (currentRenderLifetime === attempt.lifetime) {
                    currentRenderLifetime = oldRenderLifetime
                    this.element?.takeIf { it !== oldElement }?.componentKt = null
                    oldElement?.componentKt = this
                    this.element = oldElement
                    attempt.lifetime.close()
                }
            },
        )
        if (inheritedTransaction == null) transaction.commit()
        return element
    }

    /**
     * Shorthand for `child.renderTo(this@renderContents)` within [renderContents]
     */
    protected fun TagConsumer<Element>.render(child: Component) {
        child.renderTo(this)
    }

    /**
     * request that this component re-renders its contents on the next animation frame.
     * Multiple calls to this function before the next animation frame only result in a single re-render.
     * The rendering runs asynchronously, so the function returns immediately.
     * An unhandled rendering failure is logged and rethrown from the animation-frame callback so browser error
     * reporting can observe it. Override [renderFailure] at an explicit boundary to render a fallback instead.
     */
    fun requestUpdate() {
        if (!disposed && animationRequest == null) {
            animationRequest = window.requestAnimationFrame {
                animationRequest = null
                try {
                    updateContents()
                } catch (e: Throwable) {
                    LOG.error(LoggingAspect.RENDERING) {
                        "[$id] uncaught exception during rendering of [$this]: ${e.stackTraceToString()}"
                    }
                    throw e
                }
            }
        }
    }

    /**
     * re-renders the contents of this component immediately.
     * This function is called as a result of calling [requestUpdate] on the next animation frame.
     *
     * Replacement contents are built away from [currentElement]. On success, the DOM is replaced in one browser
     * operation before the previous render lifetime is closed. On failure, the attempted lifetime is closed and
     * the last successful DOM and lifetime remain active.
     */
    protected open fun updateContents() {
        val e = currentElement ?: return
        LOG.debug(LoggingAspect.RENDERING, currentElement) { "[$id] updateContents" }
        val transaction = RenderTransaction()
        val attempt = buildRenderAttempt(transaction, allowFailureBoundary = true)
        try {
            ensureActiveForCommit()
            val fragment = e.ownerDocument!!.createDocumentFragment()
            while (attempt.contents.firstChild != null) {
                fragment.appendChild(attempt.contents.firstChild!!)
            }
            ensureActiveForCommit()
            ComponentRenderHooks.replaceChildren(e, fragment)
            ensureActiveForCommit()
        } catch (exception: Throwable) {
            discardRenderAttempt(transaction, attempt, exception)
            throw exception
        }
        val oldRenderLifetime = currentRenderLifetime
        currentRenderLifetime = attempt.lifetime
        transaction.afterCommit {
            runAllRenderActions(
                { oldRenderLifetime?.close() },
                { callbacks.notify(LifecycleCallbacks.AfterRender) },
            )
        }
        transaction.commit()
    }

    /**
     * Disposes this component, firing the [LifecycleCallbacks.Dispose] callback,
     * closing the current render lifetime and removing the references to/from the DOM element,
     * so that detached components can be garbage collected.
     */
    private fun dispose() {
        if (disposed) return
        disposed = true
        LOG.debug { "[$id] dispose" }

        val failures = mutableListOf<Throwable>()
        fun attempt(cleanup: () -> Unit) {
            try {
                cleanup()
            } catch (exception: Throwable) {
                failures += exception
            }
        }

        attempt {
            callbacks.notifyCatching(LifecycleCallbacks.Dispose) { ex ->
                LOG.error { "[$id] uncaught exception during dispose callback: ${ex.stackTraceToString()}" }
            }
        }

        val renderLifetime = currentRenderLifetime
        currentRenderLifetime = null
        attempt { renderLifetime?.close() }

        val pendingAnimationRequest = animationRequest
        animationRequest = null
        attempt { pendingAnimationRequest?.let(window::cancelAnimationFrame) }

        val renderedElement = element
        element = null
        attempt { renderedElement?.componentKt = null }

        attempt { callbacks.clear() }

        failures.firstOrNull()?.let { firstFailure ->
            failures.drop(1).forEach(firstFailure::addSuppressed)
            throw firstFailure
        }
    }

    companion object {
        object LifecycleCallbacks {
            /**
             * Fired after the component's element has been created or its contents have been updated.
             * On the initial render the caller may not have inserted the returned element into the document yet.
             * Intended for DOM operations that cannot be expressed while rendering.
             * Subscriber failures happen after the render has committed and therefore do not roll it back.
             */
            val AfterRender = CallbackKey("afterRender")

            /**
             * Fired when the component is being disposed (its scope is closed).
             * Intended use is to clean up resources held by the component.
             */
            val Dispose = CallbackKey("dispose")
        }
    }
}
