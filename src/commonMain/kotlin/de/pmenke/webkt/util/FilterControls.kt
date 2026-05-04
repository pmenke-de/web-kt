package de.pmenke.webkt.util

import de.pmenke.webkt.util.StateFlowUtil.flatMapStateLatest
import de.pmenke.webkt.util.StateFlowUtil.stateCombine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FilterControls<T> {
    private val filterElements = MutableStateFlow(listOf<FilterElement<T>>())

    @OptIn(ExperimentalCoroutinesApi::class)
    val filter: StateFlow<(T)-> Boolean> = filterElements.flatMapStateLatest { elements ->
        elements.map { it.filter }.stateCombine { filters -> {item: T -> filters.all { it(item) }} }
    }

    fun addOptionFilter(options: List<FilterOption<T>>): OptionFilterElement<T> {
        val element = OptionFilterElement(options)
        filterElements.value += element
        return element
    }

    fun addOptionFilter(vararg options: FilterOption<T>) = addOptionFilter(options.toList())

    fun addFilter(filter: FilterElement<T>) {
        filterElements.value += filter
    }
}

interface FilterElement<T> {
    val filter: StateFlow<(T) -> Boolean>
    val active: Boolean
}

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

class FilterOption<T>(
    val label: String,
    val matcher: (T) -> Boolean
) {
    val selected = MutableStateFlow(false)
}

private val NOP_FILTER: (Any?) -> Boolean = { true }