package de.pmenke.webkt.util

import de.pmenke.webkt.RenderReceiver
import kotlinx.coroutines.flow.*
import kotlinx.html.a
import kotlinx.html.i
import kotlinx.html.js.onClickFunction
import kotlinx.html.span
import org.w3c.dom.events.MouseEvent

/**
 * Utility to manage multiple sort elements and their directions, providing a combined comparator.
 *
 * For application in table headers.
 *
 * Usage:
 * ```kotlin
 * val sortControls = SortControls<MyDataType>()
 * val nameSortElement = sortControls.addElement(compareBy { it.name })
 * val dateSortElement = sortControls.addElement(compareBy { it.date })
 * // Bind to UI elements (e.g. table headers) to cycle sort direction on click.
 * // Use alt key to allow multi-column sorting.
 * nameHeader.onClick = { event -> nameSortElement.cycle(clearOther = event.altKey == false) }
 * ...
 * // Use sortControls.comparator as the comparator for sorting data.
 * // Note: a "stable" sort should be used, so that no reordering occurs for the [NONE] sort direction.
 * //       also, always use the unmodified `data` list for sorting, not a previously sorted list.
 * val sortedData = data.sortedWith(sortControls.comparator.value)
 * ```
 */
class SortControls<T> {
    private val comparatorFlow = MutableStateFlow<Comparator<T>>(noneComparator())

    /**
     * A StateFlow that emits the current combined comparator based on the active sort elements and their directions.
     */
    val comparator: StateFlow<Comparator<T>> = comparatorFlow.asStateFlow()

    private val sortElements = mutableListOf<SortElementImpl>()

    /**
     * Adds a new sort element with the given comparator.
     * The returned [SortElement] can be used to cycle its sort direction.
     * Its initial direction is [SortDirection.NONE].
     */
    fun addElement(comparator: Comparator<T>): SortElement {
        return SortElementImpl(comparator).also {
            sortElements.add(it)
            // as the new element is NONE, no need to updateComparator()
        }
    }

    private fun updateComparator() {
        comparatorFlow.value = sortElements.mapNotNull {
            when (it.direction.value) {
                SortDirection.NONE -> null
                SortDirection.ASC -> it.comparator
                SortDirection.DESC -> it.comparator.reversed()
            }
        }.reduceOrNull { a, b -> a.then(b) } ?: noneComparator()
    }

    /**
     * Represents a sortable element with a comparator and a sort direction.
     * The direction can be cycled through [SortDirection] values using [cycle].
     */
    private inner class SortElementImpl(
        val comparator: Comparator<T>,
    ) : SortElement {
        private val directionFlow = MutableStateFlow(SortDirection.NONE)

        override val direction = directionFlow.asStateFlow()

        override fun cycle(clearOther: Boolean) {
            set(direction.value.cycle(), clearOther)
        }

        override fun set(newDirection: SortDirection, clearOther: Boolean) {
            val oldDirection = directionFlow.value
            directionFlow.value = newDirection
            if (clearOther) {
                sortElements.forEach {
                    if (it !== this)
                        it.directionFlow.value = SortDirection.NONE
                }
                sortElements.remove(this)
                sortElements.add(0, this) // move to front for priority
            } else if (oldDirection == SortDirection.NONE) {
                // if this was NONE before and doesn't clear others
                // make this the last active sort element
                sortElements.remove(this)
                sortElements.add(sortElements.indexOfLast { it.direction.value != SortDirection.NONE } + 1, this)
            }
            updateComparator()
        }
    }
}

interface SortElement {
    /**
     * The current sort direction of this element.
     * Initially [SortDirection.NONE].
     */
    val direction: StateFlow<SortDirection>

    /**
     * Cycles the sort direction to the next state (NONE -> ASC -> DESC).
     * If [clearOther] is true (default), all other sort elements will be reset to [SortDirection.NONE].
     * This allows for single-column sorting by default, while holding the Alt key (or similar) can enable multi-column sorting.
     */
    fun cycle(clearOther: Boolean = true)

    /**
     * Sets the sort direction to [newDirection].
     * If [clearOther] is true (default), all other sort elements will be reset to [SortDirection.NONE].
     */
    fun set(newDirection: SortDirection, clearOther: Boolean = true)
}

/**
 * Represents the sort direction of a sortable element.
 * - [ASC]: Ascending order
 * - [DESC]: Descending order
 * - [NONE]: No sorting
 *
 * Each direction has a [classSuffix] for CSS class naming and an [ariaLabel] for accessibility.
 */
enum class SortDirection(val classSuffix: String, val ariaLabel: String) {
    ASC("-asc", "aufsteigend"), DESC("-desc", "absteigend"), NONE("-none", "unsortiert");

    /** Returns the next direction in the `NONE -> ASC -> DESC -> NONE` cycle. */
    fun cycle() = when (this) {
        ASC -> DESC
        DESC -> NONE
        NONE -> ASC
    }
}

private fun <T> noneComparator(): Comparator<T> = Comparator { _, _ -> 0 }

/** Renders an accessible link that cycles [element], preserving other sorts while Alt is held. */
fun RenderReceiver.sortLink(element: SortElement) {
    a(href = "#") {
        onClickFunction = { e ->
            e.preventDefault()
            e.stopPropagation()
            element.cycle((e as? MouseEvent)?.altKey != true)
        }
        inlineFlowComponent("app-sort-link", element.direction) { direction ->
            val suffix = direction.classSuffix
            i("webkt-sort$suffix") { attributes["aria-hidden"] = "true" }
            span("visually-hidden") { +"[Sortierung: ${direction.ariaLabel}]" }
        }
    }
}

/** Sorts successful list values using the comparator currently exposed by [sortControls]. */
fun <T> Flow<Result<List<T>>?>.sortedWith(sortControls: SortControls<T>): Flow<Result<List<T>>?> =
    combine(sortControls.comparator) { result, comparator ->
        result?.map { list -> list.sortedWith(comparator) }
    }
