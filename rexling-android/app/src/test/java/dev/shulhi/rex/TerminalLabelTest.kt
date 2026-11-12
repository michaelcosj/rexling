package dev.shulhi.rex

import org.junit.Assert.*
import org.junit.Test

class TerminalLabelTest {
    @Test fun labelsSupportResetAndUnicodeButRejectControlCharactersAndOversize() {
        assertNull(terminalLabelError(""))
        assertNull(terminalLabelError("🦀".repeat(128)))
        assertNotNull(terminalLabelError("🦀".repeat(129)))
        assertNotNull(terminalLabelError("hello\nworld"))
        assertNotNull(terminalLabelError("hello\u0000world"))
    }
}
