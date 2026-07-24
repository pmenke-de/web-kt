package de.pmenke.webkt.example.components

import de.pmenke.webkt.Component
import de.pmenke.webkt.ComponentEnvironment
import de.pmenke.webkt.RenderReceiver
import de.pmenke.webkt.example.domain.Task
import de.pmenke.webkt.example.domain.TaskStatus
import de.pmenke.webkt.example.repository.TaskPersistenceException
import de.pmenke.webkt.example.repository.TaskRepository
import de.pmenke.webkt.koin_interop.getComponent
import de.pmenke.webkt.services.NavigatorService
import kotlinx.html.ButtonType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.footer
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.header
import kotlinx.html.js.onClickFunction
import kotlinx.html.main
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.strong
import org.koin.core.parameter.parametersOf

/**
 * Composition root for the application component tree.
 *
 * [renderFailure] handles a failed update render of this component. Flow-driven inline children
 * schedule their own later renders, so their asynchronous failures do not travel back through an
 * already completed parent render.
 */
class App(
    environment: ComponentEnvironment,
    private val repository: TaskRepository,
    private val navigator: NavigatorService,
) : Component(environment, "kanban-app") {
    private val editor: TaskEditor by lazy { getComponent<TaskEditor>() }

    override fun RenderReceiver.renderContents() {
        header("app-header") {
            div("app-header__title") {
                span("eyebrow") { +"WebKt example" }
                h1 { +"Flowboard" }
            }
            button(type = ButtonType.button, classes = "button button--primary") {
                +"New task"
                onClickFunction = { editor.createTask() }
            }
        }

        inlineFlowComponent("div", navigator.path, classes = "app-navigation-state") { path ->
            nav("app-navigation") {
                attributes["aria-label"] = "Primary"
                navigationLink("/board", "Board", path == "/" || path == "/board")
                navigationLink("/tasks", "Task list", path == "/tasks")
            }

            inlineFlowComponent("section", repository.tasks, classes = "board-summary") { tasks ->
                summary("Total", tasks.size)
                summary("In progress", tasks.count { it.status == TaskStatus.IN_PROGRESS })
                summary("Done", tasks.count { it.status == TaskStatus.DONE })
            }

            inlineFlowComponent("div", repository.problem, classes = "repository-problem-host") { problem ->
                if (problem != null) {
                    div("problem-banner") {
                        attributes["role"] = "alert"
                        div {
                            strong { +"There is a problem with the saved board" }
                            p { +problem.message }
                        }
                        button(type = ButtonType.button, classes = "icon-button") {
                            attributes["aria-label"] = "Dismiss storage problem"
                            +"×"
                            onClickFunction = { repository.clearProblem() }
                        }
                    }
                }
            }

            main("app-main") {
                when (path) {
                    "/", "/board" -> render(
                        getComponent<KanbanBoard> {
                            parametersOf(editor::editTask)
                        },
                    )
                    "/tasks" -> render(
                        getComponent<TaskTable> {
                            parametersOf(editor::editTask)
                        },
                    )
                    else -> render(getComponent<NotFound>())
                }
            }
        }

        footer("app-footer") {
            p { +"Changes are saved locally in this browser." }
            button(type = ButtonType.button, classes = "button-link") {
                +"Reset all data"
                onClickFunction = {
                    if (
                        kotlinx.browser.window.confirm(
                            "Reset all tasks to the bundled sample board? This cannot be undone.",
                        )
                    ) {
                        try {
                            repository.reset()
                        } catch (_: TaskPersistenceException) {
                            // The problem banner remains visible and the current board stays intact.
                        }
                    }
                }
            }
        }

        render(editor)
    }

    override fun RenderReceiver.renderFailure(exception: Throwable): Boolean {
        div("render-failure") {
            attributes["role"] = "alert"
            h2 { +"The interface could not be updated" }
            p { +"Reload the page to return to the last board saved in this browser." }
        }
        return true
    }

    private fun kotlinx.html.NAV.navigationLink(path: String, label: String, active: Boolean) {
        a(href = path, classes = if (active) "is-active" else null) {
            if (active) attributes["aria-current"] = "page"
            +label
        }
    }

    private fun kotlinx.html.TagConsumer<org.w3c.dom.Element>.summary(label: String, value: Int) {
        div("summary-item") {
            span("summary-item__value") { +value.toString() }
            span("summary-item__label") { +label }
        }
    }
}

class NotFound(parent: Component) : Component(parent, "kanban-not-found") {
    override fun RenderReceiver.renderContents() {
        h2("page-heading") { +"Page not found" }
        p { +"That view is not part of this example." }
        a(href = "/board", classes = "button button--primary") { +"Return to the board" }
    }
}
