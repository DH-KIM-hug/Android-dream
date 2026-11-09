package com.androiddream.callrelay.scan

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

data class DocItem(
    val uri: Uri,
    val name: String,
    val mime: String,
    val lastModified: Long,
    val size: Long,
) {
    val isAudio: Boolean
        get() = mime.startsWith("audio/") || RecordingName.ext(name) in RecordingName.AUDIO_EXT
    val isText: Boolean
        get() = RecordingName.ext(name) in RecordingName.TEXT_EXT
}

/**
 * SAF(Storage Access Framework)로 사용자가 허용한 폴더를 직접 조회한다.
 * DocumentFile 보다 빠르게 한 번의 쿼리로 이름/크기/수정시각을 가져온다.
 */
object DocumentScanner {
    private val PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_SIZE,
    )

    fun scan(context: Context, treeUri: Uri, maxDepth: Int = 2): List<DocItem> {
        val out = ArrayList<DocItem>()
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        walk(context, treeUri, rootId, 0, maxDepth, out)
        return out
    }

    private fun walk(context: Context, treeUri: Uri, parentId: String, depth: Int, maxDepth: Int, out: MutableList<DocItem>) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val dirs = ArrayList<String>()
        context.contentResolver.query(childrenUri, PROJECTION, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    dirs += id
                    continue
                }
                out += DocItem(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                    name = name,
                    mime = mime,
                    lastModified = if (c.isNull(3)) 0L else c.getLong(3),
                    size = if (c.isNull(4)) 0L else c.getLong(4),
                )
            }
        }
        if (depth < maxDepth) dirs.forEach { walk(context, treeUri, it, depth + 1, maxDepth, out) }
    }

    fun readText(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?.removePrefix("﻿")?.trim() ?: ""

    fun hasPermission(context: Context, treeUri: Uri?): Boolean {
        if (treeUri == null) return false
        return context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }
    }
}
