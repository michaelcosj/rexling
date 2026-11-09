package dev.shulhi.rex

import org.junit.Assert.assertEquals
import org.junit.Test

class PredictiveEchoTest {
    @Test fun defaultsToWhenSlowAndMatchesTheEngineNames() {
        assertEquals(PredictiveEcho.SLOW,PredictiveEcho.fromPreference(null))
        assertEquals(PredictiveEcho.SLOW,PredictiveEcho.fromPreference("unknown"))
        assertEquals(PredictiveEcho.OFF,PredictiveEcho.fromPreference("OFF"))
        assertEquals(listOf("off","slow","always"),PredictiveEcho.entries.map {it.wire})
        // Bit 30 is free above the engine's row flags (bits 27–29).
        assertEquals(1 shl 30,PredictiveEcho.PREDICTED_CELL)
    }
}
