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

private val LOG = Logger("de.pmenke.webkt.util.ControlValue")
private val INTERNAL_EVENT_GUARD = "de.pmenke.webkt.util.ControlValue.__internal__".toJsString()

/**
 * Represents a form control value that can be bound to an HTML form element to listen for changes in its value.
 *
 * [valueState] is a [StateFlow] that emits the current value whenever it changes.
 * [value] is the current value and can be read or updated directly.
 *
 * Bind using [bind] to an [EventTarget] (e.g. an [HTMLInputElement]) and a reference to its DOM value property (e.g. [HTMLInputElement.value]).
 */
class ControlValue<T: Any?>(initialValue: T) {
    /** Creates a value initialized from and immediately bound to a DOM property. */
    constructor(eventTarget: EventTarget, property: KMutableProperty0<T>, event: String = "input")
            : this(eventTarget.asInternal(), property, event)

    /** Creates a value initialized from and immediately bound to an internal event target. */
    internal constructor(eventTarget: EventTargetInternal, property: KMutableProperty0<T>, event: String = "input") : this(property.get()) {
        bind(eventTarget, property, event)
    }

    private var boundProperty: KMutableProperty0<T>? = null
    private var currentTarget: EventTargetInternal? = null
    private var currentEventName: String? = null
    private val changeListener: (Event) -> Unit = ::onChange
    private val touchedListener: (Event) -> Unit = ::onTouched

    private val mutValueState = MutableStateFlow(initialValue)
    /** Read-only state stream for [value]. */
    val valueState: StateFlow<T> = mutValueState.asStateFlow()
    /** Current control value. Assignments update a bound DOM property and mark this control dirty. */
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
    /** Read-only state stream for [dirty]. */
    val dirtyState: StateFlow<Boolean> = mutDirtyState.asStateFlow()
    /** Whether the value has changed programmatically or through its bound DOM control. */
    var dirty: Boolean
        get() = mutDirtyState.value
        set(value) {
            mutDirtyState.value = value
        }

    private val mutTouchedState = MutableStateFlow(false)
    /** Read-only state stream for [touched]. */
    val touchedState: StateFlow<Boolean> = mutTouchedState.asStateFlow()
    /** Whether the bound control has received focus or blur. */
    var touched: Boolean
        get() = mutTouchedState.value
        set(value) {
            mutTouchedState.value = value
        }

    /** Binds this value to [property], replacing any previous binding. */
    fun bind(eventTarget: EventTarget, property: KMutableProperty0<T>, event: String = "input") {
        bind(eventTarget.asInternal(), property, event)
    }

    /** Binds this value to an internal event target, replacing any previous binding. */
    internal fun bind(eventTarget: EventTargetInternal, property: KMutableProperty0<T>, event: String = "input") {
        unbind()

        currentTarget = eventTarget
        currentEventName = event
        boundProperty = property
        eventTarget.addEventListener(event, changeListener)
        eventTarget.addEventListener("focus", touchedListener)
        eventTarget.addEventListener("blur", touchedListener)
        // propagate initial value (this isn't a noop)
        value = value
        dirty = false
    }

    /**
     * Removes this value's DOM event listeners and releases its reference to the bound element.
     * The current value and dirty/touched state are preserved.
     */
    fun unbind() {
        val target = currentTarget ?: return
        currentEventName?.let { target.removeEventListener(it, changeListener) }
        target.removeEventListener("focus", touchedListener)
        target.removeEventListener("blur", touchedListener)
        currentTarget = null
        currentEventName = null
        boundProperty = null
    }

    private fun onChange(event: Event) {
        if (event is CustomEvent && event.detail == INTERNAL_EVENT_GUARD) {
            return
        }
        val newValue = (boundProperty ?: return).get()
        LOG.debug { "onChange (old: '$value', new: '$newValue')" }
        mutValueState.value = newValue
        dirty = true
    }

    private fun onTouched(@Suppress("UNUSED_PARAMETER") event: Event) {
        touched = true
    }
}

/** Binds this input's string value to [controlValue]. */
fun HTMLInputElement.bind(controlValue: ControlValue<String>) {
    controlValue.bind(this, this::value)
}

/** Binds this input using conversion functions for its string value. */
fun <T> HTMLInputElement.bind(controlValue: ControlValue<T>,
                              inMapper: (String) -> T,
                              outMapper: (T) -> String) {
    bind(controlValue, this::value, inMapper, outMapper)
}

/** Binds an arbitrary input [property] using conversion functions. */
fun <T, R: Any> HTMLInputElement.bind(controlValue: ControlValue<T>,
                              property: KMutableProperty0<R>,
                              inMapper: (R) -> T,
                              outMapper: (T) -> R) {
    val mapper = ControlValueMapper(inMapper, outMapper, property)
    controlValue.bind(this, mapper::value)
}

/** Minimal event-target abstraction used internally for both DOM controls and radio groups. */
internal interface EventTargetInternal {
    fun addEventListener(type: String, listener: ((Event) -> Unit)?)
    fun removeEventListener(type: String, listener: ((Event) -> Unit)?)
    fun dispatchChangeEvent(event: Event)
}

/** Adapts a browser [EventTarget] to the binding abstraction used by [ControlValue]. */
internal fun EventTarget.asInternal(): EventTargetInternal = object : EventTargetInternal {
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

/** Binds a string value to a group of radio inputs. */
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

/** Binds this select element's string value to [controlValue]. */
fun HTMLSelectElement.bind(controlValue: ControlValue<String>) {
    controlValue.bind(this, this::value)
}

/** Binds this select element using conversion functions. */
fun <T> HTMLSelectElement.bind(controlValue: ControlValue<T>,
                               inMapper: (String) -> T,
                               outMapper: (T) -> String) {
    val mapper = ControlValueMapper(inMapper, outMapper, this::value)
    controlValue.bind(this, mapper::value)
}

/** Binds this text area's string value to [controlValue]. */
fun HTMLTextAreaElement.bind(controlValue: ControlValue<String>) {
    controlValue.bind(this, this::value)
}

/** Binds this text area using conversion functions. */
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
