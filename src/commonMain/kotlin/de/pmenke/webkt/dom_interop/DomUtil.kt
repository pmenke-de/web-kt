package de.pmenke.webkt.dom_interop

import kotlinx.html.TagConsumer
import kotlinx.html.consumers.FinalizeConsumer
import kotlinx.html.dom.createTree
import kotlinx.serialization.json.Json
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.Storage

/**
 * Utility functions for DOM manipulation and storage.
 */
object DomUtil {

    /**
     * Removes all child nodes from this node - which is no natively supported operation in browsers.
     * This implementation removes all children one by one, starting from the end, which is probably faster
     * than starting from the beginning.
     */
    fun Node.removeAllChildren() {
        while (firstChild != null) {
            // removing from the end is probably faster
            removeChild(lastChild!!)
        }
    }

    /**
     * Replaces this element with a new element created by the provided [block].
     * This element must be attached to a document ([Element.ownerDocument] must be set) for this to work.
     */
    fun Element.replaceWith(block: TagConsumer<Element>.() -> Unit): Element {
        val doc = ownerDocument ?: throw IllegalStateException("Node has no ownerDocument")
        val newElement = with(doc.createTree()) {
            block()
            finalize()
        }
        replaceWith(newElement)
        return newElement
    }

    /**
     * Cast the current [TagConsumer] to return a specific element type.
     * This is useful to adapt the result type of `kotlinx.html` builders to their concrete DOM type.
     * E.g. `cast<HTMLInputElement>().input(type, classes = "form-control")`
     */
    inline fun <reified E: Element> TagConsumer<Element>.cast() = FinalizeConsumer(this) { it, _ -> it as E }

    /**
     * Sets a JSON-serialized value in the [Storage] under the given [key].
     * The value is serialized using `kotlinx.serialization`'s [Json] serializer and
     * thus must be serializable by it.
     */
    inline fun <reified T> Storage.setJson(key: String, value: T) {
        setItem(key, Json.Default.encodeToString<T>(value))
    }

    /**
     * Gets a JSON-deserialized value from the [Storage] under the given [key].
     * The value is deserialized using `kotlinx.serialization`'s [Json] serializer and
     * thus must be deserializable by it.
     * Returns `null`, if the key does not exist in the storage.
     */
    inline fun <reified T> Storage.getJson(key: String): T? {
        val json = getItem(key) ?: return null
        return Json.Default.decodeFromString<T>(json)
    }
}