package de.pmenke.webkt.example.components

import de.pmenke.webkt.Component
import de.pmenke.webkt.RenderReceiver
import de.pmenke.webkt.dom_interop.DomUtil.cast
import de.pmenke.webkt.example.domain.Priority
import de.pmenke.webkt.example.domain.Task
import de.pmenke.webkt.example.domain.TaskDraft
import de.pmenke.webkt.example.domain.TaskId
import de.pmenke.webkt.example.domain.TaskStatus
import de.pmenke.webkt.example.domain.toDraft
import de.pmenke.webkt.example.repository.TaskPersistenceException
import de.pmenke.webkt.example.repository.TaskRepository
import de.pmenke.webkt.util.ControlValue
import de.pmenke.webkt.util.bind
import kotlinx.html.ButtonType
import kotlinx.html.InputType
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.js.onClickFunction
import kotlinx.html.js.onKeyDownFunction
import kotlinx.html.js.onSubmitFunction
import kotlinx.html.label
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.textArea
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.KeyboardEvent

/**
 * Application-persistent create/edit dialog.
 *
 * The component owns its [ControlValue] instances, so a parent render can replace the dialog DOM
 * without discarding an unfinished draft. Each render rebinds the controls to the new elements;
 * [ControlValue.bind] releases the previous DOM listeners before doing so.
 */
class TaskEditor(
    parent: Component,
    private val repository: TaskRepository,
) : Component(parent, "kanban-task-editor") {
    private sealed interface Mode {
        data object Closed : Mode
        data object Create : Mode
        data class Edit(val taskId: TaskId) : Mode
    }

    private var mode: Mode = Mode.Closed
    private var validationMessage: String? = null
    private var focusTitleAfterRender = false

    private val title = ControlValue("")
    private val description = ControlValue("")
    private val assignee = ControlValue("")
    private val priority = ControlValue(Priority.MEDIUM.name)
    private val status = ControlValue(TaskStatus.BACKLOG.name)

    init {
        callbacks.subscribe(Component.Companion.LifecycleCallbacks.AfterRender) {
            if (focusTitleAfterRender) {
                focusTitleAfterRender = false
                (currentElement?.querySelector("#task-title") as? HTMLInputElement)?.focus()
            }
        }
        callbacks.subscribe(Component.Companion.LifecycleCallbacks.Dispose) {
            unbindControls()
        }
    }

    /** Opens a fresh draft. */
    fun createTask() {
        load(TaskDraft(title = ""))
        mode = Mode.Create
        open()
    }

    /** Opens the selected task without exposing repository-owned fields to the form. */
    fun editTask(task: Task) {
        load(task.toDraft())
        mode = Mode.Edit(task.id)
        open()
    }

    val isOpen: Boolean
        get() = mode != Mode.Closed

    internal val draftTitle: String
        get() = title.value

    override fun RenderReceiver.renderContents() {
        val currentMode = mode
        if (currentMode == Mode.Closed) {
            return
        }

        div("task-editor-backdrop") {
            attributes["role"] = "presentation"
            onKeyDownFunction = { event ->
                if ((event as? KeyboardEvent)?.key == "Escape") {
                    event.preventDefault()
                    closeEditor()
                }
            }

            div("task-editor") {
                attributes["role"] = "dialog"
                attributes["aria-modal"] = "true"
                attributes["aria-labelledby"] = "task-editor-title"

                div("task-editor__header") {
                    h2 {
                        id = "task-editor-title"
                        +if (currentMode == Mode.Create) "Create task" else "Edit task"
                    }
                    button(type = ButtonType.button, classes = "icon-button") {
                        attributes["aria-label"] = "Close task editor"
                        +"×"
                        onClickFunction = { closeEditor() }
                    }
                }

                form {
                    onSubmitFunction = { event ->
                        event.preventDefault()
                        save()
                    }

                    label {
                        htmlFor = "task-title"
                        +"Title"
                    }
                    val titleInput = cast<HTMLInputElement>().input(
                        type = InputType.text,
                        classes = "form-control",
                    ) {
                        id = "task-title"
                        required = true
                        attributes["autocomplete"] = "off"
                    }
                    titleInput.bind(title)

                    label {
                        htmlFor = "task-description"
                        +"Description"
                    }
                    val descriptionInput = cast<HTMLTextAreaElement>().textArea(classes = "form-control") {
                        id = "task-description"
                        rows = "4"
                    }
                    descriptionInput.bind(description)

                    label {
                        htmlFor = "task-assignee"
                        +"Assignee"
                    }
                    val assigneeInput = cast<HTMLInputElement>().input(
                        type = InputType.text,
                        classes = "form-control",
                    ) {
                        id = "task-assignee"
                        attributes["autocomplete"] = "off"
                    }
                    assigneeInput.bind(assignee)

                    div("task-editor__fields") {
                        div {
                            label {
                                htmlFor = "task-priority"
                                +"Priority"
                            }
                            val prioritySelect = cast<HTMLSelectElement>().select(classes = "form-control") {
                                id = "task-priority"
                                Priority.entries.forEach { value ->
                                    option {
                                        this.value = value.name
                                        +value.displayName
                                    }
                                }
                            }
                            prioritySelect.bind(priority)
                        }
                        div {
                            label {
                                htmlFor = "task-status"
                                +"Status"
                            }
                            val statusSelect = cast<HTMLSelectElement>().select(classes = "form-control") {
                                id = "task-status"
                                TaskStatus.entries.forEach { value ->
                                    option {
                                        this.value = value.name
                                        +value.displayName
                                    }
                                }
                            }
                            statusSelect.bind(status)
                        }
                    }

                    validationMessage?.let { message ->
                        p("form-error") {
                            attributes["role"] = "alert"
                            +message
                        }
                    }

                    div("task-editor__actions") {
                        if (currentMode is Mode.Edit) {
                            button(type = ButtonType.button, classes = "button button--danger") {
                                +"Delete"
                                onClickFunction = {
                                    if (kotlinx.browser.window.confirm("Delete this task?")) {
                                        delete(currentMode.taskId)
                                    }
                                }
                            }
                        }
                        span("task-editor__actions-spacer")
                        button(type = ButtonType.button, classes = "button button--secondary") {
                            attributes["data-action"] = "cancel"
                            +"Cancel"
                            onClickFunction = { closeEditor() }
                        }
                        button(type = ButtonType.submit, classes = "button button--primary") {
                            +"Save task"
                        }
                    }
                }
            }
        }
    }

    private fun open() {
        validationMessage = null
        focusTitleAfterRender = true
        requestUpdate()
    }

    private fun closeEditor() {
        unbindControls()
        mode = Mode.Closed
        validationMessage = null
        requestUpdate()
    }

    private fun save() {
        if (title.value.isBlank()) {
            validationMessage = "Enter a title before saving."
            requestUpdate()
            return
        }

        val draft = TaskDraft(
            title = title.value,
            description = description.value,
            assignee = assignee.value,
            priority = Priority.valueOf(priority.value),
            status = TaskStatus.valueOf(status.value),
        )
        try {
            when (val currentMode = mode) {
                Mode.Closed -> return
                Mode.Create -> repository.create(draft)
                is Mode.Edit -> repository.update(currentMode.taskId, draft)
            }
        } catch (_: TaskPersistenceException) {
            return
        }
        closeEditor()
    }

    private fun delete(taskId: TaskId) {
        try {
            repository.delete(taskId)
        } catch (_: TaskPersistenceException) {
            return
        }
        closeEditor()
    }

    private fun load(draft: TaskDraft) {
        title.value = draft.title
        description.value = draft.description
        assignee.value = draft.assignee.orEmpty()
        priority.value = draft.priority.name
        status.value = draft.status.name
        listOf(title, description, assignee, priority, status).forEach {
            it.dirty = false
            it.touched = false
        }
    }

    private fun unbindControls() {
        title.unbind()
        description.unbind()
        assignee.unbind()
        priority.unbind()
        status.unbind()
    }
}

internal val Priority.displayName: String
    get() = name.lowercase().replaceFirstChar(Char::uppercase)

internal val TaskStatus.displayName: String
    get() = when (this) {
        TaskStatus.BACKLOG -> "Backlog"
        TaskStatus.IN_PROGRESS -> "In progress"
        TaskStatus.DONE -> "Done"
    }
