package com.silenthub.capturelab

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateManifestTest {
    @Test fun semanticVersionStringIsStable() {
        assertEquals("2.10.3", SemanticVersion.parse("2.10.3").toString())
    }
}