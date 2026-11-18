package com.zuna.player.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.zuna.player.MainActivity
import com.zuna.player.R
import com.zuna.player.data.model.AudioFileMetadata
import com.zuna.player.service.MusicPlaybackService
import com.zuna.player.ui.components.loadTrackArtwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MediaNotificationManager(
    private val context: Context,
    private val onPlayAction: () -> Unit,
    private val onPauseAction: () -> Unit,
    private val onNextAction: () -> Unit,
    private val onPrevAction: () -> Unit,
    private val onSeekAction: (Long) -> Unit,
    private val onStopAction: () -> Unit = onPauseAction
) {
    companion object {
        const val CHANNEL_ID = "antigravity_playback_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PLAY = "com.zuna.player.ACTION_PLAY"
        const val ACTION_PAUSE = "com.zuna.player.ACTION_PAUSE"
        const val ACTION_NEXT = "com.zuna.player.ACTION_NEXT"
        const val ACTION_PREV = "com.zuna.player.ACTION_PREV"
        const val ACTION_STOP = "com.zuna.player.ACTION_STOP"
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    val mediaSession: MediaSessionCompat = MediaSessionCompat(context, "ZunaSession").apply {
        setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                onPlayAction()
            }

            override fun onPause() {
                onPauseAction()
            }

            override fun onSkipToNext() {
                onNextAction()
            }

            override fun onSkipToPrevious() {
                onPrevAction()
            }

            override fun onSeekTo(pos: Long) {
                onSeekAction(pos)
            }

            override fun onStop() {
                onStopAction()
            }
        })
        isActive = true
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private var artworkLoadJob: Job? = null
    private var currentTrack: AudioFileMetadata? = null
    private var currentIsPlaying: Boolean = false
    private var currentBitmap: Bitmap? = null
    private var lastBuiltNotification: Notification? = null

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Воспроизведение музыки",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Управление воспроизведением и системный медиаплеер"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun updatePlaybackState(
        isPlaying: Boolean,
        progressMs: Long,
        track: AudioFileMetadata?,
        isStopped: Boolean = false
    ) {
        val state = when {
            isStopped -> PlaybackStateCompat.STATE_STOPPED
            isPlaying -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, progressMs, 1.0f)
            .build()

        mediaSession.setPlaybackState(playbackState)

        if (track != null) {
            currentIsPlaying = isPlaying
            if (currentTrack?.id != track.id) {
                currentTrack = track
                currentBitmap = null
                artworkLoadJob?.cancel()
                setMetadata(track, null)
                val initialNotif = buildNotification(currentIsPlaying, track, null)
                lastBuiltNotification = initialNotif
                try {
                    notificationManager.notify(NOTIFICATION_ID, initialNotif)
                } catch (_: SecurityException) {}

                val targetId = track.id
                artworkLoadJob = scope.launch {
                    val art = loadTrackArtwork(context, track)
                    if (currentTrack?.id != targetId) return@launch
                    currentBitmap = art
                    setMetadata(track, art)
                    val notif = buildNotification(currentIsPlaying, track, art)
                    lastBuiltNotification = notif
                    try {
                        notificationManager.notify(NOTIFICATION_ID, notif)
                    } catch (_: SecurityException) {}
                }
            } else {
                setMetadata(track, currentBitmap)
                val notif = buildNotification(isPlaying, track, currentBitmap)
                lastBuiltNotification = notif
                try {
                    notificationManager.notify(NOTIFICATION_ID, notif)
                } catch (_: SecurityException) {}
            }
        }
    }

    private fun setMetadata(track: AudioFileMetadata, artwork: Bitmap?) {
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.durationMs)

        if (artwork != null) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, artwork)
        }

        mediaSession.setMetadata(metadataBuilder.build())
    }

    fun buildNotification(isPlaying: Boolean, track: AudioFileMetadata?, artwork: Bitmap?): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun createServicePendingIntent(action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, MusicPlaybackService::class.java).apply {
                this.action = action
            }
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    context,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getService(
                    context,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
        }

        val prevIntent = createServicePendingIntent(ACTION_PREV, 1)
        val playPauseIntent = createServicePendingIntent(if (isPlaying) ACTION_PAUSE else ACTION_PLAY, 2)
        val nextIntent = createServicePendingIntent(ACTION_NEXT, 3)

        val title = track?.title ?: "Zuna"
        val subtitle = if (track != null) "${track.artist} — ${track.album}" else "Hi-Res Audio Engine"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSubText(track?.album)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setLargeIcon(artwork ?: currentBitmap)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .setShowWhen(false)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(android.R.drawable.ic_media_previous, "Предыдущий", prevIntent)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Пауза" else "Играть",
                playPauseIntent
            )
            .addAction(android.R.drawable.ic_media_next, "Следующий", nextIntent)

        val notif = builder.build()
        lastBuiltNotification = notif
        return notif
    }

    fun getLatestNotification(isPlaying: Boolean, track: AudioFileMetadata?): Notification {
        return lastBuiltNotification ?: buildNotification(isPlaying, track, currentBitmap)
    }

    fun release() {
        try {
            notificationManager.cancel(NOTIFICATION_ID)
            mediaSession.isActive = false
            mediaSession.release()
        } catch (_: Exception) {}
    }
}
