package com.example.ibizacustommap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElmReplyTest {

    @Test
    fun clasificaRespuestas() {
        assertEquals(ElmReply.Data("410C1AF8"), ElmReply.classify("410C1AF8"))
        assertEquals(ElmReply.Data("0080:410C1AF80D321:0B65AAAA"), ElmReply.classify("0080:410C1AF80D321:0B65AAAA"))
        assertTrue(ElmReply.classify("NODATA") is ElmReply.NoData)
        assertEquals(ElmReply.Negative("7F0112", 0x12), ElmReply.classify("7F0112"))
        assertTrue(ElmReply.classify("?") is ElmReply.NotUnderstood)
        assertTrue(ElmReply.classify("CANERROR") is ElmReply.BusError)
        assertTrue(ElmReply.classify("UNABLETOCONNECT") is ElmReply.BusError)
        assertTrue(ElmReply.classify("BUSBUSY") is ElmReply.BusError)
        assertTrue(ElmReply.classify("...ERROR") is ElmReply.BusError) // "BUS INIT: ...ERROR" ya limpio
        assertTrue(ElmReply.classify("TIMEOUT") is ElmReply.Timeout)
        assertTrue(ElmReply.classify("OK") is ElmReply.Other)
        assertTrue(ElmReply.classify("") is ElmReply.Other)
    }

    @Test
    fun soloLosDatosLleganALosParsers() {
        assertEquals("410D32", ElmReply.classify("410D32").data)
        assertEquals("", ElmReply.classify("NODATA").data)
        assertEquals("", ElmReply.classify("7F0112").data)
        assertEquals(-1, ObdDecoder.parseSpeed(ElmReply.classify("CANERROR").data))
    }

    @Test
    fun queryRecogeLaRespuestaTardia() {
        val reads = ArrayDeque(listOf("TIMEOUT", "410D32", "410B96"))
        val sent = mutableListOf<String>()
        val io = object : ElmIo {
            override val isConnected = true
            override fun sendCommand(command: String) {
                sent += command
            }
            override fun readResponse(timeoutMs: Long) = reads.removeFirst()
        }

        val first = io.query("01 0D")
        assertEquals(ElmReply.Timeout(late = "410D32"), first)
        assertEquals(ElmReply.Data("410B96"), io.query("01 0B"))
        assertEquals(listOf("01 0D", "01 0B"), sent)
    }
}
