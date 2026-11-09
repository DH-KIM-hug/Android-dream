package com.androiddream.callrelay

import com.androiddream.callrelay.relay.Payload
import com.androiddream.callrelay.scan.RecordingName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ParsingTest {
    @Test fun koreanSamsungName() {
        val r = RecordingName.parse("통화 녹음 홍길동_241109_143012.m4a")
        assertEquals("홍길동", r.contact)
        assertEquals(LocalDateTime.of(2024, 11, 9, 14, 30, 12), r.dateTime)
    }

    @Test fun englishNameWithSpaces() {
        val r = RecordingName.parse("Call recording John Doe_241109_143012.m4a")
        assertEquals("John Doe", r.contact)
        assertEquals(LocalDateTime.of(2024, 11, 9, 14, 30, 12), r.dateTime)
    }

    @Test fun phoneNumberLongDate() {
        val r = RecordingName.parse("통화 녹음 010-1234-5678_20241109_143012.m4a")
        assertEquals("010-1234-5678", r.contact)
        assertEquals(LocalDateTime.of(2024, 11, 9, 14, 30, 12), r.dateTime)
    }

    @Test fun compactDate() {
        val r = RecordingName.parse("홍길동_20241109143012.amr")
        assertEquals("홍길동", r.contact)
        assertEquals(LocalDateTime.of(2024, 11, 9, 14, 30, 12), r.dateTime)
    }

    @Test fun unknownFormat() {
        val r = RecordingName.parse("meeting.m4a")
        assertEquals("meeting", r.contact)
        assertNull(r.dateTime)
    }

    @Test fun transcriptMatching() {
        val audio = "통화 녹음 홍길동_241109_143012.m4a"
        assertTrue(RecordingName.isTranscriptOf(audio, "통화 녹음 홍길동_241109_143012.txt"))
        assertTrue(RecordingName.isTranscriptOf(audio, "통화 녹음 홍길동_241109_143012 (전사).txt"))
        assertFalse(RecordingName.isTranscriptOf(audio, "통화 녹음 김철수_241109_150000.txt"))
    }

    @Test fun chunkRespectsLimit() {
        val text = (1..100).joinToString(" ") { "단어$it" }
        val parts = Payload.chunk(text, 50)
        assertTrue(parts.all { it.length <= 50 })
        assertEquals(text.replace(" ", ""), parts.joinToString("").replace(" ", ""))
    }
}
