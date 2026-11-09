package com.androiddream.callrelay.stt

import com.androiddream.callrelay.data.Settings
import com.androiddream.callrelay.relay.AudioRef
import com.androiddream.callrelay.relay.Http
import okhttp3.MultipartBody
import okhttp3.Request

/**
 * OpenAI 호환 음성 인식 API(/audio/transcriptions) 로 녹음을 텍스트로 변환.
 * 삼성 폰이 텍스트 파일을 남기지 않는 경우에도 완전 자동으로 텍스트를 보내기 위한 선택 기능.
 */
object Transcriber {
    /** OpenAI 업로드 한도 25MB */
    const val MAX_BYTES = 25L * 1024 * 1024

    fun transcribe(s: Settings, audio: AudioRef): String {
        if (!s.sttConfigured) throw IllegalStateException("음성인식 API 키가 설정되지 않았습니다")
        if (audio.size > MAX_BYTES) throw IllegalStateException("파일이 25MB를 넘어 음성인식을 건너뜁니다")
        val url = s.sttEndpoint.trimEnd('/') + "/audio/transcriptions"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", s.sttModel.ifBlank { "whisper-1" })
            .addFormDataPart("response_format", "text")
            .apply { if (s.sttLanguage.isNotBlank()) addFormDataPart("language", s.sttLanguage.trim()) }
            .addFormDataPart("file", audio.fileName, Http.streamBody(audio))
            .build()
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer ${s.sttApiKey.trim()}")
            .post(body).build()
        return Http.execute(req).trim()
    }
}
