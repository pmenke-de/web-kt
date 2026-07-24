package de.pmenke.webkt.example.repository

import de.pmenke.webkt.example.domain.BOARD_SCHEMA_VERSION
import de.pmenke.webkt.example.domain.BOARD_STORAGE_KEY
import de.pmenke.webkt.example.domain.BoardData
import de.pmenke.webkt.example.domain.Priority
import de.pmenke.webkt.example.domain.Task
import de.pmenke.webkt.example.domain.TaskDraft
import de.pmenke.webkt.example.domain.TaskId
import de.pmenke.webkt.example.domain.TaskStatus
import de.pmenke.webkt.example.domain.sampleBoardData
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class PersistentTaskRepositoryTest {
    @Test
    fun missingStorageIsSeededAndReloaded() {
        val storage = MemoryStorage()

        val first = repository(storage)
        assertEquals(sampleBoardData().tasks, first.tasks.value)
        assertEquals(1, storage.writes.size)
        assertNull(first.problem.value)
        first.close()

        val second = repository(storage)
        assertEquals(sampleBoardData().tasks, second.tasks.value)
        assertEquals(1, storage.writes.size, "Reloading existing data must not seed it again")
        assertNull(second.problem.value)
        second.close()
    }

    @Test
    fun createUpdateAndDeletePersistCompleteNormalizedBoards() {
        val storage = MemoryStorage()
        var nextId = 0
        val repository = repository(storage, newId = { TaskId("created-${nextId++}") })

        val created = repository.create(
            TaskDraft(
                title = "  Verify mutations  ",
                description = "  Exercise one storage path.  ",
                assignee = "  Sam  ",
                priority = Priority.HIGH,
                status = TaskStatus.BACKLOG,
            ),
        )
        assertEquals("Verify mutations", created.title)
        assertEquals("Exercise one storage path.", created.description)
        assertEquals("Sam", created.assignee)
        assertEquals(3, created.order)
        assertStoredEqualsState(storage, repository)

        repository.update(
            created.id,
            TaskDraft(
                title = "Verify all mutations",
                assignee = " ",
                priority = Priority.LOW,
                status = TaskStatus.DONE,
            ),
        )
        val updated = repository.tasks.value.single { it.id == created.id }
        assertEquals(created.createdAt, updated.createdAt)
        assertEquals(null, updated.assignee)
        assertEquals(TaskStatus.DONE, updated.status)
        assertEquals(3, updated.order)
        assertContiguous(repository.tasks.value)
        assertStoredEqualsState(storage, repository)

        repository.delete(created.id)
        assertTrue(repository.tasks.value.none { it.id == created.id })
        assertContiguous(repository.tasks.value)
        assertStoredEqualsState(storage, repository)
        repository.close()
    }

    @Test
    fun sameColumnMoveReordersAndClampsIndices() {
        val repository = repository(MemoryStorage())
        val backlog = repository.tasks.value.tasksIn(TaskStatus.BACKLOG)
        val last = backlog.last()

        repository.move(last.id, TaskStatus.BACKLOG, 0)
        assertEquals(
            listOf(last.id, backlog[0].id, backlog[1].id),
            repository.tasks.value.tasksIn(TaskStatus.BACKLOG).map(Task::id),
        )

        repository.move(last.id, TaskStatus.BACKLOG, 100)
        assertEquals(last.id, repository.tasks.value.tasksIn(TaskStatus.BACKLOG).last().id)
        assertContiguous(repository.tasks.value)
        repository.close()
    }

    @Test
    fun crossColumnMoveNormalizesBothColumns() {
        val repository = repository(MemoryStorage())
        val moving = repository.tasks.value.tasksIn(TaskStatus.BACKLOG)[1]
        val doneBefore = repository.tasks.value.tasksIn(TaskStatus.DONE)

        repository.move(moving.id, TaskStatus.DONE, 1)

        assertEquals(
            listOf(doneBefore[0].id, moving.id, doneBefore[1].id, doneBefore[2].id),
            repository.tasks.value.tasksIn(TaskStatus.DONE).map(Task::id),
        )
        assertEquals(TaskStatus.DONE, repository.tasks.value.single { it.id == moving.id }.status)
        assertContiguous(repository.tasks.value)
        repository.close()
    }

    @Test
    fun normalizationUsesColumnOrderAndStableInputOrderForTies() {
        val sample = sampleBoardData().tasks
        val shuffled = listOf(
            sample[4].copy(order = 9),
            sample[0].copy(order = 4),
            sample[3].copy(order = 9),
            sample[1].copy(order = 4),
        )

        val normalized = normalizeTaskOrder(shuffled)

        assertEquals(
            listOf(sample[0].id, sample[1].id, sample[4].id, sample[3].id),
            normalized.map(Task::id),
        )
        assertContiguous(normalized)
    }

    @Test
    fun resetRestoresAndPersistsExactSampleBoard() {
        val storage = MemoryStorage()
        val repository = repository(storage, newId = { TaskId("temporary") })
        repository.create(TaskDraft("Temporary task"))
        assertNotEquals(sampleBoardData().tasks, repository.tasks.value)

        repository.reset()

        assertEquals(sampleBoardData().tasks, repository.tasks.value)
        assertStoredEqualsState(storage, repository)
        repository.close()
    }

    @Test
    fun malformedAndUnsupportedDataFallBackWithoutOverwritingStorage() {
        val malformed = MemoryStorage(mutableMapOf(BOARD_STORAGE_KEY to "{not json"))
        val malformedRepository = repository(malformed)
        assertEquals(sampleBoardData().tasks, malformedRepository.tasks.value)
        assertEquals(RepositoryProblemKind.MALFORMED_DATA, malformedRepository.problem.value?.kind)
        assertContains(malformedRepository.problem.value!!.message, "Sample tasks are shown")
        assertTrue("current board" !in malformedRepository.problem.value!!.message)
        assertTrue(malformed.writes.isEmpty())
        assertEquals("{not json", malformed.values.getValue(BOARD_STORAGE_KEY))
        malformedRepository.close()

        val unsupportedValue = """{"schemaVersion":${BOARD_SCHEMA_VERSION + 1},"futureBoard":true}"""
        val unsupported = MemoryStorage(mutableMapOf(BOARD_STORAGE_KEY to unsupportedValue))
        val unsupportedRepository = repository(unsupported)
        assertEquals(RepositoryProblemKind.UNSUPPORTED_SCHEMA, unsupportedRepository.problem.value?.kind)
        assertContains(unsupportedRepository.problem.value!!.message, "Sample tasks are shown")
        assertTrue("current board" !in unsupportedRepository.problem.value!!.message)
        assertTrue(unsupported.writes.isEmpty())
        assertEquals(unsupportedValue, unsupported.values.getValue(BOARD_STORAGE_KEY))
        unsupportedRepository.close()
    }

    @Test
    fun duplicateIdsBlankTitlesAndInvalidOrderingAreRejected() {
        val sample = sampleBoardData().tasks
        val invalidBoards = listOf(
            sample + sample.first().copy(),
            sample.mapIndexed { index, task -> if (index == 0) task.copy(title = " ") else task },
            sample.mapIndexed { index, task -> if (index == 0) task.copy(order = 8) else task },
        )

        for (tasks in invalidBoards) {
            val encoded = JSON.encodeToString(BoardData(BOARD_SCHEMA_VERSION, tasks))
            val storage = MemoryStorage(mutableMapOf(BOARD_STORAGE_KEY to encoded))
            val repository = repository(storage)
            assertEquals(RepositoryProblemKind.INVALID_DATA, repository.problem.value?.kind)
            assertContains(repository.problem.value!!.message, "Sample tasks are shown")
            assertTrue("current board" !in repository.problem.value!!.message)
            assertEquals(sampleBoardData().tasks, repository.tasks.value)
            assertTrue(storage.writes.isEmpty())
            repository.close()
        }
    }

    @Test
    fun failedMutationWriteLeavesTasksUnchangedAndReportsProblem() {
        val storage = MemoryStorage()
        val repository = repository(storage, newId = { TaskId("not-persisted") })
        val before = repository.tasks.value
        storage.failWrites = true

        assertFailsWith<TaskPersistenceException> {
            repository.create(TaskDraft("Must stay absent"))
        }

        assertEquals(before, repository.tasks.value)
        assertEquals(RepositoryProblemKind.STORAGE_WRITE_FAILED, repository.problem.value?.kind)
        repository.clearProblem()
        assertNull(repository.problem.value)
        repository.close()
    }

    @Test
    fun readFailureShowsSamplesAndAProblem() {
        val storage = MemoryStorage().apply { failReads = true }
        val repository = repository(storage)

        assertEquals(sampleBoardData().tasks, repository.tasks.value)
        assertEquals(RepositoryProblemKind.STORAGE_READ_FAILED, repository.problem.value?.kind)
        assertTrue(storage.writes.isEmpty())
        repository.close()
    }

    @Test
    fun externalValidAndInvalidChangesHaveTruthfulStateAndMessages() {
        val storage = MemoryStorage()
        val changes = RecordingStorageChanges()
        val repository = repository(storage, changes = changes)
        val externalTasks = sampleBoardData().tasks.dropLast(1)

        changes.emit(JSON.encodeToString(BoardData(BOARD_SCHEMA_VERSION, externalTasks)))
        assertEquals(externalTasks, repository.tasks.value)
        assertNull(repository.problem.value)

        val invalidUpdates = listOf(
            "{broken" to RepositoryProblemKind.MALFORMED_DATA,
            """{"schemaVersion":${BOARD_SCHEMA_VERSION + 1},"futureBoard":true}""" to
                RepositoryProblemKind.UNSUPPORTED_SCHEMA,
            JSON.encodeToString(
                BoardData(
                    BOARD_SCHEMA_VERSION,
                    externalTasks.mapIndexed { index, task ->
                        if (index == 0) task.copy(title = " ") else task
                    },
                ),
            ) to RepositoryProblemKind.INVALID_DATA,
        )
        for ((value, expectedKind) in invalidUpdates) {
            changes.emit(value)
            assertEquals(externalTasks, repository.tasks.value)
            assertEquals(expectedKind, repository.problem.value?.kind)
            assertContains(repository.problem.value!!.message, "The current board was kept")
            assertTrue("Sample tasks are shown" !in repository.problem.value!!.message)
        }

        repository.close()
        assertTrue(changes.closed)
        changes.emit(JSON.encodeToString(sampleBoardData()))
        assertEquals(externalTasks, repository.tasks.value)
    }

    @Test
    fun removedExternalValueShowsSamplesWithoutWritingThemBack() {
        val storage = MemoryStorage()
        val changes = RecordingStorageChanges()
        val repository = repository(storage, changes = changes)
        repository.delete(repository.tasks.value.first().id)
        val writesBeforeExternalRemoval = storage.writes.size

        changes.emit(null)

        assertEquals(sampleBoardData().tasks, repository.tasks.value)
        assertEquals(writesBeforeExternalRemoval, storage.writes.size)
        repository.close()
    }

    @Test
    fun invalidDraftsAndUnknownIdsDoNotWrite() {
        val storage = MemoryStorage()
        val repository = repository(storage)
        val writesAfterSeed = storage.writes.size

        assertFailsWith<IllegalArgumentException> { repository.create(TaskDraft(" ")) }
        assertFailsWith<NoSuchElementException> {
            repository.update(TaskId("missing"), TaskDraft("Title"))
        }
        assertFailsWith<NoSuchElementException> {
            repository.delete(TaskId("missing"))
        }
        assertFailsWith<NoSuchElementException> {
            repository.move(TaskId("missing"), TaskStatus.DONE, 0)
        }
        assertEquals(writesAfterSeed, storage.writes.size)
        repository.close()
    }

    private fun repository(
        storage: MemoryStorage,
        changes: StorageChangeSource = StorageChangeSource.None,
        newId: () -> TaskId = { TaskId("created") },
    ) = PersistentTaskRepository(
        storage = storage,
        storageChanges = changes,
        json = JSON,
        newId = newId,
        now = { Instant.parse("2026-07-24T08:00:00Z") },
    )

    private fun assertStoredEqualsState(storage: MemoryStorage, repository: TaskRepository) {
        val stored = JSON.decodeFromString<BoardData>(storage.values.getValue(BOARD_STORAGE_KEY))
        assertEquals(BOARD_SCHEMA_VERSION, stored.schemaVersion)
        assertEquals(repository.tasks.value, stored.tasks)
    }

    private fun assertContiguous(tasks: List<Task>) {
        for (status in TaskStatus.entries) {
            assertEquals(
                tasks.tasksIn(status).indices.toList(),
                tasks.tasksIn(status).map(Task::order),
                "$status order",
            )
        }
    }

    private fun List<Task>.tasksIn(status: TaskStatus): List<Task> = filter { it.status == status }

    private class MemoryStorage(
        val values: MutableMap<String, String> = mutableMapOf(),
    ) : KeyValueStorage {
        val writes = mutableListOf<Pair<String, String>>()
        var failReads = false
        var failWrites = false

        override fun read(key: String): String? {
            if (failReads) error("read failed")
            return values[key]
        }

        override fun write(key: String, value: String) {
            if (failWrites) error("write failed")
            values[key] = value
            writes += key to value
        }
    }

    private class RecordingStorageChanges : StorageChangeSource {
        private var listener: ((String?) -> Unit)? = null
        var closed = false
            private set

        override fun subscribe(key: String, listener: (String?) -> Unit): AutoCloseable {
            assertEquals(BOARD_STORAGE_KEY, key)
            this.listener = listener
            return AutoCloseable {
                closed = true
                this.listener = null
            }
        }

        fun emit(value: String?) {
            listener?.invoke(value)
        }
    }

    companion object {
        private val JSON = PersistentTaskRepository.DEFAULT_JSON
    }
}
