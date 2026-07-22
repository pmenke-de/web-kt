package de.pmenke.webkt.util

import de.pmenke.webkt.util.StateFlowUtil.flatMapStateLatest
import de.pmenke.webkt.util.StateFlowUtil.stateCombine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Combines independently configurable [FilterElement]s into one predicate state flow. */
class FilterControls<T> {
    private val filterElements = MutableStateFlow(listOf<FilterElement<T>>())

    @OptIn(ExperimentalCoroutinesApi::class)
    val filter: StateFlow<(T)-> Boolean> = filterElements.flatMapStateLatest { elements ->
        elements.map { it.filter }.stateCombine { filters -> {item: T -> filters.all { it(item) }} }
    }

    /** Adds and returns a multi-option filter. */
    fun addOptionFilter(options: List<FilterOption<T>>): OptionFilterElement<T> {
        val element = OptionFilterElement(options)
        filterElements.value += element
        return element
    }

    /** Vararg convenience overload for [addOptionFilter]. */
    fun addOptionFilter(vararg options: FilterOption<T>) = addOptionFilter(options.toList())

    /** Adds an application-defined filter element. */
    fun addFilter(filter: FilterElement<T>) {
        filterElements.value += filter
    }
}

/** A stateful predicate that can participate in [FilterControls]. */
interface FilterElement<T> {
    /** Predicate reflecting the element's current selection. */
    val filter: StateFlow<(T) -> Boolean>
    /** Whether this element currently has a user-visible selection. */
    val active: Boolean
}

/** A filter that matches an item when any selected option matches it. */
class OptionFilterElement<T>(
    options: List<FilterOption<T>>
) : FilterElement<T>  {
    val options: List<FilterOption<T>> = options.toList() // copy to ensure consistent size

    override val filter: StateFlow<(T) -> Boolean> = options.map { it.selected }.stateCombine { selected ->
        val effectiveMatchers = options.filterIndexed { index, _ -> selected[index] }
        if (effectiveMatchers.isEmpty() || effectiveMatchers.size == options.size) NOP_FILTER
        else { item: T -> effectiveMatchers.any { matcher -> matcher.matcher(item) } }
    }

    override val active: Boolean
        get() = options.any { it.selected.value }
}

/** One selectable label and predicate in an [OptionFilterElement]. */
class FilterOption<T>(
    val label: String,
    val matcher: (T) -> Boolean
) {
    /** Mutable selection state used by the combined filter. */
    val selected = MutableStateFlow(false)
}

private val NOP_FILTER: (Any?) -> Boolean = { true }
