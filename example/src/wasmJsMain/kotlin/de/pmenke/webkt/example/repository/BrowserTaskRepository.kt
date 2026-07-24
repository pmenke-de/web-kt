package de.pmenke.webkt.example.repository

import kotlinx.browser.window
import org.w3c.dom.Storage
import org.w3c.dom.events.Event

@JsFun("(event) => event.key")
private external fun storageEventKey(event: Event): String?

@JsFun("(event) => event.newValue")
private external fun storageEventNewValue(event: Event): String?

@JsFun("(event) => event.storageArea")
private external fun storageEventArea(event: Event): Storage?

/** Browser `localStorage` adapter. */
class BrowserKeyValueStorage(
    private val storage: Storage? = null,
) : KeyValueStorage {
    override fun read(key: String): String? = browserStorage().getItem(key)

    override fun write(key: String, value: String) {
        browserStorage().setItem(key, value)
    }

    // Resolve lazily so browsers that deny storage access report through RepositoryProblem.
    private fun browserStorage(): Storage = storage ?: window.localStorage
}

/** Cross-tab change source backed by the browser's global `storage` event. */
object BrowserStorageChangeSource : StorageChangeSource {
    override fun subscribe(key: String, listener: (String?) -> Unit): AutoCloseable {
        val eventListener: (Event) -> Unit = { event ->
            if (
                isExpectedStorageChange(
                    expectedKey = key,
                    eventKey = storageEventKey(event),
                    eventStorage = storageEventArea(event),
                    expectedStorage = { window.localStorage },
                )
            ) {
                listener(storageEventNewValue(event))
            }
        }
        window.addEventListener("storage", eventListener)
        return AutoCloseable { window.removeEventListener("storage", eventListener) }
    }
}

/**
 * Filters browser storage events without eagerly touching `window.localStorage`.
 *
 * Access to `localStorage` can itself throw when browser policy denies storage. Such an event is
 * ignored; normal repository reads still surface the denial through [RepositoryProblem].
 */
internal fun <S : Any> isExpectedStorageChange(
    expectedKey: String,
    eventKey: String?,
    eventStorage: S?,
    expectedStorage: () -> S,
): Boolean {
    if (eventKey != expectedKey || eventStorage == null) return false
    val localStorage = try {
        expectedStorage()
    } catch (_: Throwable) {
        return false
    }
    return eventStorage === localStorage
}

/** Creates the application repository with persistence and cross-tab synchronization. */
fun createBrowserTaskRepository(): TaskRepository = PersistentTaskRepository(
    storage = BrowserKeyValueStorage(),
    storageChanges = BrowserStorageChangeSource,
)
