package com.androiddream.callrelay.relay

import java.io.InputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 첨부할 녹음 파일. 스트림은 채널마다 새로 연다. */
class AudioRef(
    val fileName: String,
    val mime: String,
    val size: Long,
    val open: () -> InputStream,
)

/** 모든 채널에 공통으로 전달되는 내용 */
data class Payload(
    val contact: String,
    val dateTime: LocalDateTime?,
    val durationSec: Long?,
    val fileName: String?,
    val transcript: String?,
    val note: String? = null,
    val audio: AudioRef? = null,
) {
    val dateText: String
        get() = dateTime?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) ?: "-"

    val durationText: String
        get() = durationSec?.let { String.format("%d분 %02d초", it / 60, it % 60) } ?: "-"

    val subject: String
        get() = "[통화녹음] $contact ${dateTime?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) ?: ""}".trim()

    /** 머리글(상대/일시/길이/파일) */
    fun header(): String = buildString {
        appendLine("📞 통화 녹음")
        appendLine("상대: $contact")
        appendLine("일시: $dateText")
        if (durationSec != null) appendLine("길이: $durationText")
        if (fileName != null) appendLine("파일: $fileName")
        if (!note.isNullOrBlank()) appendLine("참고: $note")
    }.trimEnd()

    fun transcriptOrPlaceholder(): String =
        transcript?.takeIf { it.isNotBlank() } ?: "(변환된 텍스트 없음)"

    /** 사람이 읽는 전체 본문 */
    fun fullText(maxTranscriptChars: Int = Int.MAX_VALUE): String = buildString {
        appendLine(header())
        appendLine()
        appendLine("── 통화 내용 ──")
        append(truncate(transcriptOrPlaceholder(), maxTranscriptChars))
    }

    companion object {
        fun truncate(s: String, max: Int): String =
            if (s.length <= max) s else s.take(max - 20) + "\n…(이하 생략)"

        /** 메시지 길이 제한이 있는 채널용 분할 */
        fun chunk(s: String, size: Int): List<String> {
            if (s.length <= size) return listOf(s)
            val out = ArrayList<String>()
            var rest = s
            while (rest.isNotEmpty()) {
                if (rest.length <= size) { out += rest; break }
                // 가능하면 줄바꿈/공백에서 자른다
                var cut = rest.lastIndexOf('\n', size)
                if (cut < size / 2) cut = rest.lastIndexOf(' ', size)
                if (cut < size / 2) cut = size
                out += rest.substring(0, cut).trimEnd()
                rest = rest.substring(cut).trimStart()
            }
            return out
        }
    }
}
