package de.pmenke.webkt.js_interop

/**
 * A wrapper to access JS native JSON methods.
 */
object JSON {
    /** Serializes [value] with JavaScript's native `JSON.stringify`. */
    fun stringify(value: JsAny?): String = jsonStringify(value)

    /** Parses [value] with JavaScript's native `JSON.parse`. */
    fun parse(value: String): JsAny? = jsonParse(value)
}

private fun jsonStringify(value: JsAny?): String = js("JSON.stringify(value)")
private fun jsonParse(value: String): JsAny? = js("JSON.parse(value)")
