package de.pmenke.webkt.example.repository

import de.pmenke.webkt.example.domain.BOARD_SCHEMA_VERSION
import de.pmenke.webkt.example.domain.BOARD_STORAGE_KEY
import de.pmenke.webkt.example.domain.BoardData
import de.pmenke.webkt.example.domain.Task
import de.pmenke.webkt.example.domain.TaskDraft
import de.pmenke.webkt.example.domain.TaskId
import de.pmenke.webkt.example.domain.TaskStatus
import de.pmenke.webkt.example.domain.sampleBoardData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Transactional, versioned task repository backed by one [KeyValueStorage] value.
 *
 * Invalid stored data is not repaired silently. The UI receives sample tasks and a problem, while
 * the original value remains available for diagnosis until a successful mutation or reset.
 * Invalid updates received from another tab leave the currently displayed board unchanged.
 */
class PersistentTaskRepository(
    private val storage: KeyValueStorage,
    storageChanges: StorageChangeSource = StorageChangeSource.None,
    private val json: Json = DEFAULT_JSON,
    private val newId: () -> TaskId = ::randomTaskId,
    private val now: () -> Instant = Clock.System::now,
) : TaskRepository {
    private val mutableTasks = MutableStateFlow(sampleBoardData().tasks)
    override val tasks = mutableTasks.asStateFlow()

    private val mutableProblem = MutableStateFlow<RepositoryProblem?>(null)
    override val problem = mutableProblem.asStateFlow()

    private var closed = false
    private val storageSubscription: AutoCloseable

    init {
        loadInitialState()
        storageSubscription = storageChanges.subscribe(BOARD_STORAGE_KEY, ::applyExternalValue)
    }

    override fun create(draft: TaskDraft): Task {
        ensureOpen()
        val cleanDraft = draft.validated()
        val existingIds = mutableTasks.value.mapTo(mutableSetOf(), Task::id)
        var id: TaskId
        do {
            id = newId()
        } while (id in existingIds)

        val created = Task(
            id = id,
            title = cleanDraft.title,
            description = cleanDraft.description,
            assignee = cleanDraft.assignee,
            priority = cleanDraft.priority,
            status = cleanDraft.status,
            order = mutableTasks.value.count { it.status == cleanDraft.status },
            createdAt = now(),
        )
        persistAndPublish(mutableTasks.value + created)
        return mutableTasks.value.single { it.id == id }
    }

    override fun update(id: TaskId, draft: TaskDraft) {
        ensureOpen()
        val cleanDraft = draft.validated()
        val current = mutableTasks.value
        val existing = current.findTask(id)
        val retained = current.filterNot { it.id == id }
        val updated = existing.copy(
            title = cleanDraft.title,
            description = cleanDraft.description,
            assignee = cleanDraft.assignee,
            priority = cleanDraft.priority,
            status = cleanDraft.status,
            order = if (existing.status == cleanDraft.status) existing.order
            else retained.count { it.status == cleanDraft.status },
        )
        persistAndPublish(retained + updated)
    }

    override fun delete(id: TaskId) {
        ensureOpen()
        val current = mutableTasks.value
        current.findTask(id)
        persistAndPublish(current.filterNot { it.id == id })
    }

    /**
     * Moves [id] to [targetIndex] in the target column after removing it from its old position.
     * Out-of-range indices are clamped.
     */
    override fun move(id: TaskId, targetStatus: TaskStatus, targetIndex: Int) {
        ensureOpen()
        val current = mutableTasks.value
        val moving = current.findTask(id)
        val withoutMoving = current.filterNot { it.id == id }
        val targetTasks = withoutMoving
            .filter { it.status == targetStatus }
            .sortedBy(Task::order)
            .toMutableList()
        targetTasks.add(targetIndex.coerceIn(0, targetTasks.size), moving.copy(status = targetStatus))

        val reordered = withoutMoving.filterNot { it.status == targetStatus } +
            targetTasks.mapIndexed { order, task -> task.copy(order = order) }
        persistAndPublish(reordered)
    }

    override fun reset() {
        ensureOpen()
        persistAndPublish(sampleBoardData().tasks)
    }

    override fun clearProblem() {
        ensureOpen()
        mutableProblem.value = null
    }

    override fun close() {
        if (closed) return
        closed = true
        storageSubscription.close()
    }

    private fun loadInitialState() {
        val stored = try {
            storage.read(BOARD_STORAGE_KEY)
        } catch (_: Throwable) {
            mutableProblem.value = RepositoryProblem(
                RepositoryProblemKind.STORAGE_READ_FAILED,
                "The saved board could not be read. Sample tasks are shown for this session.",
            )
            return
        }

        if (stored == null) {
            val defaults = sampleBoardData().tasks
            try {
                storage.write(BOARD_STORAGE_KEY, encode(defaults))
            } catch (_: Throwable) {
                mutableProblem.value = RepositoryProblem(
                    RepositoryProblemKind.STORAGE_WRITE_FAILED,
                    "Sample tasks could not be saved. Changes may not survive a page reload.",
                )
            }
            mutableTasks.value = defaults
            return
        }

        when (val result = decodeAndValidate(stored)) {
            is DecodeResult.Valid -> mutableTasks.value = result.tasks
            is DecodeResult.Invalid -> mutableProblem.value = result.initialLoadProblem()
        }
    }

    private fun applyExternalValue(value: String?) {
        if (closed) return
        if (value == null) {
            mutableTasks.value = sampleBoardData().tasks
            mutableProblem.value = null
            return
        }

        when (val result = decodeAndValidate(value)) {
            is DecodeResult.Valid -> {
                mutableTasks.value = result.tasks
                mutableProblem.value = null
            }
            is DecodeResult.Invalid -> mutableProblem.value = result.externalUpdateProblem()
        }
    }

    private fun persistAndPublish(candidate: List<Task>) {
        val normalized = normalizeTaskOrder(candidate)
        val serialized = encode(normalized)
        try {
            storage.write(BOARD_STORAGE_KEY, serialized)
        } catch (failure: Throwable) {
            val message = "The board change could not be saved. No tasks were changed."
            mutableProblem.value = RepositoryProblem(RepositoryProblemKind.STORAGE_WRITE_FAILED, message)
            throw TaskPersistenceException(message, failure)
        }
        mutableTasks.value = normalized
        mutableProblem.value = null
    }

    private fun encode(tasks: List<Task>): String =
        json.encodeToString(BoardData(BOARD_SCHEMA_VERSION, tasks))

    private fun decodeAndValidate(serialized: String): DecodeResult {
        val schemaVersion = try {
            json.parseToJsonElement(serialized).jsonObject["schemaVersion"]?.jsonPrimitive?.intOrNull
        } catch (_: SerializationException) {
            return malformedResult()
        } catch (_: IllegalArgumentException) {
            return malformedResult()
        }
        if (schemaVersion != null && schemaVersion != BOARD_SCHEMA_VERSION) {
            return DecodeResult.Invalid(
                kind = RepositoryProblemKind.UNSUPPORTED_SCHEMA,
                detail = "uses unsupported schema version $schemaVersion",
            )
        }

        val board = try {
            json.decodeFromString<BoardData>(serialized)
        } catch (_: SerializationException) {
            return malformedResult()
        } catch (_: IllegalArgumentException) {
            return malformedResult()
        }

        val invalidReason = validateTasks(board.tasks)
        if (invalidReason != null) {
            return DecodeResult.Invalid(
                kind = RepositoryProblemKind.INVALID_DATA,
                detail = "is invalid ($invalidReason)",
            )
        }
        return DecodeResult.Valid(board.tasks)
    }

    private fun malformedResult() = DecodeResult.Invalid(
        kind = RepositoryProblemKind.MALFORMED_DATA,
        detail = "is malformed",
    )

    private fun ensureOpen() {
        check(!closed) { "Task repository is closed" }
    }

    private sealed interface DecodeResult {
        data class Valid(val tasks: List<Task>) : DecodeResult

        data class Invalid(
            val kind: RepositoryProblemKind,
            val detail: String,
        ) : DecodeResult {
            fun initialLoadProblem() = RepositoryProblem(
                kind = kind,
                message = "The saved board $detail. Sample tasks are shown; reset to replace it.",
            )

            fun externalUpdateProblem() = RepositoryProblem(
                kind = kind,
                message = "A board update from another tab $detail. The current board was kept.",
            )
        }
    }

    companion object {
        val DEFAULT_JSON: Json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            prettyPrint = true
        }
    }
}

/** Produces deterministic column order and contiguous zero-based card order. */
fun normalizeTaskOrder(tasks: List<Task>): List<Task> {
    val originalPosition = tasks.withIndex().associate { it.value.id to it.index }
    return TaskStatus.entries.flatMap { status ->
        tasks
            .filter { it.status == status }
            .sortedWith(compareBy<Task> { it.order }.thenBy { originalPosition.getValue(it.id) })
            .mapIndexed { order, task -> task.copy(order = order) }
    }
}

private fun validateTasks(tasks: List<Task>): String? {
    if (tasks.any { it.title.isBlank() }) return "task titles must not be blank"
    if (tasks.map(Task::id).toSet().size != tasks.size) return "task IDs must be unique"
    for (status in TaskStatus.entries) {
        val actual = tasks.filter { it.status == status }.map(Task::order)
        if (actual != actual.indices.toList()) {
            return "${status.name} ordering must be contiguous and zero-based"
        }
    }
    if (tasks != normalizeTaskOrder(tasks)) return "tasks must be stored in board order"
    return null
}

private fun TaskDraft.validated(): TaskDraft {
    val cleanTitle = title.trim()
    require(cleanTitle.isNotEmpty()) { "A task title must not be blank" }
    return copy(
        title = cleanTitle,
        description = description.trim(),
        assignee = assignee?.trim()?.takeIf(String::isNotEmpty),
    )
}

private fun List<Task>.findTask(id: TaskId): Task =
    firstOrNull { it.id == id } ?: throw NoSuchElementException("No task exists with ID '$id'")

private fun randomTaskId(): TaskId =
    TaskId("task-${Clock.System.now().toEpochMilliseconds()}-${Random.nextInt().toUInt().toString(16)}")
