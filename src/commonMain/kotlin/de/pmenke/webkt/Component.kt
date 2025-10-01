package de.pmenke.webkt

import de.pmenke.webkt.dom_interop.DomUtil.removeAllChildren
import de.pmenke.webkt.js_interop.JsObject
import de.pmenke.webkt.js_interop.WeakReference
import de.pmenke.webkt.log.Logger
import de.pmenke.webkt.log.LoggingAspect
import de.pmenke.webkt.util.AttributeStore
import de.pmenke.webkt.util.CallbackKey
import de.pmenke.webkt.util.Callbacks
import de.pmenke.webkt.util.HierarchicalAttributeStore
import de.pmenke.webkt.util.IdGenerator
import de.pmenke.webkt.util.SequenceUtil.firstInstance
import js.memory.FinalizationRegistry
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.html.HTMLTag
import kotlinx.html.TagConsumer
import kotlinx.html.dom.append
import kotlinx.html.visitAndFinalize
import org.koin.core.component.KoinScopeComponent
import org.koin.core.component.createScope
import org.koin.core.scope.Scope
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private val LOG = Logger("de.pmenke.webkt.Component")

@OptIn(ExperimentalAtomicApi::class)
private val scopeIdGen = AtomicInt(0).let { gen -> { gen.fetchAndAdd(1) } }

/**
 * Components are the core building block of a webkt application.
 * They encapsulate a piece of UI, defined by their [renderContents] function.
 *
 * Each component is represented in the DOM by its own element, of type [tagName] - which thus should be sth. like `app-$componentName`.
 *
 * Components can have child-components, which are rendered within the parent's [renderContents] function.
 * Thus, they form a tree-hierarchy which mirrors the DOM-tree.
 *
 * Components can be stateful, i.e. they can hold data which influences their rendering.
 * When the state changes, the component can ask to be re-rendered via [requestUpdate].
 *
 * Components implement the [KoinScopeComponent] interface, thus they can use dependency injection to obtain their dependencies and child components.
 * Dependencies (like services, repositories, ...) generally should be [singletons][org.koin.core.module.Module.single],
 * while child-components should be [factory][org.koin.core.module.Module.factory] definitions, as the same component-class
 * can be used multiple times in the component-hierarchy.
 *
 * Each component is part of a koin [Scope], which can be used to define dependencies with a lifecycle tied to the component's lifecycle.
 * By default, a component inherits the scope of its parent component. But it can decide to have its own child-scope, by setting [createScope] to true.
 * This is useful, if the component wants to have dependencies with a lifecycle tied to its own lifecycle, like a [ComponentCoroutineScope][de.pmenke.webkt.util.ComponentCoroutineScope],
 * that should be cancelled, when the component is destroyed.
 * Child scopes aren't linked to their parent scopes, i.e. dependencies in the parent scope aren't automatically available in the child scope -
 * but dependencies from the root-scope are. If you want to access definitions from the parent's scope explicitly, you can do so via `this.parent.scope`.
 *
 * Components can access their parent component via the [parent] property (Note: The "root" component is its own parent!).
 * Through helpers like [de.pmenke.webkt.util.ComponentUtil.parents] and [de.pmenke.webkt.util.SequenceUtil.firstInstance]
 * it is easy to walk up the component-hierarchy and find a specific parent-component.
 *
 * For a component-type independent way to pass values from parent to child components, [Component] contains an [AttributeStore],
 * which supports [hierarchical lookup][HierarchicalAttributeStore] through the component-hierarchy.
 *
 * Components also contain a [Callbacks] registry, which can be used to subscribe to lifecycle-events of the component,
 * which also can be used for custom events within component implementations.
 */
abstract class Component(
    parent: Component?,
    private val tagName: String,
    private val initialAttributes: Map<String, String> = emptyMap(),
    createScope: Boolean = false,
) : KoinScopeComponent {
    // probably unique id for this component instance.
    // for now just intended for log-statements.
    private val id = "$tagName-${IdGenerator.next}"

    // inherit scope from parent component, unless createScope is true, or we're the root-component
    override val scope: Scope =
        parent?.scope?.takeUnless { createScope } ?: createScope("ComponentScope-${scopeIdGen()}", this)

    /**
     * The parent component of this component.
     *
     * A "root" component is its own parent.
     * This trades the necessity of null-checks in all "normal" components for a recursion-check in "special" components,
     * which want to walk up the component-hierarchy or want to check, if they are the root-component.
     */
    val parent: Component = parent ?: this

    /**
     * A store for attributes, which can be used to pass values from parent to child components.
     */
    private val componentContext: HierarchicalAttributeStore = HierarchicalAttributeStore(parent?.componentContext)

    // reference to the result of the last render
    private var element: HTMLElement? = null
    val currentElement get() = element
    // remember if we have an animationFrame-request pending, to avoid concurrent requests
    private var animationRequest: Int? = null

    protected val callbacks = Callbacks()

    // some opaque value, used as a reference for the finalization-registry
    private val jsFinalizeToken = JsObject()
    // remember when [destroy] was called, to avoid double-calls from finalization and explicit destroy
    private var destroyCalled = false

    init {
        if (parent != null) {
            // if our parent gets destroyed, we also must destroy ourselves.
            // we use only a weak-reference from the parent to us, to avoid memory-leaks when our instance is forgotten.
            val weakThis = WeakReference(this)
            parent.callbacks.subscribe(LifecycleCallbacks.Destroy) {
                weakThis.deref()?.destroy("parent")
            }.let { handle ->
                // we also remove the subscription, when we get destroyed ourselves, in case our parent lives longer than we do.
                // so that we don't leak the weak-reference to us in the parent's callback-list (building giant "empty" callback lists).
                callbacks.subscribe(LifecycleCallbacks.Destroy) { handle.unsubscribe() }
            }
        }
        // NOTE: we don't `scope.linkTo(parent.scope)`, as we don't want to
        //       resolve components from parent scopes automatically, but only from the root scope.
        //       if an implementations wants to resolve from the parent scope,
        //       it can do so via `this.parent.scope`, `this.parent.get<SomeComponent>()` etc.
        LOG.debug { "[$id] component-init in scope ${scope.id}" }
    }

    /**
     * renders the contents of this component into the element created for this component
     * (don't render the [tagName] element in the implementation).
     */
    protected abstract fun TagConsumer<Element>.renderContents()

    /**
     * renders this component into the given [TagConsumer], which usually is the receiver of [renderContents] of the parent component.
     */
    fun renderTo(consumer: TagConsumer<Element>): HTMLElement {
        LOG.debug(LoggingAspect.RENDERING) { "[$id] renderTo" }
        val tag = HTMLTag(tagName, consumer, initialAttributes, inlineTag = false, emptyTag = false)
        val element = tag.visitAndFinalize(consumer) { consumer.renderContents() } as HTMLElement
        // back-reference to us, so we can find our component from the DOM element.
        // and more importantly, keep us from being garbage collected, as long as the element is in the DOM.
        element.componentKt = this
        // register for finalization, so we get destroyed, when the element is removed from the DOM and garbage collected.
        if (this.element != null) {
            componentFinalizationRegistry.unregister(jsFinalizeToken)
        }
        componentFinalizationRegistry.register(element, this.toJsReference(), jsFinalizeToken)
        this.element = element
        callbacks.notify(LifecycleCallbacks.AfterRender)
        return element
    }

    /**
     * request that this component re-renders its contents on the next animation frame.
     * Multiple calls to this function before the next animation frame only result in a single re-render.
     * The rendering runs asynchronously, so the function returns immediately.
     */
    fun requestUpdate() {
        if (animationRequest == null) {
            animationRequest = window.requestAnimationFrame {
                animationRequest = null
                try {
                    updateContents()
                } catch (e: Throwable) {
                    LOG.error(LoggingAspect.RENDERING) {
                        "[$id] uncaught exception during rendering of [$this]: ${e.stackTraceToString()}"
                    }
                }
            }
        }
    }

    /**
     * re-renders the contents of this component immediately.
     * This function is called as a result of calling [requestUpdate] on the next animation frame.
     */
    protected open fun updateContents() {
        val e = element ?: return
        LOG.debug(LoggingAspect.RENDERING, element) { "[$id] updateContents" }
        e.removeAllChildren()
        e.append {
            renderContents()
            try {
                finalize()
            } catch (e: IllegalStateException) {
                // finalize breaks, if nothing was rendered
                if (e.message != "We can't finalize as there was no tags") throw e
            }
        }
        callbacks.notify(LifecycleCallbacks.AfterRender)
    }

    /**
     * Destroys this component, calling all registered destroy-callbacks and closing its koin scope (if it has its own).
     * This method's primary task is to release resources and avoid resource-leaks.
     * This won't remove the component's element from the DOM etc.
     *
     * Thus, it's normally not to be called from user-code, but rather from the finalization-registry or the parent-component.
     */
    fun destroy(source: String = "user-code") {
        LOG.debug(LoggingAspect.LIFECYCLE, element) { "[$id] component-destroy($destroyCalled) called from $source" }
        if (destroyCalled) return
        destroyCalled = true
        callbacks.notify(LifecycleCallbacks.Destroy) { ex ->
            LOG.error(LoggingAspect.LIFECYCLE, "[$id] exception in Component.destroy of", element, ex.stackTraceToString())
        }
        componentFinalizationRegistry.unregister(jsFinalizeToken)
        if (scope !== parent.scope) {
            scope.close()
        }
    }

    // normally extension-functions, but double-receiver extension-functions aren't syntactically possible

    /**
     * Declare and render an inline child-component, that is based on a [Flow] of values.
     * The component will be re-rendered, whenever the flow emits a new value.
     */
    fun <T> TagConsumer<Element>.inlineFlowComponent(
        tagName: String,
        flow: Flow<T>,
        coroutineScope: CoroutineScope,
        initialValue: T,
        classes: String = "",
        renderBlock: ComponentReceiver.(T) -> Unit
    ) = inlineFlowComponent(this@Component, tagName, flow, coroutineScope, initialValue, classes, renderBlock)

    /**
     * Declare and render an inline child-component, that is based on a [Flow] of values.
     * The component will be re-rendered, whenever the flow emits a new value.
     */
    fun <T> TagConsumer<Element>.inlineFlowComponent(
        tagName: String,
        flow: StateFlow<T>,
        coroutineScope: CoroutineScope,
        classes: String = "",
        renderBlock: ComponentReceiver.(T) -> Unit
    ) = inlineFlowComponent(this@Component, tagName, flow, coroutineScope, classes, renderBlock)

    companion object {
        object LifecycleCallbacks {
            /**
             * Fired after the component was rendered or re-rendered and applied to the DOM.
             * Intended use is to perform DOM operations, that aren't possible while the component is being rendered.
             */
            val AfterRender = CallbackKey("afterRender")

            /**
             * Fired when the component is being destroyed, either because its parent is being destroyed,
             * or because the component's element was removed from the DOM and garbage collected.
             * Intended use is to release resources, like cancelling coroutines, closing websockets, ...
             */
            val Destroy = CallbackKey("destroy")
        }
    }
}

private val componentFinalizationRegistry = FinalizationRegistry<JsReference<Component>> {
    it.get().destroy("finalization")
}

/**
 * An inline version of [Component], which allows to create simple / stateless child-components
 * from a [Component.renderContents] call without having to create a separate class for it.
 */
internal class InlineComponent(
    parent: Component?,
    tagName: String,
    initialAttributes: Map<String, String>,
    createScope: Boolean = false,
    private val renderBlock: ComponentReceiver.() -> Unit
) : Component(parent, tagName, initialAttributes, createScope) {
    override fun TagConsumer<Element>.renderContents() {
        renderBlock(object : ComponentReceiver, TagConsumer<Element> by this@renderContents {
            override val component: InlineComponent = this@InlineComponent
        })
    }
}

/**
 * combination of [Component] and [TagConsumer], which is the receiver of the [renderBlock] of an [InlineComponent].
 * It allows access to the [component] itself, e.g. to call [Component.requestUpdate].
 */
@InlineComponentDSL
interface ComponentReceiver : TagConsumer<Element> {
    val component: Component
}

/**
 * Declare and render an inline child-component, that is based on a [Flow] of values.
 * The component will be re-rendered, whenever the flow emits a new value.
 */
fun <T> TagConsumer<Element>.inlineFlowComponent(
    parent: Component?,
    tagName: String,
    flow: Flow<T>,
    coroutineScope: CoroutineScope,
    initialValue: T,
    classes: String = "",
    renderBlock: ComponentReceiver.(T) -> Unit) {
    var currentValue: T = initialValue
    val component = InlineComponent(parent, tagName, classes.toInitialAttributes()) {
        renderBlock(currentValue)
    }
    flow.onEach {
        value -> currentValue = value
        component.requestUpdate()
    }.launchIn(coroutineScope)
    component.renderTo(this)
}

/**
 * Declare and render an inline child-component, that is based on a [Flow] of values.
 * The component will be re-rendered, whenever the flow emits a new value.
 */
fun <T> TagConsumer<Element>.inlineFlowComponent(
    parent: Component?,
    tagName: String,
    flow: StateFlow<T>,
    coroutineScope: CoroutineScope,
    classes: String = "",
    renderBlock: ComponentReceiver.(T) -> Unit) {
    val component = InlineComponent(parent, tagName, classes.toInitialAttributes()) {
        renderBlock(flow.value)
    }
    flow.onEach { component.requestUpdate() }.launchIn(coroutineScope)
    component.renderTo(this)
}

@Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE", "UNCHECKED_CAST", "CAST_NEVER_SUCCEEDS")
var HTMLElement.componentKt: Component?
    get() = ((this as JsObject)["component_kt"] as JsReference<Component>?)?.get()
    set(value) {
        (this as JsObject)["component_kt"] = value?.toJsReference()
    }

/**
 * DSL Marker annotation for [ComponentReceiver].
 * Disallows unexpected / unwanted implicit access to koin-/coroutine-scopes of outer components,
 * when nesting inline components.
 */
@DslMarker
annotation class InlineComponentDSL

/**
 * convenience function to create an initial attributes map with just the css classes of component
 */
private fun String.toInitialAttributes(): Map<String, String> {
    return if (this.isNotEmpty()) {
        mapOf("class" to this)
    } else {
        emptyMap()
    }
}