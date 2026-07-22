package de.pmenke.webkt.log

import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * Creates a CoroutineExceptionHandler that logs unhandled exceptions using the provided logger.
 */
@Suppress("FunctionName")
fun LoggingErrorHandler(logger: Logger): CoroutineExceptionHandler = CoroutineExceptionHandler { context, throwable ->
    logger.error { "unhandled exception in coroutine-context $context: ${throwable.stackTraceToString()}" }
}
