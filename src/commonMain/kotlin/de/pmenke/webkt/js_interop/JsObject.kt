package de.pmenke.webkt.js_interop

import de.pmenke.webkt.js_interop.JsUtil.toJsAny

/**
 * A representation of a JavaScript object, allowing dynamic property access.
 */
@JsName("Object")
open external class JsObject : JsAny {
    operator fun get(key: String): JsAny?
    operator fun set(key: String, value: JsAny?)
}

/** Creates a plain JavaScript object populated from Kotlin key/value pairs. */
fun JsObject(vararg values: Pair<String, Any?>): JsObject {
    val jsObject = JsObject()
    for (pair in values) {
        jsObject[pair.first] = pair.second?.toJsAny()
    }
    return jsObject
}

/** Converts this map to a plain JavaScript object using each key's string representation. */
fun Map<*, *>.toJsObject(): JsObject {
    val jsMap = JsObject()
    forEach { (key, value) ->
        jsMap[key.toString()] = value?.toJsAny()
    }
    return jsMap
}
