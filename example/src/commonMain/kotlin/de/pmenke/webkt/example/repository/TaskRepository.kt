package de.pmenke.webkt.example.repository

import de.pmenke.webkt.example.domain.Task
import de.pmenke.webkt.example.domain.TaskDraft
import de.pmenke.webkt.example.domain.TaskId
import de.pmenke.webkt.example.domain.TaskStatus
import kotlinx.coroutines.flow.StateFlow

/** Minimal persistence boundary, separated from browser globals for deterministic tests. */
interface KeyValueStorage {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

/**
 * Delivers a storage key's new value when another browser context changes it.
 * A `null` value means the key was removed. The returned handle detaches the listener.
 */
fun interface StorageChangeSource {
    fun subscribe(key: String, listener: (String?) -> Unit): AutoCloseable

    companion object {
        val None: StorageChangeSource = StorageChangeSource { _, _ -> AutoCloseable {} }
    }
}

enum class RepositoryProblemKind {
    STORAGE_READ_FAILED,
    STORAGE_WRITE_FAILED,
    MALFORMED_DATA,
    UNSUPPORTED_SCHEMA,
    INVALID_DATA,
}

/** Recoverable persistence problem suitable for an application banner. */
data class RepositoryProblem(
    val kind: RepositoryProblemKind,
    val message: String,
)

/**
 * Application-owned source of truth shared by the board and task table.
 *
 * Mutation methods throw [TaskPersistenceException] if the complete board cannot be stored.
 * In that case [tasks] is unchanged and [problem] explains the failure.
 */
interface TaskRepository : AutoCloseable {
    /** Current canonical board order, grouped by status with contiguous per-column positions. */
    val tasks: StateFlow<List<Task>>

    /** Latest recoverable storage problem, or `null` while persistence is healthy. */
    val problem: StateFlow<RepositoryProblem?>

    /** Validates, appends, persists, and returns a new task. */
    fun create(draft: TaskDraft): Task

    /** Replaces editable fields, appending to the target column if status changes. */
    fun update(id: TaskId, draft: TaskDraft)

    /** Removes a task and closes the resulting gap in its column. */
    fun delete(id: TaskId)

    /** Moves a task to an index calculated after removing it from its previous position. */
    fun move(id: TaskId, targetStatus: TaskStatus, targetIndex: Int)

    /** Restores and persists the exact bundled sample board. */
    fun reset()

    /** Dismisses the current problem without modifying stored or observable tasks. */
    fun clearProblem()
}

/** Signals that a mutation was rejected because its complete snapshot could not be persisted. */
class TaskPersistenceException(message: String, cause: Throwable) : IllegalStateException(message, cause)
