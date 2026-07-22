package de.pmenke.webkt.log

import de.pmenke.webkt.js_interop.ConsoleUtil.error
import de.pmenke.webkt.js_interop.ConsoleUtil.info
import de.pmenke.webkt.js_interop.ConsoleUtil.log
import de.pmenke.webkt.js_interop.ConsoleUtil.warn
import de.pmenke.webkt.util.Debug.ifDebugEnabled
import de.pmenke.webkt.util.PrefixMap
import web.console.console

/**
 * Lightweight browser-console logger with hierarchical name and cross-cutting aspect filtering.
 *
 * A message is written when either the longest configured name prefix or its [LoggingAspect]
 * enables the message's [LogLevel]. Configure filters through [LoggingConfig].
 */
class Logger(
    private val name: String,
) {
    private val printName: String = name.abbreviateLoggerName()

    /** Logs eager [args] at [LogLevel.DEBUG] when [aspect] or this logger's name is enabled. */
    fun debug(vararg args: Any?, aspect: LoggingAspect? = null) {
        ifDebugEnabled {
            if (!shouldLog(LogLevel.DEBUG, aspect)) return
            log(LogLevel.DEBUG, *args)
        }
    }

    /** Builds and logs a debug message lazily, followed by [args], when its filter is enabled. */
    inline fun debug(aspect: LoggingAspect? = null, vararg args: Any?, messageBuilder: () -> String) {
        ifDebugEnabled {
            if (!shouldLog(LogLevel.DEBUG, aspect)) return
            log(LogLevel.DEBUG, messageBuilder(), *args)
        }
    }

    /** Logs eager [args] at [LogLevel.INFO] when [aspect] or this logger's name is enabled. */
    fun info(vararg args: Any?, aspect: LoggingAspect? = null) {
        if (!shouldLog(LogLevel.INFO, aspect)) return
        log(LogLevel.INFO, *args)
    }

    /** Builds and logs an informational message lazily when its filter is enabled. */
    fun info(aspect: LoggingAspect? = null, messageBuilder: () -> String) {
        if (!shouldLog(LogLevel.INFO, aspect)) return
        log(LogLevel.INFO, messageBuilder())
    }

    /** Logs eager [args] at [LogLevel.WARN] when [aspect] or this logger's name is enabled. */
    fun warn(vararg args: Any?, aspect: LoggingAspect? = null) {
        if (!shouldLog(LogLevel.WARN, aspect)) return
        log(LogLevel.WARN, *args)
    }

    /** Builds and logs a warning lazily when its filter is enabled. */
    fun warn(aspect: LoggingAspect? = null, messageBuilder: () -> String) {
        if (!shouldLog(LogLevel.WARN, aspect)) return
        log(LogLevel.WARN, messageBuilder())
    }

    /** Logs eager [args] at [LogLevel.ERROR] when [aspect] or this logger's name is enabled. */
    fun error(vararg args: Any?, aspect: LoggingAspect? = null) {
        if (!shouldLog(LogLevel.ERROR, aspect)) return
        log(LogLevel.ERROR, *args)
    }

    /** Builds and logs an error message lazily when its filter is enabled. */
    fun error(aspect: LoggingAspect? = null, messageBuilder: () -> String) {
        if (!shouldLog(LogLevel.ERROR, aspect)) return
        log(LogLevel.ERROR, messageBuilder())
    }

    @PublishedApi
    internal fun log(level: LogLevel, vararg args: Any?) {
        val prefix = "[$printName][${level.name}]"
        when (level) {
            LogLevel.DEBUG -> console.log(prefix, *args)
            LogLevel.INFO -> console.info(prefix, *args)
            LogLevel.WARN -> console.warn(prefix, *args)
            LogLevel.ERROR -> console.error(prefix, *args)
        }
    }

    @PublishedApi
    internal fun shouldLog(level: LogLevel, aspect: LoggingAspect?): Boolean {
        return (aspect != null && LoggingConfig.aspectLevels[aspect]?.let { it.ordinal <= level.ordinal } == true)
                || (LoggingConfig.levels.longestPrefixMatch(name)?.let { it.ordinal <= level.ordinal } == true)
    }
}

/** Logging severity, ordered from least to most severe. */
enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

/** Optional cross-cutting category that can be filtered independently from logger names. */
enum class LoggingAspect {
    HTTP_REQUEST,
    RENDERING,
    LOGIC,
    LIFECYCLE,
}

/** Global logging filter configuration. Logging is disabled until a matching filter is configured. */
object LoggingConfig {
    internal val levels = PrefixMap<LogLevel>()
    internal val aspectLevels: MutableMap<LoggingAspect, LogLevel> = mutableMapOf()

    /** Enables [level] and more severe messages for the longest matching logger-name prefix [name]. */
    fun setLevel(name: String, level: LogLevel) {
        levels.insert(name, level)
    }

    /** Enables [level] and more severe messages carrying [aspect], independently of logger name. */
    fun setAspectLevel(aspect: LoggingAspect, level: LogLevel) {
        aspectLevels[aspect] = level
    }

    /** Removes all name and aspect filters, disabling logging globally. */
    fun clear() {
        levels.clear()
        aspectLevels.clear()
    }
}

private fun String.abbreviateLoggerName(): String {
    val segments = split('.').filter(String::isNotEmpty)
    if (segments.size <= 1) return segments.singleOrNull() ?: this
    return segments.dropLast(1).joinToString(".") { it.first().toString() } + "." + segments.last()
}
