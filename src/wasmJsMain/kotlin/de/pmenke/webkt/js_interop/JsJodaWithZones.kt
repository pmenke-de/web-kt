package de.pmenke.webkt.js_interop

// magic snippet from https://github.com/Kotlin/kotlinx-datetime/blob/af003a396e47aa5a8e25cda436902b65ad821d23/js-with-timezones/src/wasmJsMain/kotlin/JsJodaTimeZoneModule.kt
// which is needed to kotlinx-datetime work with the js-joda-timezone module

@JsModule("@js-joda/timezone")
external object JsJodaTimeZoneModule

private val jsJodaTz = JsJodaTimeZoneModule