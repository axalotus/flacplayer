package com.zuna.player.audio

/**
 * Singleton wrapper for the C++ Oboe Audio Engine.
 */
object NativeAudioEngine {
    
    init {
        System.loadLibrary("audioplayer")
    }

    /**
     * Initializes the C++ audio engine.
     */
    external fun initEngine()

    /**
     * Opens an audio file by direct file descriptor.
     * @param fd Raw open Linux file descriptor.
     * @return true if opened successfully.
     */
    external fun openFd(fd: Int): Boolean

    /**
     * Opens a FLAC file and prepares the decoder and ring buffer.
     * @param filePath Absolute path to the FLAC file.
     * @return true if opened successfully.
     */
    external fun openFile(filePath: String): Boolean

    /**
     * Seeks to a specific PCM frame.
     * @param targetFrame The target frame.
     * @return The actual frame sought to, or -1 on error.
     */
    external fun seekTo(targetFrame: Long): Long

    /**
     * Gets the current playback position in PCM frames.
     */
    external fun getPosition(): Long

    /**
     * Gets the total duration in PCM frames.
     */
    external fun getDuration(): Long

    /**
     * Gets the active decoder output sample rate in Hz (may differ from source for <44.1kHz tracks), or 0 if unloaded.
     */
    external fun getSampleRate(): Int

    /**
     * Checks if the track reached end of file and audio output has drained.
     */
    external fun isEof(): Boolean

    // --- DSP / Equalizer ---

    /**
     * Enables or disables the 10-band equalizer and bass boost.
     */
    external fun setEqEnabled(enabled: Boolean)

    /**
     * Sets the gain for a specific equalizer band.
     * @param bandIndex 0 to 9.
     * @param gainDb Gain in decibels (e.g., -12f to +12f).
     */
    external fun setBandGain(bandIndex: Int, gainDb: Float)

    /**
     * Sets the strength of the Bass Boost filter.
     * @param strength 0.0f (off) to 1.0f (max).
     */
    external fun setBassBoost(strength: Float)

    /**
     * Starts or resumes playback.
     * @return true if successfully started, false otherwise.
     */
    external fun start(): Boolean

    /**
     * Pauses playback.
     */
    external fun pause()

    /**
     * Stops playback and closes the stream.
     */
    external fun stop()

    /**
     * Destroys the engine instance, freeing all resources.
     */
    external fun destroyEngine()

    /**
     * Recreates the Oboe audio stream with the selected audio API.
     * @param mode 0=AAudio (low latency), 1=OpenSL ES, 2=Hi-Res (exact sampleRate, no SRC)
     */
    external fun recreateStream(mode: Int): Boolean

    /**
     * Sets safe/deep buffer mode to eliminate underruns and crackling on slow CPUs or Android < 8.0.
     * @param enabled True for deep buffer (PerformanceMode::None, 50-100ms buffer), false for LowLatency.
     */
    external fun setSafeBufferMode(enabled: Boolean): Boolean

    /**
     * Reopens stream after routing change / disconnect, preserving current frame position.
     * @param restartIfPlaying If true, restarts playback if engine was playing.
     */
    external fun reopenStream(restartIfPlaying: Boolean): Boolean

    /**
     * Directs Oboe audio stream to a specific AudioDeviceInfo ID.
     * @param deviceId The device ID from AudioDeviceInfo (or 0 for unspecified/system default).
     */
    external fun setAudioDeviceId(deviceId: Int): Boolean

    /**
     * Sets master playback volume (0.0f to 1.0f) with smooth per-sample ramping (used for Crossfade).
     */
    external fun setVolume(volume: Float)

    /**
     * Smoothly fades output volume down before pausing playback.
     */
    external fun beginPauseFade(durationMs: Int)

    /**
     * Cancels any ongoing pause fade-out and restores unity volume ramp.
     */
    external fun cancelPauseFade()

    /**
     * Enables or disables automatic ReplayGain loudness normalization in the native DSP pipeline.
     */
    external fun setReplayGainEnabled(enabled: Boolean)

    /**
     * Enables or disables gapless stream reuse across track transitions.
     */
    external fun setGaplessEnabled(enabled: Boolean)

    /**
     * Preloads the next track into the secondary deck for true overlapping dual-deck crossfade.
     */
    external fun preloadNextFile(filePath: String): Boolean

    /**
     * Preloads the next track by Linux file descriptor into the secondary deck.
     */
    external fun preloadNextFd(fd: Int): Boolean

    /**
     * Starts an equal-power overlapping crossfade between the active primary track and preloaded next track.
     */
    external fun startCrossfade(durationMs: Int): Boolean

    /**
     * Cancels any preloaded secondary track or active crossfade and returns to solo primary playback.
     */
    external fun cancelCrossfade()

    /**
     * Returns true once an active crossfade has faded the previous track to silence.
     */
    external fun isCrossfadeCompleted(): Boolean

    /**
     * Atomically promotes the preloaded/playing secondary track to become the primary deck without audio gaps.
     */
    external fun promotePreloadedTrack(): Boolean
}
