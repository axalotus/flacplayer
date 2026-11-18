package com.zuna.player.service

import android.app.Service
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import com.zuna.player.audio.DacDetector
import com.zuna.player.audio.MediaNotificationManager
import com.zuna.player.data.storage.LibraryPreferences

class MusicPlaybackService : Service() {

    companion object {
        private const val TAG = "MusicPlaybackService"
        @Volatile
        private var activeInstance: MusicPlaybackService? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        fun clearBtAutoResume() {
            activeInstance?.wasPlayingBeforeBtDisconnect = false
        }

        fun start(context: Context) {
            val inst = activeInstance
            if (inst != null) {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    inst.syncForegroundState(fromCommand = false)
                } else {
                    mainHandler.post { activeInstance?.syncForegroundState(fromCommand = false) }
                }
                return
            }
            if (!AudioPlayerService.isForegroundPlaybackActive()) {
                return
            }
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
            val inst = activeInstance
            if (inst != null) {
                mainHandler.post {
                    activeInstance?.let { svc ->
                        svc.releaseWakeLock()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            svc.stopForeground(STOP_FOREGROUND_REMOVE)
                        } else {
                            @Suppress("DEPRECATION")
                            svc.stopForeground(true)
                        }
                        svc.stopSelf()
                    }
                }
                return
            }
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
    @Volatile
    private var wasPlayingBeforeBtDisconnect = false

    private fun isNonAudioBluetoothEvent(intent: Intent): Boolean {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } ?: return false
        val majorClass = try {
            device.bluetoothClass?.majorDeviceClass
        } catch (_: SecurityException) {
            null
        }
        return majorClass != null && majorClass != BluetoothClass.Device.Major.AUDIO_VIDEO
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val prefs = LibraryPreferences.getInstance(applicationContext)
            when (intent?.action) {
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    if (prefs.isAutoPauseOnDisconnect.value && AudioPlayerService.isPlaying.value) {
                        Log.d(TAG, "Audio becoming noisy -> pausing playback cleanly")
                        AudioPlayerService.pause()
                    }
                    DacDetector.getInstance(applicationContext).updateDevice()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (isNonAudioBluetoothEvent(intent)) return
                    if (prefs.isAutoPauseOnDisconnect.value) {
                        val wasPlaying = AudioPlayerService.isPlaying.value
                        Log.d(TAG, "Bluetooth audio ACL disconnected -> pausing playback cleanly")
                        AudioPlayerService.pause()
                        if (wasPlaying) {
                            wasPlayingBeforeBtDisconnect = true
                        }
                    }
                    DacDetector.getInstance(applicationContext).updateDevice()
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    if (isNonAudioBluetoothEvent(intent)) return
                    DacDetector.getInstance(applicationContext).updateDevice()
                    if (prefs.isAutoResumeOnConnect.value && wasPlayingBeforeBtDisconnect) {
                        wasPlayingBeforeBtDisconnect = false
                        Log.d(TAG, "Bluetooth audio ACL connected -> auto-resuming playback")
                        AudioPlayerService.play()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        Log.d(TAG, "onCreate: Initializing MusicPlaybackService")
        AudioPlayerService.init(applicationContext)
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Zuna:PlaybackWakeLock")?.apply {
            setReferenceCounted(false)
        }

        try {
            val filter = IntentFilter().apply {
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(noisyReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(noisyReceiver, filter)
            }
            noisyReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register audio routing receiver", e)
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
                AudioPlayerService.pause()
                releaseWakeLock()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Generic start
            }
        }

        syncForegroundState(fromCommand = true)
        return START_NOT_STICKY
    }

    fun syncForegroundState(fromCommand: Boolean = false) {
        val isPlaying = AudioPlayerService.isPlaying.value
        val keepActive = AudioPlayerService.isForegroundPlaybackActive()
        val track = AudioPlayerService.currentTrack.value

        if (keepActive || fromCommand) {
            val notification = AudioPlayerService.getNotificationForForeground(isPlaying, track)
            if (notification != null) {
                try {
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
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update foreground service state", e)
                }
            }
        }

        if (keepActive) {
            acquireWakeLock()
        } else {
            releaseWakeLock()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(false)
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!AudioPlayerService.isForegroundPlaybackActive()) {
            releaseWakeLock()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeInstance === this) {
            activeInstance = null
        }
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
