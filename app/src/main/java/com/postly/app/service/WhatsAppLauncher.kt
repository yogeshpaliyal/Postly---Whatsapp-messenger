package com.postly.app.service

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.postly.app.data.model.ScheduleEntity
import com.postly.app.util.AttachmentStore
import java.net.URLEncoder

/**
 * Builds and fires the intent that opens WhatsApp for a scheduled task.
 *
 * - Text only + phone:     whatsapp://send deep link straight into the chat.
 * - Attachment + phone:    ACTION_SEND with a jid extra, which opens WhatsApp's media
 *                          preview for that chat with the message prefilled as caption.
 * - Attachment, name only: ACTION_SEND without jid, which opens WhatsApp's "Send to" picker;
 *                          the accessibility service then searches and selects the contact.
 * - Text only, name only:  plain WhatsApp launch; the accessibility service searches the chat.
 */
object WhatsAppLauncher {

    private const val TAG = "WhatsAppLauncher"

    fun cleanPhone(phone: String) = phone.replace("+", "").replace(" ", "").replace("-", "")

    fun targetPackage(platform: String) =
        if (platform.contains("business", ignoreCase = true)) "com.whatsapp.w4b" else "com.whatsapp"

    fun hasAttachment(task: ScheduleEntity) = !task.attachmentUri.isNullOrBlank()

    /** Returns true if an activity was started. */
    fun launch(context: Context, task: ScheduleEntity): Boolean {
        return try {
            val intent = buildIntent(context, task) ?: run {
                Log.e(TAG, "WhatsApp is not installed on this device")
                return false
            }
            context.startActivity(intent)
            Log.d(TAG, "Launched WhatsApp for task ${task.id} (attachment: ${hasAttachment(task)})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch WhatsApp", e)
            false
        }
    }

    private fun buildIntent(context: Context, task: ScheduleEntity): Intent? {
        val phone = cleanPhone(task.recipientPhone)
        val pkg = targetPackage(task.platform)

        if (hasAttachment(task)) {
            val mediaUri = Uri.parse(task.attachmentUri)
            // Explicit grant as well as the intent flag: we may be launching from a Service.
            try {
                context.grantUriPermission(pkg, mediaUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                Log.w(TAG, "grantUriPermission failed for $mediaUri", e)
            }
            return Intent(Intent.ACTION_SEND).apply {
                setPackage(pkg)
                type = AttachmentStore.mimeType(context, mediaUri, task.attachmentType)
                putExtra(Intent.EXTRA_STREAM, mediaUri)
                clipData = ClipData.newRawUri(task.attachmentName ?: "attachment", mediaUri)
                if (task.message.isNotBlank()) putExtra(Intent.EXTRA_TEXT, task.message)
                if (phone.isNotEmpty()) putExtra("jid", "$phone@s.whatsapp.net")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }

        if (phone.isNotEmpty()) {
            val encodedMsg = URLEncoder.encode(task.message, "UTF-8")
            return Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?phone=$phone&text=$encodedMsg")).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }

        return context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }
}
