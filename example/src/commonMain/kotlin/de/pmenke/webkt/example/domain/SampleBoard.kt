package de.pmenke.webkt.example.domain

import kotlin.time.Instant

/** Current on-disk schema understood by the example. */
const val BOARD_SCHEMA_VERSION: Int = 1

/** Namespaced browser key. */
const val BOARD_STORAGE_KEY: String = "de.pmenke.webkt.example.kanban.v1"

/** Returns a fresh copy of the board used for first launch and "Reset all data". */
fun sampleBoardData(): BoardData = BoardData(
    schemaVersion = BOARD_SCHEMA_VERSION,
    tasks = listOf(
        task(
            id = "sample-research",
            title = "Research keyboard drag alternatives",
            description = "Document accessible ways to move cards without a pointer.",
            assignee = "Ada",
            priority = Priority.HIGH,
            status = TaskStatus.BACKLOG,
            order = 0,
            createdAt = "2026-06-01T09:00:00Z",
        ),
        task(
            id = "sample-empty-state",
            title = "Design helpful empty states",
            description = "Keep every board column useful when it has no tasks.",
            assignee = "Lin",
            priority = Priority.MEDIUM,
            status = TaskStatus.BACKLOG,
            order = 1,
            createdAt = "2026-06-02T10:30:00Z",
        ),
        task(
            id = "sample-copy",
            title = "Review interface copy",
            description = "Use concise English labels throughout the example.",
            assignee = null,
            priority = Priority.LOW,
            status = TaskStatus.BACKLOG,
            order = 2,
            createdAt = "2026-06-03T14:15:00Z",
        ),
        task(
            id = "sample-board",
            title = "Build the Kanban board",
            description = "Render ordered cards in three synchronized columns.",
            assignee = "Grace",
            priority = Priority.HIGH,
            status = TaskStatus.IN_PROGRESS,
            order = 0,
            createdAt = "2026-06-04T08:45:00Z",
        ),
        task(
            id = "sample-storage",
            title = "Persist board changes",
            description = "Write complete, versioned board snapshots to localStorage.",
            assignee = "Lin",
            priority = Priority.HIGH,
            status = TaskStatus.IN_PROGRESS,
            order = 1,
            createdAt = "2026-06-05T11:20:00Z",
        ),
        task(
            id = "sample-table",
            title = "Add the task table",
            description = "Provide filtering and sorting over the same task source.",
            assignee = "Ada",
            priority = Priority.MEDIUM,
            status = TaskStatus.IN_PROGRESS,
            order = 2,
            createdAt = "2026-06-06T13:00:00Z",
        ),
        task(
            id = "sample-lifetimes",
            title = "Explain component lifetimes",
            description = "Show render-owned and persistent children in context.",
            assignee = "Grace",
            priority = Priority.MEDIUM,
            status = TaskStatus.DONE,
            order = 0,
            createdAt = "2026-05-28T15:30:00Z",
        ),
        task(
            id = "sample-fixture",
            title = "Create a component fixture",
            description = "Make real-DOM component tests concise and deterministic.",
            assignee = "Ada",
            priority = Priority.HIGH,
            status = TaskStatus.DONE,
            order = 1,
            createdAt = "2026-05-29T12:10:00Z",
        ),
        task(
            id = "sample-styles",
            title = "Establish responsive styles",
            description = "Keep board and table usable on narrow screens.",
            assignee = null,
            priority = Priority.LOW,
            status = TaskStatus.DONE,
            order = 2,
            createdAt = "2026-05-30T16:40:00Z",
        ),
    ),
)

private fun task(
    id: String,
    title: String,
    description: String,
    assignee: String?,
    priority: Priority,
    status: TaskStatus,
    order: Int,
    createdAt: String,
) = Task(
    id = TaskId(id),
    title = title,
    description = description,
    assignee = assignee,
    priority = priority,
    status = status,
    order = order,
    createdAt = Instant.parse(createdAt),
)
