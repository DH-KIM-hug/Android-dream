package com.androiddream.callrelay.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/** 텍스트(전사) 확보 방식 */
enum class TranscriptMode(val label: String) {
    FILE_THEN_STT("텍스트 파일 우선, 없으면 음성인식 API"),
    FILE_ONLY("텍스트 파일만 사용"),
    STT_ONLY("항상 음성인식 API로 변환"),
    NONE("텍스트 없이 녹음 정보만 전송"),
}

enum class SmtpSecurity { SSL, STARTTLS, NONE }

/** 전송 채널 식별자 */
enum class ChannelId(val label: String) {
    EMAIL("이메일"),
    TEAMS("Microsoft Teams"),
    KAKAO("카카오톡 (나에게 보내기)"),
    SLACK("Slack"),
    TELEGRAM("Telegram"),
    DISCORD("Discord"),
    WEBHOOK("사용자 웹훅 (Make·Zapier·n8n 등)"),
}

/**
 * SharedPreferences 기반 설정 저장소. 모든 값은 기기 안에만 저장된다.
 */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("callrelay_settings", Context.MODE_PRIVATE)

    private fun str(key: String, def: String = "") = prefs.getString(key, def) ?: def
    private fun putStr(key: String, v: String) = prefs.edit().putString(key, v).apply()
    private fun bool(key: String, def: Boolean = false) = prefs.getBoolean(key, def)
    private fun putBool(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()
    private fun int(key: String, def: Int) = prefs.getInt(key, def)
    private fun putInt(key: String, v: Int) = prefs.edit().putInt(key, v).apply()
    private fun long(key: String, def: Long) = prefs.getLong(key, def)
    private fun putLong(key: String, v: Long) = prefs.edit().putLong(key, v).apply()

    // ---- 일반 ----
    var autoEnabled: Boolean
        get() = bool("auto_enabled", true); set(v) = putBool("auto_enabled", v)

    /** 녹음 폴더 (SAF 트리 URI) */
    var recordingTreeUri: Uri?
        get() = str("tree_uri").takeIf { it.isNotEmpty() }?.let(Uri::parse)
        set(v) = putStr("tree_uri", v?.toString() ?: "")

    /** 전사 텍스트 파일이 다른 폴더에 저장되는 경우 (비우면 녹음 폴더에서 찾음) */
    var transcriptTreeUri: Uri?
        get() = str("transcript_tree_uri").takeIf { it.isNotEmpty() }?.let(Uri::parse)
        set(v) = putStr("transcript_tree_uri", v?.toString() ?: "")

    /** 이 시각 이후에 생긴 녹음만 전송 (폴더 선택 시점) */
    var baselineMillis: Long
        get() = long("baseline", 0L); set(v) = putLong("baseline", v)

    var transcriptMode: TranscriptMode
        get() = runCatching { TranscriptMode.valueOf(str("transcript_mode", TranscriptMode.FILE_THEN_STT.name)) }
            .getOrDefault(TranscriptMode.FILE_THEN_STT)
        set(v) = putStr("transcript_mode", v.name)

    /** 텍스트 파일이 생성되길 기다리는 최대 시간(분) */
    var transcriptWaitMinutes: Int
        get() = int("transcript_wait_min", 3); set(v) = putInt("transcript_wait_min", v)

    var notifyResult: Boolean
        get() = bool("notify_result", true); set(v) = putBool("notify_result", v)

    /** 파일명에 이 단어 중 하나가 포함된 녹음만 전송 (쉼표 구분, 비우면 전체) */
    var contactFilter: String
        get() = str("contact_filter"); set(v) = putStr("contact_filter", v)

    // ---- 음성인식(STT) : OpenAI 호환 /audio/transcriptions ----
    var sttEndpoint: String
        get() = str("stt_endpoint", "https://api.openai.com/v1"); set(v) = putStr("stt_endpoint", v)
    var sttApiKey: String
        get() = str("stt_key"); set(v) = putStr("stt_key", v)
    var sttModel: String
        get() = str("stt_model", "whisper-1"); set(v) = putStr("stt_model", v)
    var sttLanguage: String
        get() = str("stt_lang", "ko"); set(v) = putStr("stt_lang", v)
    val sttConfigured: Boolean get() = sttApiKey.isNotBlank() && sttEndpoint.isNotBlank()

    // ---- 채널 on/off ----
    fun isEnabled(id: ChannelId) = bool("ch_${id.name}_on")
    fun setEnabled(id: ChannelId, v: Boolean) = putBool("ch_${id.name}_on", v)
    fun enabledChannels(): List<ChannelId> = ChannelId.entries.filter { isEnabled(it) }

    // ---- Email ----
    var smtpHost: String get() = str("smtp_host", "smtp.gmail.com"); set(v) = putStr("smtp_host", v)
    var smtpPort: Int get() = int("smtp_port", 465); set(v) = putInt("smtp_port", v)
    var smtpSecurity: SmtpSecurity
        get() = runCatching { SmtpSecurity.valueOf(str("smtp_sec", SmtpSecurity.SSL.name)) }.getOrDefault(SmtpSecurity.SSL)
        set(v) = putStr("smtp_sec", v.name)
    var smtpUser: String get() = str("smtp_user"); set(v) = putStr("smtp_user", v)
    var smtpPassword: String get() = str("smtp_pass"); set(v) = putStr("smtp_pass", v)
    var mailFrom: String get() = str("mail_from"); set(v) = putStr("mail_from", v)
    var mailTo: String get() = str("mail_to"); set(v) = putStr("mail_to", v)
    var mailAttachAudio: Boolean get() = bool("mail_attach", true); set(v) = putBool("mail_attach", v)

    // ---- Teams / Slack / Discord / Webhook ----
    var teamsWebhook: String get() = str("teams_url"); set(v) = putStr("teams_url", v)
    var slackWebhook: String get() = str("slack_url"); set(v) = putStr("slack_url", v)
    var discordWebhook: String get() = str("discord_url"); set(v) = putStr("discord_url", v)
    var discordAttachAudio: Boolean get() = bool("discord_attach", false); set(v) = putBool("discord_attach", v)
    var customWebhook: String get() = str("hook_url"); set(v) = putStr("hook_url", v)
    var customWebhookAuth: String get() = str("hook_auth"); set(v) = putStr("hook_auth", v)

    // ---- Telegram ----
    var telegramToken: String get() = str("tg_token"); set(v) = putStr("tg_token", v)
    var telegramChatId: String get() = str("tg_chat"); set(v) = putStr("tg_chat", v)
    var telegramSendAudio: Boolean get() = bool("tg_audio", true); set(v) = putBool("tg_audio", v)

    // ---- Kakao (REST API, 나에게 보내기) ----
    var kakaoRestKey: String get() = str("kakao_key"); set(v) = putStr("kakao_key", v)
    var kakaoClientSecret: String get() = str("kakao_secret"); set(v) = putStr("kakao_secret", v)
    var kakaoRedirectUri: String
        get() = str("kakao_redirect", "https://localhost/kakao-oauth"); set(v) = putStr("kakao_redirect", v)
    var kakaoAccessToken: String get() = str("kakao_at"); set(v) = putStr("kakao_at", v)
    var kakaoRefreshToken: String get() = str("kakao_rt"); set(v) = putStr("kakao_rt", v)
    var kakaoAccessExpiresAt: Long get() = long("kakao_at_exp", 0L); set(v) = putLong("kakao_at_exp", v)
    /** 카카오 메시지 1건은 200자 제한 → 긴 텍스트를 나눠 보낼 최대 건수 */
    var kakaoMaxParts: Int get() = int("kakao_parts", 5); set(v) = putInt("kakao_parts", v)
}
