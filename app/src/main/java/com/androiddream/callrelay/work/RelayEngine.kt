package com.androiddream.callrelay.work

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.androiddream.callrelay.Notifier
import com.androiddream.callrelay.data.ChannelId
import com.androiddream.callrelay.data.History
import com.androiddream.callrelay.data.RecordState
import com.androiddream.callrelay.data.Settings
import com.androiddream.callrelay.data.TranscriptMode
import com.androiddream.callrelay.relay.AudioRef
import com.androiddream.callrelay.relay.Payload
import com.androiddream.callrelay.relay.Senders
import com.androiddream.callrelay.scan.DocItem
import com.androiddream.callrelay.scan.DocumentScanner
import com.androiddream.callrelay.scan.RecordingName
import com.androiddream.callrelay.stt.Transcriber
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

data class ScanResult(val sent: Int, val waiting: Int, val failed: Int)

object RelayEngine {
    private const val TAG = "RelayEngine"
    private const val MAX_ATTEMPTS = 6
    /** 녹음 저장이 끝났다고 판단하는 최소 경과 시간 */
    private const val SETTLE_MS = 8_000L

    private val mutex = Mutex()

    /** 녹음 폴더를 검사해 새 통화녹음을 모든 활성 채널로 보낸다. */
    suspend fun scanAndRelay(context: Context): ScanResult = mutex.withLock {
        val s = Settings(context)
        val history = History(context)
        val tree = s.recordingTreeUri ?: return ScanResult(0, 0, 0)
        if (!s.autoEnabled || s.enabledChannels().isEmpty()) return ScanResult(0, 0, 0)
        if (!DocumentScanner.hasPermission(context, tree)) {
            Notifier.error(context, "녹음 폴더 접근 권한이 없습니다. 앱에서 폴더를 다시 선택하세요.")
            return ScanResult(0, 0, 1)
        }

        val items = runCatching { DocumentScanner.scan(context, tree) }.getOrElse {
            Log.w(TAG, "scan failed", it); return ScanResult(0, 0, 1)
        }
        val transcriptItems = s.transcriptTreeUri
            ?.takeIf { DocumentScanner.hasPermission(context, it) }
            ?.let { runCatching { DocumentScanner.scan(context, it) }.getOrDefault(emptyList()) }
            ?.filter { it.isText }
            ?: items.filter { it.isText }

        val filters = s.contactFilter.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val now = System.currentTimeMillis()
        val audios = items
            .filter { it.isAudio && it.size > 0 && it.lastModified >= s.baselineMillis }
            .filter { a -> filters.isEmpty() || filters.any { a.name.contains(it, ignoreCase = true) } }
            .sortedBy { it.lastModified }

        var sent = 0; var waiting = 0; var failed = 0
        for (audio in audios) {
            val key = audio.uri.toString()
            val state = history.get(key) ?: RecordState(key)
            if (state.done || state.attempts >= MAX_ATTEMPTS) continue
            if (now - audio.lastModified < SETTLE_MS) { waiting++; continue }

            val ageMin = (now - audio.lastModified) / 60_000.0
            val txtFile = transcriptItems
                .filter { RecordingName.isTranscriptOf(audio.name, it.name) }
                .maxByOrNull { it.lastModified }

            val waitMore = ageMin < s.transcriptWaitMinutes
            val transcriptResult: Pair<String?, String?> = when (s.transcriptMode) {
                TranscriptMode.NONE -> null to null
                TranscriptMode.FILE_ONLY -> when {
                    txtFile != null -> readTxt(context, txtFile) to null
                    waitMore -> { waiting++; continue }
                    else -> null to "텍스트 파일을 찾지 못했습니다"
                }
                TranscriptMode.FILE_THEN_STT -> when {
                    txtFile != null -> readTxt(context, txtFile) to null
                    waitMore -> { waiting++; continue }
                    s.sttConfigured -> stt(context, s, audio)
                    else -> null to "텍스트 파일이 없고 음성인식 API가 설정되지 않았습니다"
                }
                TranscriptMode.STT_ONLY -> stt(context, s, audio)
            }

            val name = RecordingName.parse(audio.name)
            val payload = Payload(
                contact = name.contact,
                dateTime = name.dateTime ?: LocalDateTime.ofInstant(Instant.ofEpochMilli(audio.lastModified), ZoneId.systemDefault()),
                durationSec = durationSec(context, audio.uri),
                fileName = audio.name,
                transcript = transcriptResult.first,
                note = transcriptResult.second,
                audio = audioRef(context, audio),
            )
            when (deliver(context, s, history, state, payload)) {
                true -> sent++
                false -> failed++
            }
        }
        if (sent > 0 || failed > 0) Log.i(TAG, "relay sent=$sent waiting=$waiting failed=$failed")
        return ScanResult(sent, waiting, failed)
    }

    /**
     * 활성 채널 중 아직 성공하지 않은 채널로만 전송하고 상태를 저장한다.
     * @return 모든 채널 성공 시 true
     */
    fun deliver(context: Context, s: Settings, history: History, state: RecordState, p: Payload): Boolean {
        val errors = ArrayList<String>()
        for (ch in s.enabledChannels()) {
            if (ch.name in state.sent) continue
            try {
                Senders.forChannel(s, ch).send(p)
                state.sent += ch.name
                history.put(state) // 채널마다 즉시 저장 → 중간에 종료돼도 중복 전송 방지
            } catch (e: Exception) {
                Log.w(TAG, "send ${ch.name} failed", e)
                errors += "${ch.label}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        val okChannels = s.enabledChannels().filter { it.name in state.sent }.joinToString { it.label }
        return if (errors.isEmpty()) {
            state.done = true
            state.lastError = ""
            history.put(state)
            history.log("${p.contact} · ${p.dateText}", "전송 완료: $okChannels" + (p.note?.let { " / $it" } ?: ""), true)
            if (s.notifyResult) Notifier.sent(context, p.contact, okChannels)
            true
        } else {
            state.attempts++
            state.lastError = errors.joinToString("\n")
            history.put(state)
            history.log("${p.contact} · ${p.dateText}", "실패(${state.attempts}/$MAX_ATTEMPTS)\n${state.lastError}", false)
            if (state.attempts >= MAX_ATTEMPTS) Notifier.error(context, "${p.contact} 통화 전송 실패: ${errors.first()}")
            false
        }
    }

    /** 공유하기로 받은 내용 전송. key 별로 채널 성공 여부를 기억해 재시도 시 중복을 막는다. */
    fun deliverShared(context: Context, key: String, p: Payload): Boolean {
        val s = Settings(context)
        if (s.enabledChannels().isEmpty()) {
            Notifier.error(context, "활성화된 전송 채널이 없습니다")
            return false
        }
        val history = History(context)
        val state = history.get(key) ?: RecordState(key)
        return deliver(context, s, history, state, p)
    }

    /** 특정 채널로 테스트 메시지 */
    fun test(context: Context, ch: ChannelId): Result<Unit> = runCatching {
        val p = Payload(
            contact = "테스트",
            dateTime = LocalDateTime.now(),
            durationSec = 83,
            fileName = "통화 녹음 테스트_000000_000000.m4a",
            transcript = "통화녹음 전달 앱의 테스트 메시지입니다.\n이 메시지가 보이면 ${ch.label} 설정이 정상입니다.",
        )
        Senders.forChannel(Settings(context), ch).send(p)
    }

    private fun readTxt(context: Context, item: DocItem): String? =
        runCatching { DocumentScanner.readText(context, item.uri) }.getOrNull()?.takeIf { it.isNotBlank() }

    /** 음성인식 결과는 캐시해 재시도 시 API 를 다시 호출하지 않는다 */
    private fun stt(context: Context, s: Settings, item: DocItem): Pair<String?, String?> {
        val dir = java.io.File(context.cacheDir, "stt").apply { mkdirs() }
        val cache = java.io.File(dir, "${item.uri.toString().hashCode()}_${item.size}.txt")
        if (cache.exists()) return cache.readText().takeIf { it.isNotBlank() } to null
        return runCatching { Transcriber.transcribe(s, audioRef(context, item)) }
            .onSuccess { cache.writeText(it) }
            .fold({ it.takeIf(String::isNotBlank) to null }, { null to "음성인식 실패: ${it.message}" })
    }

    fun audioRef(context: Context, item: DocItem) = AudioRef(
        fileName = item.name,
        mime = item.mime.ifBlank { "audio/mp4" },
        size = item.size,
        open = { context.contentResolver.openInputStream(item.uri) ?: throw java.io.IOException("파일을 열 수 없습니다") },
    )

    fun durationSec(context: Context, uri: Uri): Long? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.div(1000)
        } finally {
            r.release()
        }
    }.getOrNull()
}
