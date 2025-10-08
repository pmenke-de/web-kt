package de.pmenke.webkt.util

import de.pmenke.webkt.log.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.w3c.dom.*
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventTarget
import kotlin.collections.filter
import kotlin.collections.firstOrNull
import kotlin.collections.forEach
import kotlin.js.toJsString
import kotlin.reflect.KMutableProperty0
import kotlin.reflect.KProperty

private val LOG = Logger("de.pmenke.webkt.util.FormUtil")
private val INTERNAL_EVENT_GUARD = "de.pmenke.webkt.util.FormUtil.__internal__".toJsString()

/**
 * Represents a form control value that can be bound to an HTML form element to listen for changes in its value.
 *
 * [valueState] is a [StateFlow] that emits the current value whenever it changes.
 * [value] is the current value and can be read or updated directly.
 *
 * Bind using [bind] to an [EventTarget] (e.g. an [HTMLInputElement]) and a reference to its DOM value property (e.g. [HTMLInputElement.value]).
 */
class ControlValue<T: Any?>(initialValue: T) {
    constructor(eventTarget: EventTarget, property: KMutableProperty0<T>, event: String = "input")
            : this(eventTarget.asInternal(), property, event)

    constructor(eventTarget: EventTargetInternal, property: KMutableProperty0<T>, event: String = "input") : this(property.get()) {
        bind(eventTarget, property, event)
    }

    private var boundProperty: KMutableProperty0<T>? = null
    private var currentTarget: EventTargetInternal? = null
    private var currentEventName: String? = null

    private val mutValueState = MutableStateFlow(initialValue)
    val valueState: StateFlow<T> = mutValueState.asStateFlow()
    var value: T
        get() = mutValueState.value
        set(value) {
            mutValueState.value = value
            if (boundProperty != null) {
                boundProperty?.set(value)
                currentTarget?.dispatchChangeEvent(
                    CustomEvent(
                        currentEventName!!, CustomEventInit(
                            // avoid infinite loops, when triggered from onChange handler
                            detail = INTERNAL_EVENT_GUARD
                        )
                    )
                )
            }
            dirty = true
        }

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
        return value
    }

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        this.value = value
    }

    private val mutDirtyState = MutableStateFlow(false)
    val dirtyState: StateFlow<Boolean> = mutDirtyState.asStateFlow()
    var dirty: Boolean
        get() = mutDirtyState.value
        set(value) {
            mutDirtyState.value = value
        }

    private val mutTouchedState = MutableStateFlow(false)
    val touchedState: StateFlow<Boolean> = mutTouchedState.asStateFlow()
    var touched: Boolean
        get() = mutTouchedState.value
        set(value) {
            mutTouchedState.value = value
        }

    fun bind(eventTarget: EventTarget, property: KMutableProperty0<T>, event: String = "input") {
        bind(eventTarget.asInternal(), property, event)
    }

    fun bind(eventTarget: EventTargetInternal, property: KMutableProperty0<T>, event: String = "input") {
        currentTarget?.removeEventListener(currentEventName!!, ::onChange)
        currentTarget?.removeEventListener("focus", ::onTouched)
        currentTarget?.removeEventListener("blur", ::onTouched)

        currentTarget = eventTarget
        currentEventName = event
        boundProperty = property
        eventTarget.addEventListener(event, ::onChange)
        // propagate initial value (this isn't a noop)
        value = value
    }

    private fun onChange(event: Event) {
        if (event is CustomEvent && event.detail == INTERNAL_EVENT_GUARD) {
            return
        }
        LOG.debug { "onChange (old: '$value', new: '${boundProperty?.get()}')" }
        value = (boundProperty ?: return).get()
    }

    private fun onTouched(event: Event) {
        touched = true
    }
}

fun HTMLInputElement.bind(controlValue: ControlValue<String>) {
    controlValue.bind(this, this::value)
}

fun <T> HTMLInputElement.bind(controlValue: ControlValue<T>,
                              inMapper: (String) -> T,
                              outMapper: (T) -> String) {
    bind(controlValue, this::value, inMapper, outMapper)
}

fun <T, R: Any> HTMLInputElement.bind(controlValue: ControlValue<T>,
                              property: KMutableProperty0<R>,
                              inMapper: (R) -> T,
                              outMapper: (T) -> R) {
    val mapper = ControlValueMapper(inMapper, outMapper, property)
    controlValue.bind(this, mapper::value)
}

/**
 * internal excerpt of the (external) EventTarget interface,
 * so that we can implement ourselves for special-cases like radio buttons
 */
interface EventTargetInternal {
    fun addEventListener(type: String, listener: ((Event) -> Unit)?)
    fun removeEventListener(type: String, listener: ((Event) -> Unit)?)
    fun dispatchChangeEvent(event: Event)
}

fun EventTarget.asInternal(): EventTargetInternal = object : EventTargetInternal {
    override fun addEventListener(type: String, listener: ((Event) -> Unit)?) {
        this@asInternal.addEventListener(type, listener)
    }

    override fun removeEventListener(type: String, listener: ((Event) -> Unit)?) {
        this@asInternal.removeEventListener(type, listener)
    }

    override fun dispatchChangeEvent(event: Event) {
        this@asInternal.dispatchEvent(event)
    }
}

fun List<HTMLInputElement>.bindRadio(controlValue: ControlValue<String>) {
    val property: KMutableProperty0<String> = object {
        var value: String
            get() = this@bindRadio.firstOrNull { it.checked }?.value ?: ""
            set(value) {
                this@bindRadio.forEach { it.checked = (it.value == value) }
            }
    }::value
    val eventTarget = object : EventTargetInternal {
        override fun addEventListener(type: String, listener: ((Event) -> Unit)?) {
            this@bindRadio.forEach { it.addEventListener(type, listener) }
        }

        override fun removeEventListener(type: String, listener: ((Event) -> Unit)?) {
            this@bindRadio.forEach { it.removeEventListener(type, listener) }
        }

        override fun dispatchChangeEvent(event: Event) {
            // `change` event is only dispatched on the checked radio button
            this@bindRadio.filter { it.checked }.forEach { it.dispatchEvent(event) }
        }
    }
    controlValue.bind(eventTarget, property, "change")
}

fun HTMLSelectElement.bind(controlValue: ControlValue<String>) {
    controlValue.bind(this, this::value)
}

fun <T> HTMLSelectElement.bind(controlValue: ControlValue<T>,
                               inMapper: (String) -> T,
                               outMapper: (T) -> String) {
    val mapper = ControlValueMapper(inMapper, outMapper, this::value)
    controlValue.bind(this, mapper::value)
}

fun HTMLTextAreaElement.bind(controlValue: ControlValue<String>) {
    controlValue.bind(this, this::value)
}

fun <T> HTMLTextAreaElement.bind(controlValue: ControlValue<T>,
                                  inMapper: (String) -> T,
                                  outMapper: (T) -> String) {
    val mapper = ControlValueMapper(inMapper, outMapper, this::value)
    controlValue.bind(this, mapper::value)
}

private class ControlValueMapper<T, R>(
    private val inMapper: (R) -> T,
    private val outMapper: (T) -> R,
    private val property: KMutableProperty0<R>,
) {
    var value: T
        get() = inMapper(property.get())
        set(value) {
            property.set(outMapper(value))
        }
}