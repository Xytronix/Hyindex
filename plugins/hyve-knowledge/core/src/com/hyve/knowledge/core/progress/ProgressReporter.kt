// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.core.progress


interface ProgressReporter {
    val isCanceled: Boolean
    fun status(message: String)
    fun fraction(value: Double)
}

object NoopProgressReporter : ProgressReporter {
    override val isCanceled: Boolean = false
    override fun status(message: String) {}
    override fun fraction(value: Double) {}
}

class StdoutProgressReporter(private val prefix: String = "") : ProgressReporter {
    @Volatile override var isCanceled: Boolean = false
    var lastStatus: String = ""; private set
    var lastFraction: Double = 0.0; private set

    override fun status(message: String) {
        lastStatus = message
        val pct = (lastFraction * 100).toInt()
        println(if (prefix.isEmpty()) "[$pct%] $message" else "[$prefix $pct%] $message")
    }
    override fun fraction(value: Double) { lastFraction = value.coerceIn(0.0, 1.0) }
}
