package de.pmenke.webkt.example.domain

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Instant

/** Stable identifier persisted with a task. */
@Serializable
@JvmInline
value class TaskId(val value: String) {
    init {
        require(value.isNotBlank()) { "A task ID must not be blank" }
    }

    override fun toString(): String = value
}

/** The board column containing a task. Declaration order is the board's display order. */
@Serializable
enum class TaskStatus {
    BACKLOG,
    IN_PROGRESS,
    DONE,
}

/** User-selected task importance. */
@Serializable
enum class Priority {
    LOW,
    MEDIUM,
    HIGH,
}

/** A persisted Kanban task. */
@Serializable
data class Task(
    val id: TaskId,
    val title: String,
    val description: String,
    val assignee: String?,
    val priority: Priority,
    val status: TaskStatus,
    val order: Int,
    val createdAt: Instant,
)

/**
 * Editable task fields.
 *
 * [createdAt], [Task.id], and ordering are repository-owned and intentionally absent.
 */
data class TaskDraft(
    val title: String,
    val description: String = "",
    val assignee: String? = null,
    val priority: Priority = Priority.MEDIUM,
    val status: TaskStatus = TaskStatus.BACKLOG,
)

/** Starts an editor draft without exposing repository-owned task fields to the form. */
fun Task.toDraft(): TaskDraft = TaskDraft(
    title = title,
    description = description,
    assignee = assignee,
    priority = priority,
    status = status,
)

/** Versioned payload written as one value to browser storage. */
@Serializable
data class BoardData(
    val schemaVersion: Int,
    val tasks: List<Task>,
)
