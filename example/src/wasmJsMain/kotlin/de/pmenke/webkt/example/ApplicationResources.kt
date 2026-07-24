package de.pmenke.webkt.example

/**
 * Owns resources acquired while starting the browser application.
 *
 * Slots are filled immediately after each successful acquisition. [close] always attempts every
 * owned cleanup in UI-to-container order; a cleanup that fails remains registered so a later call
 * can retry it, while successful cleanups are never repeated.
 */
internal class ApplicationResources : AutoCloseable {
    private var shutdownRequested = false
    private var removePageHideListener: (() -> Unit)? = null
    private var root: (() -> Unit)? = null
    private var navigator: (() -> Unit)? = null
    private var repository: (() -> Unit)? = null
    private var rootScope: (() -> Unit)? = null
    private var application: (() -> Unit)? = null

    fun ownPageHideListener(remove: () -> Unit) {
        ensureStarting()
        check(removePageHideListener == null) { "The page-hide listener is already owned" }
        removePageHideListener = remove
    }

    fun <T : AutoCloseable> ownRoot(resource: T): T =
        own(resource, root, "root component", { it.close() }) { root = it }

    fun <T : AutoCloseable> ownNavigator(resource: T): T =
        own(resource, navigator, "navigator", { it.close() }) { navigator = it }

    fun <T : AutoCloseable> ownRepository(resource: T): T =
        own(resource, repository, "repository", { it.close() }) { repository = it }

    fun <T> ownRootScope(resource: T, close: (T) -> Unit): T =
        own(resource, rootScope, "root scope", close) { rootScope = it }

    fun <T> ownApplication(resource: T, close: (T) -> Unit): T =
        own(resource, application, "Koin application", close) { application = it }

    override fun close() {
        shutdownRequested = true
        var failure: Throwable? = null

        fun attempt(action: (() -> Unit)?, clear: () -> Unit) {
            if (action == null) return
            try {
                action()
                clear()
            } catch (exception: Throwable) {
                if (failure == null) failure = exception else failure.addSuppressed(exception)
            }
        }

        attempt(removePageHideListener) { removePageHideListener = null }
        attempt(root) { root = null }
        attempt(navigator) { navigator = null }
        attempt(repository) { repository = null }
        attempt(rootScope) { rootScope = null }
        attempt(application) { application = null }
        failure?.let { throw it }
    }

    private fun <T> own(
        resource: T,
        current: (() -> Unit)?,
        label: String,
        close: (T) -> Unit,
        assign: ((() -> Unit)?) -> Unit,
    ): T {
        ensureStarting()
        check(current == null) { "The $label is already owned" }
        assign { close(resource) }
        return resource
    }

    private fun ensureStarting() {
        check(!shutdownRequested) { "Application shutdown has already started" }
    }
}
