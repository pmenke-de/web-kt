package de.pmenke.webkt.js_interop

import js.core.JsPrimitives.toByte
import js.typedarrays.Uint8Array
import org.w3c.dom.ItemArrayLike
import org.w3c.dom.events.EventTarget
import kotlin.io.encoding.Base64

/**
 * Utility methods for JS interop.
 */
object JsUtil {
    /**
     * Recursively converts a kotlin [Any] to a [JsAny] on a best-effort basis.
     */
    fun Any.toJsAny(): JsAny {
        return when(this) {
            // types, that are supported by js / console.log can pretty print
            is EventTarget, is JsString, is JsNumber, is JsBoolean,
            is JsException, is JsArray<*>, is JsObject ->
                @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
                this as JsAny
            is Map<*, *> -> toJsObject()
            is Array<*> -> map { it?.toJsAny() }.toJsArray()
            is Iterable<*> -> map { it?.toJsAny() }.toJsArray()
            is Boolean -> toJsBoolean()
            is Int -> toJsNumber()
            is Float -> toDouble().toJsNumber()
            is Double -> toJsNumber()
            is String -> toJsString()
            else ->
                // try and let kotlin handle the conversion
                @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
                this as JsAny
        }
    }

    inline fun <T : JsAny?> JsArray<T>.forEach(action: (T) -> Unit) {
        for (i in 0 until length) {
            @Suppress("UNCHECKED_CAST")
            action(this[i] as T)
        }
    }

    inline fun <T: JsAny?> ItemArrayLike<T>.forEach(action: (T) -> Unit) {
        for (i in 0 until length) {
            @Suppress("UNCHECKED_CAST")
            action(this.item(i) as T)
        }
    }

    fun <T: JsAny?> ItemArrayLike<T>.asSequence() = sequence {
        forEach { yield(it) }
    }

    private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    fun Uint8Array<*>.toBase64Url(): String {
        // would be nice to use `toBase64`, but that's not widely supported in JS (only Firefox and Safari atm)
        val bytes = ByteArray(length) { index -> this[index].toByte() }
        return base64Url.encode(bytes)
    }
}