package com.silenthub.capturelab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {
    @Test fun comparesAllNumericComponents() {
        assertTrue(SemanticVersion.parse("1.0.0") > SemanticVersion.parse("0.99.99"))
        assertTrue(SemanticVersion.parse("0.5.0") > SemanticVersion.parse("0.4.99"))
        assertTrue(SemanticVersion.parse("0.4.10") > SemanticVersion.parse("0.4.9"))
    }

    @Test fun equalVersionsCompareEqual() {
        assertEquals(0, SemanticVersion.parse("0.5.0").compareTo(SemanticVersion.parse("0.5.0")))
    }

    @Test fun rejectsMalformedVersions() {
        assertThrows(IllegalArgumentException::class.java) { SemanticVersion.parse("0.5") }
        assertThrows(IllegalArgumentException::class.java) { SemanticVersion.parse("v0.5.0") }
        assertThrows(IllegalArgumentException::class.java) { SemanticVersion.parse("0.05.0") }
    }
}