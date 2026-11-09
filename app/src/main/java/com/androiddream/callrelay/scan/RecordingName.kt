package com.androiddream.callrelay.scan

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 삼성 통화녹음 파일명에서 상대방/일시를 추출한다.
 *
 * 예)
 *  - "통화 녹음 홍길동_241109_143012.m4a"
 *  - "Call recording John Doe_241109_143012.m4a"
 *  - "통화 녹음 010-1234-5678_20241109_143012.m4a"
 *  - "홍길동_20241109143012.m4a"
 */
data class RecordingName(val contact: String, val dateTime: LocalDateTime?) {
    companion object {
        private val PREFIXES = listOf("통화 녹음", "통화녹음", "Call recording", "Call Recording", "call_rec", "Call")
        private val SPLIT = Regex("""^(.*?)[_ ](\d{6}|\d{8})[_ ]?(\d{6})$""")
        private val COMPACT = Regex("""^(.*?)[_ ](\d{14})$""")
        val AUDIO_EXT = setOf("m4a", "mp3", "amr", "3gp", "wav", "ogg", "aac", "opus", "flac", "mp4")
        val TEXT_EXT = setOf("txt")

        fun baseName(fileName: String): String = fileName.substringBeforeLast('.', fileName)
        fun ext(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()

        fun parse(fileName: String): RecordingName {
            var base = baseName(fileName).trim()
            for (p in PREFIXES) {
                if (base.startsWith(p, ignoreCase = true) && base.length > p.length) {
                    base = base.substring(p.length).trimStart(' ', '_', '-')
                    break
                }
            }
            SPLIT.matchEntire(base)?.let { m ->
                val (who, d, t) = m.destructured
                return RecordingName(cleanContact(who), parseDate(d, t))
            }
            COMPACT.matchEntire(base)?.let { m ->
                val (who, dt) = m.destructured
                return RecordingName(cleanContact(who), parseDate(dt.substring(0, 8), dt.substring(8)))
            }
            return RecordingName(cleanContact(base), null)
        }

        private fun cleanContact(s: String) = s.trim(' ', '_', '-').ifEmpty { "알 수 없음" }

        private fun parseDate(d: String, t: String): LocalDateTime? = runCatching {
            val pattern = if (d.length == 6) "yyMMddHHmmss" else "yyyyMMddHHmmss"
            LocalDateTime.parse(d + t, DateTimeFormatter.ofPattern(pattern))
        }.getOrNull()

        /** 녹음 파일과 전사 텍스트 파일이 같은 통화인지 이름으로 판단 */
        fun isTranscriptOf(audioFileName: String, textFileName: String): Boolean {
            val a = normalize(baseName(audioFileName))
            val t = normalize(baseName(textFileName))
            if (a.isEmpty() || t.isEmpty()) return false
            return t.contains(a) || (a.contains(t) && t.length >= 8)
        }

        private fun normalize(s: String) = s.lowercase().replace(Regex("""[\s_\-()\[\]]"""), "")
    }
}
