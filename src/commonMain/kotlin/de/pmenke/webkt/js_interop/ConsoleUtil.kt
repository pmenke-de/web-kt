package de.pmenke.webkt.js_interop

import de.pmenke.webkt.js_interop.JsUtil.toJsAny
import web.console.Console

/**
 * Extensions on [web.console.Console] to accept kotlin [Any] types.
 */
object ConsoleUtil {
    /** Writes Kotlin values through the browser console's general logging channel. */
    fun Console.log(vararg args: Any?) {
        log(*args.map { it?.toJsAny() }.toTypedArray())
    }

    /** Writes Kotlin values through the browser console's error channel. */
    fun Console.error(vararg args: Any?) {
        error(*args.map { it?.toJsAny() }.toTypedArray())
    }

    /** Writes Kotlin values through the browser console's informational channel. */
    fun Console.info(vararg args: Any?) {
        info(*args.map { it?.toJsAny() }.toTypedArray())
    }

    /** Writes Kotlin values through the browser console's warning channel. */
    fun Console.warn(vararg args: Any?) {
        warn(*args.map { it?.toJsAny() }.toTypedArray())
    }
}
