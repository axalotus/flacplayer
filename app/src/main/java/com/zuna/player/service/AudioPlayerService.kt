package com.zuna.player.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.zuna.player.audio.MediaNotificationManager
import com.zuna.player.audio.NativeAudioEngine
import com.zuna.player.ui.components.RepeatMode
import com.zuna.player.data.model.AudioFileMetadata
import com.zuna.player.data.storage.LibraryPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

object AudioPlayerService {
    private const val TAG = "PlayerService"

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var trackerJob: Job? = null
    private var loadTrackJob: Job? = null
    private var pauseFadeJob: Job? = null
    private val engineLoadMutex = Mutex()

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

    private val _isStopped = MutableStateFlow(false)
    val isStopped = _isStopped.asStateFlow()

    private val _progressMs = MutableStateFlow(0L)
    val progressMs = _progressMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs = _durationMs.asStateFlow()

    private val _isShuffleOn = MutableStateFlow(false)
    val isShuffleOn = _isShuffleOn.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode = _repeatMode.asStateFlow()

    @Volatile private var lastSeekTimestamp = 0L
    @Volatile private var eofHandled = false
    @Volatile private var crossfadeTriggered = false
    @Volatile private var preloadedTrackId: String? = null
    @Volatile private var preloadedTrackIndex: Int = -1
    @Volatile private var preloadedTrackMeta: AudioFileMetadata? = null
    private val playbackEpoch = AtomicLong(0L)
    private var allLibraryTracks: List<AudioFileMetadata> = emptyList()
    private var originalQueueBeforeShuffle: List<AudioFileMetadata>? = null
    @Volatile private var isUiForeground: Boolean = true

    fun setUiForeground(foreground: Boolean) {
        isUiForeground = foreground
    }

    fun isForegroundPlaybackActive(): Boolean = _isPlaying.value || (pauseFadeJob?.isActive == true)

    fun refreshAudioRouteForOpenSL() {
        val ctx = appContext ?: return
        val prefs = LibraryPreferences.getInstance(ctx)
        if (prefs.audioEngineMode.value != 1 || !_isPlaying.value) return
        serviceScope.launch {
            engineLoadMutex.withLock {
                if (_isPlaying.value) {
                    withContext(Dispatchers.IO) {
                        NativeAudioEngine.reopenStream(true)
                    }
                }
            }
        }
    }

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

        observeEnginePreferences(prefs)

        mediaNotificationManager = MediaNotificationManager(
            context = context.applicationContext,
            onPlayAction = { togglePlayPause() },
            onPauseAction = { pause() },
            onNextAction = { skipToNext() },
            onPrevAction = { skipToPrevious() },
            onSeekAction = { pos -> seekTo(pos) },
            onStopAction = { stopPlayback() }
        )

        startPositionTracker()
        cleanLegacyAudioCache(context.applicationContext)
        Log.d(TAG, "AudioPlayerService initialized (safeBuffer=${prefs.isSafeBufferMode.value}, mode=${prefs.audioEngineMode.value})")
    }

    private fun observeEnginePreferences(prefs: LibraryPreferences) {
        serviceScope.launch {
            prefs.audioEngineMode.drop(1).collect { mode ->
                engineLoadMutex.withLock {
                    withContext(Dispatchers.IO) {
                        NativeAudioEngine.recreateStream(mode)
                    }
                }
            }
        }
        serviceScope.launch {
            prefs.isSafeBufferMode.drop(1).collect { enabled ->
                engineLoadMutex.withLock {
                    withContext(Dispatchers.IO) {
                        NativeAudioEngine.setSafeBufferMode(enabled)
                    }
                }
            }
        }
        serviceScope.launch {
            prefs.isReplayGainEnabled.drop(1).collect { enabled ->
                NativeAudioEngine.setReplayGainEnabled(enabled)
            }
        }
        serviceScope.launch {
            prefs.isGaplessEnabled.drop(1).collect { enabled ->
                NativeAudioEngine.setGaplessEnabled(enabled)
            }
        }
    }

    private fun effectiveSampleRate(track: AudioFileMetadata?): Int {
        // Use the native decoder output rate (upsampled to 44.1/48kHz for <44.1kHz tracks)
        // so frame counts from getDuration()/getPosition()/seekTo() match wall-clock ms.
        val engineSr = NativeAudioEngine.getSampleRate()
        if (engineSr > 0) return engineSr
        return track?.sampleRateHz?.takeIf { it > 0 } ?: 44100
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

        pauseFadeJob?.cancel()
        pauseFadeJob = null
        loadTrackJob?.cancel()
        loadTrackJob = serviceScope.launch {
            try {
                Log.d(TAG, "setInitialTracks: Pre-loading last played track '${initialTrack.title}' (saved pos: $lastProgressMs ms)")
                val openSuccess = engineLoadMutex.withLock {
                    ensureActive()
                    clearPreloadStateLocked(clearEngine = false)
                    openTrackIntoDeck(initialTrack, isPreload = false)
                }
                ensureActive()
                if (openSuccess) {
                    val durationFrames = NativeAudioEngine.getDuration()
                    val sr = effectiveSampleRate(initialTrack)
                    _durationMs.value = if (durationFrames > 0) (durationFrames * 1000L) / sr else initialTrack.durationMs
                    if (lastProgressMs > 0) {
                        val targetFrame = (lastProgressMs * sr) / 1000L
                        NativeAudioEngine.seekTo(targetFrame)
                    }
                }
                updateNotification()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error pre-loading initial track", e)
            }
        }
    }

    fun playTrack(track: AudioFileMetadata, queue: List<AudioFileMetadata>? = null) {
        val providedNewQueue = !queue.isNullOrEmpty() && queue !== _activeQueue.value
        val baseQueue = if (!queue.isNullOrEmpty()) queue else _activeQueue.value
        if (_isShuffleOn.value && (providedNewQueue || originalQueueBeforeShuffle == null) && baseQueue.size > 1) {
            originalQueueBeforeShuffle = baseQueue
            val rest = baseQueue.filter { it.id != track.id }.shuffled()
            val shuffled = listOf(track) + rest
            _activeQueue.value = shuffled
            playTrackAtIndex(0, shuffled, startPlayback = true)
        } else {
            if (providedNewQueue) {
                originalQueueBeforeShuffle = null
            }
            _activeQueue.value = baseQueue
            val index = baseQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            playTrackAtIndex(index, baseQueue, startPlayback = true)
        }
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
        originalQueueBeforeShuffle = null
        _activeQueue.value = listOf(current)
        _currentTrackIndex.value = 0
    }

    fun playTrackAtIndex(
        index: Int,
        queue: List<AudioFileMetadata>? = null,
        startPlayback: Boolean = true,
        consecutiveFailures: Int = 0
    ) {
        val q = queue ?: _activeQueue.value
        if (index !in q.indices) return

        MusicPlaybackService.clearBtAutoResume()
        eofHandled = false
        _isStopped.value = false
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

        pauseFadeJob?.cancel()
        pauseFadeJob = null
        val epoch = playbackEpoch.incrementAndGet()
        loadTrackJob?.cancel()
        loadTrackJob = serviceScope.launch {
            try {
                Log.d(TAG, "playTrackAtIndex: Opening '${track.title}'")
                val openSuccess = engineLoadMutex.withLock {
                    ensureActive()
                    clearPreloadStateLocked(clearEngine = false)
                    openTrackIntoDeck(track, isPreload = false)
                }
                ensureActive()
                if (openSuccess) {
                    val durationFrames = NativeAudioEngine.getDuration()
                    val sr = effectiveSampleRate(track)
                    _durationMs.value = if (durationFrames > 0) (durationFrames * 1000L) / sr else track.durationMs

                    if (startPlayback && playbackEpoch.get() == epoch) {
                        requestAudioFocus()
                        val startRes = engineLoadMutex.withLock {
                            if (playbackEpoch.get() == epoch) {
                                NativeAudioEngine.cancelPauseFade()
                                NativeAudioEngine.start()
                            } else false
                        }
                        Log.d(TAG, "playTrackAtIndex: Play result for '${track.title}': $startRes")
                        if (playbackEpoch.get() == epoch) {
                            _isPlaying.value = true
                            eofHandled = false
                        }
                    }
                } else {
                    val nextFailures = consecutiveFailures + 1
                    Log.e(TAG, "playTrackAtIndex: Failed to open '${track.title}' ($nextFailures/${q.size})")
                    if (q.size > 1 && nextFailures < q.size) {
                        val nextIdx = (index + 1) % q.size
                        playTrackAtIndex(nextIdx, q, startPlayback, consecutiveFailures = nextFailures)
                    } else {
                        _isPlaying.value = false
                        abandonAudioFocus()
                    }
                }
                updateNotification()
            } catch (e: CancellationException) {
                throw e
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
                pauseFadeJob?.cancel()
                pauseFadeJob = null
                playbackEpoch.incrementAndGet()
                NativeAudioEngine.cancelPauseFade()
                NativeAudioEngine.seekTo(0)
                _progressMs.value = 0L
                val startRes = NativeAudioEngine.start()
                _isPlaying.value = true
                eofHandled = false
                updateNotification()
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
            MusicPlaybackService.clearBtAutoResume()
            if (_isStopped.value) {
                _isStopped.value = false
                val idx = _currentTrackIndex.value.coerceIn(0, _activeQueue.value.lastIndex)
                playTrackAtIndex(idx, _activeQueue.value, startPlayback = true)
                return
            }
            val epoch = playbackEpoch.incrementAndGet()
            pauseFadeJob?.cancel()
            pauseFadeJob = null
            _isPlaying.value = true
            requestAudioFocus()
            NativeAudioEngine.cancelPauseFade()
            updateNotification()

            serviceScope.launch {
                engineLoadMutex.withLock {
                    if (playbackEpoch.get() == epoch && _isPlaying.value) {
                        val res = withContext(Dispatchers.IO) {
                            NativeAudioEngine.cancelPauseFade()
                            NativeAudioEngine.start()
                        }
                        Log.d(TAG, "play() called, result: $res")
                    }
                }
            }
        }
    }

    /** Full playback stop: halts and releases audio stream, resets position to 0:00, keeps current track & queue intact. */
    fun stopPlayback() {
        MusicPlaybackService.clearBtAutoResume()
        val epoch = playbackEpoch.incrementAndGet()
        pauseFadeJob?.cancel()
        pauseFadeJob = null
        loadTrackJob?.cancel()
        loadTrackJob = null
        _isPlaying.value = false
        _isStopped.value = _currentTrack.value != null
        _progressMs.value = 0L
        eofHandled = false
        crossfadeTriggered = false
        abandonAudioFocus()
        appContext?.let { LibraryPreferences.getInstance(it).saveLastProgress(0L) }

        serviceScope.launch {
            engineLoadMutex.withLock {
                if (playbackEpoch.get() == epoch && !_isPlaying.value) {
                    clearPreloadStateLocked(clearEngine = true)
                    withContext(Dispatchers.IO) {
                        NativeAudioEngine.cancelPauseFade()
                        NativeAudioEngine.stop()
                    }
                }
            }
        }

        val track = _currentTrack.value
        mediaNotificationManager?.updatePlaybackState(false, 0L, track, isStopped = true)
        appContext?.let { MusicPlaybackService.stop(it) }
    }

    fun pause() {
        MusicPlaybackService.clearBtAutoResume()
        if (_isPlaying.value) {
            val epoch = playbackEpoch.incrementAndGet()
            _isPlaying.value = false
            abandonAudioFocus()
            Log.d(TAG, "pause() fading out cleanly at ${_progressMs.value} ms")
            appContext?.let { com.zuna.player.data.storage.LibraryPreferences.getInstance(it).saveLastProgress(_progressMs.value) }

            pauseFadeJob?.cancel()
            pauseFadeJob = serviceScope.launch {
                try {
                    NativeAudioEngine.beginPauseFade(120)
                    delay(130)
                    ensureActive()
                    engineLoadMutex.withLock {
                        if (playbackEpoch.get() == epoch && !_isPlaying.value) {
                            withContext(Dispatchers.IO) {
                                NativeAudioEngine.pause()
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error during smooth pause fade", e)
                } finally {
                    if (playbackEpoch.get() == epoch) {
                        pauseFadeJob = null
                        if (!_isPlaying.value) {
                            updateNotification()
                        }
                    }
                }
            }
            // Keep foreground & WakeLock alive while pauseFadeJob is tapering the audio out
            updateNotification()
        }
    }

    fun seekTo(positionMs: Long) {
        lastSeekTimestamp = System.currentTimeMillis()
        _progressMs.value = positionMs
        eofHandled = false
        val hadActiveFade = crossfadeTriggered
        crossfadeTriggered = false
        if (hadActiveFade) {
            preloadedTrackId = null
            preloadedTrackIndex = -1
            preloadedTrackMeta = null
        }
        appContext?.let { com.zuna.player.data.storage.LibraryPreferences.getInstance(it).saveLastProgress(positionMs) }
        val track = _currentTrack.value
        if (track != null) {
            val sr = effectiveSampleRate(track)
            val targetFrame = (positionMs * sr) / 1000L
            val actualFrame = NativeAudioEngine.seekTo(targetFrame)
            Log.d(TAG, "seekTo: requested $positionMs ms (frame $targetFrame), got actualFrame: $actualFrame")
        }
        if (hadActiveFade) {
            serviceScope.launch(Dispatchers.IO) {
                engineLoadMutex.withLock {
                    clearPreloadStateLocked(clearEngine = true)
                }
            }
        }
        updateNotification()
    }

    fun setShuffle(enabled: Boolean) {
        if (_isShuffleOn.value == enabled) return
        _isShuffleOn.value = enabled
        val queue = _activeQueue.value
        val cur = _currentTrack.value
        if (enabled) {
            if (queue.size > 1) {
                originalQueueBeforeShuffle = queue
                val rest = if (cur != null) queue.filter { it.id != cur.id }.shuffled() else queue.shuffled()
                val reordered = if (cur != null) listOf(cur) + rest else rest
                _activeQueue.value = reordered
                _currentTrackIndex.value = 0
            }
        } else {
            val orig = originalQueueBeforeShuffle
            originalQueueBeforeShuffle = null
            if (!orig.isNullOrEmpty()) {
                _activeQueue.value = orig
                val restoredIdx = if (cur != null) orig.indexOfFirst { it.id == cur.id } else 0
                _currentTrackIndex.value = restoredIdx.coerceAtLeast(0)
            }
        }
    }

    fun toggleShuffle() {
        setShuffle(!_isShuffleOn.value)
    }

    fun toggleRepeat() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }

    private fun resolveUpcomingTrack(): Pair<Int, AudioFileMetadata>? {
        val queue = _activeQueue.value
        if (queue.isEmpty()) return null
        return when (_repeatMode.value) {
            RepeatMode.ONE -> null
            RepeatMode.ALL -> {
                val nextIndex = (_currentTrackIndex.value + 1) % queue.size
                queue.getOrNull(nextIndex)?.let { nextIndex to it }
            }
            RepeatMode.OFF -> {
                val nextIndex = _currentTrackIndex.value + 1
                if (nextIndex < queue.size) {
                    nextIndex to queue[nextIndex]
                } else {
                    null
                }
            }
        }
    }

    private fun clearPreloadStateLocked(clearEngine: Boolean) {
        preloadedTrackId = null
        preloadedTrackIndex = -1
        preloadedTrackMeta = null
        crossfadeTriggered = false
        try {
            preloadedPfd?.close()
        } catch (_: Exception) {}
        preloadedPfd = null
        if (clearEngine) {
            NativeAudioEngine.cancelCrossfade()
        }
    }

    private suspend fun promoteCompletedCrossfade(prefs: LibraryPreferences?) {
        var promotedTrack: AudioFileMetadata? = null
        var promotedIdx = -1
        var promotedDurationMs = 0L
        var promotedPosMs = 0L

        engineLoadMutex.withLock {
            if (!NativeAudioEngine.isCrossfadeCompleted()) return@withLock
            val targetId = preloadedTrackId
            val targetIndexHint = preloadedTrackIndex
            val targetFallbackMeta = preloadedTrackMeta
            if (!NativeAudioEngine.promotePreloadedTrack()) {
                clearPreloadStateLocked(clearEngine = true)
                return@withLock
            }

            try {
                activePfd?.close()
            } catch (_: Exception) {}
            activePfd = preloadedPfd
            preloadedPfd = null
            preloadedTrackId = null
            preloadedTrackIndex = -1
            preloadedTrackMeta = null
            crossfadeTriggered = false
            eofHandled = false

            val queue = _activeQueue.value
            val matchedIndex = when {
                targetIndexHint in queue.indices && queue[targetIndexHint].id == targetId -> targetIndexHint
                targetId != null -> queue.indexOfFirst { it.id == targetId }
                else -> -1
            }
            val nextTrack = queue.getOrNull(matchedIndex) ?: targetFallbackMeta
            if (nextTrack != null) {
                promotedTrack = nextTrack
                promotedIdx = if (matchedIndex >= 0) {
                    matchedIndex
                } else {
                    _currentTrackIndex.value.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
                }
                val sr = effectiveSampleRate(nextTrack)
                val durationFrames = NativeAudioEngine.getDuration()
                promotedDurationMs = if (durationFrames > 0) (durationFrames * 1000L) / sr else nextTrack.durationMs
                val posFrames = NativeAudioEngine.getPosition()
                promotedPosMs = if (posFrames >= 0) (posFrames * 1000L) / sr else 0L
            }
        }

        val track = promotedTrack ?: return
        withContext(Dispatchers.Main) {
            _currentTrackIndex.value = promotedIdx
            _currentTrack.value = track
            _durationMs.value = promotedDurationMs
            _progressMs.value = promotedPosMs
            prefs?.recordTrackPlayed(track.id)
            prefs?.saveLastPlaybackState(track.id, promotedPosMs, _activeQueue.value.map { it.id })
            updateNotification()
            Log.d(TAG, "Crossfade promoted track [${promotedIdx}] '${track.title}' cleanly")
        }
    }

    private fun startPositionTracker() {
        if (trackerJob?.isActive == true) return
        trackerJob = serviceScope.launch(Dispatchers.Default) {
            Log.d(TAG, "Position & EOF Tracker loop started")
            var nextDelayMs = 120L
            while (isActive) {
                delay(nextDelayMs)
                nextDelayMs = if (_isPlaying.value) 120L else 500L
                try {
                    if (!_isPlaying.value) continue

                    // Check seek debouncing
                    val timeSinceSeek = System.currentTimeMillis() - lastSeekTimestamp
                    if (timeSinceSeek < 200L) continue

                    val ctx = appContext
                    val prefs = if (ctx != null) LibraryPreferences.getInstance(ctx) else null

                    // If dual-deck crossfade completed on audio thread, promote Deck B cleanly to primary
                    if (crossfadeTriggered && NativeAudioEngine.isCrossfadeCompleted()) {
                        promoteCompletedCrossfade(prefs)
                        continue
                    }

                    val track = _currentTrack.value ?: continue
                    val sr = effectiveSampleRate(track)
                    val framePos = NativeAudioEngine.getPosition()
                    if (framePos < 0) continue

                    val currentMs = (framePos * 1000L) / sr
                    val durationFrames = NativeAudioEngine.getDuration()
                    val totalMs = if (durationFrames > 0) (durationFrames * 1000L) / sr else track.durationMs
                    if (totalMs > 0) {
                        _durationMs.value = totalMs
                    }

                    val crossfadeSec = prefs?.crossfadeSeconds?.value ?: 0
                    val isGapless = prefs?.isGaplessEnabled?.value ?: true
                    val crossfadeWindowMs = crossfadeSec * 1000L
                    val remainingMs = (totalMs - currentMs).coerceAtLeast(0L)
                    val nearTransition = totalMs > 0L && remainingMs <= crossfadeWindowMs + 4500L

                    // Only push frequent UI progress ticks when UI is actually visible or limit is off
                    if (isUiForeground || nearTransition) {
                        _progressMs.value = currentMs
                    }

                    nextDelayMs = when {
                        nearTransition -> 120L
                        !isUiForeground -> 600L
                        prefs?.effectiveLimitUiRefresh == true -> {
                            if (prefs.powerSaverConfig.value.mode == "balanced") 260L else 420L
                        }
                        else -> 120L
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastProgressSaveTimestamp > 2000L) {
                        lastProgressSaveTimestamp = now
                        prefs?.saveLastProgress(currentMs)
                    }
                    val dualCrossfadeEligible = crossfadeWindowMs > 0L && totalMs > crossfadeWindowMs * 2

                    if (dualCrossfadeEligible && !crossfadeTriggered && !eofHandled) {
                        val upcoming = resolveUpcomingTrack()
                        // Invalidate stale preload if upcoming track changed or became null (e.g., Repeat ONE / queue edit)
                        if (preloadedTrackId != null && (upcoming == null || upcoming.second.id != preloadedTrackId)) {
                            engineLoadMutex.withLock {
                                clearPreloadStateLocked(clearEngine = true)
                            }
                        }

                        // Preload next track 4 seconds before crossfade window begins
                        if (upcoming != null && remainingMs <= crossfadeWindowMs + 4000L && preloadedTrackId != upcoming.second.id) {
                            engineLoadMutex.withLock {
                                // Double-check under mutex that track didn't skip while waiting
                                val curTrack = _currentTrack.value
                                if (curTrack?.id == track.id && preloadedTrackId != upcoming.second.id) {
                                    clearPreloadStateLocked(clearEngine = true)
                                    val preloaded = openTrackIntoDeck(upcoming.second, isPreload = true)
                                    if (preloaded) {
                                        preloadedTrackId = upcoming.second.id
                                        preloadedTrackIndex = upcoming.first
                                        preloadedTrackMeta = upcoming.second
                                    } else {
                                        clearPreloadStateLocked(clearEngine = true)
                                    }
                                }
                            }
                        }

                        // Start true overlapping dual-deck crossfade under engineLoadMutex so it cannot race seekTo/skip
                        if (upcoming != null &&
                            remainingMs <= crossfadeWindowMs &&
                            preloadedTrackId == upcoming.second.id &&
                            !crossfadeTriggered) {
                            engineLoadMutex.withLock {
                                if (_currentTrack.value?.id == track.id &&
                                    preloadedTrackId == upcoming.second.id &&
                                    !crossfadeTriggered) {
                                    val fadeDurationMs = remainingMs.coerceIn(300L, crossfadeWindowMs).toInt()
                                    val started = NativeAudioEngine.startCrossfade(fadeDurationMs)
                                    if (started) {
                                        crossfadeTriggered = true
                                        Log.d(TAG, "Dual-deck crossfade started (${fadeDurationMs}ms) -> '${upcoming.second.title}'")
                                    }
                                }
                            }
                        }
                    } else if (!dualCrossfadeEligible && preloadedTrackId != null && !crossfadeTriggered) {
                        engineLoadMutex.withLock {
                            clearPreloadStateLocked(clearEngine = true)
                        }
                    }

                    val isEngineEof = NativeAudioEngine.isEof()
                    val shouldAdvance = when {
                        crossfadeTriggered -> false // Dual-deck crossfade handles promotion seamlessly
                        isGapless -> isEngineEof || (totalMs > 1000L && remainingMs <= 160L)
                        else -> isEngineEof
                    }

                    if (!eofHandled && shouldAdvance) {
                        Log.d(TAG, "Track transition triggered! eof=$isEngineEof, cur=$currentMs/$totalMs ms, crossfade=${crossfadeSec}s, gapless=$isGapless")
                        eofHandled = true
                        if (!isGapless && crossfadeWindowMs == 0L) {
                            // Non-gapless mode: insert a clean inter-track gap before starting next track
                            delay(250)
                        }
                        withContext(Dispatchers.Main) {
                            playNextInternal()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
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
        val stopped = _isStopped.value
        val track = _currentTrack.value
        mediaNotificationManager?.updatePlaybackState(playing, _progressMs.value, track, isStopped = stopped)
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
    private var preloadedPfd: android.os.ParcelFileDescriptor? = null

    private fun replaceDeckPfd(newPfd: android.os.ParcelFileDescriptor?, isPreload: Boolean) {
        if (isPreload) {
            try { preloadedPfd?.close() } catch (_: Exception) {}
            preloadedPfd = newPfd
        } else {
            try { activePfd?.close() } catch (_: Exception) {}
            activePfd = newPfd
        }
    }

    private suspend fun openTrackIntoDeck(track: AudioFileMetadata, isPreload: Boolean): Boolean = withContext(Dispatchers.IO) {
        val uri = track.uri
        if (uri.scheme == "content") {
            val ctx = appContext ?: return@withContext false
            try {
                replaceDeckPfd(null, isPreload)
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    replaceDeckPfd(pfd, isPreload)
                    val fd = pfd.fd
                    Log.d(TAG, "openTrackIntoDeck(preload=$isPreload): Opening via FD $fd for '${track.title}'")
                    val success = if (isPreload) {
                        NativeAudioEngine.preloadNextFd(fd)
                    } else {
                        NativeAudioEngine.openFd(fd)
                    }
                    if (success) {
                        return@withContext true
                    }
                    Log.w(TAG, "openTrackIntoDeck(preload=$isPreload): FD $fd open returned false, falling back to resolveFilePath")
                }
            } catch (e: Exception) {
                Log.e(TAG, "openTrackIntoDeck(preload=$isPreload): Failed opening ParcelFileDescriptor for $uri", e)
            }
        }
        val path = resolveFilePath(track.uri, isPreload)
        Log.d(TAG, "openTrackIntoDeck(preload=$isPreload): Opening via file path '$path' for '${track.title}'")
        return@withContext if (isPreload) {
            NativeAudioEngine.preloadNextFile(path)
        } else {
            NativeAudioEngine.openFile(path)
        }
    }

    private suspend fun resolveFilePath(uri: Uri, isPreload: Boolean = false): String = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") {
            return@withContext uri.path ?: ""
        }
        if (uri.scheme == "http" || uri.scheme == "https") {
            return@withContext uri.toString()
        }
        val ctx = appContext ?: return@withContext ""
        try {
            replaceDeckPfd(null, isPreload)
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                replaceDeckPfd(pfd, isPreload)
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
        playbackEpoch.incrementAndGet()
        pauseFadeJob?.cancel()
        pauseFadeJob = null
        loadTrackJob?.cancel()
        trackerJob?.cancel()
        mediaNotificationManager?.release()
        try {
            preloadedPfd?.close()
            preloadedPfd = null
        } catch (_: Exception) {}
        try {
            activePfd?.close()
            activePfd = null
        } catch (_: Exception) {}
        preloadedTrackId = null
        preloadedTrackIndex = -1
        preloadedTrackMeta = null
        crossfadeTriggered = false
        NativeAudioEngine.stop()
    }
}
