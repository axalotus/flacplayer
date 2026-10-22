package com.antigravity.audioplayer.desktop.audio

import com.antigravity.audioplayer.desktop.model.DesktopTrack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.sound.sampled.*
import kotlin.math.*

enum class DesktopRepeatMode {
    OFF, ALL, ONE
}

object DesktopAudioPlayer {

    private const val TAG = "DesktopAudioPlayer"

    private val _currentTrack = MutableStateFlow<DesktopTrack?>(null)
    val currentTrack = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _progressMs = MutableStateFlow(0L)
    val progressMs = _progressMs.asStateFlow()

    private val _volume = MutableStateFlow(0.85f)
    val volume = _volume.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted = _isMuted.asStateFlow()

    private val _isShuffleOn = MutableStateFlow(false)
    val isShuffleOn = _isShuffleOn.asStateFlow()

    private val _repeatMode = MutableStateFlow(DesktopRepeatMode.OFF)
    val repeatMode = _repeatMode.asStateFlow()

    private val _playlist = MutableStateFlow<List<DesktopTrack>>(emptyList())
    val playlist = _playlist.asStateFlow()

    private var sourceDataLine: SourceDataLine? = null
    private var audioStream: AudioInputStream? = null
    private var playbackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var isSeeking = false
    @Volatile
    private var seekTargetFrame = 0L
    @Volatile
    private var currentFramePos = 0L
    @Volatile
    private var targetSampleRate = 44100

    fun setPlaylist(tracks: List<DesktopTrack>) {
        _playlist.value = tracks
    }

    fun playTrack(track: DesktopTrack) {
        _currentTrack.value = track
        startPlayback(track, startFromMs = 0L)
    }

    fun playTrackAtIndex(index: Int) {
        val tracks = _playlist.value
        if (index in tracks.indices) {
            playTrack(tracks[index])
        }
    }

    fun togglePlayPause() {
        if (_isPlaying.value) {
            pause()
        } else {
            resume()
        }
    }

    fun pause() {
        _isPlaying.value = false
    }

    fun resume() {
        if (_currentTrack.value != null && !_isPlaying.value) {
            _isPlaying.value = true
        }
    }

    fun seekTo(positionMs: Long) {
        val track = _currentTrack.value ?: return
        val targetMs = positionMs.coerceIn(0L, track.durationMs)
        _progressMs.value = targetMs
        val sr = targetSampleRate.takeIf { it > 0 } ?: 44100
        seekTargetFrame = (targetMs * sr) / 1000L
        isSeeking = true
    }

    fun setVolume(vol: Float) {
        val clamped = vol.coerceIn(0f, 1f)
        _volume.value = clamped
        _isMuted.value = false
    }

    fun toggleMute() {
        _isMuted.value = !_isMuted.value
    }

    fun toggleShuffle() {
        _isShuffleOn.value = !_isShuffleOn.value
    }

    fun toggleRepeat() {
        _repeatMode.value = when (_repeatMode.value) {
            DesktopRepeatMode.OFF -> DesktopRepeatMode.ALL
            DesktopRepeatMode.ALL -> DesktopRepeatMode.ONE
            DesktopRepeatMode.ONE -> DesktopRepeatMode.OFF
        }
    }

    fun skipToNext() {
        val tracks = _playlist.value
        if (tracks.isEmpty()) return
        val cur = _currentTrack.value
        val currentIndex = tracks.indexOfFirst { it.id == cur?.id }

        val nextIndex = if (_isShuffleOn.value) {
            tracks.indices.random()
        } else {
            (currentIndex + 1) % tracks.size
        }
        playTrack(tracks[nextIndex])
    }

    fun skipToPrevious() {
        val tracks = _playlist.value
        if (tracks.isEmpty()) return
        val cur = _currentTrack.value
        val currentIndex = tracks.indexOfFirst { it.id == cur?.id }

        val prevIndex = if (currentIndex > 0) currentIndex - 1 else tracks.size - 1
        playTrack(tracks[prevIndex])
    }

    private fun startPlayback(track: DesktopTrack, startFromMs: Long) {
        playbackJob?.cancel()
        _isPlaying.value = true
        _progressMs.value = startFromMs

        playbackJob = scope.launch(Dispatchers.IO) {
            try {
                val file = File(track.filePath)
                if (!file.exists()) {
                    _isPlaying.value = false
                    return@launch
                }

                val inStream = AudioSystem.getAudioInputStream(file)
                val baseFormat = inStream.format

                // Decode to 16-bit PCM Stereo if compressed
                val decodedFormat = AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    baseFormat.sampleRate,
                    16,
                    baseFormat.channels.coerceAtLeast(2),
                    baseFormat.channels.coerceAtLeast(2) * 2,
                    baseFormat.sampleRate,
                    false
                )

                val decodedStream = AudioSystem.getAudioInputStream(decodedFormat, inStream)
                audioStream = decodedStream
                targetSampleRate = decodedFormat.sampleRate.toInt()

                val info = DataLine.Info(SourceDataLine::class.java, decodedFormat)
                val line = AudioSystem.getLine(info) as SourceDataLine
                line.open(decodedFormat, 65536)
                line.start()
                sourceDataLine = line

                val sampleRate = decodedFormat.sampleRate
                val channels = decodedFormat.channels
                val bytesPerFrame = decodedFormat.frameSize
                val bufferSize = 4096
                val rawBuffer = ByteArray(bufferSize)

                currentFramePos = 0L
                if (startFromMs > 0) {
                    val skipFrames = (startFromMs * sampleRate / 1000.0).toLong()
                    decodedStream.skip(skipFrames * bytesPerFrame)
                    currentFramePos = skipFrames
                }

                // DSP biquad filter setup for 10 bands
                val filters = Array(10) { BiquadFilter() }
                var lastGains = FloatArray(10) { 0f }

                while (isActive) {
                    if (!_isPlaying.value) {
                        delay(20)
                        continue
                    }

                    if (isSeeking) {
                        val targetFrame = seekTargetFrame
                        isSeeking = false
                        try {
                            inStream.close()
                            val newInStream = AudioSystem.getAudioInputStream(file)
                            val newDecoded = AudioSystem.getAudioInputStream(decodedFormat, newInStream)
                            newDecoded.skip(targetFrame * bytesPerFrame)
                            audioStream = newDecoded
                            currentFramePos = targetFrame
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    val currentStream = audioStream ?: break
                    val bytesRead = currentStream.read(rawBuffer, 0, rawBuffer.size)
                    if (bytesRead <= 0) {
                        // Track ended
                        onTrackFinished()
                        break
                    }

                    // Process DSP Equalizer & Volume
                    val isEqEnabled = DesktopEqualizer.isEnabled.value
                    val currentGains = DesktopEqualizer.currentGains.value
                    val currentVol = if (_isMuted.value) 0f else _volume.value

                    // Update filter coefficients if gains changed
                    if (isEqEnabled) {
                        for (b in 0 until 10) {
                            if (currentGains[b] != lastGains[b]) {
                                filters[b].configurePeaking(
                                    DesktopEqualizer.BAND_FREQUENCIES[b],
                                    sampleRate,
                                    1.414f,
                                    currentGains[b]
                                )
                                lastGains[b] = currentGains[b]
                            }
                        }
                    }

                    // Convert 16-bit PCM bytes to floats, apply DSP, convert back
                    val numSamples = bytesRead / 2
                    var sampleIdx = 0
                    while (sampleIdx < numSamples) {
                        val byteOffset = sampleIdx * 2
                        val sampleShort = (rawBuffer[byteOffset].toInt() and 0xFF) or (rawBuffer[byteOffset + 1].toInt() shl 8)
                        var sampleFloat = sampleShort / 32768.0f

                        // Apply 10-band EQ
                        if (isEqEnabled) {
                            for (b in 0 until 10) {
                                sampleFloat = filters[b].process(sampleFloat)
                            }
                        }

                        // Apply volume
                        sampleFloat = (sampleFloat * currentVol).coerceIn(-1.0f, 1.0f)

                        // Convert back to 16-bit PCM
                        val outShort = (sampleFloat * 32767.0f).toInt().coerceIn(-32768, 32767)
                        rawBuffer[byteOffset] = (outShort and 0xFF).toByte()
                        rawBuffer[byteOffset + 1] = ((outShort shr 8) and 0xFF).toByte()

                        sampleIdx++
                    }

                    line.write(rawBuffer, 0, bytesRead)
                    val framesWritten = bytesRead / bytesPerFrame
                    currentFramePos += framesWritten
                    _progressMs.value = (currentFramePos * 1000L / sampleRate.toLong())
                }

                line.drain()
                line.stop()
                line.close()
            } catch (e: Exception) {
                e.printStackTrace()
                _isPlaying.value = false
            }
        }
    }

    private fun onTrackFinished() {
        val mode = _repeatMode.value
        val cur = _currentTrack.value
        when (mode) {
            DesktopRepeatMode.ONE -> {
                cur?.let { playTrack(it) }
            }
            DesktopRepeatMode.ALL -> {
                skipToNext()
            }
            DesktopRepeatMode.OFF -> {
                val tracks = _playlist.value
                val curIdx = tracks.indexOfFirst { it.id == cur?.id }
                if (curIdx < tracks.lastIndex) {
                    skipToNext()
                } else {
                    _isPlaying.value = false
                    _progressMs.value = 0L
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// BIQUAD IIR DSP PEAKING FILTER
// ─────────────────────────────────────────────────────────────────────────────
class BiquadFilter {
    private var b0 = 1.0f
    private var b1 = 0.0f
    private var b2 = 0.0f
    private var a1 = 0.0f
    private var a2 = 0.0f

    private var x1 = 0.0f
    private var x2 = 0.0f
    private var y1 = 0.0f
    private var y2 = 0.0f

    fun configurePeaking(freqHz: Float, sampleRate: Float, q: Float, gainDb: Float) {
        val A = 10.0f.pow(gainDb / 40.0f)
        val w0 = 2.0f * PI.toFloat() * (freqHz / sampleRate).coerceIn(0.0001f, 0.499f)
        val alpha = sin(w0) / (2.0f * q)

        val a0 = 1.0f + alpha / A
        b0 = (1.0f + alpha * A) / a0
        b1 = (-2.0f * cos(w0)) / a0
        b2 = (1.0f - alpha * A) / a0
        a1 = (-2.0f * cos(w0)) / a0
        a2 = (1.0f - alpha / A) / a0
    }

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return if (y.isNaN() || y.isInfinite()) 0f else y
    }
}
