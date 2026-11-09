package com.androiddream.callrelay.relay

import com.androiddream.callrelay.data.ChannelId
import com.androiddream.callrelay.data.Settings
import com.androiddream.callrelay.data.SmtpSecurity
import okhttp3.MultipartBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Date
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.DataSource
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.internet.MimeUtility

/** 채널 하나로 전송. 실패하면 예외를 던진다. (항상 백그라운드 스레드에서 호출) */
fun interface Sender {
    fun send(p: Payload)
}

object Senders {
    fun forChannel(s: Settings, id: ChannelId): Sender = when (id) {
        ChannelId.EMAIL -> EmailSender(s)
        ChannelId.TEAMS -> TeamsSender(s)
        ChannelId.KAKAO -> KakaoSender(s)
        ChannelId.SLACK -> SlackSender(s)
        ChannelId.TELEGRAM -> TelegramSender(s)
        ChannelId.DISCORD -> DiscordSender(s)
        ChannelId.WEBHOOK -> WebhookSender(s)
    }
}

private fun require(v: String, what: String): String =
    v.trim().ifEmpty { throw IllegalStateException("$what 이(가) 설정되지 않았습니다") }

// ───────────────────────── Email (SMTP) ─────────────────────────
class EmailSender(private val s: Settings) : Sender {
    override fun send(p: Payload) {
        val host = require(s.smtpHost, "SMTP 서버")
        val user = require(s.smtpUser, "SMTP 계정")
        val pass = require(s.smtpPassword, "SMTP 비밀번호")
        val to = require(s.mailTo, "받는 사람")
        val from = s.mailFrom.ifBlank { user }

        val props = Properties().apply {
            put("mail.smtp.host", host)
            put("mail.smtp.port", s.smtpPort.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.connectiontimeout", "20000")
            put("mail.smtp.timeout", "120000")
            put("mail.smtp.writetimeout", "180000")
            when (s.smtpSecurity) {
                SmtpSecurity.SSL -> {
                    put("mail.smtp.ssl.enable", "true")
                    put("mail.smtp.ssl.checkserveridentity", "true")
                }
                SmtpSecurity.STARTTLS -> {
                    put("mail.smtp.starttls.enable", "true")
                    put("mail.smtp.starttls.required", "true")
                }
                SmtpSecurity.NONE -> Unit
            }
        }
        val session = Session.getInstance(props, object : javax.mail.Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(user, pass)
        })

        val msg = MimeMessage(session).apply {
            setFrom(InternetAddress(from))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(to.replace(';', ',')))
            setSubject(p.subject, "UTF-8")
            sentDate = Date()
        }
        val audio = p.audio?.takeIf { s.mailAttachAudio }
        val tooBig = audio != null && audio.size > MAX_ATTACH
        val bodyText = if (tooBig) {
            p.fullText() + "\n\n(녹음 파일이 ${audio!!.size / 1024 / 1024}MB라 첨부를 생략했습니다)"
        } else p.fullText()

        val multipart = MimeMultipart()
        multipart.addBodyPart(MimeBodyPart().apply { setText(bodyText, "UTF-8") })
        p.transcript?.takeIf { it.isNotBlank() }?.let { text ->
            multipart.addBodyPart(MimeBodyPart().apply {
                setText(text, "UTF-8")
                fileName = MimeUtility.encodeText("${p.fileName?.substringBeforeLast('.') ?: "통화"}.txt", "UTF-8", null)
            })
        }
        if (audio != null && !tooBig) {
            multipart.addBodyPart(MimeBodyPart().apply {
                dataHandler = DataHandler(StreamDataSource(audio))
                fileName = MimeUtility.encodeText(audio.fileName, "UTF-8", null)
            })
        }
        msg.setContent(multipart)
        Transport.send(msg)
    }

    private class StreamDataSource(private val a: AudioRef) : DataSource {
        override fun getInputStream(): InputStream = a.open()
        override fun getOutputStream(): OutputStream = throw IOException("read-only")
        override fun getContentType(): String = a.mime.ifBlank { "application/octet-stream" }
        override fun getName(): String = a.fileName
    }

    companion object {
        /** 대부분의 메일 서비스 첨부 한도(약 25MB)를 고려 */
        const val MAX_ATTACH = 20L * 1024 * 1024
    }
}

// ───────────────────────── Microsoft Teams ─────────────────────────
/**
 * Teams 채널 웹훅. 새 'Workflows(Power Automate) 웹훅'과 기존 'Incoming Webhook' 모두
 * Adaptive Card 첨부 형식을 받는다.
 */
class TeamsSender(private val s: Settings) : Sender {
    override fun send(p: Payload) {
        val url = require(s.teamsWebhook, "Teams 웹훅 URL")
        val facts = JSONArray()
            .put(fact("상대", p.contact))
            .put(fact("일시", p.dateText))
        if (p.durationSec != null) facts.put(fact("길이", p.durationText))
        if (p.fileName != null) facts.put(fact("파일", p.fileName))
        if (!p.note.isNullOrBlank()) facts.put(fact("참고", p.note))

        val body = JSONArray()
            .put(JSONObject().put("type", "TextBlock").put("text", "📞 통화 녹음 · ${p.contact}")
                .put("weight", "Bolder").put("size", "Medium").put("wrap", true))
            .put(JSONObject().put("type", "FactSet").put("facts", facts))
            .put(JSONObject().put("type", "TextBlock").put("text", "통화 내용").put("weight", "Bolder").put("spacing", "Medium"))
            .put(JSONObject().put("type", "TextBlock").put("wrap", true)
                .put("text", Payload.truncate(p.transcriptOrPlaceholder(), 18_000)))

        val card = JSONObject()
            .put("\$schema", "http://adaptivecards.io/schemas/adaptive-card.json")
            .put("type", "AdaptiveCard")
            .put("version", "1.4")
            .put("body", body)
            .put("msteams", JSONObject().put("width", "Full"))

        val payload = JSONObject()
            .put("type", "message")
            .put("attachments", JSONArray().put(
                JSONObject().put("contentType", "application/vnd.microsoft.card.adaptive")
                    .put("contentUrl", JSONObject.NULL)
                    .put("content", card)
            ))
        Http.postJson(url, payload)
    }

    private fun fact(t: String, v: String) = JSONObject().put("title", t).put("value", v)
}

// ───────────────────────── Slack ─────────────────────────
class SlackSender(private val s: Settings) : Sender {
    override fun send(p: Payload) {
        val url = require(s.slackWebhook, "Slack 웹훅 URL")
        Http.postJson(url, JSONObject().put("text", p.fullText(maxTranscriptChars = 35_000)))
    }
}

// ───────────────────────── Discord ─────────────────────────
class DiscordSender(private val s: Settings) : Sender {
    override fun send(p: Payload) {
        val url = require(s.discordWebhook, "Discord 웹훅 URL")
        val parts = Payload.chunk(p.fullText(maxTranscriptChars = 18_000), 1900)
        parts.forEach { Http.postJson(url, JSONObject().put("content", it)) }
        val audio = p.audio
        if (s.discordAttachAudio && audio != null && audio.size in 1..(24L * 1024 * 1024)) {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("payload_json", JSONObject().put("content", "🎙️ ${audio.fileName}").toString())
                .addFormDataPart("files[0]", audio.fileName, Http.streamBody(audio))
                .build()
            Http.execute(Request.Builder().url(url).post(body).build())
        }
    }
}

// ───────────────────────── Telegram ─────────────────────────
class TelegramSender(private val s: Settings) : Sender {
    override fun send(p: Payload) {
        val token = require(s.telegramToken, "Telegram 봇 토큰")
        val chat = require(s.telegramChatId, "Telegram chat_id")
        val base = "https://api.telegram.org/bot$token"
        Payload.chunk(p.fullText(maxTranscriptChars = 30_000), 4000).forEach { part ->
            Http.postJson("$base/sendMessage", JSONObject().put("chat_id", chat).put("text", part))
        }
        val audio = p.audio
        if (s.telegramSendAudio && audio != null && audio.size in 1..(49L * 1024 * 1024)) {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chat)
                .addFormDataPart("caption", "${p.contact} · ${p.dateText}")
                .addFormDataPart("document", audio.fileName, Http.streamBody(audio))
                .build()
            Http.execute(Request.Builder().url("$base/sendDocument").post(body).build())
        }
    }
}

// ───────────────────────── Generic webhook ─────────────────────────
/** Make / Zapier / n8n / Power Automate 등으로 연결해 원하는 서비스로 보낼 수 있는 범용 JSON */
class WebhookSender(private val s: Settings) : Sender {
    override fun send(p: Payload) {
        val url = require(s.customWebhook, "웹훅 URL")
        val json = JSONObject()
            .put("event", "call_recording")
            .put("contact", p.contact)
            .put("datetime", p.dateTime?.toString() ?: JSONObject.NULL)
            .put("duration_sec", p.durationSec ?: JSONObject.NULL)
            .put("file_name", p.fileName ?: JSONObject.NULL)
            .put("transcript", p.transcript ?: JSONObject.NULL)
            .put("note", p.note ?: JSONObject.NULL)
            .put("subject", p.subject)
            .put("text", p.fullText())
        val headers = if (s.customWebhookAuth.isNotBlank()) mapOf("Authorization" to s.customWebhookAuth.trim()) else emptyMap()
        Http.postJson(url, json, headers)
    }
}

// ───────────────────────── KakaoTalk ─────────────────────────
/**
 * 카카오톡 '나에게 보내기' (REST API: /v2/api/talk/memo/default/send).
 * 기본 텍스트 템플릿은 200자 제한이 있어 긴 내용은 여러 건으로 나눠 보낸다.
 */
class KakaoSender(private val s: Settings) : Sender {
    override fun send(p: Payload) {
        val token = KakaoAuth.validAccessToken(s)
        val header = p.header()
        val transcript = p.transcriptOrPlaceholder()
        val pieces = ArrayList<String>()
        pieces += Payload.chunk(header, 190)
        val maxParts = s.kakaoMaxParts.coerceIn(1, 30)
        val body = Payload.chunk(transcript, 185)
        val allowed = (maxParts - pieces.size).coerceAtLeast(1)
        body.take(allowed).forEachIndexed { i, part ->
            val last = i == allowed - 1 && body.size > allowed
            pieces += if (last) part.take(170) + "\n…(이하 생략, 메일 등 확인)" else part
        }
        val total = pieces.size
        pieces.forEachIndexed { i, text ->
            val labeled = if (total > 1) "(${i + 1}/$total) $text" else text
            sendOne(token, labeled.take(200))
        }
    }

    private fun sendOne(token: String, text: String) {
        val template = JSONObject()
            .put("object_type", "text")
            .put("text", text)
            .put("link", JSONObject().put("web_url", "https://www.kakao.com").put("mobile_web_url", "https://www.kakao.com"))
        val form = okhttp3.FormBody.Builder().add("template_object", template.toString()).build()
        val req = Request.Builder()
            .url("https://kapi.kakao.com/v2/api/talk/memo/default/send")
            .header("Authorization", "Bearer $token")
            .post(form)
            .build()
        Http.execute(req)
    }
}
