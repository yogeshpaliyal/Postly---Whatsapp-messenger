package com.postly.app.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Keeps scheduled attachments in app-private storage.
 *
 * URIs returned by the system picker (GetContent) only grant temporary read access,
 * which is gone by the time an alarm fires (or after a reboot). So the file is copied
 * into filesDir/attachments at pick time and shared with WhatsApp through our FileProvider.
 */
object AttachmentStore {

    private const val TAG = "AttachmentStore"
    private const val DIR = "attachments"

    fun authority(context: Context) = "${context.packageName}.fileprovider"

    /** Copies [source] into private storage. Returns a content:// URI from our FileProvider, or null on failure. */
    fun importAttachment(context: Context, source: Uri, displayName: String?): Uri? {
        return try {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val safeName = (displayName ?: queryName(context, source) ?: "attachment")
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .takeLast(80)
            val target = File(dir, "${System.currentTimeMillis()}_$safeName")
            context.contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            FileProvider.getUriForFile(context, authority(context), target)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import attachment $source", e)
            null
        }
    }

    /** Best-effort delete of a file we previously imported. Ignores URIs that aren't ours. */
    fun delete(context: Context, uriString: String?) {
        if (uriString.isNullOrBlank()) return
        try {
            val uri = Uri.parse(uriString)
            if (uri.authority != authority(context)) return
            val name = uri.lastPathSegment ?: return
            File(File(context.filesDir, DIR), name).takeIf { it.exists() }?.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete attachment $uriString", e)
        }
    }

    fun mimeType(context: Context, uri: Uri, attachmentType: String?): String {
        val resolved = try { context.contentResolver.getType(uri) } catch (e: Exception) { null }
        if (!resolved.isNullOrBlank() && resolved != "application/octet-stream") return resolved
        return when (attachmentType) {
            "IMAGE" -> "image/*"
            "VIDEO" -> "video/*"
            "AUDIO" -> "audio/*"
            else -> "*/*"
        }
    }

    private fun queryName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
}
