package de.pmenke.webkt.js_interop

import de.pmenke.webkt.js_interop.PromiseUtil.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asPromise
import kotlinx.coroutines.async
import kotlin.js.Promise
import kotlin.js.toJsString
import kotlin.test.Test
import kotlin.test.assertFails

class PromiseUtilTest {
    @Test
    fun resumesWhenAJavaScriptValueIsRejected(): Promise<JsAny?> = CoroutineScope(Dispatchers.Main).async {
        assertFails {
            Promise.reject("rejected".toJsString()).await()
        }
    }.asPromise()
}
