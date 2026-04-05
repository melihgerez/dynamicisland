package com.example.dynamic

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Bitmap
import android.app.Notification
import android.app.PendingIntent
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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
        "com.google.android.apps.youtube.music"
    )
    private val navigationPackages = setOf("com.google.android.apps.maps")
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
    private val activeNavigationNotifications = mutableSetOf<String>()
    private val callActionMap = mutableMapOf<String, CallActions>()
    private var latestCallKey: String? = null

    private data class CallActions(
        val answer: PendingIntent?,
        val reject: PendingIntent?
    )

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val command = intent.getStringExtra("command")
            try {
                when (action) {
                    "MEDIA_COMMAND" -> {
                        if (command.isNullOrBlank()) return
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
                        if (command.isNullOrBlank()) return
                        val key = intent.getStringExtra("callKey") ?: latestCallKey ?: return
                        val actions = callActionMap[key] ?: return
                        when (command) {
                            "ANSWER" -> actions.answer?.send()
                            "REJECT" -> actions.reject?.send()
                        }
                    }
                    "PROCESS_COMMAND" -> {
                        when (intent.getStringExtra("mode")) {
                            "MEDIA" -> toggleActiveMediaPlayback()
                            "CALL" -> {
                                val key = intent.getStringExtra("callKey") ?: latestCallKey
                                if (key != null) {
                                    callActionMap[key]?.reject?.send()
                                }
                            }
                            else -> {
                                val sourcePackage = intent.getStringExtra("sourcePackage")
                                openApp(sourcePackage)
                            }
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
            addAction("PROCESS_COMMAND")
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
            return
        }
        if (transferPackages.contains(packageName)) {
            handleTransferNotification(sbn)
            return
        }
        if (navigationPackages.contains(packageName)) {
            handleNavigationNotification(sbn)
            return
        }
        if (mediaPackages.contains(packageName)) {
            handleMediaNotification(packageName)
            return
        }

        sendPulseNotification(sbn)
    }

    private fun handleMediaNotification(packageName: String) {

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
                val artRaw: Bitmap? = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                val art: Bitmap? = artRaw?.let {
                    Bitmap.createScaledBitmap(it, 96, 96, true)
                }
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
        if (sbn?.key != null && activeNavigationNotifications.remove(sbn.key) && activeNavigationNotifications.isEmpty()) {
            sendBroadcast(Intent("NAVIGATION_STOPPED"))
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

    private fun handleNavigationNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val lower = "$title $text".lowercase(Locale.ROOT)

        val looksLikeNavigation = notification.category == Notification.CATEGORY_NAVIGATION ||
                lower.contains("rota") || lower.contains("yol") || lower.contains("turn") ||
                lower.contains("exit") || lower.contains("arrive") || lower.contains("km") || lower.contains("dk")

        if (!looksLikeNavigation) {
            if (activeNavigationNotifications.remove(sbn.key) && activeNavigationNotifications.isEmpty()) {
                sendBroadcast(Intent("NAVIGATION_STOPPED"))
            }
            return
        }

        activeNavigationNotifications.add(sbn.key)
        val message = (text.ifBlank { title }).ifBlank { "Yol tarifi aktif" }
        sendBroadcast(Intent("NAVIGATION_UPDATE").apply {
            putExtra("sourcePackage", sbn.packageName)
            putExtra("text", message)
        })
    }

    private fun sendPulseNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        if (notification.category == Notification.CATEGORY_CALL || notification.category == Notification.CATEGORY_NAVIGATION) return

        val iconBitmap = runCatching {
            val drawable = packageManager.getApplicationIcon(sbn.packageName)
            drawableToBitmap(drawable)
        }.getOrNull()

        sendBroadcast(Intent("PULSE_UPDATE").apply {
            putExtra("sourcePackage", sbn.packageName)
            putExtra("appIcon", iconBitmap)
        })
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun toggleActiveMediaPlayback() {
        val sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val controllers = sessionManager.getActiveSessions(
            ComponentName(this@MediaListenerService, MediaListenerService::class.java)
        )
        val controller = controllers.firstOrNull() ?: return
        if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
            controller.transportControls.pause()
        } else {
            controller.transportControls.play()
        }
    }

    private fun openApp(packageName: String?) {
        if (packageName.isNullOrBlank()) return
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(launchIntent) }
    }
}