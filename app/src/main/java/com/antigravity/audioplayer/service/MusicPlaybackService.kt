package com.antigravity.audioplayer.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import com.antigravity.audioplayer.audio.MediaNotificationManager

class MusicPlaybackService : Service() {

    companion object {
        private const val TAG = "MusicPlaybackService"

        fun start(context: Context) {
            val intent = Intent(context, MusicPlaybackService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MusicPlaybackService", e)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, MusicPlaybackService::class.java).apply {
                    action = MediaNotificationManager.ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop MusicPlaybackService", e)
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var noisyReceiverRegistered = false

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.d(TAG, "Audio becoming noisy (headphones unplugged) -> pausing playback")
                AudioPlayerService.pause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Initializing MusicPlaybackService")
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FlacPlayer:PlaybackWakeLock")?.apply {
            setReferenceCounted(false)
        }

        try {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(noisyReceiver, filter)
            }
            noisyReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register noisy receiver", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            MediaNotificationManager.ACTION_PLAY -> {
                AudioPlayerService.play()
            }
            MediaNotificationManager.ACTION_PAUSE -> {
                AudioPlayerService.pause()
            }
            MediaNotificationManager.ACTION_NEXT -> {
                AudioPlayerService.skipToNext()
            }
            MediaNotificationManager.ACTION_PREV -> {
                AudioPlayerService.skipToPrevious()
            }
            MediaNotificationManager.ACTION_STOP -> {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Generic start
            }
        }

        syncForegroundState()
        return START_STICKY
    }

    fun syncForegroundState() {
        val isPlaying = AudioPlayerService.isPlaying.value
        val track = AudioPlayerService.currentTrack.value

        val notification = AudioPlayerService.getNotificationForForeground(isPlaying, track)
        if (notification != null) {
            try {
                if (isPlaying) {
                    acquireWakeLock()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ServiceCompat.startForeground(
                            this,
                            MediaNotificationManager.NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                        )
                    } else {
                        startForeground(MediaNotificationManager.NOTIFICATION_ID, notification)
                    }
                } else {
                    releaseWakeLock()
                    // Keep notification but detach foreground sticky lock when paused
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_DETACH)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update foreground service state", e)
            }
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hours max
                Log.d(TAG, "WakeLock acquired")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release wakeLock", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        if (noisyReceiverRegistered) {
            try {
                unregisterReceiver(noisyReceiver)
            } catch (_: Exception) {}
            noisyReceiverRegistered = false
        }
        Log.d(TAG, "onDestroy: MusicPlaybackService destroyed")
    }
}
