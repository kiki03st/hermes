package com.hermes.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class MimeTypeTest {

    @Test
    fun `guessMimeType maps known extensions`() {
        assertEquals("text/markdown", guessMimeType("report.md"))
        assertEquals("text/plain", guessMimeType("notes.txt"))
        assertEquals("application/pdf", guessMimeType("doc.pdf"))
        assertEquals("text/csv", guessMimeType("data.csv"))
        assertEquals("application/json", guessMimeType("out.json"))
        assertEquals("application/zip", guessMimeType("archive.zip"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            guessMimeType("report.docx"),
        )
    }

    @Test
    fun `guessMimeType is case-insensitive on the extension`() {
        assertEquals("text/markdown", guessMimeType("REPORT.MD"))
    }

    @Test
    fun `guessMimeType falls back to octet-stream for unknown or missing extensions`() {
        assertEquals("application/octet-stream", guessMimeType("weird.xyz123"))
        assertEquals("application/octet-stream", guessMimeType("noextension"))
    }
}
