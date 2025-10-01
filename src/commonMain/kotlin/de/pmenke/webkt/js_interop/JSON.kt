package de.pmenke.webkt.js_interop

/**
 * A wrapper to access JS native JSON methods.
 */
object JSON {
    fun stringify(value: JsAny?): String = jsonStringify(value)
    fun parse(value: String): JsAny? = jsonParse(value)
}

private fun jsonStringify(value: JsAny?): String = js("JSON.stringify(value)")
private fun jsonParse(value: String): JsAny? = js("JSON.parse(value)")