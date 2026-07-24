package de.pmenke.webkt.example.components

import de.pmenke.webkt.example.domain.Task
import de.pmenke.webkt.example.domain.TaskId
import de.pmenke.webkt.example.repository.KeyValueStorage
import de.pmenke.webkt.example.repository.PersistentTaskRepository
import de.pmenke.webkt.testing.renderChildComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asPromise
import kotlinx.coroutines.cancel
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskTableTest {
    private val testScope = CoroutineScope(Dispatchers.Default)

    @AfterTest
    fun closeTestScope() {
        testScope.cancel()
    }

    @Test
    fun searchFilterSortAndEditUseTheSharedTaskState(): Promise<JsAny?> = testScope.async {
        val repository = PersistentTaskRepository(MapStorage())
        var editedTask: Task? = null
        val fixture = renderChildComponent { parent ->
            TaskTable(parent, repository) { editedTask = it }
        }

        try {
            fixture.input("#task-search", "task table")
            fixture.awaitUntil {
                fixture.container.querySelectorAll("tbody tr").length == 1 &&
                    fixture.container.querySelector(".task-result-count")?.textContent
                        ?.contains("Showing 1 of 9 tasks") == true
            }
            assertEquals(
                "Add the task table",
                fixture.query(".task-table__title").textContent,
            )

            fixture.click(".clear-filters")
            fixture.awaitUntil { fixture.container.querySelectorAll("tbody tr").length == 9 }

            fixture.click("#task-filter-status-1")
            fixture.awaitUntil { fixture.container.querySelectorAll("tbody tr").length == 3 }
            assertTrue(
                fixture.container.querySelectorAll("tbody .status-in_progress").length == 3,
            )

            fixture.click("thead th:first-child .sort-button")
            fixture.awaitUntil {
                fixture.container.querySelector("thead th:first-child")
                    ?.getAttribute("aria-sort") == "ascending" &&
                    fixture.container.querySelector("tbody .task-table__title")
                        ?.textContent == "Add the task table"
            }

            fixture.click("tbody tr:first-child .button-link")
            assertEquals(TaskId("sample-table"), editedTask?.id)
        } finally {
            fixture.close()
            repository.close()
        }
    }.asPromise()

    private class MapStorage : KeyValueStorage {
        private val values = mutableMapOf<String, String>()

        override fun read(key: String): String? = values[key]

        override fun write(key: String, value: String) {
            values[key] = value
        }
    }
}
