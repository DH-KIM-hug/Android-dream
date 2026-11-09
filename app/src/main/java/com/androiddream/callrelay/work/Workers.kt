package com.androiddream.callrelay.work

import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.androiddream.callrelay.data.Settings
import com.androiddream.callrelay.relay.AudioRef
import com.androiddream.callrelay.relay.Payload
import com.androiddream.callrelay.scan.RecordingName
import com.androiddream.callrelay.stt.Transcriber
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * 감지 경로 3가지를 함께 사용한다.
 *  1) 통화 종료(PHONE_STATE) → 잠시 후 검사
 *  2) MediaStore 변경(새 오디오 파일) 콘텐츠 트리거 → 검사
 *  3) 15분 주기 검사 (안전망)
 */
object Scheduler {
    private const val PERIODIC = "scan-periodic"
    private const val SOON = "scan-soon"
    private const val FOLLOWUP = "scan-followup"
    private const val MEDIA_TRIGGER = "media-trigger"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun ensureScheduled(context: Context) {
        val wm = WorkManager.getInstance(context)
        if (!Settings(context).autoEnabled) {
            wm.cancelUniqueWork(PERIODIC)
            wm.cancelUniqueWork(MEDIA_TRIGGER)
            return
        }
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ScanWorker>(15, TimeUnit.MINUTES).setConstraints(network).build(),
        )
        armMediaTrigger(context, ExistingWorkPolicy.KEEP)
    }

    /** 새 오디오가 MediaStore 에 등록되면 깨어나는 1회성 작업 (실행될 때마다 다시 등록) */
    fun armMediaTrigger(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        val c = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(5, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            MEDIA_TRIGGER, policy, OneTimeWorkRequestBuilder<MediaTriggerWorker>().setConstraints(c).build(),
        )
    }

    fun scanSoon(context: Context, delaySec: Long = 15) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            SOON, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ScanWorker>()
                .setInitialDelay(delaySec, TimeUnit.SECONDS)
                .setConstraints(network).build(),
        )
    }

    fun scanFollowUp(context: Context, delaySec: Long = 60) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            FOLLOWUP, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ScanWorker>()
                .setInitialDelay(delaySec, TimeUnit.SECONDS)
                .setConstraints(network).build(),
        )
    }

    fun scanNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "scan-now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ScanWorker>().setConstraints(network).build(),
        )
    }

    fun sendShared(context: Context, dir: File) {
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<ShareSendWorker>()
                .setInputData(workDataOf(ShareSendWorker.KEY_DIR to dir.absolutePath))
                .setConstraints(network).build(),
        )
    }
}

class ScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val r = RelayEngine.scanAndRelay(applicationContext)
        // 텍스트 파일 생성을 기다리는 중이거나 일부 실패 → 잠시 후 다시 검사
        if (r.waiting > 0) Scheduler.scanFollowUp(applicationContext, 45)
        else if (r.failed > 0) Scheduler.scanFollowUp(applicationContext, 5 * 60)
        return Result.success()
    }
}

class MediaTriggerWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Scheduler.scanSoon(applicationContext, 10)
        Scheduler.armMediaTrigger(applicationContext)
        return Result.success()
    }
}

/** 다른 앱의 '공유'로 받은 텍스트/녹음을 보낸다. */
class ShareSendWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val dir = File(inputData.getString(KEY_DIR) ?: return Result.failure())
        if (!dir.exists()) return Result.failure()
        val meta = runCatching { JSONObject(File(dir, "meta.json").readText()) }.getOrElse { JSONObject() }
        val files = dir.listFiles()?.filter { it.name != "meta.json" }.orEmpty()

        val audioFile = files.firstOrNull { RecordingName.ext(it.name) in RecordingName.AUDIO_EXT }
        val texts = buildList {
            meta.optString("text").takeIf { it.isNotBlank() }?.let(::add)
            files.filter { RecordingName.ext(it.name) in RecordingName.TEXT_EXT }
                .forEach { add(it.readText().removePrefix("﻿").trim()) }
        }.filter { it.isNotBlank() }

        val audio = audioFile?.let { f ->
            AudioRef(f.name, meta.optString("audioMime").ifBlank { "audio/mp4" }, f.length()) { f.inputStream() }
        }
        val s = Settings(applicationContext)
        var note: String? = null
        var transcript = texts.joinToString("\n\n").ifBlank { null }
        if (transcript == null && audio != null && s.sttConfigured) {
            runCatching { Transcriber.transcribe(s, audio) }
                .onSuccess { transcript = it }
                .onFailure { note = "음성인식 실패: ${it.message}" }
        }

        val nameSource = audioFile?.name
            ?: files.firstOrNull { RecordingName.ext(it.name) in RecordingName.TEXT_EXT }?.name
            ?: meta.optString("subject").ifBlank { null }
        val parsed = nameSource?.let { RecordingName.parse(it) }
        val payload = Payload(
            contact = parsed?.contact ?: "공유된 통화",
            dateTime = parsed?.dateTime ?: LocalDateTime.now(),
            durationSec = audioFile?.let { RelayEngine.durationSec(applicationContext, android.net.Uri.fromFile(it)) },
            fileName = audioFile?.name,
            transcript = transcript,
            note = note,
            audio = audio,
        )
        val ok = RelayEngine.deliverShared(applicationContext, "share:${dir.name}", payload)
        if (ok || runAttemptCount >= 3) {
            dir.deleteRecursively()
            return Result.success()
        }
        return Result.retry()
    }

    companion object { const val KEY_DIR = "dir" }
}
