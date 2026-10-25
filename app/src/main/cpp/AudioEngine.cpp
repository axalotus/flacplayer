#include "AudioEngine.h"
#include <cmath>
#include <android/log.h>
#include <sys/resource.h>

#define OBOE_TAG "OboeAudioEngine"
#define LOG_OBOE_D(...) __android_log_print(ANDROID_LOG_DEBUG, OBOE_TAG, __VA_ARGS__)
#define LOG_OBOE_I(...) __android_log_print(ANDROID_LOG_INFO, OBOE_TAG, __VA_ARGS__)
#define LOG_OBOE_W(...) __android_log_print(ANDROID_LOG_WARN, OBOE_TAG, __VA_ARGS__)
#define LOG_OBOE_E(...) __android_log_print(ANDROID_LOG_ERROR, OBOE_TAG, __VA_ARGS__)

// 1 second of audio at 192kHz (stereo) = 192000 * 2 = 384000 floats.
const int32_t RING_BUFFER_CAPACITY = 384000;
const int32_t PREBUFFER_SIZE = 16384;

AudioEngine::AudioEngine() : mPreBuffer(PREBUFFER_SIZE) {
    mDecoder = std::make_unique<AudioDecoder>();
    mRingBuffer = std::make_unique<RingBuffer<float>>(RING_BUFFER_CAPACITY);
    mEqualizer = std::make_unique<EqualizerEngine>();
    LOG_OBOE_I("AudioEngine created");
}

AudioEngine::~AudioEngine() {
    stop();
    LOG_OBOE_I("AudioEngine destroyed");
}

void AudioEngine::stopDecodeThread() {
    mDecodeThreadRunning.store(false, std::memory_order_release);
    mDecodeCv.notify_all();
    if (mDecodeThread.joinable()) {
        mDecodeThread.join();
    }
}

void AudioEngine::beginBufferReset() {
    mIsResettingBuffer.store(true, std::memory_order_seq_cst);
    // Wait for any in-flight onAudioReady callback to finish reading from mRingBuffer
    while (mActiveCallbacks.load(std::memory_order_acquire) > 0) {
        std::this_thread::yield();
    }
}

void AudioEngine::endBufferReset() {
    mIsResettingBuffer.store(false, std::memory_order_release);
}

bool AudioEngine::initStreamAfterLoad() {
    int sampleRate = mDecoder->getSampleRate();
    int channels = mDecoder->getChannels();
    LOG_OBOE_I("initStreamAfterLoad: Successfully opened. SampleRate: %d, Channels: %d, TotalFrames: %lld",
               sampleRate, channels, (long long)mDecoder->getTotalFrames());

    // Pre-decode initial frames into RingBuffer so buffer is never dry on startup
    uint32_t ch = (channels > 0) ? (uint32_t)channels : 2u;
    uint64_t framesToRead = PREBUFFER_SIZE / ch;
    uint64_t framesDecoded = mDecoder->decodeFrames(mPreBuffer.data(), framesToRead);
    if (framesDecoded > 0) {
        int32_t totalSamples = (int32_t)(framesDecoded * ch);
        mRingBuffer->write(mPreBuffer.data(), totalSamples);

        // Estimate initial ReplayGain loudness from pre-buffered samples
        double sumSq = 0.0;
        float peak = 0.0f;
        int32_t nonSilentCount = 0;
        for (int32_t i = 0; i < totalSamples; ++i) {
            float absVal = std::fabs(mPreBuffer[i]);
            if (absVal > 0.001f) {
                sumSq += (double)absVal * (double)absVal;
                nonSilentCount++;
            }
            if (absVal > peak) peak = absVal;
        }
        if (nonSilentCount > 256) {
            float rms = (float)std::sqrt(sumSq / (double)nonSilentCount);
            // Target reference RMS ~0.18f (-15 dBFS), clamped to [-6 dB .. +6 dB] (0.5f .. 2.0f) and headroom-limited by peak
            float desiredGain = (rms > 0.005f) ? (0.18f / rms) : 1.0f;
            if (desiredGain > 2.0f) desiredGain = 2.0f;
            if (desiredGain < 0.5f) desiredGain = 0.5f;
            if (peak * desiredGain > 0.98f && peak > 0.01f) {
                desiredGain = 0.98f / peak;
            }
            mTrackReplayGainLinear.store(desiredGain, std::memory_order_release);
        } else {
            mTrackReplayGainLinear.store(1.0f, std::memory_order_release);
        }
        LOG_OBOE_D("initStreamAfterLoad: Pre-buffered %lld frames into RingBuffer (ReplayGain linear=%.2f)",
                   (long long)framesDecoded, mTrackReplayGainLinear.load());
    } else {
        mTrackReplayGainLinear.store(1.0f, std::memory_order_release);
    }

    // Check if existing Oboe stream can be reused (always reuse when Gapless is enabled and format matches)
    bool gapless = mGaplessEnabled.load(std::memory_order_acquire);
    bool needNewStream = (!mStream ||
                          !gapless ||
                          mStream->getState() == oboe::StreamState::Closed ||
                          mStream->getState() == oboe::StreamState::Disconnected ||
                          mStream->getSampleRate() != sampleRate ||
                          mStream->getChannelCount() != channels);

    if (needNewStream) {
        LOG_OBOE_I("initStreamAfterLoad: Creating Oboe stream (SR: %d, Ch: %d, gapless=%d)", sampleRate, channels, (int)gapless);
        closeStream();
        if (!openStream(channels, sampleRate)) {
            LOG_OBOE_E("initStreamAfterLoad: openStream failed!");
            return false;
        }
    } else {
        LOG_OBOE_I("initStreamAfterLoad: Gapless reuse of existing Oboe stream (Current State: %s)", oboe::convertToText(mStream->getState()));
    }

    return true;
}

bool AudioEngine::loadFd(int fd) {
    std::lock_guard<std::mutex> lock(mLock);
    LOG_OBOE_I("loadFd: requesting to open fd %d", fd);

    mIsPlaying.store(false, std::memory_order_release);
    stopDecodeThread();

    beginBufferReset();
    mIsEof = false;
    mDecoderReachedEof = false;
    mPlayedSampleCount.store(0, std::memory_order_release);
    mRingBuffer->clear();
    if (mEqualizer) {
        mEqualizer->reset();
    }

    if (!mDecoder->openFd(fd)) {
        endBufferReset();
        LOG_OBOE_E("loadFd: Failed to open fd %d in AudioDecoder", fd);
        return false;
    }

    bool ok = initStreamAfterLoad();
    endBufferReset();
    return ok;
}

bool AudioEngine::loadFile(const std::string& filePath) {
    if (filePath.rfind("/proc/self/fd/", 0) == 0) {
        int fd = -1;
        if (sscanf(filePath.c_str(), "/proc/self/fd/%d", &fd) == 1 && fd >= 0) {
            LOG_OBOE_I("loadFile: path '%s' is proc fd -> delegating to loadFd(%d)", filePath.c_str(), fd);
            return loadFd(fd);
        }
    }

    std::lock_guard<std::mutex> lock(mLock);
    LOG_OBOE_I("loadFile: requesting to open '%s'", filePath.c_str());

    mIsPlaying.store(false, std::memory_order_release);
    stopDecodeThread();

    beginBufferReset();
    mIsEof = false;
    mDecoderReachedEof = false;
    mPlayedSampleCount.store(0, std::memory_order_release);
    mRingBuffer->clear();
    if (mEqualizer) {
        mEqualizer->reset();
    }

    if (!mDecoder->openFile(filePath)) {
        endBufferReset();
        LOG_OBOE_E("loadFile: Failed to open '%s' in AudioDecoder", filePath.c_str());
        return false;
    }

    bool ok = initStreamAfterLoad();
    endBufferReset();
    return ok;
}

int64_t AudioEngine::seekTo(int64_t targetFrame) {
    std::lock_guard<std::mutex> lock(mLock);
    if (!mDecoder) return -1;

    if (targetFrame < 0) targetFrame = 0;
    int64_t totalFrames = (int64_t)mDecoder->getTotalFrames();
    if (totalFrames > 0 && targetFrame >= totalFrames) {
        targetFrame = totalFrames - 1;
    }

    LOG_OBOE_I("seekTo: targetFrame = %lld", (long long)targetFrame);

    // Pause decoding thread temporarily
    bool wasRunning = mDecodeThreadRunning.load(std::memory_order_acquire);
    if (wasRunning) {
        stopDecodeThread();
    }

    // Fence off onAudioReady so clear(), EQ reset, and pre-buffering happen atomically with respect to audio callback
    beginBufferReset();
    mIsEof = false;
    mDecoderReachedEof = false;

    int64_t result = mDecoder->seek(targetFrame);
    int64_t actualFrame = (result >= 0) ? result : targetFrame;
    if (actualFrame < 0) actualFrame = 0;
    uint32_t ch = mDecoder->getChannels();
    if (ch == 0) ch = 2;
    mPlayedSampleCount.store((uint64_t)(actualFrame * ch), std::memory_order_release);

    mRingBuffer->clear();
    if (mEqualizer) {
        mEqualizer->reset();
    }

    // Pre-decode frames immediately using pre-allocated buffer to prevent onAudioReady from underrunning
    uint64_t framesToRead = PREBUFFER_SIZE / ch;
    uint64_t framesDecoded = mDecoder->decodeFrames(mPreBuffer.data(), framesToRead);
    if (framesDecoded > 0) {
        mRingBuffer->write(mPreBuffer.data(), framesDecoded * ch);
    }
    endBufferReset();

    // Resume decoding thread if it was running or player is playing
    if ((wasRunning || mIsPlaying.load(std::memory_order_acquire)) && mDecoder->getSampleRate() > 0) {
        mDecodeThreadRunning.store(true, std::memory_order_release);
        mDecodeThread = std::thread(&AudioEngine::decodeLoop, this);
    }

    LOG_OBOE_D("seekTo: Seek completed to frame %lld", (long long)actualFrame);
    return result;
}

int64_t AudioEngine::getCurrentFrame() const {
    if (!mDecoder) return 0;
    uint32_t ch = mDecoder->getChannels();
    if (ch == 0) ch = 1;
    uint64_t count = mPlayedSampleCount.load(std::memory_order_acquire);
    return (int64_t)(count / ch);
}

int64_t AudioEngine::getTotalFrames() const {
    if (!mDecoder) return 0;
    return mDecoder->getTotalFrames();
}

bool AudioEngine::start() {
    std::lock_guard<std::mutex> lock(mLock);
    const char* curStateStr = mStream ? oboe::convertToText(mStream->getState()) : "null";
    LOG_OBOE_I("start() called. Current stream state: %s, mIsPlaying: %d", curStateStr, (int)mIsPlaying.load());

    if (!mDecoder || mDecoder->getSampleRate() == 0) {
        LOG_OBOE_E("start() called but no file loaded in decoder");
        return false;
    }

    if (!mStream || mStream->getState() == oboe::StreamState::Closed || mStream->getState() == oboe::StreamState::Disconnected) {
        LOG_OBOE_I("start(): Stream is closed or disconnected, recreating stream...");
        closeStream();
        if (!openStream(mDecoder->getChannels(), mDecoder->getSampleRate())) {
            LOG_OBOE_E("start(): openStream failed!");
            return false;
        }
    }

    oboe::StreamState state = mStream->getState();
    if (state != oboe::StreamState::Started && state != oboe::StreamState::Starting) {
        oboe::Result result = mStream->requestStart();
        LOG_OBOE_I("start(): mStream->requestStart() returned %s (%d)", oboe::convertToText(result), (int)result);
        if (result != oboe::Result::OK) {
            LOG_OBOE_W("start(): requestStart failed (%s), attempting full stream reopen...", oboe::convertToText(result));
            closeStream();
            if (openStream(mDecoder->getChannels(), mDecoder->getSampleRate())) {
                beginBufferReset();
                int64_t currentFrame = getCurrentFrame();
                mDecoder->seek(currentFrame);
                mRingBuffer->clear();
                endBufferReset();
                result = mStream->requestStart();
                LOG_OBOE_I("start(): retry requestStart returned %s", oboe::convertToText(result));
            }
            if (result != oboe::Result::OK) {
                LOG_OBOE_E("start(): fatal failed to start audio stream");
                return false;
            }
        }
    }

    mIsPlaying.store(true, std::memory_order_release);
    mIsEof = false;
    mDecoderReachedEof = false;

    // Safely start decode thread
    stopDecodeThread();
    mDecodeThreadRunning.store(true, std::memory_order_release);
    mDecodeThread = std::thread(&AudioEngine::decodeLoop, this);

    LOG_OBOE_I("start(): AudioEngine playback active and running");
    return true;
}

void AudioEngine::pause() {
    std::lock_guard<std::mutex> lock(mLock);
    LOG_OBOE_I("pause() called. mIsPlaying: %d", (int)mIsPlaying.load());

    if (!mIsPlaying.load(std::memory_order_acquire) || !mStream) return;

    oboe::Result result = mStream->requestPause();
    LOG_OBOE_I("pause(): requestPause returned %s", oboe::convertToText(result));

    mIsPlaying.store(false, std::memory_order_release);
    stopDecodeThread();
}

void AudioEngine::stop() {
    std::lock_guard<std::mutex> lock(mLock);
    LOG_OBOE_I("stop() called");

    mIsPlaying.store(false, std::memory_order_release);
    stopDecodeThread();

    if (!mStream) return;

    oboe::Result result = mStream->requestStop();
    LOG_OBOE_D("stop(): requestStop returned %s", oboe::convertToText(result));

    closeStream();
}

void AudioEngine::decodeLoop() {
    LOG_OBOE_D("Decode thread started");
    setpriority(PRIO_PROCESS, 0, -8); // Boost decode thread priority to prevent underruns
    const int32_t BUFFER_SIZE = 8192;
    const int32_t MIN_WRITE_CHUNK = 2048;
    std::vector<float> tempBuffer(BUFFER_SIZE);

    while (mDecodeThreadRunning.load(std::memory_order_acquire)) {
        if (mDecoderReachedEof.load(std::memory_order_acquire)) {
            std::unique_lock<std::mutex> lock(mDecodeCvMutex);
            mDecodeCv.wait_for(lock, std::chrono::milliseconds(100), [this]() {
                return !mDecodeThreadRunning.load(std::memory_order_acquire) ||
                       !mDecoderReachedEof.load(std::memory_order_acquire);
            });
            continue;
        }

        int32_t availableWrite = mRingBuffer->getAvailableWrite();
        if (availableWrite >= MIN_WRITE_CHUNK) {
            uint32_t ch = mDecoder->getChannels();
            if (ch == 0) ch = 2;
            int32_t samplesToDecode = std::min<int32_t>(availableWrite, BUFFER_SIZE);
            uint64_t framesToRead = (uint64_t)(samplesToDecode / ch);
            if (framesToRead == 0) framesToRead = 1;

            uint64_t framesDecoded = mDecoder->decodeFrames(tempBuffer.data(), framesToRead);

            if (framesDecoded > 0) {
                mRingBuffer->write(tempBuffer.data(), framesDecoded * ch);
            } else {
                // End of file reached by decoder
                if (!mDecoderReachedEof.load(std::memory_order_acquire)) {
                    LOG_OBOE_I("decodeLoop: Decoder reached EOF");
                    mDecoderReachedEof.store(true, std::memory_order_release);
                }
            }
        } else {
            // Ring buffer is nearly full; wait until onAudioReady consumes frames or thread stops
            std::unique_lock<std::mutex> lock(mDecodeCvMutex);
            mDecodeCv.wait_for(lock, std::chrono::milliseconds(4), [this, MIN_WRITE_CHUNK]() {
                return !mDecodeThreadRunning.load(std::memory_order_acquire) ||
                       mRingBuffer->getAvailableWrite() >= MIN_WRITE_CHUNK;
            });
        }
    }
    LOG_OBOE_D("Decode thread finished");
}

bool AudioEngine::openStream(int channelCount, int sampleRate) {
    bool isSafe = mSafeBufferMode.load();

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
           ->setSharingMode(oboe::SharingMode::Shared)
           ->setFormat(oboe::AudioFormat::Float)
           ->setFormatConversionAllowed(true)
           ->setChannelConversionAllowed(true)
           ->setUsage(oboe::Usage::Media)
           ->setContentType(oboe::ContentType::Music)
           ->setChannelCount(channelCount)
           ->setCallback(this);

    if (isSafe) {
        LOG_OBOE_I("openStream: Configuring Safe Audio Buffer mode (PerformanceMode::None, capacity=8192)");
        builder.setPerformanceMode(oboe::PerformanceMode::None);
        builder.setBufferCapacityInFrames(8192);
    } else {
        builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    }

    // Apply audio mode with automatic fallback for Android < 8.0 (API < 26)
    bool wantsAAudio = (mAudioMode == 0 || mAudioMode == 2);
    if (wantsAAudio && !builder.isAAudioSupported()) {
        LOG_OBOE_I("openStream: AAudio is not supported on this device/Android version (API < 26). Auto-falling back to OpenSL ES.");
        wantsAAudio = false;
    }

    if (!wantsAAudio || mAudioMode == 1) {
        builder.setAudioApi(oboe::AudioApi::OpenSLES);
        builder.setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
    } else {
        builder.setAudioApi(oboe::AudioApi::AAudio);
        if (mAudioMode == 2) {
            builder.setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None);
        } else {
            builder.setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
        }
    }
    builder.setSampleRate(sampleRate);

    // Apply output device routing if specified
    if (mDeviceId != oboe::kUnspecified) {
        builder.setDeviceId(mDeviceId);
    }

    oboe::Result result = builder.openStream(mStream);
    if (result != oboe::Result::OK && wantsAAudio) {
        LOG_OBOE_W("openStream: AAudio openStream failed (%s), retrying with OpenSL ES...", oboe::convertToText(result));
        builder.setAudioApi(oboe::AudioApi::OpenSLES);
        builder.setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
        result = builder.openStream(mStream);
    }

    if (result != oboe::Result::OK) {
        LOG_OBOE_E("openStream: Failed to open stream: %s", oboe::convertToText(result));
        return false;
    }

    // Configure hardware buffer size for glitch-free playback
    int32_t burst = mStream->getFramesPerBurst();
    int32_t capacity = mStream->getBufferCapacityInFrames();
    if (isSafe) {
        int32_t targetBuffer = (burst > 0) ? (burst * 8) : 4096;
        if (targetBuffer < 2048) targetBuffer = 2048;
        if (capacity > 0 && targetBuffer > capacity) targetBuffer = capacity;
        mStream->setBufferSizeInFrames(targetBuffer);
        LOG_OBOE_I("openStream: Safe Buffer configured: target=%d frames, actual=%d, burst=%d, capacity=%d",
                   targetBuffer, mStream->getBufferSizeInFrames(), burst, capacity);
    } else {
        int32_t targetBuffer = (burst > 0) ? (burst * 2) : 1024;
        mStream->setBufferSizeInFrames(targetBuffer);
    }

    int32_t actualSr = mStream->getSampleRate() > 0 ? mStream->getSampleRate() : sampleRate;
    int32_t actualCh = mStream->getChannelCount() > 0 ? mStream->getChannelCount() : channelCount;
    mEqualizer->init((float)actualSr, actualCh);

    LOG_OBOE_I("openStream: Stream opened successfully: Mode=%d, API=%s, SampleRate=%d, Channels=%d, BufferFrames=%d",
               mAudioMode, oboe::convertToText(mStream->getAudioApi()), mStream->getSampleRate(), mStream->getChannelCount(),
               mStream->getBufferSizeInFrames());
    return true;
}

void AudioEngine::closeStream() {
    if (mStream) {
        LOG_OBOE_D("closeStream(): closing stream");
        mStream->close();
        mStream.reset();
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream *oboeStream, void *audioData, int32_t numFrames) {
    struct CallbackScopeGuard {
        std::atomic<int32_t>& counter;
        explicit CallbackScopeGuard(std::atomic<int32_t>& c) : counter(c) {
            counter.fetch_add(1, std::memory_order_acq_rel);
        }
        ~CallbackScopeGuard() {
            counter.fetch_sub(1, std::memory_order_acq_rel);
        }
    } guard(mActiveCallbacks);

    float *floatData = static_cast<float *>(audioData);
    int32_t channelCount = oboeStream->getChannelCount();
    int32_t totalSamples = numFrames * channelCount;

    if (!mIsPlaying.load(std::memory_order_acquire) ||
        mIsResettingBuffer.load(std::memory_order_acquire) ||
        !mRingBuffer) {
        memset(audioData, 0, totalSamples * sizeof(float));
        return oboe::DataCallbackResult::Continue;
    }

    int32_t samplesRead = mRingBuffer->read(floatData, totalSamples);

    // Wake up decode thread immediately if space opened up in RingBuffer
    if (samplesRead > 0 && mRingBuffer->getAvailableWrite() >= 8192) {
        mDecodeCv.notify_one();
    }

    // Track actual samples played for accurate position
    if (samplesRead > 0) {
        mPlayedSampleCount.fetch_add((uint64_t)samplesRead, std::memory_order_relaxed);
    }

    // Fill the rest with silence if we had an underrun
    if (samplesRead < totalSamples) {
        for (int32_t i = samplesRead; i < totalSamples; ++i) {
            floatData[i] = 0.0f;
        }

        // Only set EOF if decoder finished reading AND ring buffer is completely drained AND we played frames
        if (mDecoderReachedEof.load(std::memory_order_acquire) &&
            mRingBuffer->getAvailableRead() == 0 &&
            mPlayedSampleCount.load(std::memory_order_relaxed) > 0) {
            if (!mIsEof.load(std::memory_order_relaxed)) {
                mIsEof.store(true, std::memory_order_release);
                LOG_OBOE_I("onAudioReady: Hardware playback reached EOF! Total samples played: %lld", (long long)mPlayedSampleCount.load());
            }
        }
    }

    // Process DSP (Equalizer & Bass Boost) in-place on actual decoded audio
    if (mEqualizer && samplesRead > 0) {
        mEqualizer->processBuffer(floatData, samplesRead);
    }

    // Apply ReplayGain loudness normalization and smooth Crossfade/Master Volume ramping
    float targetVol = mTargetVolume.load(std::memory_order_relaxed);
    float rgGain = mReplayGainEnabled.load(std::memory_order_relaxed)
                       ? mTrackReplayGainLinear.load(std::memory_order_relaxed)
                       : 1.0f;

    // Continuously refine ReplayGain estimate on active audio blocks when enabled
    if (mReplayGainEnabled.load(std::memory_order_relaxed) && samplesRead > 0) {
        double blockSq = 0.0;
        int32_t activeCount = 0;
        for (int32_t i = 0; i < samplesRead; i += 4) {
            float v = std::fabs(floatData[i]);
            if (v > 0.005f) {
                blockSq += (double)v * (double)v;
                activeCount++;
            }
        }
        if (activeCount > 16) {
            float blockRms = (float)std::sqrt(blockSq / (double)activeCount);
            float blockTarget = std::clamp(0.18f / blockRms, 0.5f, 2.0f);
            float smoothedRg = rgGain * 0.995f + blockTarget * 0.005f;
            mTrackReplayGainLinear.store(smoothedRg, std::memory_order_relaxed);
            rgGain = smoothedRg;
        }
    }

    // Safety guard + smooth volume/ReplayGain application + soft limiting:
    // ensure no NaN/Inf or >0dBFS overflow ever reaches OpenSL ES / AAudio hardware
    for (int32_t i = 0; i < totalSamples; ++i) {
        mCurrentVolume += (targetVol - mCurrentVolume) * 0.0015f;
        float s = floatData[i] * mCurrentVolume * rgGain;
        if (std::isnan(s) || std::isinf(s)) {
            floatData[i] = 0.0f;
        } else if (s > 1.0f) {
            floatData[i] = 1.0f;
        } else if (s < -1.0f) {
            floatData[i] = -1.0f;
        } else {
            floatData[i] = s;
        }
    }

    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorBeforeClose(oboe::AudioStream *oboeStream, oboe::Result error) {
    LOG_OBOE_E("onErrorBeforeClose: %s (%d)", oboe::convertToText(error), (int)error);
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream *oboeStream, oboe::Result error) {
    LOG_OBOE_E("onErrorAfterClose: %s (%d)", oboe::convertToText(error), (int)error);

    // Automatically recover audio stream on device routing change (Bluetooth disconnect/connect, USB DAC change)
    if (error == oboe::Result::ErrorDisconnected ||
        error == oboe::Result::ErrorUnavailable ||
        error == oboe::Result::ErrorInternal ||
        error == oboe::Result::ErrorInvalidState) {
        LOG_OBOE_I("Device disconnected or routing changed, recovering audio stream...");
        reopenStream(mIsPlaying.load(std::memory_order_acquire));
    } else {
        mIsPlaying.store(false, std::memory_order_release);
        mDecodeThreadRunning.store(false, std::memory_order_release);
        mDecodeCv.notify_all();
    }
}

bool AudioEngine::reopenStream(bool restartIfPlaying) {
    std::lock_guard<std::mutex> lock(mLock);

    if (!mDecoder || mDecoder->getSampleRate() == 0) {
        LOG_OBOE_D("reopenStream: No active decoder / file loaded");
        return false;
    }

    LOG_OBOE_I("reopenStream: Reopening stream (restartIfPlaying=%d, wasPlaying=%d)", restartIfPlaying, (int)mIsPlaying.load());

    // Stop decode thread safely
    stopDecodeThread();

    bool shouldPlay = restartIfPlaying && mIsPlaying.load(std::memory_order_acquire);
    mIsPlaying.store(false, std::memory_order_release);

    // Close old dead stream
    closeStream();

    // Open new audio stream with active hardware route
    if (!openStream(mDecoder->getChannels(), mDecoder->getSampleRate())) {
        LOG_OBOE_E("reopenStream: Failed to open new audio stream");
        return false;
    }

    // Seek decoder back to exact playback frame (preserves mPlayedSampleCount)
    beginBufferReset();
    int64_t currentFrame = getCurrentFrame();
    mDecoder->seek(currentFrame);
    mRingBuffer->clear();
    endBufferReset();

    if (shouldPlay) {
        mIsPlaying.store(true, std::memory_order_release);
        mDecodeThreadRunning.store(true, std::memory_order_release);
        mDecodeThread = std::thread(&AudioEngine::decodeLoop, this);
        oboe::Result res = mStream->requestStart();
        if (res != oboe::Result::OK) {
            LOG_OBOE_E("reopenStream: requestStart failed: %s", oboe::convertToText(res));
            return false;
        }
    }

    LOG_OBOE_I("reopenStream: Audio stream recovered successfully at frame %lld", (long long)currentFrame);
    return true;
}

bool AudioEngine::recreateStream(int mode) {
    std::lock_guard<std::mutex> lock(mLock);
    mAudioMode = mode;
    LOG_OBOE_I("recreateStream: mode = %d", mode);

    if (!mDecoder || mDecoder->getSampleRate() == 0) return false;

    // Stop decode thread
    stopDecodeThread();

    bool wasPlaying = mIsPlaying.load(std::memory_order_acquire);
    mIsPlaying.store(false, std::memory_order_release);

    if (mStream) {
        mStream->requestStop();
        closeStream();
    }

    if (!openStream(mDecoder->getChannels(), mDecoder->getSampleRate())) return false;

    // Re-sync decoder to current playback frame
    beginBufferReset();
    int64_t currentFrame = getCurrentFrame();
    mDecoder->seek(currentFrame);
    mRingBuffer->clear();
    endBufferReset();

    if (wasPlaying) {
        mIsPlaying.store(true, std::memory_order_release);
        mDecodeThreadRunning.store(true, std::memory_order_release);
        mDecodeThread = std::thread(&AudioEngine::decodeLoop, this);
        mStream->requestStart();
    }

    return true;
}

bool AudioEngine::setAudioDeviceId(int deviceId) {
    LOG_OBOE_I("setAudioDeviceId: changing device ID to %d", deviceId);
    mDeviceId = deviceId;
    return reopenStream(mIsPlaying);
}

bool AudioEngine::setSafeBufferMode(bool enabled) {
    LOG_OBOE_I("setSafeBufferMode: changing to %d (was %d)", (int)enabled, (int)mSafeBufferMode.load());
    mSafeBufferMode.store(enabled);
    return reopenStream(mIsPlaying);
}

void AudioEngine::setVolume(float volume) {
    float clamped = std::clamp(volume, 0.0f, 1.0f);
    mTargetVolume.store(clamped, std::memory_order_release);
}

void AudioEngine::setReplayGainEnabled(bool enabled) {
    LOG_OBOE_I("setReplayGainEnabled: %d", (int)enabled);
    mReplayGainEnabled.store(enabled, std::memory_order_release);
}

void AudioEngine::setGaplessEnabled(bool enabled) {
    LOG_OBOE_I("setGaplessEnabled: %d", (int)enabled);
    mGaplessEnabled.store(enabled, std::memory_order_release);
}

bool AudioEngine::isEof() const {
    return mIsEof.load();
}
