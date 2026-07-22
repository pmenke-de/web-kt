package de.pmenke.webkt

import de.pmenke.webkt.js_interop.JsObject
import de.pmenke.webkt.util.HierarchicalAttributeStore
import de.pmenke.webkt.util.ObservableValue
import de.pmenke.webkt.util.asObservableValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.html.TagConsumer
import org.koin.core.component.KoinScopeComponent
import org.koin.core.parameter.ParametersDefinition
import org.koin.core.parameter.parametersOf
import org.koin.core.scope.Scope
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

@ComponentDSL
interface RenderReceiver : TagConsumer<Element>, KoinScopeComponent {
    /**
     * The current rendering [Scope] for this component.
     * A new scope is created for each rendering of the component, so that
     * resources and coroutines, created by child-components during rendering,
     * will be closed / cancelled, when the component is re-rendered.
     *
     * This is the Koin compatibility view of the current render lifetime.
     */
    override val scope: Scope

    /**
     * Reference to the current rendering [Component].
     *
     * Primarily used to create child-components with the correct parent reference.
     */
    val component: Component

    /**
     * The [CoroutineScope] owned by the current render lifetime.
     * This coroutine scope is tied to the current rendering [scope], so that
     * coroutines launched in this scope will be cancelled, when the component is re-rendered.
     *
     * Primarily used in [inlineFlowComponent]s, to launch collectors for the given [Flow]s,
     * as they should be cancelled, when the component is re-rendered and the old inline-components are disposed.
     */
    val coroutineScope: CoroutineScope

    /**
     * The [HierarchicalAttributeStore] for the current rendering [Component], which supports hierarchical lookup through the component-hierarchy.
     */
    val componentContext: HierarchicalAttributeStore

    /**
     * Declare and render an inline child-component, that is based on a [Flow] of values.
     * The component will be re-rendered, whenever the flow emits a new value.
     *
     * The component inherits the current [coroutineScope] and [renderingScope][scope].
     *
     * @param tagName The HTML tag name to use for the inline component's root element in the DOM.
     * @param flow The [Flow] of values to base the inline component on.
     * @param initialValue The initial value to use for the inline component before the flow emits its first value.
     * @param classes Optional CSS classes to set on the inline component's element in the DOM.
     * @param renderBlock The rendering block for the inline component, which receives the current value from the flow.
     * This block essentially is the implementation of [Component.renderContents] for the inline component.
     */
    fun <T> inlineFlowComponent(
        tagName: String,
        flow: Flow<T>,
        initialValue: T,
        classes: String = "",
        renderBlock: RenderReceiver.(T) -> Unit): Component {
        var currentValue: T = initialValue
        val component = InlineComponent(component, scope, tagName, classes.toInitialAttributes()) {
            renderBlock(currentValue)
        }
        flow.onEach { value ->
            currentValue = value
            component.requestUpdate()
        }.launchIn(coroutineScope)
        component.renderTo(this)
        return component
    }

    /**
     * Declare and render an inline child-component, that is based on a [Flow] of values.
     * The component will be re-rendered, whenever the flow emits a new value.
     *
     * The component inherits the current [coroutineScope] and [renderingScope][scope].
     *
     * @param tagName The HTML tag name to use for the inline component's root element in the DOM.
     * @param flow The [Flow] of values to base the inline component on.
     * @param classes Optional CSS classes to set on the inline component's element in the DOM.
     * @param renderBlock The rendering block for the inline component, which receives the current value from the flow.
     * This block essentially is the implementation of [Component.renderContents] for the inline component.
     */
    fun <T> inlineFlowComponent(
        tagName: String,
        flow: StateFlow<T>,
        classes: String = "",
        renderBlock: RenderReceiver.(T) -> Unit): Component = inlineFlowComponent(
        tagName = tagName,
        value = flow.asObservableValue(),
        classes = classes,
        renderBlock = renderBlock,
    )

    /**
     * Declares an inline child component driven by a scope-free [ObservableValue].
     *
     * The current value is rendered synchronously. Collection of [ObservableValue.updates] belongs to this
     * receiver's render lifetime and is cancelled when that render is replaced. A first update equal to the
     * synchronously rendered value is skipped; a value that changed before collection began is not lost.
     */
    fun <T> inlineFlowComponent(
        tagName: String,
        value: ObservableValue<T>,
        classes: String = "",
        renderBlock: RenderReceiver.(T) -> Unit): Component {
        var currentValue = value.value
        var initiallyRenderedValue: Any? = currentValue
        var awaitingFirstEmission = true
        val component = InlineComponent(component, scope, tagName, classes.toInitialAttributes()) {
            renderBlock(currentValue)
        }
        value.updates.onEach { update ->
            val isAlreadyRendered = awaitingFirstEmission && initiallyRenderedValue == update
            awaitingFirstEmission = false
            initiallyRenderedValue = null
            currentValue = update
            if (!isAlreadyRendered) component.requestUpdate()
        }
            .launchIn(coroutineScope)
        component.renderTo(this)
        return component
    }
}

/**
 * Specialized version of [Scope.get] which automatically adds the current component and scope as leading parameters,
 * as components are supposed to be created with a reference to their parent component and scope.
 */
inline fun <reified T: Component> RenderReceiver.getComponent(noinline parameters: ParametersDefinition? = null): T {
    return scope.get<T> {
        if (parameters == null) parametersOf(component, scope)
        else parameters().insert(0, component).insert(1, scope)
    }
}

/**
 * Specialized version of [Scope.get] which automatically adds the current component and scope as leading parameters,
 * as components are supposed to be created with a reference to their parent component and scope.
 */
inline fun <reified T: Component> Component.getComponent(noinline parameters: ParametersDefinition? = null): T {
    return scope.get<T> {
        if (parameters == null) parametersOf(this, scope)
        else parameters().insert(0, this).insert(1, scope)
    }
}

/**
 * An inline version of [Component], which allows to create simple / stateless child-components
 * from a [Component.renderContents] call without having to create a separate class for it.
 */
internal class InlineComponent(
    parent: Component,
    scope: Scope,
    tagName: String,
    initialAttributes: Map<String, String>,
    private val renderBlock: RenderReceiver.() -> Unit
) : Component(parent, scope, tagName, initialAttributes) {
    override fun RenderReceiver.renderContents() {
        renderBlock()
    }
}

@Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE", "UNCHECKED_CAST", "CAST_NEVER_SUCCEEDS")
var HTMLElement.componentKt: Component?
    get() = ((this as JsObject)["component_kt"] as JsReference<Component>?)?.get()
    set(value) {
        (this as JsObject)["component_kt"] = value?.toJsReference()
    }

/**
 * DSL Marker annotation for [RenderReceiver].
 * Disallows unexpected / unwanted implicit access to koin-/coroutine-scopes of outer components,
 * when nesting inline components.
 */
@DslMarker
annotation class ComponentDSL

/** Creates an initial-attributes map containing the CSS classes for an inline component. */
private fun String.toInitialAttributes(): Map<String, String> {
    return if (isNotEmpty()) mapOf("class" to this) else emptyMap()
}
