package com.antigravity.audioplayer.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.antigravity.audioplayer.audio.MediaNotificationManager
import com.antigravity.audioplayer.audio.NativeAudioEngine
import com.antigravity.audioplayer.ui.components.RepeatMode
import com.antigravity.audioplayer.data.model.AudioFileMetadata
import com.antigravity.audioplayer.data.storage.LibraryPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object AudioPlayerService {
    private const val TAG = "PlayerService"

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var trackerJob: Job? = null

    private var appContext: Context? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var mediaNotificationManager: MediaNotificationManager? = null

    private val _currentTrack = MutableStateFlow<AudioFileMetadata?>(null)
    val currentTrack = _currentTrack.asStateFlow()

    private val _activeQueue = MutableStateFlow<List<AudioFileMetadata>>(emptyList())
    val activeQueue = _activeQueue.asStateFlow()

    private val _currentTrackIndex = MutableStateFlow(0)
    val currentTrackIndex = _currentTrackIndex.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _progressMs = MutableStateFlow(0L)
    val progressMs = _progressMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs = _durationMs.asStateFlow()

    private val _isShuffleOn = MutableStateFlow(false)
    val isShuffleOn = _isShuffleOn.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode = _repeatMode.asStateFlow()

    private var lastSeekTimestamp = 0L
    private var eofHandled = false
    private var allLibraryTracks: List<AudioFileMetadata> = emptyList()

    fun updateAllLibraryTracks(tracks: List<AudioFileMetadata>) {
        if (tracks.isNotEmpty()) {
            allLibraryTracks = tracks
        }
    }

    fun init(context: Context) {
        if (appContext != null) {
            startPositionTracker()
            return
        }
        appContext = context.applicationContext
        audioManager = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        val prefs = LibraryPreferences.getInstance(context.applicationContext)
        NativeAudioEngine.initEngine()
        NativeAudioEngine.setSafeBufferMode(prefs.isSafeBufferMode.value)
        NativeAudioEngine.setReplayGainEnabled(prefs.isReplayGainEnabled.value)
        NativeAudioEngine.setGaplessEnabled(prefs.isGaplessEnabled.value)
        NativeAudioEngine.setVolume(1.0f)
        NativeAudioEngine.recreateStream(prefs.audioEngineMode.value)

        mediaNotificationManager = MediaNotificationManager(
            context = context.applicationContext,
            onPlayAction = { togglePlayPause() },
            onPauseAction = { pause() },
            onNextAction = { skipToNext() },
            onPrevAction = { skipToPrevious() },
            onSeekAction = { pos -> seekTo(pos) }
        )

        startPositionTracker()
        cleanLegacyAudioCache(context.applicationContext)
        Log.d(TAG, "AudioPlayerService initialized (safeBuffer=${prefs.isSafeBufferMode.value}, mode=${prefs.audioEngineMode.value})")
    }

    private var lastProgressSaveTimestamp = 0L

    fun setInitialTracks(tracks: List<AudioFileMetadata>) {
        if (tracks.isEmpty()) return
        if (_activeQueue.value.isNotEmpty() && _currentTrack.value != null) {
            // Already initialized or currently playing
            return
        }

        val ctx = appContext
        val prefs = if (ctx != null) LibraryPreferences.getInstance(ctx) else null
        val lastTrackId: String? = prefs?.getLastPlayedTrackId()
        val lastProgressMs: Long = prefs?.getLastPlayedProgressMs() ?: 0L
        val lastQueueIds: List<String> = prefs?.getLastQueueTrackIds() ?: emptyList()

        // Reconstruct saved queue if matches tracks in library, else use tracks
        val savedQueue: List<AudioFileMetadata> = if (lastQueueIds.isNotEmpty()) {
            val trackMap = tracks.associateBy { it.id }
            val reconstructed = lastQueueIds.mapNotNull { id -> trackMap[id] }
            if (reconstructed.isNotEmpty()) reconstructed else tracks
        } else {
            tracks
        }

        _activeQueue.value = savedQueue

        // Find last played track or default to first
        val targetIndex = if (!lastTrackId.isNullOrBlank()) {
            savedQueue.indexOfFirst { it.id == lastTrackId }.takeIf { it >= 0 } ?: 0
        } else {
            0
        }

        val initialTrack = savedQueue.getOrNull(targetIndex) ?: return
        _currentTrackIndex.value = targetIndex
        _currentTrack.value = initialTrack
        _progressMs.value = lastProgressMs

        serviceScope.launch {
            try {
                Log.d(TAG, "setInitialTracks: Pre-loading last played track '${initialTrack.title}' (saved pos: $lastProgressMs ms)")
                val openSuccess = openTrack(initialTrack)
                if (openSuccess) {
                    val durationFrames = NativeAudioEngine.getDuration()
                    val sr = initialTrack.sampleRateHz.takeIf { it > 0 } ?: 44100
                    _durationMs.value = if (durationFrames > 0) (durationFrames * 1000L) / sr else initialTrack.durationMs
                    if (lastProgressMs > 0) {
                        val targetFrame = (lastProgressMs * sr) / 1000L
                        NativeAudioEngine.seekTo(targetFrame)
                    }
                }
                updateNotification()
            } catch (e: Exception) {
                Log.e(TAG, "Error pre-loading initial track", e)
            }
        }
    }

    fun playTrack(track: AudioFileMetadata, queue: List<AudioFileMetadata>? = null) {
        val newQueue = if (!queue.isNullOrEmpty()) queue else _activeQueue.value
        _activeQueue.value = newQueue
        val index = newQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        playTrackAtIndex(index, newQueue, startPlayback = true)
    }

    fun playNext(track: AudioFileMetadata) {
        val current = _activeQueue.value.toMutableList()
        if (current.isEmpty() || _currentTrack.value == null) {
            playTrack(track, listOf(track))
            return
        }
        val insertIndex = (_currentTrackIndex.value + 1).coerceIn(0, current.size)
        val existingIdx = current.indexOfFirst { it.id == track.id }
        if (existingIdx >= 0 && existingIdx != _currentTrackIndex.value) {
            current.removeAt(existingIdx)
            val adjusted = if (existingIdx < insertIndex) insertIndex - 1 else insertIndex
            current.add(adjusted.coerceIn(0, current.size), track)
            if (existingIdx < _currentTrackIndex.value) {
                _currentTrackIndex.value = (_currentTrackIndex.value - 1).coerceAtLeast(0)
            }
        } else if (existingIdx < 0) {
            current.add(insertIndex, track)
        }
        _activeQueue.value = current
    }

    fun addToQueue(track: AudioFileMetadata) {
        val current = _activeQueue.value.toMutableList()
        if (current.isEmpty() || _currentTrack.value == null) {
            playTrack(track, listOf(track))
            return
        }
        if (current.none { it.id == track.id }) {
            current.add(track)
            _activeQueue.value = current
        }
    }

    fun removeFromQueue(index: Int) {
        val current = _activeQueue.value.toMutableList()
        if (index !in current.indices) return
        val curIdx = _currentTrackIndex.value
        if (index == curIdx) {
            if (current.size == 1) {
                pause()
                _activeQueue.value = emptyList()
                _currentTrackIndex.value = 0
                return
            }
            current.removeAt(index)
            val nextIdx = index.coerceAtMost(current.lastIndex)
            playTrackAtIndex(nextIdx, current, startPlayback = _isPlaying.value)
        } else {
            current.removeAt(index)
            _activeQueue.value = current
            if (index < curIdx) {
                _currentTrackIndex.value = (curIdx - 1).coerceAtLeast(0)
            }
        }
    }

    fun clearUpcomingQueue() {
        val current = _currentTrack.value ?: return
        _activeQueue.value = listOf(current)
        _currentTrackIndex.value = 0
    }

    fun playTrackAtIndex(index: Int, queue: List<AudioFileMetadata>? = null, startPlayback: Boolean = true) {
        val q = queue ?: _activeQueue.value
        if (index !in q.indices) return

        eofHandled = false
        _activeQueue.value = q
        _currentTrackIndex.value = index
        val track = q[index]
        _currentTrack.value = track
        if (startPlayback) {
            _progressMs.value = 0L
        }

        appContext?.let { ctx ->
            val prefs = LibraryPreferences.getInstance(ctx)
            if (startPlayback) {
                prefs.recordTrackPlayed(track.id)
            }
            prefs.saveLastPlaybackState(track.id, _progressMs.value, q.map { it.id })
        }

        serviceScope.launch {
            try {
                Log.d(TAG, "playTrackAtIndex: Opening '${track.title}'")
                val openSuccess = openTrack(track)
                if (openSuccess) {
                    val durationFrames = NativeAudioEngine.getDuration()
                    val sr = track.sampleRateHz.takeIf { it > 0 } ?: 44100
                    _durationMs.value = if (durationFrames > 0) (durationFrames * 1000L) / sr else track.durationMs

                    if (startPlayback) {
                        requestAudioFocus()
                        val startRes = NativeAudioEngine.start()
                        Log.d(TAG, "playTrackAtIndex: Play result for '${track.title}': $startRes")
                        _isPlaying.value = true
                        eofHandled = false
                    }
                } else {
                    Log.e(TAG, "playTrackAtIndex: Failed to open '${track.title}', skipping to next...")
                    if (q.size > 1) {
                        val nextIdx = (index + 1) % q.size
                        playTrackAtIndex(nextIdx, q, startPlayback)
                    }
                }
                updateNotification()
            } catch (e: Exception) {
                Log.e(TAG, "playTrackAtIndex error", e)
            }
        }
    }

    fun playNextInternal() {
        val queue = _activeQueue.value
        if (queue.isEmpty()) return

        when (_repeatMode.value) {
            RepeatMode.ONE -> {
                Log.d(TAG, "Repeat ONE: Seeking to 0 and restarting current track")
                NativeAudioEngine.seekTo(0)
                _progressMs.value = 0L
                val startRes = NativeAudioEngine.start()
                _isPlaying.value = true
                eofHandled = false
                Log.d(TAG, "Repeat ONE play result: $startRes")
            }
            RepeatMode.ALL -> {
                val nextIndex = (_currentTrackIndex.value + 1) % queue.size
                val next = queue.getOrNull(nextIndex) ?: return
                Log.d(TAG, "Repeat ALL: Advancing to track [${nextIndex}] '${next.title}'")
                playTrackAtIndex(nextIndex, queue, startPlayback = true)
            }
            RepeatMode.OFF -> {
                val nextIndex = _currentTrackIndex.value + 1
                if (nextIndex < queue.size) {
                    val next = queue[nextIndex]
                    Log.d(TAG, "Advancing to track [${nextIndex}] '${next.title}'")
                    playTrackAtIndex(nextIndex, queue, startPlayback = true)
                } else {
                    // Queue / listening history finished! As requested: switch to an endless mix of tracks
                    val pool = if (allLibraryTracks.isNotEmpty()) allLibraryTracks else queue
                    val mix = pool.shuffled()
                    Log.d(TAG, "Queue finished! Starting mix of ${mix.size} tracks")
                    _isShuffleOn.value = true
                    playTrackAtIndex(0, mix, startPlayback = true)
                }
            }
        }
    }

    fun skipToNext() {
        playNextInternal()
    }

    fun skipToPrevious() {
        val queue = _activeQueue.value
        if (queue.isEmpty()) return
        val prevIndex = if (_currentTrackIndex.value > 0) _currentTrackIndex.value - 1 else queue.size - 1
        val prev = queue.getOrNull(prevIndex) ?: return
        Log.d(TAG, "Going back to track [${prevIndex}] '${prev.title}' by '${prev.artist}'")
        playTrackAtIndex(prevIndex, queue, startPlayback = true)
    }

    fun togglePlayPause() {
        if (_isPlaying.value) {
            pause()
        } else {
            play()
        }
    }

    fun play() {
        if (_activeQueue.value.isNotEmpty()) {
            requestAudioFocus()
            val res = NativeAudioEngine.start()
            Log.d(TAG, "play() called, result: $res")
            _isPlaying.value = true
            updateNotification()
        }
    }

    fun pause() {
        if (_isPlaying.value) {
            NativeAudioEngine.pause()
            abandonAudioFocus()
            _isPlaying.value = false
            Log.d(TAG, "pause() called at ${_progressMs.value} ms")
            appContext?.let { com.antigravity.audioplayer.data.storage.LibraryPreferences.getInstance(it).saveLastProgress(_progressMs.value) }
            updateNotification()
        }
    }

    fun seekTo(positionMs: Long) {
        lastSeekTimestamp = System.currentTimeMillis()
        _progressMs.value = positionMs
        eofHandled = false
        appContext?.let { com.antigravity.audioplayer.data.storage.LibraryPreferences.getInstance(it).saveLastProgress(positionMs) }
        val track = _currentTrack.value
        if (track != null) {
            val sr = track.sampleRateHz.takeIf { it > 0 } ?: 44100
            val targetFrame = (positionMs * sr) / 1000L
            val actualFrame = NativeAudioEngine.seekTo(targetFrame)
            Log.d(TAG, "seekTo: requested $positionMs ms (frame $targetFrame), got actualFrame: $actualFrame")
        }
        updateNotification()
    }

    fun setShuffle(enabled: Boolean) {
        _isShuffleOn.value = enabled
    }

    fun toggleShuffle() {
        _isShuffleOn.value = !_isShuffleOn.value
    }

    fun toggleRepeat() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }

    private fun startPositionTracker() {
        if (trackerJob?.isActive == true) return
        trackerJob = serviceScope.launch(Dispatchers.Default) {
            Log.d(TAG, "Position & EOF Tracker loop started")
            while (isActive) {
                delay(200)
                try {
                    if (!_isPlaying.value) continue

                    // Check seek debouncing
                    val timeSinceSeek = System.currentTimeMillis() - lastSeekTimestamp
                    if (timeSinceSeek < 200L) continue

                    val track = _currentTrack.value ?: continue
                    val sr = track.sampleRateHz.takeIf { it > 0 } ?: 44100
                    val framePos = NativeAudioEngine.getPosition()
                    if (framePos < 0) continue

                    val currentMs = (framePos * 1000L) / sr
                    _progressMs.value = currentMs

                    val ctx = appContext
                    val prefs = if (ctx != null) LibraryPreferences.getInstance(ctx) else null

                    val now = System.currentTimeMillis()
                    if (now - lastProgressSaveTimestamp > 2000L) {
                        lastProgressSaveTimestamp = now
                        prefs?.saveLastProgress(currentMs)
                    }

                    val durationFrames = NativeAudioEngine.getDuration()
                    val totalMs = if (durationFrames > 0) (durationFrames * 1000L) / sr else track.durationMs
                    if (totalMs > 0) {
                        _durationMs.value = totalMs
                    }

                    val crossfadeSec = prefs?.crossfadeSeconds?.value ?: 0
                    val isGapless = prefs?.isGaplessEnabled?.value ?: true
                    val crossfadeWindowMs = crossfadeSec * 1000L
                    val remainingMs = (totalMs - currentMs).coerceAtLeast(0L)

                    // Apply Crossfade volume envelope if enabled and track is long enough
                    if (crossfadeWindowMs > 0L && totalMs > crossfadeWindowMs * 2) {
                        val halfFadeMs = (crossfadeWindowMs / 2L).coerceAtLeast(500L)
                        when {
                            currentMs < halfFadeMs -> {
                                // Smooth fade-in at start of track
                                val fadeIn = (0.2f + 0.8f * (currentMs.toFloat() / halfFadeMs.toFloat())).coerceIn(0.2f, 1.0f)
                                NativeAudioEngine.setVolume(fadeIn)
                            }
                            remainingMs <= crossfadeWindowMs -> {
                                // Smooth fade-out approaching transition
                                val fadeOut = (remainingMs.toFloat() / crossfadeWindowMs.toFloat()).coerceIn(0.08f, 1.0f)
                                NativeAudioEngine.setVolume(fadeOut)
                            }
                            else -> {
                                NativeAudioEngine.setVolume(1.0f)
                            }
                        }
                    } else {
                        NativeAudioEngine.setVolume(1.0f)
                    }

                    val isEngineEof = NativeAudioEngine.isEof()
                    val shouldAdvance = when {
                        crossfadeWindowMs > 0L && totalMs > crossfadeWindowMs * 2 -> {
                            isEngineEof || remainingMs <= (crossfadeWindowMs / 2L).coerceAtLeast(400L)
                        }
                        isGapless -> {
                            isEngineEof || (totalMs > 1000L && remainingMs <= 160L)
                        }
                        else -> {
                            isEngineEof
                        }
                    }

                    if (!eofHandled && shouldAdvance) {
                        Log.d(TAG, "Track transition triggered! eof=$isEngineEof, cur=$currentMs/$totalMs ms, crossfade=${crossfadeSec}s, gapless=$isGapless")
                        eofHandled = true
                        if (!isGapless && crossfadeWindowMs == 0L) {
                            // Non-gapless mode: insert a clean inter-track gap before starting next track
                            delay(250)
                        }
                        withContext(Dispatchers.Main) {
                            if (crossfadeWindowMs > 0L) {
                                NativeAudioEngine.setVolume(0.2f)
                            } else {
                                NativeAudioEngine.setVolume(1.0f)
                            }
                            playNextInternal()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in position tracker loop", e)
                }
            }
        }
    }

    fun getNotificationForForeground(isPlaying: Boolean = _isPlaying.value, track: AudioFileMetadata? = _currentTrack.value): android.app.Notification? {
        return mediaNotificationManager?.getLatestNotification(isPlaying, track)
    }

    private fun updateNotification() {
        val playing = _isPlaying.value
        val track = _currentTrack.value
        mediaNotificationManager?.updatePlaybackState(playing, _progressMs.value, track)
        appContext?.let { ctx ->
            MusicPlaybackService.start(ctx)
        }
    }

    private var legacyAudioFocusListener: AudioManager.OnAudioFocusChangeListener? = null

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { focusChange ->
                        when (focusChange) {
                            AudioManager.AUDIOFOCUS_LOSS,
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                        }
                    }
                    .build()
            }
            audioFocusRequest?.let { am.requestAudioFocus(it) }
        } else {
            if (legacyAudioFocusListener == null) {
                legacyAudioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
                    Log.d(TAG, "Legacy onAudioFocusChange: $focusChange")
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                    }
                }
            }
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                legacyAudioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            legacyAudioFocusListener?.let { listener ->
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(listener)
            }
        }
    }

    private var activePfd: android.os.ParcelFileDescriptor? = null

    private suspend fun openTrack(track: AudioFileMetadata): Boolean = withContext(Dispatchers.IO) {
        val uri = track.uri
        if (uri.scheme == "content") {
            val ctx = appContext ?: return@withContext false
            try {
                activePfd?.close()
                activePfd = null
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    activePfd = pfd
                    val fd = pfd.fd
                    Log.d(TAG, "openTrack: Opening via direct kernel FD $fd for '${track.title}'")
                    val success = NativeAudioEngine.openFd(fd)
                    if (success) {
                        return@withContext true
                    }
                    Log.w(TAG, "openTrack: openFd returned false for FD $fd, falling back to resolveFilePath")
                }
            } catch (e: Exception) {
                Log.e(TAG, "openTrack: Failed opening ParcelFileDescriptor for $uri", e)
            }
        }
        val path = resolveFilePath(track.uri)
        Log.d(TAG, "openTrack: Opening via file path '$path' for '${track.title}'")
        return@withContext NativeAudioEngine.openFile(path)
    }

    private suspend fun resolveFilePath(uri: Uri): String = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") {
            return@withContext uri.path ?: ""
        }
        if (uri.scheme == "http" || uri.scheme == "https") {
            return@withContext uri.toString()
        }
        val ctx = appContext ?: return@withContext ""
        try {
            activePfd?.close()
            activePfd = null
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                activePfd = pfd
                val fd = pfd.fd
                val fdPath = "/proc/self/fd/$fd"
                Log.d(TAG, "resolveFilePath: Streaming directly via kernel FD: $fdPath (FD $fd)")
                return@withContext fdPath
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolveFilePath: Failed opening SAF ParcelFileDescriptor for $uri", e)
        }
        return@withContext uri.path ?: ""
    }

    fun cleanLegacyAudioCache(context: Context) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val cacheDir = context.cacheDir ?: return@launch
                var deletedCount = 0
                var freedBytes = 0L
                cacheDir.listFiles()?.forEach { file ->
                    val name = file.name.lowercase()
                    if (name.startsWith("audio_") || name.endsWith(".dat") || name.endsWith(".tmp") ||
                        name.endsWith(".pcm") || name.endsWith(".wav") || name.endsWith(".raw")) {
                        freedBytes += file.length()
                        if (file.delete()) deletedCount++
                    }
                }
                Log.d(TAG, "cleanLegacyAudioCache: Deleted $deletedCount legacy audio cache files (${freedBytes / (1024 * 1024)} MB freed)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed cleaning legacy audio cache", e)
            }
        }
    }

    fun release() {
        trackerJob?.cancel()
        mediaNotificationManager?.release()
        try {
            activePfd?.close()
            activePfd = null
        } catch (_: Exception) {}
        NativeAudioEngine.stop()
    }
}
