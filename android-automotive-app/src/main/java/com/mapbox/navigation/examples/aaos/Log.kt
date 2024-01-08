package com.mapbox.navigation.examples.aaos

import android.util.Log

sealed class LogLevel(val logLevel: Int) {
    object Debug : LogLevel(0)
    object Info : LogLevel(1)
    object Warning : LogLevel(2)
    object Error : LogLevel(3)
}

object Log {

    var currentLevel: LogLevel? = null

    fun d(tag: String, block: () -> String) = println(LogLevel.Debug, tag, block)
    fun i(tag: String, block: () -> String) = println(LogLevel.Info, tag, block)
    fun w(tag: String, block: () -> String) = println(LogLevel.Warning, tag, block)
    fun e(tag: String, block: () -> String) = println(LogLevel.Error, tag, block)

    private inline fun println(level: LogLevel, tag: String, block: () -> String) {
        val currentLevel = this.currentLevel ?: return
        if (level.logLevel >= currentLevel.logLevel) {
            when (level) {
                LogLevel.Debug -> Log.d(tag, block())
                LogLevel.Info -> Log.i(tag, block())
                LogLevel.Warning -> Log.w(tag, block())
                LogLevel.Error -> Log.e(tag, block())
            }
        }
    }
}
