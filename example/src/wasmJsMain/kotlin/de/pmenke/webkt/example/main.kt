package de.pmenke.webkt.example

import de.pmenke.webkt.Component
import de.pmenke.webkt.constructComponent
import de.pmenke.webkt.example.components.App
import de.pmenke.webkt.example.repository.TaskRepository
import de.pmenke.webkt.koin_interop.KoinComponentEnvironment
import de.pmenke.webkt.services.NavigatorService
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.html.dom.createTree
import org.koin.core.context.startKoin
import org.w3c.dom.events.Event

@JsFun("(event) => event.persisted === true")
private external fun isPersistedPageHide(event: Event): Boolean

fun main() {
    val resources = ApplicationResources()

    try {
        val application = resources.ownApplication(
            startKoin { modules(appModule) },
            close = { it.close() },
        )
        val rootScope = resources.ownRootScope(
            application.koin.createScope<Component>("example-root"),
            close = { it.close() },
        )
        val repository = resources.ownRepository(application.koin.get<TaskRepository>())
        val navigator = resources.ownNavigator(application.koin.get<NavigatorService>())

        val pageHideListener: (Event) -> Unit = { event ->
            if (!isPersistedPageHide(event)) resources.close()
        }
        window.addEventListener("pagehide", pageHideListener)
        resources.ownPageHideListener {
            window.removeEventListener("pagehide", pageHideListener)
        }

        val component = resources.ownRoot(
            constructComponent {
                App(
                    environment = KoinComponentEnvironment(rootScope),
                    repository = repository,
                    navigator = navigator,
                )
            },
        )
        val element = document.createTree().let { consumer ->
            component.renderTo(consumer)
            consumer.finalize()
        }
        document.body?.append(element)
            ?: error("The document has no body")
    } catch (startupFailure: Throwable) {
        try {
            resources.close()
        } catch (cleanupFailure: Throwable) {
            startupFailure.addSuppressed(cleanupFailure)
        }
        throw startupFailure
    }
}
