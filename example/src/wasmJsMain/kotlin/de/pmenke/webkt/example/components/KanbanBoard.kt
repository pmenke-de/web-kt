package de.pmenke.webkt.example.components

import de.pmenke.webkt.Component
import de.pmenke.webkt.RenderReceiver
import de.pmenke.webkt.example.domain.Task
import de.pmenke.webkt.example.domain.TaskId
import de.pmenke.webkt.example.domain.TaskStatus
import de.pmenke.webkt.example.repository.TaskPersistenceException
import de.pmenke.webkt.example.repository.TaskRepository
import de.pmenke.webkt.koin_interop.getComponent
import kotlinx.coroutines.flow.onEach
import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import kotlinx.html.ButtonType
import kotlinx.html.article
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.js.onClickFunction
import kotlinx.html.js.onDragEndFunction
import kotlinx.html.js.onDragLeaveFunction
import kotlinx.html.js.onDragOverFunction
import kotlinx.html.js.onDragStartFunction
import kotlinx.html.js.onDropFunction
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import org.koin.core.parameter.parametersOf
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

@JsFun("(event, type, value) => event.dataTransfer && event.dataTransfer.setData(type, value)")
private external fun setDragData(event: Event, type: String, value: String)

@JsFun("(event, type) => event.dataTransfer ? event.dataTransfer.getData(type) : ''")
private external fun getDragData(event: Event, type: String): String

private const val TASK_MIME_TYPE = "application/x-webkt-kanban-task"

/** Render-owned board page over the application repository. */
class KanbanBoard(
    parent: Component,
    private val repository: TaskRepository,
    private val onEdit: (Task) -> Unit,
) : Component(parent, "kanban-board") {
    private var draggedTaskId: TaskId? = null

    init {
        callbacks.subscribe(Component.Companion.LifecycleCallbacks.Dispose) { clearDragState() }
    }

    override fun RenderReceiver.renderContents() {
        h2("page-heading") { +"Board" }
        p("page-intro") {
            +"Drag cards to reorder them, or use each card’s move buttons from the keyboard."
        }

        inlineFlowComponent(
            tagName = "div",
            flow = repository.tasks.onEach { clearDragState() },
            initialValue = repository.tasks.value,
            classes = "board-columns",
        ) { tasks ->
            TaskStatus.entries.forEach { status ->
                val columnTasks = tasks
                    .filter { it.status == status }
                    .sortedBy(Task::order)
                renderColumn(status, columnTasks)
            }
        }
    }

    private fun RenderReceiver.renderColumn(status: TaskStatus, tasks: List<Task>) {
        section("board-column") {
            attributes["aria-labelledby"] = "column-${status.name}"
            attributes["data-status"] = status.name

            div("board-column__heading") {
                h3 {
                    attributes["id"] = "column-${status.name}"
                    +status.displayName
                }
                span("count-badge") { +tasks.size.toString() }
            }

            div("board-column__cards") {
                tasks.forEachIndexed { index, task ->
                    div("card-drop-slot") {
                        dropTarget(status, index)
                        attributes["data-drop-index"] = index.toString()
                        attributes["aria-hidden"] = "true"
                    }
                    render(
                        getComponent<TaskCard> {
                            parametersOf(task, onEdit, this@KanbanBoard)
                        },
                    )
                }
                div("column-drop-target") {
                    dropTarget(status, tasks.size)
                    attributes["data-drop-index"] = tasks.size.toString()
                    if (tasks.isEmpty()) {
                        p { +"Drop a task here" }
                    } else {
                        span("visually-hidden") { +"Move task to the end of ${status.displayName}" }
                    }
                }
            }
        }
    }

    private fun kotlinx.html.CommonAttributeGroupFacade.dropTarget(
        status: TaskStatus,
        index: Int,
    ) {
        onDragOverFunction = { event ->
            event.preventDefault()
            (event.currentTarget as? HTMLElement)?.addClass("is-drop-target")
        }
        onDragLeaveFunction = { event ->
            (event.currentTarget as? HTMLElement)?.removeClass("is-drop-target")
        }
        onDropFunction = { event ->
            event.preventDefault()
            val id = draggedTaskId?.value
                ?: getDragData(event, TASK_MIME_TYPE).takeIf(String::isNotBlank)
            clearDragState()
            if (id != null) {
                try {
                    val taskId = TaskId(id)
                    repository.tasks.value.firstOrNull { it.id == taskId }?.let { task ->
                        repository.move(
                            taskId,
                            status,
                            postRemovalDropIndex(task, status, index),
                        )
                    }
                } catch (_: TaskPersistenceException) {
                    // The repository problem banner explains the rejected mutation.
                }
            }
        }
    }

    private fun clearDragState() {
        draggedTaskId = null
        currentElement?.querySelectorAll(".is-drop-target")?.let { matches ->
            for (index in 0 until matches.length) {
                (matches.item(index) as? HTMLElement)?.removeClass("is-drop-target")
            }
        }
    }

    /** Render-owned card. */
    class TaskCard(
        parent: Component,
        private val repository: TaskRepository,
        private val task: Task,
        private val onEdit: (Task) -> Unit,
        private val board: KanbanBoard,
    ) : Component(
        parent,
        "kanban-task-card",
        mapOf("class" to "task-card", "data-task-id" to task.id.value),
    ) {
        override fun RenderReceiver.renderContents() {
            article {
                attributes["draggable"] = "true"
                onDragStartFunction = { event ->
                    board.draggedTaskId = task.id
                    setDragData(event, TASK_MIME_TYPE, task.id.value)
                    currentElement?.addClass("is-dragging")
                }
                onDragEndFunction = {
                    currentElement?.removeClass("is-dragging")
                    board.clearDragState()
                }

                div("task-card__meta") {
                    span("priority-badge priority-${task.priority.name.lowercase()}") {
                        +task.priority.displayName
                    }
                    task.assignee?.let { span("task-assignee") { +it } }
                }
                h3("task-card__title") { +task.title }
                if (task.description.isNotBlank()) {
                    p("task-card__description") { +task.description }
                }
                div("task-card__actions") {
                    adjacentStatus(-1)?.let { target ->
                        button(type = ButtonType.button, classes = "icon-button") {
                            attributes["aria-label"] = "Move ${task.title} left to ${target.displayName}"
                            attributes["data-action"] = "move-left"
                            +"←"
                            onClickFunction = { moveToEnd(target) }
                        }
                    }
                    adjacentStatus(1)?.let { target ->
                        button(type = ButtonType.button, classes = "icon-button") {
                            attributes["aria-label"] = "Move ${task.title} right to ${target.displayName}"
                            attributes["data-action"] = "move-right"
                            +"→"
                            onClickFunction = { moveToEnd(target) }
                        }
                    }
                    button(type = ButtonType.button, classes = "button-link task-card__edit") {
                        +"Edit"
                        onClickFunction = { onEdit(task) }
                    }
                }
            }
        }

        private fun adjacentStatus(offset: Int): TaskStatus? =
            TaskStatus.entries.getOrNull(task.status.ordinal + offset)

        private fun moveToEnd(status: TaskStatus) {
            val targetIndex = repository.tasks.value.count { it.status == status }
            try {
                repository.move(task.id, status, targetIndex)
            } catch (_: TaskPersistenceException) {
                // The repository problem banner explains the rejected mutation.
            }
        }
    }
}

/**
 * Translates a slot rendered against the pre-move column into the repository's insertion index,
 * which is defined after removing the moving task.
 */
internal fun postRemovalDropIndex(
    task: Task?,
    targetStatus: TaskStatus,
    visibleSlotIndex: Int,
): Int = if (
    task != null &&
    task.status == targetStatus &&
    task.order < visibleSlotIndex
) {
    visibleSlotIndex - 1
} else {
    visibleSlotIndex
}
