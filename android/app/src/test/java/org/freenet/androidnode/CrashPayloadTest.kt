package org.freenet.androidnode

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashPayloadTest {
    @Test
    fun missingLogFilesAreReportedOnce() {
        val body = buildCrashBody("boom", "", false)
        assertTrue(body.contains("No log files were on disk."))
        assertTrue(body.contains("boom"))
    }

    @Test
    fun existingLogFilesAreNotMentionedInTheHeader() {
        val body = buildCrashBody("boom", "", true)
        assertFalse(body.contains("No log files were on disk."))
    }

    @Test
    fun ringIsAppendedWhenPresent() {
        val body = buildCrashBody("boom", "ERROR panic", true)
        assertTrue(body.contains("--- ring captured at crash ---"))
        assertTrue(body.contains("ERROR panic"))
    }

    @Test
    fun emptyDetailIsMarked() {
        assertTrue(buildCrashBody("", "", true).contains("(none)"))
    }
}
