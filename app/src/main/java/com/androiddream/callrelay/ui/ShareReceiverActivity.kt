package com.androiddream.callrelay.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import com.androiddream.callrelay.data.Settings
import com.androiddream.callrelay.work.Scheduler
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 삼성 음성녹음/전화 앱에서 '공유 → 통화녹음 전달' 을 누르면 실행된다.
 * 받은 텍스트·파일을 앱 내부에 복사해 두고 백그라운드 전송을 예약한 뒤 바로 닫힌다.
 */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val count = Settings(this).enabledChannels().size
        if (count == 0) {
            Toast.makeText(this, "먼저 앱에서 전송 채널을 설정하세요", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish(); return
        }
        val ok = runCatching { handle(intent) }.getOrDefault(false)
        Toast.makeText(
            this,
            if (ok) "전송을 시작합니다 (채널 ${count}개)" else "공유된 내용을 읽지 못했습니다",
            Toast.LENGTH_SHORT,
        ).show()
        finish()
    }

    private fun handle(intent: Intent): Boolean {
        val dir = File(cacheDir, "shared/${UUID.randomUUID()}").apply { mkdirs() }
        val meta = JSONObject()
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { meta.put("text", it) }
        intent.getStringExtra(Intent.EXTRA_SUBJECT)?.let { meta.put("subject", it) }

        val uris = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> streamExtra(intent)?.let(uris::add)
            Intent.ACTION_SEND_MULTIPLE -> uris += streamListExtra(intent)
        }
        uris.forEachIndexed { i, uri ->
            val name = displayName(uri) ?: "shared_$i"
            val mime = contentResolver.getType(uri).orEmpty()
            if (mime.startsWith("audio/")) meta.put("audioMime", mime)
            contentResolver.openInputStream(uri)?.use { input ->
                File(dir, name.replace('/', '_')).outputStream().use { input.copyTo(it) }
            }
        }
        if (!meta.has("text") && uris.isEmpty()) { dir.deleteRecursively(); return false }
        File(dir, "meta.json").writeText(meta.toString())
        Scheduler.sendShared(this, dir)
        return true
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamListExtra(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment
}
