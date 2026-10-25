package com.antigravity.audioplayer.audio

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
     * Enables or disables automatic ReplayGain loudness normalization in the native DSP pipeline.
     */
    external fun setReplayGainEnabled(enabled: Boolean)

    /**
     * Enables or disables gapless stream reuse across track transitions.
     */
    external fun setGaplessEnabled(enabled: Boolean)
}
