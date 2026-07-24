package de.pmenke.webkt.example.components

import de.pmenke.webkt.Component
import de.pmenke.webkt.RenderReceiver
import de.pmenke.webkt.dom_interop.DomUtil.cast
import de.pmenke.webkt.example.domain.Priority
import de.pmenke.webkt.example.domain.Task
import de.pmenke.webkt.example.domain.TaskStatus
import de.pmenke.webkt.example.repository.TaskRepository
import de.pmenke.webkt.util.ControlValue
import de.pmenke.webkt.util.FilterControls
import de.pmenke.webkt.util.FilterOption
import de.pmenke.webkt.util.OptionFilterElement
import de.pmenke.webkt.util.SortControls
import de.pmenke.webkt.util.SortDirection
import de.pmenke.webkt.util.SortElement
import de.pmenke.webkt.util.asObservableValue
import de.pmenke.webkt.util.bind
import de.pmenke.webkt.util.combineValues
import kotlinx.html.ButtonType
import kotlinx.html.InputType
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.fieldSet
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.js.onChangeFunction
import kotlinx.html.js.onClickFunction
import kotlinx.html.label
import kotlinx.html.legend
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.MouseEvent

/**
 * Render-owned, searchable table over the same repository state as [KanbanBoard].
 *
 * The filtered and sorted rows are a scope-free observable value. Collection starts only when
 * [RenderReceiver.inlineFlowComponent] renders the results and ends with that render lifetime.
 */
class TaskTable(
    parent: Component,
    private val repository: TaskRepository,
    private val onEdit: (Task) -> Unit,
) : Component(parent, "kanban-task-table") {
    private val search = ControlValue("")
    private val filters = FilterControls<Task>()
    private val statusFilter = filters.addOptionFilter(
        TaskStatus.entries.map { status ->
            FilterOption(status.displayName) { task -> task.status == status }
        },
    )
    private val priorityFilter = filters.addOptionFilter(
        Priority.entries.map { priority ->
            FilterOption(priority.displayName) { task -> task.priority == priority }
        },
    )

    private val sorts = SortControls<Task>()
    private val titleSort = sorts.addElement(compareBy { it.title.lowercase() })
    private val statusSort = sorts.addElement(compareBy { it.status.ordinal })
    private val prioritySort = sorts.addElement(compareBy { it.priority.ordinal })
    private val assigneeSort = sorts.addElement(
        compareBy<Task> { it.assignee == null }.thenBy { it.assignee?.lowercase().orEmpty() },
    )
    private val createdSort = sorts.addElement(compareBy(Task::createdAt))

    /**
     * `sortedWith` is stable, so with no active sort the repository's deterministic board order is
     * retained; equal explicit-sort values retain that same order as their tie-breaker.
     */
    private val visibleTasks = repository.tasks.asObservableValue().combineValues(
        search.valueState.asObservableValue(),
        filters.filter,
        sorts.comparator,
    ) { tasks, query, predicate, comparator ->
        val normalizedQuery = query.trim().lowercase()
        tasks
            .asSequence()
            .filter(predicate)
            .filter { task -> normalizedQuery.isEmpty() || task.matches(normalizedQuery) }
            .toList()
            .sortedWith(comparator)
    }

    init {
        callbacks.subscribe(Component.Companion.LifecycleCallbacks.Dispose) {
            search.unbind()
        }
    }

    override fun RenderReceiver.renderContents() {
        h2("page-heading") { +"Task list" }
        p("page-intro") {
            +"Search, filter, and sort every task on the board. Select a row to edit it."
        }

        div("task-table-controls") {
            div("task-search") {
                label {
                    htmlFor = "task-search"
                    +"Search tasks"
                }
                val searchInput = cast<HTMLInputElement>().input(
                    type = InputType.search,
                    classes = "form-control",
                ) {
                    id = "task-search"
                    placeholder = "Title, description, or assignee"
                    attributes["autocomplete"] = "off"
                }
                searchInput.bind(search)
            }

            renderOptionFilter("Status", "status", statusFilter)
            renderOptionFilter("Priority", "priority", priorityFilter)

            button(type = ButtonType.button, classes = "button button--secondary clear-filters") {
                +"Clear filters"
                onClickFunction = { clearFilters() }
            }
        }

        inlineFlowComponent(
            tagName = "div",
            value = visibleTasks,
            classes = "task-table-results",
        ) { tasks ->
            p("task-result-count") {
                attributes["aria-live"] = "polite"
                +"Showing ${tasks.size} of ${repository.tasks.value.size} tasks"
            }

            if (tasks.isEmpty()) {
                div("empty-state") {
                    p { +"No tasks match the current search and filters." }
                    button(type = ButtonType.button, classes = "button-link") {
                        +"Clear filters"
                        onClickFunction = { clearFilters() }
                    }
                }
            } else {
                div("task-table-scroll") {
                    table("task-table") {
                        thead {
                            tr {
                                sortableHeader("Title", titleSort)
                                sortableHeader("Status", statusSort)
                                sortableHeader("Priority", prioritySort)
                                sortableHeader("Assignee", assigneeSort)
                                sortableHeader("Created", createdSort)
                                th {
                                    attributes["scope"] = "col"
                                    span("visually-hidden") { +"Actions" }
                                }
                            }
                        }
                        tbody {
                            tasks.forEach { task ->
                                tr {
                                    attributes["data-task-id"] = task.id.value
                                    onClickFunction = { onEdit(task) }
                                    td("task-table__title") { +task.title }
                                    td {
                                        span("status-badge status-${task.status.name.lowercase()}") {
                                            +task.status.displayName
                                        }
                                    }
                                    td {
                                        span("priority-badge priority-${task.priority.name.lowercase()}") {
                                            +task.priority.displayName
                                        }
                                    }
                                    td { +(task.assignee ?: "Unassigned") }
                                    td { +task.createdAt.toString().substringBefore('T') }
                                    td("task-table__action") {
                                        button(type = ButtonType.button, classes = "button-link") {
                                            attributes["aria-label"] = "Edit ${task.title}"
                                            +"Edit"
                                            onClickFunction = { event ->
                                                event.stopPropagation()
                                                onEdit(task)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun RenderReceiver.renderOptionFilter(
        title: String,
        idPrefix: String,
        filter: OptionFilterElement<Task>,
    ) {
        fieldSet("task-filter") {
            legend { +title }
            filter.options.forEachIndexed { index, option ->
                val controlId = "task-filter-$idPrefix-$index"
                label("task-filter__option") {
                    htmlFor = controlId
                    input(type = InputType.checkBox) {
                        id = controlId
                        checked = option.selected.value
                        onChangeFunction = { event ->
                            option.selected.value = (event.currentTarget as HTMLInputElement).checked
                        }
                    }
                    +option.label
                }
            }
        }
    }

    private fun kotlinx.html.TR.sortableHeader(label: String, element: SortElement) {
        val direction = element.direction.value
        th {
            attributes["scope"] = "col"
            attributes["aria-sort"] = direction.ariaSort
            button(type = ButtonType.button, classes = "sort-button") {
                attributes["aria-label"] = "Sort by $label; ${direction.description}"
                +label
                span("sort-indicator") {
                    attributes["aria-hidden"] = "true"
                    +direction.indicator
                }
                onClickFunction = { event ->
                    element.cycle(clearOther = (event as? MouseEvent)?.altKey != true)
                }
            }
        }
    }

    private fun clearFilters() {
        search.value = ""
        statusFilter.options.forEach { it.selected.value = false }
        priorityFilter.options.forEach { it.selected.value = false }
        currentElement?.querySelectorAll(".task-filter input")?.let { inputs ->
            for (index in 0 until inputs.length) {
                (inputs.item(index) as? HTMLInputElement)?.checked = false
            }
        }
    }
}

private fun Task.matches(query: String): Boolean =
    title.lowercase().contains(query) ||
        description.lowercase().contains(query) ||
        assignee?.lowercase()?.contains(query) == true

private val SortDirection.ariaSort: String
    get() = when (this) {
        SortDirection.NONE -> "none"
        SortDirection.ASC -> "ascending"
        SortDirection.DESC -> "descending"
    }

private val SortDirection.description: String
    get() = when (this) {
        SortDirection.NONE -> "not sorted"
        SortDirection.ASC -> "sorted ascending"
        SortDirection.DESC -> "sorted descending"
    }

private val SortDirection.indicator: String
    get() = when (this) {
        SortDirection.NONE -> "↕"
        SortDirection.ASC -> "↑"
        SortDirection.DESC -> "↓"
    }
