package com.example.dynamic

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.app.Notification
import android.app.PendingIntent
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale

class MediaListenerService : NotificationListenerService() {

    private val mediaPackages = setOf(
        "com.spotify.music",
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.google.android.apps.maps"
    )
    private val transferPackages = setOf(
        "com.instagram.android",
        "com.whatsapp",
        "com.google.android.apps.docs"
    )
    private val callPackages = setOf(
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.incallui",
        "com.whatsapp"
    )
    private val activeTransferNotifications = mutableSetOf<String>()
    private val callActionMap = mutableMapOf<String, CallActions>()
    private var latestCallKey: String? = null

    private data class CallActions(
        val answer: PendingIntent?,
        val reject: PendingIntent?
    )

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val command = intent?.getStringExtra("command") ?: return
            try {
                when (intent.action) {
                    "MEDIA_COMMAND" -> {
                        val sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
                        val controllers = sessionManager.getActiveSessions(
                            ComponentName(this@MediaListenerService, MediaListenerService::class.java)
                        )
                        val controller = controllers.firstOrNull() ?: return
                        when (command) {
                            "PLAY_PAUSE" -> {
                                if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                                    controller.transportControls.pause()
                                } else {
                                    controller.transportControls.play()
                                }
                            }
                            "NEXT" -> controller.transportControls.skipToNext()
                            "PREV" -> controller.transportControls.skipToPrevious()
                        }
                    }
                    "CALL_COMMAND" -> {
                        val key = intent.getStringExtra("callKey") ?: latestCallKey ?: return
                        val actions = callActionMap[key] ?: return
                        when (command) {
                            "ANSWER" -> actions.answer?.send()
                            "REJECT" -> actions.reject?.send()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DynamicIsland", "Komut hatası: ${e.message}")
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val filter = IntentFilter().apply {
            addAction("MEDIA_COMMAND")
            addAction("CALL_COMMAND")
        }
        ContextCompat.registerReceiver(this, commandReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        unregisterReceiver(commandReceiver)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)

        val packageName = sbn?.packageName ?: return
        if (isCallNotification(sbn)) {
            handleCallNotification(sbn)
        }
        if (transferPackages.contains(packageName)) {
            handleTransferNotification(sbn)
        }
        if (!mediaPackages.contains(packageName)) return

        try {
            val sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val controllers = sessionManager.getActiveSessions(
                ComponentName(this, MediaListenerService::class.java)
            )

            val prioritizedControllers = controllers
                .filter { mediaPackages.contains(it.packageName) }
                .sortedByDescending { it.packageName == packageName }

            for (controller in prioritizedControllers) {
                val metadata = controller.metadata ?: continue
                val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: continue
                val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                val art: Bitmap? = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                val playbackState = controller.playbackState?.state

                Log.d("DynamicIsland", "Müzik: $title - $artist")

                if (playbackState == PlaybackState.STATE_PLAYING) {
                    val intent = Intent("MEDIA_UPDATE").apply {
                        putExtra("title", title)
                        putExtra("artist", artist)
                        putExtra("albumArt", art)
                        putExtra("sourcePackage", controller.packageName)
                    }
                    sendBroadcast(intent)
                } else {
                    sendBroadcast(Intent("MEDIA_STOPPED"))
                }
                break
            }
        } catch (e: Exception) {
            Log.e("DynamicIsland", "Hata: ${e.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (mediaPackages.contains(sbn?.packageName)) {
            sendBroadcast(Intent("MEDIA_STOPPED"))
        }
        if (sbn?.key != null && callActionMap.remove(sbn.key) != null) {
            if (latestCallKey == sbn.key) latestCallKey = callActionMap.keys.lastOrNull()
            if (callActionMap.isEmpty()) {
                sendBroadcast(Intent("CALL_STOPPED"))
            }
        }
        if (sbn?.key != null && activeTransferNotifications.remove(sbn.key) && activeTransferNotifications.isEmpty()) {
            sendBroadcast(Intent("TRANSFER_STOPPED"))
        }
    }

    private fun isCallNotification(sbn: StatusBarNotification): Boolean {
        val category = sbn.notification?.category
        return category == Notification.CATEGORY_CALL || callPackages.contains(sbn.packageName)
    }

    private fun handleCallNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val callerName = (title.ifBlank { text }).ifBlank { "Arayan" }

        var answerIntent: PendingIntent? = null
        var rejectIntent: PendingIntent? = null
        notification.actions?.forEach { action ->
            val label = action.title?.toString()?.lowercase(Locale.ROOT).orEmpty()
            if (answerIntent == null && (label.contains("answer") || label.contains("yanitla") || label.contains("accept"))) {
                answerIntent = action.actionIntent
            }
            if (rejectIntent == null && (label.contains("decline") || label.contains("reddet") || label.contains("reject"))) {
                rejectIntent = action.actionIntent
            }
        }

        callActionMap[sbn.key] = CallActions(answerIntent, rejectIntent)
        latestCallKey = sbn.key

        sendBroadcast(Intent("CALL_UPDATE").apply {
            putExtra("callerName", callerName)
            putExtra("sourcePackage", sbn.packageName)
            putExtra("callKey", sbn.key)
        })
    }

    private fun handleTransferNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return
        val progress = extras.getInt(Notification.EXTRA_PROGRESS, -1)
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, -1)
        val isIndeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val lower = "$title $text".lowercase(Locale.ROOT)

        val looksLikeTransfer = isIndeterminate || (progress >= 0 && max > 0 && progress < max) ||
                lower.contains("download") || lower.contains("upload") ||
                lower.contains("indir") || lower.contains("yukle")

        if (!looksLikeTransfer) {
            if (activeTransferNotifications.remove(sbn.key) && activeTransferNotifications.isEmpty()) {
                sendBroadcast(Intent("TRANSFER_STOPPED"))
            }
            return
        }

        activeTransferNotifications.add(sbn.key)
        val percentText = if (progress >= 0 && max > 0) {
            val pct = (progress * 100) / max
            "$pct%"
        } else {
            "..."
        }

        val appLabel = try {
            val appInfo = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            sbn.packageName
        }

        val transferType = if (lower.contains("upload") || lower.contains("yukle")) "UPLOAD" else "DOWNLOAD"
        val message = "$appLabel $percentText"

        sendBroadcast(Intent("TRANSFER_UPDATE").apply {
            putExtra("sourcePackage", sbn.packageName)
            putExtra("transferType", transferType)
            putExtra("text", message)
        })
    }
}