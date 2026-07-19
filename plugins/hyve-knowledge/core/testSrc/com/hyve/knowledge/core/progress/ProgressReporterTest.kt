package com.hyve.knowledge.core.progress

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ProgressReporterTest {
    @Test fun `noop reporter never cancels and accepts updates`() {
        val r: ProgressReporter = NoopProgressReporter
        r.status("parsing")
        r.fraction(0.5)
        assertFalse(r.isCanceled)
    }

    @Test fun `stdout reporter records last status and fraction`() {
        val r = StdoutProgressReporter(prefix = "code")
        r.status("embedding")
        r.fraction(0.42)
        assertEquals("embedding", r.lastStatus)
        assertEquals(0.42, r.lastFraction, 1e-9)
        assertFalse(r.isCanceled)
    }
}
