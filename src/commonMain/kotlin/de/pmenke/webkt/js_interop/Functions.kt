package de.pmenke.webkt.js_interop

import js.array.Tuple
import js.function.JsFunction

/**
 * Utility functions to create JS functions from Kotlin lambdas.
 * This is needed in some places; e.g. when JS libraries expect callback functions inside a config object.
 */
object Functions {
    fun <T: JsAny?> function0(block: ()->T): JsFunction<Tuple, T> = jsFunction0(block)
    fun <A: JsAny?, T: JsAny?> function1(block: (A)->T): JsFunction<Tuple, T> = jsFunction1(block)
    fun <A: JsAny?, B: JsAny?, T: JsAny?> function2(block: (A, B)->T): JsFunction<Tuple, T> = jsFunction2(block)
    fun <A: JsAny?, B: JsAny?, C: JsAny?, T: JsAny?> function3(block: (A, B, C)->T): JsFunction<Tuple, T> = jsFunction3(block)
    fun <A: JsAny?, B: JsAny?, C: JsAny?, D: JsAny?, T: JsAny?> function4(block: (A, B, C, D)->T): JsFunction<Tuple, T> = jsFunction4(block)
}

private fun <T: JsAny?> jsFunction0(block: ()->T): JsFunction<Tuple, T> {
    js("return function() { return block() }")
}

private fun <A: JsAny?, T: JsAny?> jsFunction1(block: (A)->T): JsFunction<Tuple, T> {
    js("return function(a) { return block(a) }")
}

private fun <A: JsAny?, B: JsAny?, T: JsAny?> jsFunction2(block: (A, B)->T): JsFunction<Tuple, T> {
    js("return function(a, b) { return block(a, b) }")
}

private fun <A: JsAny?, B: JsAny?, C: JsAny?, T: JsAny?> jsFunction3(block: (A, B, C)->T): JsFunction<Tuple, T> {
    js("return function(a, b, c) { return block(a, b, c) }")
}

private fun <A: JsAny?, B: JsAny?, C: JsAny?, D: JsAny?, T: JsAny?> jsFunction4(block: (A, B, C, D)->T): JsFunction<Tuple, T> {
    js("return function(a, b, c, d) { return block(a, b, c, d) }")
}
