// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.config

import com.hyindex.knowledge.core.logging.LogProvider
import com.hyindex.knowledge.core.logging.StdoutLogProvider
import java.io.File


class ConfigFileWatcher(
    private val file: File,
    private val pollIntervalMs: Long = 5000,
    private val log: LogProvider = StdoutLogProvider,
    private val onChange: () -> Unit,
) {
    @Volatile
    private var running = false
    private var lastMtime = 0L
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        lastMtime = if (file.exists()) file.lastModified() else 0L

        thread = Thread({
            while (running) {
                try {
                    Thread.sleep(pollIntervalMs)
                    if (!running) break

                    val currentMtime = if (file.exists()) file.lastModified() else 0L
                    if (currentMtime != lastMtime && currentMtime > 0) {
                        lastMtime = currentMtime
                        log.info("Config file changed: ${file.name}")
                        onChange()
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    log.warn("Config watcher error", e)
                }
            }
        }, "hyindex-config-watcher").apply {
            isDaemon = true
        }
        thread!!.start()
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }
}
