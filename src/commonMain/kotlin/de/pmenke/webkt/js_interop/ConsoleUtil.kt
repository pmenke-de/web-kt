package de.pmenke.webkt.js_interop

import de.pmenke.webkt.js_interop.JsUtil.toJsAny
import web.console.Console

/**
 * Extensions on [web.console.Console] to accept kotlin [Any] types.
 */
object ConsoleUtil {
    fun Console.log(vararg args: Any?) {
        log(*args.map { it?.toJsAny() }.toTypedArray())
    }

    fun Console.error(vararg args: Any?) {
        error(*args.map { it?.toJsAny() }.toTypedArray())
    }
}