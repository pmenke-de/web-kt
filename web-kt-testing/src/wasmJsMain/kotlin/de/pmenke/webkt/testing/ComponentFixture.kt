package de.pmenke.webkt.testing

import de.pmenke.webkt.Component
import de.pmenke.webkt.ComponentEnvironment
import de.pmenke.webkt.RenderReceiver
import de.pmenke.webkt.constructComponent
import de.pmenke.webkt.util.CallbackHandle
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.html.dom.createTree
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@JsFun("(type) => new Event(type, { bubbles: true, cancelable: true })")
private external fun bubblingEvent(type: String): Event

/**
 * A constructed WebKt component tree rendered into an isolated DOM container and attached to the
 * test document.
 *
 * The fixture owns [component]'s root tree. Closing it first closes that tree, then removes
 * [container] from the DOM. Queries are scoped to the container and never inspect another fixture.
 */
class ComponentFixture<T : Component> internal constructor(
    val component: T,
    private val owningRoot: Component,
    val container: HTMLElement,
) : AutoCloseable {
    private var closed = false

    /** The component's current root element. */
    val element: HTMLElement
        get() = checkNotNull(component.currentElement) {
            "Component '${component.id}' is not currently rendered"
        }

    /**
     * Finds a descendant of this fixture.
     *
     * The isolated container is the query scope, so the selector may match the component root too.
     */
    fun query(selector: String): Element =
        checkNotNull(container.querySelector(selector)) {
            "No element matched selector '$selector' in component fixture '${component.id}'"
        }

    /** Finds and type-checks a descendant of this fixture. */
    inline fun <reified E : Element> queryAs(selector: String): E {
        val match = query(selector)
        return match as? E
            ?: error(
                "Element matching selector '$selector' was ${match::class.simpleName}, " +
                    "not ${E::class.simpleName}",
            )
    }

    /** Activates a real HTML element through the browser's click behavior. */
    fun click(selector: String) {
        queryAs<HTMLElement>(selector).click()
    }

    /**
     * Updates a text-capable form control and dispatches its normal browser event.
     *
     * Inputs and text areas dispatch `input`; selects dispatch `change`.
     */
    fun input(selector: String, value: String) {
        when (val control = query(selector)) {
            is HTMLInputElement -> {
                control.value = value
                control.dispatchEvent(bubblingEvent("input"))
            }

            is HTMLTextAreaElement -> {
                control.value = value
                control.dispatchEvent(bubblingEvent("input"))
            }

            is HTMLSelectElement -> {
                control.value = value
                control.dispatchEvent(bubblingEvent("change"))
            }

            else -> error(
                "Element matching selector '$selector' is not an input, select, or textarea",
            )
        }
    }

    /** Dispatches an event supplied by the caller to a fixture-scoped element. */
    fun dispatch(selector: String, event: Event): Boolean = query(selector).dispatchEvent(event)

    /**
     * Subscribes to this component's next completed render before invoking [action].
     *
     * This is the preferred wait when [action] schedules an update of the component under test.
     */
    suspend fun awaitRender(
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        action: () -> Unit,
    ) {
        withTimeout(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                lateinit var subscription: CallbackHandle
                subscription = component.callbacks.subscribe(Component.Companion.LifecycleCallbacks.AfterRender) {
                    subscription.unsubscribe()
                    if (continuation.isActive) continuation.resume(Unit)
                }
                continuation.invokeOnCancellation { subscription.unsubscribe() }

                try {
                    action()
                } catch (failure: Throwable) {
                    subscription.unsubscribe()
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        }
    }

    /**
     * Waits until [predicate] becomes true, checking once per animation frame.
     *
     * Use this for descendant renders or browser behavior that does not emit the target component's
     * `AfterRender` callback. Cancellation and timeout both cancel the pending animation frame.
     */
    suspend fun awaitUntil(
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        predicate: () -> Boolean,
    ) {
        if (predicate()) return

        withTimeout(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                var animationRequest: Int? = null

                fun checkNextFrame(@Suppress("UNUSED_PARAMETER") timestamp: Double) {
                    animationRequest = null
                    try {
                        if (predicate()) {
                            if (continuation.isActive) continuation.resume(Unit)
                        } else if (continuation.isActive) {
                            animationRequest = window.requestAnimationFrame(::checkNextFrame)
                        }
                    } catch (failure: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }

                continuation.invokeOnCancellation {
                    animationRequest?.let(window::cancelAnimationFrame)
                    animationRequest = null
                }
                animationRequest = window.requestAnimationFrame(::checkNextFrame)
            }
        }
    }

    /**
     * Closes the complete component tree exactly once and always removes the temporary DOM node.
     */
    override fun close() {
        if (closed) return
        closed = true
        try {
            owningRoot.close()
        } finally {
            container.remove()
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Long = 2_000
    }
}

/**
 * Constructs a root component, renders it into an isolated container, and attaches that container
 * as a child of [attachTo].
 */
fun <T : Component> renderRootComponent(
    attachTo: HTMLElement = document.body ?: error("The document has no body"),
    factory: () -> T,
): ComponentFixture<T> = withAttachedFixtureContainer(attachTo) { container ->
    var component: T? = null
    try {
        component = constructComponent(factory)
        val rendered = document.createTree().run {
            component.renderTo(this)
            finalize()
        } as HTMLElement
        container.appendChild(rendered)
        ComponentFixture(component, component, container)
    } catch (failure: Throwable) {
        try {
            component?.close()
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }
}

/**
 * Constructs a synthetic root and its normal non-null-parent child, renders the tree into an
 * isolated container, and attaches that container as a child of [attachTo].
 */
fun <T : Component> renderChildComponent(
    environment: ComponentEnvironment = ComponentEnvironment.Empty,
    attachTo: HTMLElement = document.body ?: error("The document has no body"),
    factory: (parent: Component) -> T,
): ComponentFixture<T> = withAttachedFixtureContainer(attachTo) { container ->
    var root: SyntheticRoot<T>? = null
    try {
        root = constructComponent { SyntheticRoot(environment, factory) }
        val rendered = document.createTree().run {
            root.renderTo(this)
            finalize()
        } as HTMLElement
        container.appendChild(rendered)
        ComponentFixture(root.child, root, container)
    } catch (failure: Throwable) {
        try {
            root?.close()
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }
}

private inline fun <T> withAttachedFixtureContainer(
    attachTo: HTMLElement,
    createFixture: (HTMLElement) -> T,
): T {
    val container = document.createElement("div") as HTMLElement
    container.setAttribute("data-webkt-fixture", "")
    attachTo.appendChild(container)
    try {
        return createFixture(container)
    } catch (failure: Throwable) {
        container.remove()
        throw failure
    }
}

private class SyntheticRoot<T : Component>(
    environment: ComponentEnvironment,
    factory: (Component) -> T,
) : Component(environment, "webkt-test-root") {
    val child: T = factory(this)

    override fun RenderReceiver.renderContents() {
        render(child)
    }
}
