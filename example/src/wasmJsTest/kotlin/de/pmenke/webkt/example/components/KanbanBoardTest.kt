package de.pmenke.webkt.example.components

import de.pmenke.webkt.Component
import de.pmenke.webkt.RenderReceiver
import de.pmenke.webkt.constructComponent
import de.pmenke.webkt.example.components.KanbanBoard.TaskCard
import de.pmenke.webkt.example.domain.TaskId
import de.pmenke.webkt.example.domain.TaskStatus
import de.pmenke.webkt.example.repository.KeyValueStorage
import de.pmenke.webkt.example.repository.PersistentTaskRepository
import de.pmenke.webkt.example.repository.TaskRepository
import de.pmenke.webkt.koin_interop.KoinComponentEnvironment
import de.pmenke.webkt.testing.renderChildComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asPromise
import kotlinx.coroutines.cancel
import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@JsFun("() => new DataTransfer()")
private external fun createDataTransfer(): JsAny

@JsFun("(type, dataTransfer) => new DragEvent(type, { bubbles: true, cancelable: true, dataTransfer: dataTransfer })")
private external fun dragEvent(type: String, dataTransfer: JsAny): Event

@JsFun("(type) => new Event(type, { bubbles: true, cancelable: true })")
private external fun bubblingEvent(type: String): Event

class KanbanBoardTest {
    private val testScope = CoroutineScope(Dispatchers.Default)

    @AfterTest
    fun closeTestScope() {
        testScope.cancel()
    }

    @Test
    fun keyboardMoveUpdatesRepositoryAndRenderedColumn(): Promise<JsAny?> = testScope.async {
        val repository = PersistentTaskRepository(MapStorage())
        val application = koinApplication {
            modules(
                module {
                    single<TaskRepository> { repository }
                    factoryOf(::TaskCard)
                },
            )
        }
        val rootScope = application.koin.createScope<Component>("board-test-root")
        val fixture = renderChildComponent(
            environment = KoinComponentEnvironment(rootScope),
        ) { parent ->
            KanbanBoard(parent, repository) {}
        }

        try {
            val taskId = TaskId("sample-research")
            fixture.click("[data-task-id='${taskId.value}'] [data-action='move-right']")
            fixture.awaitUntil {
                repository.tasks.value.single { it.id == taskId }.status == TaskStatus.IN_PROGRESS &&
                    fixture.container.querySelector(
                        "[data-status='IN_PROGRESS'] [data-task-id='${taskId.value}']",
                    ) != null
            }

            assertEquals(
                TaskStatus.IN_PROGRESS,
                repository.tasks.value.single { it.id == taskId }.status,
            )
            assertNotNull(
                fixture.container.querySelector(
                    "[data-status='IN_PROGRESS'] [data-task-id='${taskId.value}']",
                ),
            )
        } finally {
            fixture.close()
            rootScope.close()
            repository.close()
            application.close()
        }
    }.asPromise()

    @Test
    fun sameColumnDropUsesTheVisibleSlotAfterRemovingTheDraggedCard(): Promise<JsAny?> = testScope.async {
        val repository = PersistentTaskRepository(MapStorage())
        val application = koinApplication {
            modules(
                module {
                    single<TaskRepository> { repository }
                    factoryOf(::TaskCard)
                },
            )
        }
        val rootScope = application.koin.createScope<Component>("board-drag-test-root")
        val fixture = renderChildComponent(
            environment = KoinComponentEnvironment(rootScope),
        ) { parent ->
            KanbanBoard(parent, repository) {}
        }

        try {
            val transfer = createDataTransfer()
            fixture.dispatch(
                "[data-task-id='sample-research'] article",
                dragEvent("dragstart", transfer),
            )
            fixture.dispatch(
                "[data-status='BACKLOG'] [data-drop-index='2']",
                dragEvent("drop", transfer),
            )
            fixture.awaitUntil {
                repository.tasks.value
                    .filter { it.status == TaskStatus.BACKLOG }
                    .map { it.id.value } ==
                    listOf("sample-empty-state", "sample-research", "sample-copy")
            }

            assertEquals(
                listOf("sample-empty-state", "sample-research", "sample-copy"),
                repository.tasks.value
                    .filter { it.status == TaskStatus.BACKLOG }
                    .map { it.id.value },
            )
        } finally {
            fixture.close()
            rootScope.close()
            repository.close()
            application.close()
        }
    }.asPromise()

    @Test
    fun persistentEditorKeepsDraftWhenParentRendersAgain(): Promise<JsAny?> = testScope.async {
        val repository = PersistentTaskRepository(MapStorage())
        val fixture = renderChildComponent { parent ->
            EditorHost(parent, repository)
        }

        try {
            fixture.component.editor.createTask()
            fixture.awaitUntil { fixture.container.querySelector("#task-title") != null }
            fixture.input("#task-title", "Draft survives")

            fixture.awaitRender {
                fixture.component.renderAgain()
            }

            val title = fixture.queryAs<HTMLInputElement>("#task-title")
            assertEquals("Draft survives", title.value)
            assertTrue(fixture.component.editor.isOpen)

            fixture.click("[data-action='cancel']")
            fixture.awaitUntil { fixture.container.querySelector("#task-title") == null }
            title.value = "Detached input must not mutate the draft"
            title.dispatchEvent(bubblingEvent("input"))

            assertEquals("Draft survives", fixture.component.editor.draftTitle)
        } finally {
            fixture.close()
            repository.close()
        }
    }.asPromise()

    private class EditorHost(
        parent: Component,
        repository: PersistentTaskRepository,
    ) : Component(parent, "editor-test-host") {
        val editor = constructComponent { TaskEditor(this, repository) }

        override fun RenderReceiver.renderContents() {
            render(editor)
        }

        fun renderAgain() = requestUpdate()
    }

    private class MapStorage : KeyValueStorage {
        private val values = mutableMapOf<String, String>()

        override fun read(key: String): String? = values[key]

        override fun write(key: String, value: String) {
            values[key] = value
        }
    }
}
