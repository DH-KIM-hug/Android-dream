package com.androiddream.callrelay.relay

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun postJson(url: String, body: JSONObject, headers: Map<String, String> = emptyMap()): String {
        val req = Request.Builder().url(url).post(body.toString().toRequestBody(JSON))
        headers.forEach { (k, v) -> req.header(k, v) }
        return execute(req.build())
    }

    fun execute(request: Request): String {
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${text.take(300)}")
            return text
        }
    }

    /** 녹음 파일 스트림을 그대로 업로드하는 RequestBody */
    fun streamBody(audio: AudioRef): RequestBody = object : RequestBody() {
        override fun contentType(): MediaType? = runCatching { audio.mime.toMediaType() }.getOrNull()
        override fun contentLength(): Long = if (audio.size > 0) audio.size else -1
        override fun writeTo(sink: BufferedSink) {
            audio.open().source().use { sink.writeAll(it) }
        }
    }
}
