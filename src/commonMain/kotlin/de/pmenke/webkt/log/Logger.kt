package de.pmenke.webkt.log

import de.pmenke.webkt.js_interop.ConsoleUtil.error
import de.pmenke.webkt.js_interop.ConsoleUtil.log
import de.pmenke.webkt.util.Debug.ifDebugEnabled
import de.pmenke.webkt.util.PrefixMap
import web.console.console

class Logger(
    private val name: String,
) {
    private val printName: String = name
        .split('.')
        .dropLast(1)
        .joinToString(".") { it[0].toString() } + "." + name.split('.').last()

    fun debug(vararg args: Any?, aspect: LoggingAspect? = null) = ifDebugEnabled {
        if (!shouldLog(LogLevel.DEBUG, aspect)) return
        log(LogLevel.DEBUG, *args)
    }

    inline fun debug(aspect: LoggingAspect? = null, vararg args: Any?, messageBuilder: () -> String) = ifDebugEnabled {
        if (!shouldLog(LogLevel.DEBUG, aspect)) return
        log(LogLevel.DEBUG, messageBuilder(), *args)
    }

    fun info(vararg args: Any?, aspect: LoggingAspect? = null) {
        if (!shouldLog(LogLevel.INFO, aspect)) return
        log(LogLevel.INFO, *args)
    }

    fun info(aspect: LoggingAspect? = null, messageBuilder: () -> String) {
        if (!shouldLog(LogLevel.INFO, aspect)) return
        log(LogLevel.INFO, messageBuilder())
    }

    fun warn(vararg args: Any?, aspect: LoggingAspect? = null) {
        if (!shouldLog(LogLevel.WARN, aspect)) return
        log(LogLevel.WARN, *args)
    }

    fun warn(aspect: LoggingAspect? = null, messageBuilder: () -> String) {
        if (!shouldLog(LogLevel.WARN, aspect)) return
        log(LogLevel.WARN, messageBuilder())
    }

    fun error(vararg args: Any?, aspect: LoggingAspect? = null) {
        if (!shouldLog(LogLevel.ERROR, aspect)) return
        log(LogLevel.ERROR, *args)
    }

    fun error(aspect: LoggingAspect? = null, messageBuilder: () -> String) {
        if (!shouldLog(LogLevel.ERROR, aspect)) return
        log(LogLevel.ERROR, messageBuilder())
    }

    @PublishedApi
    internal fun log(level: LogLevel, vararg args: Any?) {
        if (level == LogLevel.ERROR) {
            console.error("[$printName][${level.name}] ", *args)
        } else {
            console.log("[$printName][${level.name}]", *args)
        }
    }

    @PublishedApi
    internal fun shouldLog(level: LogLevel, aspect: LoggingAspect?): Boolean {
        return (aspect != null && LoggingConfig.aspectLevels[aspect]?.let { it.ordinal <= level.ordinal } == true)
                || (LoggingConfig.levels.longestPrefixMatch(name)?.let { it.ordinal <= level.ordinal } == true)
    }
}

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

enum class LoggingAspect {
    HTTP_REQUEST,
    RENDERING,
    LOGIC,
    LIFECYCLE,
}

object LoggingConfig {
    internal val levels = PrefixMap<LogLevel>()
    internal val aspectLevels: MutableMap<LoggingAspect, LogLevel> = mutableMapOf()

    fun setLevel(name: String, level: LogLevel) {
        levels.insert(name, level)
    }

    fun setAspectLevel(aspect: LoggingAspect, level: LogLevel) {
        aspectLevels[aspect] = level
    }
}