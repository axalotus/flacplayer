#include "AudioEngine.h"
#include <algorithm>
#include <cmath>
#include <cstring>
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

AudioEngine::AudioEngine()
    : mPreBuffer(PREBUFFER_SIZE),
      mNextPreBuffer(PREBUFFER_SIZE * 2),
      mNextMixBuffer(PREBUFFER_SIZE) {
    mDecoder = std::make_unique<AudioDecoder>();
    mRingBuffer = std::make_unique<RingBuffer<float>>(RING_BUFFER_CAPACITY);
    mNextDecoder = std::make_unique<AudioDecoder>();
    mNextRingBuffer = std::make_unique<RingBuffer<float>>(RING_BUFFER_CAPACITY);
    mEqualizer = std::make_unique<EqualizerEngine>();
    mRecoveryThread = std::thread(&AudioEngine::recoveryLoop, this);
    LOG_OBOE_I("AudioEngine created");
}

AudioEngine::~AudioEngine() {
    stop();
    {
        std::lock_guard<std::mutex> lock(mRecoveryMutex);
        mRecoveryShutdown = true;
        mRecoveryPending = false;
        mPendingRecoveryStream = nullptr;
    }
    mRecoveryCv.notify_all();
    if (mRecoveryThread.joinable()) {
        mRecoveryThread.join();
    }
    LOG_OBOE_I("AudioEngine destroyed");
}

void AudioEngine::stopDecodeThread() {
    mDecodeThreadRunning.store(false, std::memory_order_release);
    mDecodeCv.notify_all();
    if (mDecodeThread.joinable()) {
        mDecodeThread.join();
    }
}

void AudioEngine::stopNextDecodeThread() {
    mNextDecodeThreadRunning.store(false, std::memory_order_release);
    mNextDecodeCv.notify_all();
    if (mNextDecodeThread.joinable()) {
        mNextDecodeThread.join();
    }
}

void AudioEngine::resetNextDeckLocked() {
    mCrossfadeActive.store(false, std::memory_order_release);
    mCrossfadeCompleted.store(false, std::memory_order_release);
    mNextIsActive.store(false, std::memory_order_release);
    mNextPreloaded.store(false, std::memory_order_release);
    stopNextDecodeThread();
    beginBufferReset();
    if (mNextRingBuffer) {
        mNextRingBuffer->clear();
    }
    mNextPlayedSampleCount.store(0, std::memory_order_release);
    mNextOutputSampleRate.store(0, std::memory_order_release);
    mNextTrackReplayGainLinear.store(1.0f, std::memory_order_release);
    mNextDecoderReachedEof.store(false, std::memory_order_release);
    mNextEof.store(false, std::memory_order_release);
    mCrossfadeDurationFrames.store(0, std::memory_order_release);
    mCrossfadeElapsedFrames = 0;
    endBufferReset();
    if (mNextDecoder) {
        mNextDecoder->close();
    }
}

float AudioEngine::estimatePrebufferReplayGain(const float* buffer, int32_t totalSamples) {
    if (!buffer || totalSamples <= 0) return 1.0f;
    double sumSq = 0.0;
    float peak = 0.0f;
    int32_t nonSilentCount = 0;
    for (int32_t i = 0; i < totalSamples; ++i) {
        float absVal = std::fabs(buffer[i]);
        if (absVal > 0.001f) {
            sumSq += (double)absVal * (double)absVal;
            nonSilentCount++;
        }
        if (absVal > peak) peak = absVal;
    }
    if (nonSilentCount <= 256) return 1.0f;

    float rms = (float)std::sqrt(sumSq / (double)nonSilentCount);
    // Target reference RMS ~0.18f (-15 dBFS), clamped to [-6 dB .. +6 dB] (0.5f .. 2.0f) and headroom-limited by peak
    float desiredGain = (rms > 0.005f) ? (0.18f / rms) : 1.0f;
    desiredGain = std::clamp(desiredGain, 0.5f, 2.0f);
    if (peak * desiredGain > 0.98f && peak > 0.01f) {
        desiredGain = 0.98f / peak;
    }
    return desiredGain;
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

void AudioEngine::syncDecoderSampleRateToStreamLocked() {
    if (!mStream || !mDecoder) return;
    int32_t hwSr = mStream->getSampleRate();
    uint32_t decSr = mDecoder->getSampleRate();
    if (hwSr > 0 && decSr > 0 && static_cast<uint32_t>(hwSr) != decSr) {
        int64_t prevFrame = getCurrentFrame();
        uint32_t targetSr = static_cast<uint32_t>(hwSr);
        mDecoder->reconfigureOutputSampleRate(targetSr);
        mOutputSampleRate.store(targetSr, std::memory_order_release);
        uint32_t ch = mDecoder->getChannels() > 0 ? mDecoder->getChannels() : 2u;
        int64_t scaledFrame = (prevFrame * static_cast<int64_t>(targetSr)) / static_cast<int64_t>(decSr);
        if (scaledFrame < 0) scaledFrame = 0;
        mPlayedSampleCount.store(static_cast<uint64_t>(scaledFrame) * ch, std::memory_order_release);
        // If stream rate changed underneath (e.g. device switch), invalidate preloaded Deck B at old rate
        if (mNextPreloaded.load(std::memory_order_acquire) ||
            mNextIsActive.load(std::memory_order_acquire) ||
            mCrossfadeActive.load(std::memory_order_acquire) ||
            mCrossfadeCompleted.load(std::memory_order_acquire)) {
            resetNextDeckLocked();
        }
        LOG_OBOE_I("syncDecoderSampleRateToStreamLocked: Matched decoder %u Hz -> hardware %u Hz", decSr, targetSr);
    }
}

bool AudioEngine::initStreamAfterLoad() {
    int sampleRate = mDecoder->getSampleRate();
    int channels = mDecoder->getChannels();
    mOutputSampleRate.store((sampleRate > 0) ? (uint32_t)sampleRate : 0u, std::memory_order_release);
    LOG_OBOE_I("initStreamAfterLoad: Opened file. SampleRate: %d, Channels: %d, TotalFrames: %lld",
               sampleRate, channels, (long long)mDecoder->getTotalFrames());

    // Open or reuse Oboe stream first so we can verify actual hardware sample rate before pre-buffering
    bool gapless = mGaplessEnabled.load(std::memory_order_acquire);
    bool rateCompatible = mStream && (mStream->getSampleRate() == sampleRate ||
                                      (mLastRequestedSampleRate > 0 && mLastRequestedSampleRate == sampleRate));
    bool needNewStream = (!mStream ||
                          !gapless ||
                          mStream->getState() == oboe::StreamState::Closed ||
                          mStream->getState() == oboe::StreamState::Disconnected ||
                          !rateCompatible ||
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

    syncDecoderSampleRateToStreamLocked();

    // Pre-decode initial frames into RingBuffer at confirmed stream sample rate
    uint32_t ch = (channels > 0) ? (uint32_t)channels : 2u;
    uint64_t framesToRead = PREBUFFER_SIZE / ch;
    uint64_t framesDecoded = mDecoder->decodeFrames(mPreBuffer.data(), framesToRead);
    if (framesDecoded > 0) {
        int32_t totalSamples = (int32_t)(framesDecoded * ch);
        mRingBuffer->write(mPreBuffer.data(), totalSamples);
        float rgLinear = mDecoder->hasReplayGain()
                             ? mDecoder->getReplayGainLinear()
                             : estimatePrebufferReplayGain(mPreBuffer.data(), totalSamples);
        mTrackReplayGainLinear.store(rgLinear, std::memory_order_release);
        LOG_OBOE_D("initStreamAfterLoad: Pre-buffered %lld frames (ReplayGain %.2f, fromTag=%d)",
                   (long long)framesDecoded, rgLinear, (int)mDecoder->hasReplayGain());
    } else {
        float rgLinear = mDecoder->hasReplayGain() ? mDecoder->getReplayGainLinear() : 1.0f;
        mTrackReplayGainLinear.store(rgLinear, std::memory_order_release);
    }

    return true;
}

bool AudioEngine::loadFd(int fd) {
    std::lock_guard<std::mutex> lock(mLock);
    LOG_OBOE_I("loadFd: requesting to open fd %d", fd);

    mIsPlaying.store(false, std::memory_order_release);
    stopDecodeThread();
    resetNextDeckLocked();

    beginBufferReset();
    mIsEof = false;
    mDecoderReachedEof = false;
    mPlayedSampleCount.store(0, std::memory_order_release);
    mOutputSampleRate.store(0, std::memory_order_release);
    mVolumeRampCoeff.store(0.0015f, std::memory_order_release);
    mTargetVolume.store(1.0f, std::memory_order_release);
    mCurrentVolume = 1.0f;
    mLimiterGain = 1.0f;
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
    resetNextDeckLocked();

    beginBufferReset();
    mIsEof = false;
    mDecoderReachedEof = false;
    mPlayedSampleCount.store(0, std::memory_order_release);
    mOutputSampleRate.store(0, std::memory_order_release);
    mVolumeRampCoeff.store(0.0015f, std::memory_order_release);
    mTargetVolume.store(1.0f, std::memory_order_release);
    mCurrentVolume = 1.0f;
    mLimiterGain = 1.0f;
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

    // Cancel any active/completed crossfade when seeking inside primary track
    if (mCrossfadeActive.load(std::memory_order_acquire) ||
        mCrossfadeCompleted.load(std::memory_order_acquire) ||
        mNextIsActive.load(std::memory_order_acquire)) {
        resetNextDeckLocked();
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

uint32_t AudioEngine::getSampleRate() const {
    return mOutputSampleRate.load(std::memory_order_acquire);
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
        syncDecoderSampleRateToStreamLocked();
        beginBufferReset();
        int64_t currentFrame = getCurrentFrame();
        mDecoder->seek(currentFrame);
        mRingBuffer->clear();
        endBufferReset();
    }

    oboe::StreamState state = mStream->getState();
    if (state != oboe::StreamState::Started && state != oboe::StreamState::Starting) {
        oboe::Result result = mStream->requestStart();
        LOG_OBOE_I("start(): mStream->requestStart() returned %s (%d)", oboe::convertToText(result), (int)result);
        if (result != oboe::Result::OK) {
            LOG_OBOE_W("start(): requestStart failed (%s), attempting full stream reopen...", oboe::convertToText(result));
            closeStream();
            if (openStream(mDecoder->getChannels(), mDecoder->getSampleRate())) {
                syncDecoderSampleRateToStreamLocked();
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
    mVolumeRampCoeff.store(0.0015f, std::memory_order_release);
    mTargetVolume.store(1.0f, std::memory_order_release);
    mIsEof = false;
    mDecoderReachedEof = false;

    // Safely start decode thread(s)
    stopDecodeThread();
    mDecodeThreadRunning.store(true, std::memory_order_release);
    mDecodeThread = std::thread(&AudioEngine::decodeLoop, this);

    if (mNextIsActive.load(std::memory_order_acquire) && mNextDecoder && mNextDecoder->getSampleRate() > 0) {
        stopNextDecodeThread();
        mNextDecodeThreadRunning.store(true, std::memory_order_release);
        mNextDecodeThread = std::thread(&AudioEngine::nextDecodeLoop, this);
    }

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
    stopNextDecodeThread();
    mVolumeRampCoeff.store(0.0015f, std::memory_order_release);
    mTargetVolume.store(1.0f, std::memory_order_release);
}

void AudioEngine::stop() {
    std::lock_guard<std::mutex> lock(mLock);
    LOG_OBOE_I("stop() called");

    mIsPlaying.store(false, std::memory_order_release);
    stopDecodeThread();
    resetNextDeckLocked();
    mVolumeRampCoeff.store(0.0015f, std::memory_order_release);
    mTargetVolume.store(1.0f, std::memory_order_release);

    if (!mStream) return;

    oboe::Result result = mStream->requestStop();
    LOG_OBOE_D("stop(): requestStop returned %s", oboe::convertToText(result));

    closeStream();
}

void AudioEngine::runDecodeLoop(
    AudioDecoder* decoder,
    RingBuffer<float>* ringBuffer,
    std::atomic<bool>& runningFlag,
    std::atomic<bool>& eofFlag,
    std::mutex& cvMutex,
    std::condition_variable& cv
) {
    LOG_OBOE_D("Decode thread started");
    setpriority(PRIO_PROCESS, 0, -8); // Boost decode thread priority to prevent underruns
    const int32_t BUFFER_SIZE = 8192;
    const int32_t MIN_WRITE_CHUNK = 2048;
    std::vector<float> tempBuffer(BUFFER_SIZE);

    while (runningFlag.load(std::memory_order_acquire)) {
        if (eofFlag.load(std::memory_order_acquire)) {
            std::unique_lock<std::mutex> lock(cvMutex);
            cv.wait_for(lock, std::chrono::milliseconds(100), [&]() {
                return !runningFlag.load(std::memory_order_acquire) ||
                       !eofFlag.load(std::memory_order_acquire);
            });
            continue;
        }

        int32_t availableWrite = ringBuffer->getAvailableWrite();
        if (availableWrite >= MIN_WRITE_CHUNK) {
            uint32_t ch = decoder->getChannels();
            if (ch == 0) ch = 2;
            int32_t samplesToDecode = std::min<int32_t>(availableWrite, BUFFER_SIZE);
            uint64_t framesToRead = (uint64_t)(samplesToDecode / ch);
            if (framesToRead == 0) framesToRead = 1;

            uint64_t framesDecoded = decoder->decodeFrames(tempBuffer.data(), framesToRead);

            if (framesDecoded > 0) {
                ringBuffer->write(tempBuffer.data(), framesDecoded * ch);
            } else {
                // End of file reached by decoder
                if (!eofFlag.load(std::memory_order_acquire)) {
                    LOG_OBOE_I("decodeLoop: Decoder reached EOF");
                    eofFlag.store(true, std::memory_order_release);
                }
            }
        } else {
            // Ring buffer is nearly full; wait until onAudioReady consumes frames or thread stops
            std::unique_lock<std::mutex> lock(cvMutex);
            cv.wait_for(lock, std::chrono::milliseconds(4), [&]() {
                return !runningFlag.load(std::memory_order_acquire) ||
                       ringBuffer->getAvailableWrite() >= MIN_WRITE_CHUNK;
            });
        }
    }
    LOG_OBOE_D("Decode thread finished");
}

void AudioEngine::decodeLoop() {
    runDecodeLoop(mDecoder.get(), mRingBuffer.get(), mDecodeThreadRunning, mDecoderReachedEof, mDecodeCvMutex, mDecodeCv);
}

void AudioEngine::nextDecodeLoop() {
    runDecodeLoop(mNextDecoder.get(), mNextRingBuffer.get(), mNextDecodeThreadRunning, mNextDecoderReachedEof, mNextDecodeCvMutex, mNextDecodeCv);
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
        builder.setSharingMode(oboe::SharingMode::Shared)
               ->setAudioApi(oboe::AudioApi::OpenSLES)
               ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
    } else {
        builder.setAudioApi(oboe::AudioApi::AAudio);
        if (mAudioMode == 2) {
            // Hi-Res Direct: attempt Exclusive direct hardware bypass without OS SRC
            builder.setSharingMode(oboe::SharingMode::Exclusive)
                   ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None);
        } else {
            builder.setSharingMode(oboe::SharingMode::Shared)
                   ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
        }
    }
    builder.setSampleRate(sampleRate);

    // Apply output device routing if specified
    if (mDeviceId != oboe::kUnspecified) {
        builder.setDeviceId(mDeviceId);
    }

    oboe::Result result = builder.openStream(mStream);
    if (result != oboe::Result::OK && wantsAAudio && mAudioMode == 2) {
        LOG_OBOE_W("openStream: AAudio Exclusive failed (%s), retrying AAudio Shared...", oboe::convertToText(result));
        builder.setSharingMode(oboe::SharingMode::Shared)
               ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Best);
        result = builder.openStream(mStream);
    }
    if (result != oboe::Result::OK && wantsAAudio) {
        LOG_OBOE_W("openStream: AAudio openStream failed (%s), retrying with OpenSL ES...", oboe::convertToText(result));
        builder.setSharingMode(oboe::SharingMode::Shared)
               ->setAudioApi(oboe::AudioApi::OpenSLES)
               ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
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
    mLastRequestedSampleRate = sampleRate;

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
    mLastRequestedSampleRate = 0;
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

    bool rgEnabled = mReplayGainEnabled.load(std::memory_order_relaxed);
    float rgA = rgEnabled ? mTrackReplayGainLinear.load(std::memory_order_relaxed) : 1.0f;
    float rgB = rgEnabled ? mNextTrackReplayGainLinear.load(std::memory_order_relaxed) : 1.0f;
    float postRgGain = 1.0f;

    bool crossfadeDone = mCrossfadeCompleted.load(std::memory_order_acquire);
    bool crossfadeNow = !crossfadeDone &&
                        mCrossfadeActive.load(std::memory_order_acquire) &&
                        mNextIsActive.load(std::memory_order_acquire) &&
                        mNextRingBuffer;

    if (crossfadeDone && mNextIsActive.load(std::memory_order_acquire) && mNextRingBuffer) {
        // Crossfade has finished fading Deck A out; keep Deck B playing seamlessly until promotion
        int32_t samplesRead = mNextRingBuffer->read(floatData, totalSamples);
        if (samplesRead > 0 && mNextRingBuffer->getAvailableWrite() >= 8192) {
            mNextDecodeCv.notify_one();
        }
        if (samplesRead > 0) {
            mNextPlayedSampleCount.fetch_add((uint64_t)samplesRead, std::memory_order_relaxed);
        }
        for (int32_t i = samplesRead; i < totalSamples; ++i) {
            floatData[i] = 0.0f;
        }
        if (samplesRead < totalSamples &&
            mNextDecoderReachedEof.load(std::memory_order_acquire) &&
            mNextRingBuffer->getAvailableRead() == 0 &&
            mNextPlayedSampleCount.load(std::memory_order_relaxed) > 0) {
            mNextEof.store(true, std::memory_order_release);
        }
        if (mEqualizer && samplesRead > 0) {
            mEqualizer->processBuffer(floatData, samplesRead);
        }
        postRgGain = rgB;
    } else if (crossfadeNow) {
        // Simultaneous dual-deck equal-power crossfade mix
        int32_t samplesA = mRingBuffer->read(floatData, totalSamples);
        if (samplesA > 0 && mRingBuffer->getAvailableWrite() >= 8192) {
            mDecodeCv.notify_one();
        }
        if (samplesA > 0) {
            mPlayedSampleCount.fetch_add((uint64_t)samplesA, std::memory_order_relaxed);
        }
        for (int32_t i = samplesA; i < totalSamples; ++i) {
            floatData[i] = 0.0f;
        }
        if (samplesA < totalSamples &&
            mDecoderReachedEof.load(std::memory_order_acquire) &&
            mRingBuffer->getAvailableRead() == 0 &&
            mPlayedSampleCount.load(std::memory_order_relaxed) > 0) {
            mIsEof.store(true, std::memory_order_release);
        }

        uint64_t fadeTotal = mCrossfadeDurationFrames.load(std::memory_order_acquire);
        if (fadeTotal == 0) fadeTotal = 1;
        int32_t safeCh = std::max(1, channelCount);
        int32_t maxChunkFrames = std::max(1, static_cast<int32_t>(mNextMixBuffer.size()) / safeCh);
        int32_t framesProcessed = 0;

        while (framesProcessed < numFrames) {
            int32_t chunkFrames = std::min(numFrames - framesProcessed, maxChunkFrames);
            int32_t chunkSamples = chunkFrames * safeCh;
            int32_t samplesB = mNextRingBuffer->read(mNextMixBuffer.data(), chunkSamples);
            if (samplesB > 0) {
                mNextPlayedSampleCount.fetch_add((uint64_t)samplesB, std::memory_order_relaxed);
            }
            for (int32_t s = samplesB; s < chunkSamples; ++s) {
                mNextMixBuffer[s] = 0.0f;
            }
            if (samplesB < chunkSamples &&
                mNextDecoderReachedEof.load(std::memory_order_acquire) &&
                mNextRingBuffer->getAvailableRead() == 0 &&
                mNextPlayedSampleCount.load(std::memory_order_relaxed) > 0) {
                mNextEof.store(true, std::memory_order_release);
            }

            int32_t baseSample = framesProcessed * safeCh;
            for (int32_t f = 0; f < chunkFrames; ++f) {
                float t = (mCrossfadeElapsedFrames < fadeTotal)
                              ? (static_cast<float>(mCrossfadeElapsedFrames) / static_cast<float>(fadeTotal))
                              : 1.0f;
                float angle = t * 1.57079632679f;
                float gainA = std::cos(angle);
                float gainB = std::sin(angle);
                for (int32_t ch = 0; ch < safeCh; ++ch) {
                    int32_t idx = baseSample + f * safeCh + ch;
                    float sA = floatData[idx] * rgA;
                    float sB = mNextMixBuffer[f * safeCh + ch] * rgB;
                    floatData[idx] = sA * gainA + sB * gainB;
                }
                if (mCrossfadeElapsedFrames < fadeTotal) {
                    mCrossfadeElapsedFrames++;
                }
            }
            framesProcessed += chunkFrames;
        }

        if (mNextRingBuffer->getAvailableWrite() >= 8192) {
            mNextDecodeCv.notify_one();
        }
        if (mEqualizer) {
            mEqualizer->processBuffer(floatData, totalSamples);
        }
        if (mCrossfadeElapsedFrames >= fadeTotal) {
            mCrossfadeActive.store(false, std::memory_order_release);
            mCrossfadeCompleted.store(true, std::memory_order_release);
        }
        postRgGain = 1.0f;
    } else {
        // Normal single-deck playback
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

        // Fixed per-track ReplayGain (no dynamic AGC compression in callback)
        postRgGain = rgA;
    }

    // Smooth Stereo-Linked Peak Envelope Limiter: zero distortion up to 0.96, smoothly rides peaks down
    // across channels without squaring/clipping bass sine waves (no harmonic "farting" on sub-bass).
    float targetVol = mTargetVolume.load(std::memory_order_relaxed);
    float rampCoeff = mVolumeRampCoeff.load(std::memory_order_relaxed);
    uint32_t outSr = mOutputSampleRate.load(std::memory_order_relaxed);
    float srF = (outSr > 0) ? static_cast<float>(outSr) : 48000.0f;
    float limAttack = 1.0f - std::exp(-1.0f / (0.0003f * srF));
    float limRelease = 1.0f - std::exp(-1.0f / (0.120f * srF));
    constexpr float kEnvThresh = 0.96f;
    constexpr float kHardCeil = 0.996f;

    int32_t safeCh = std::max<int32_t>(1, channelCount);
    for (int32_t f = 0; f < numFrames; ++f) {
        int32_t base = f * safeCh;
        float framePeak = 0.0f;
        for (int32_t ch = 0; ch < safeCh; ++ch) {
            mCurrentVolume += (targetVol - mCurrentVolume) * rampCoeff;
            float s = floatData[base + ch] * mCurrentVolume * postRgGain;
            if (!std::isfinite(s)) s = 0.0f;
            floatData[base + ch] = s;
            float a = std::fabs(s);
            if (a > framePeak) framePeak = a;
        }

        float desiredLim = (framePeak > kEnvThresh) ? (kEnvThresh / framePeak) : 1.0f;
        if (desiredLim < mLimiterGain) {
            mLimiterGain += (desiredLim - mLimiterGain) * limAttack;
            if (framePeak * mLimiterGain > kHardCeil && framePeak > 0.0f) {
                mLimiterGain = kHardCeil / framePeak;
            }
        } else {
            mLimiterGain += (desiredLim - mLimiterGain) * limRelease;
        }

        if (mLimiterGain < 0.9999f) {
            for (int32_t ch = 0; ch < safeCh; ++ch) {
                floatData[base + ch] = std::clamp(floatData[base + ch] * mLimiterGain, -0.9999f, 0.9999f);
            }
        }
    }

    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorBeforeClose(oboe::AudioStream *oboeStream, oboe::Result error) {
    LOG_OBOE_E("onErrorBeforeClose: %s (%d)", oboe::convertToText(error), (int)error);
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream *oboeStream, oboe::Result error) {
    LOG_OBOE_E("onErrorAfterClose: %s (%d)", oboe::convertToText(error), (int)error);

    // Automatically recover audio stream on device routing change (Bluetooth disconnect/connect, USB DAC change).
    // Defer to mRecoveryThread so this Oboe callback never contends on mLock while another thread calls close().
    if (error == oboe::Result::ErrorDisconnected ||
        error == oboe::Result::ErrorUnavailable ||
        error == oboe::Result::ErrorInternal ||
        error == oboe::Result::ErrorInvalidState) {
        LOG_OBOE_I("Device disconnected or routing changed, scheduling async stream recovery...");
        {
            std::lock_guard<std::mutex> lock(mRecoveryMutex);
            mPendingRecoveryStream = oboeStream;
            mRecoveryPending = true;
        }
        mRecoveryCv.notify_one();
    } else {
        mIsPlaying.store(false, std::memory_order_release);
        mDecodeThreadRunning.store(false, std::memory_order_release);
        mDecodeCv.notify_all();
        mNextDecodeThreadRunning.store(false, std::memory_order_release);
        mNextDecodeCv.notify_all();
    }
}

void AudioEngine::recoveryLoop() {
    while (true) {
        oboe::AudioStream* erroredStream = nullptr;
        {
            std::unique_lock<std::mutex> lock(mRecoveryMutex);
            mRecoveryCv.wait(lock, [this]() {
                return mRecoveryShutdown || mRecoveryPending;
            });
            if (mRecoveryShutdown) {
                return;
            }
            erroredStream = mPendingRecoveryStream;
            mPendingRecoveryStream = nullptr;
            mRecoveryPending = false;
        }

        std::lock_guard<std::mutex> engineLock(mLock);
        // Only recover if mStream still refers to the stream that actually errored
        if (mStream && mStream.get() == erroredStream) {
            reopenStreamLocked(mIsPlaying.load(std::memory_order_acquire));
        }
    }
}

bool AudioEngine::reopenStream(bool restartIfPlaying) {
    std::lock_guard<std::mutex> lock(mLock);
    return reopenStreamLocked(restartIfPlaying);
}

bool AudioEngine::reopenStreamLocked(bool restartIfPlaying) {
    if (!mDecoder || mDecoder->getSampleRate() == 0) {
        LOG_OBOE_D("reopenStream: No active decoder / file loaded");
        return false;
    }

    LOG_OBOE_I("reopenStream: Reopening stream (restartIfPlaying=%d, wasPlaying=%d)", restartIfPlaying, (int)mIsPlaying.load());

    // Stop decode thread(s) safely
    stopDecodeThread();
    stopNextDecodeThread();

    bool shouldPlay = restartIfPlaying && mIsPlaying.load(std::memory_order_acquire);
    mIsPlaying.store(false, std::memory_order_release);

    // Close old dead stream
    closeStream();

    // Open new audio stream with active hardware route
    if (!openStream(mDecoder->getChannels(), mDecoder->getSampleRate())) {
        LOG_OBOE_E("reopenStream: Failed to open new audio stream");
        return false;
    }

    // Match decoder output rate if Oboe hardware forced a different sample rate, then seek
    syncDecoderSampleRateToStreamLocked();
    beginBufferReset();
    int64_t currentFrame = getCurrentFrame();
    mDecoder->seek(currentFrame);
    mRingBuffer->clear();
    endBufferReset();

    if (shouldPlay) {
        mIsPlaying.store(true, std::memory_order_release);
        mDecodeThreadRunning.store(true, std::memory_order_release);
        mDecodeThread = std::thread(&AudioEngine::decodeLoop, this);
        if (mNextIsActive.load(std::memory_order_acquire) && mNextDecoder && mNextDecoder->getSampleRate() > 0) {
            mNextDecodeThreadRunning.store(true, std::memory_order_release);
            mNextDecodeThread = std::thread(&AudioEngine::nextDecodeLoop, this);
        }
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

    // Stop decode thread(s)
    stopDecodeThread();
    stopNextDecodeThread();

    bool wasPlaying = mIsPlaying.load(std::memory_order_acquire);
    mIsPlaying.store(false, std::memory_order_release);

    if (mStream) {
        mStream->requestStop();
        closeStream();
    }

    if (!openStream(mDecoder->getChannels(), mDecoder->getSampleRate())) return false;

    // Match decoder output rate if new API forced a different sample rate, then seek
    syncDecoderSampleRateToStreamLocked();
    beginBufferReset();
    int64_t currentFrame = getCurrentFrame();
    mDecoder->seek(currentFrame);
    mRingBuffer->clear();
    endBufferReset();

    if (wasPlaying) {
        mIsPlaying.store(true, std::memory_order_release);
        mDecodeThreadRunning.store(true, std::memory_order_release);
        mDecodeThread = std::thread(&AudioEngine::decodeLoop, this);
        if (mNextIsActive.load(std::memory_order_acquire) && mNextDecoder && mNextDecoder->getSampleRate() > 0) {
            mNextDecodeThreadRunning.store(true, std::memory_order_release);
            mNextDecodeThread = std::thread(&AudioEngine::nextDecodeLoop, this);
        }
        mStream->requestStart();
    }

    return true;
}

bool AudioEngine::setAudioDeviceId(int deviceId) {
    std::lock_guard<std::mutex> lock(mLock);
    LOG_OBOE_I("setAudioDeviceId: changing device ID to %d", deviceId);
    mDeviceId = deviceId;
    return reopenStreamLocked(mIsPlaying.load(std::memory_order_acquire));
}

bool AudioEngine::setSafeBufferMode(bool enabled) {
    std::lock_guard<std::mutex> lock(mLock);
    LOG_OBOE_I("setSafeBufferMode: changing to %d (was %d)", (int)enabled, (int)mSafeBufferMode.load());
    mSafeBufferMode.store(enabled);
    return reopenStreamLocked(mIsPlaying.load(std::memory_order_acquire));
}

void AudioEngine::setVolume(float volume) {
    float clamped = std::clamp(volume, 0.0f, 1.0f);
    mTargetVolume.store(clamped, std::memory_order_release);
}

void AudioEngine::beginPauseFade(int durationMs) {
    uint32_t sr = mOutputSampleRate.load(std::memory_order_acquire);
    if (sr == 0) sr = 44100;
    float durSec = static_cast<float>(std::clamp(durationMs, 30, 500)) / 1000.0f;
    // Settle to -50 dB (~0.003x) within durationMs for a smooth exponential pause taper
    float coeff = std::clamp(5.8f / (durSec * static_cast<float>(sr) * 2.0f), 0.0001f, 0.01f);
    mVolumeRampCoeff.store(coeff, std::memory_order_release);
    mTargetVolume.store(0.0f, std::memory_order_release);
}

void AudioEngine::cancelPauseFade() {
    mVolumeRampCoeff.store(0.0015f, std::memory_order_release);
    mTargetVolume.store(1.0f, std::memory_order_release);
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

bool AudioEngine::initNextDeckAfterLoad() {
    uint32_t nextSr = mNextDecoder->getSampleRate();
    uint32_t nextCh = mNextDecoder->getChannels();
    if (nextSr == 0 || nextCh == 0) return false;

    mNextOutputSampleRate.store(nextSr, std::memory_order_release);
    uint64_t framesToRead = (uint64_t)(mNextPreBuffer.size() / nextCh);
    uint64_t framesDecoded = mNextDecoder->decodeFrames(mNextPreBuffer.data(), framesToRead);
    if (framesDecoded > 0) {
        int32_t totalSamples = (int32_t)(framesDecoded * nextCh);
        mNextRingBuffer->write(mNextPreBuffer.data(), totalSamples);
        float rgLinear = mNextDecoder->hasReplayGain()
                             ? mNextDecoder->getReplayGainLinear()
                             : estimatePrebufferReplayGain(mNextPreBuffer.data(), totalSamples);
        mNextTrackReplayGainLinear.store(rgLinear, std::memory_order_release);
    } else {
        float rgLinear = mNextDecoder->hasReplayGain() ? mNextDecoder->getReplayGainLinear() : 1.0f;
        mNextTrackReplayGainLinear.store(rgLinear, std::memory_order_release);
    }
    LOG_OBOE_I("initNextDeckAfterLoad: Pre-buffered %llu frames (SR=%u, RG=%.2f)",
               (unsigned long long)framesDecoded, nextSr, mNextTrackReplayGainLinear.load());
    return true;
}

bool AudioEngine::preloadNextFd(int fd) {
    std::lock_guard<std::mutex> lock(mLock);
    if (!mDecoder || mDecoder->getSampleRate() == 0) {
        LOG_OBOE_W("preloadNextFd: No primary track active");
        return false;
    }

    resetNextDeckLocked();
    if (!mNextDecoder || !mNextRingBuffer) return false;

    // Force Deck B output rate to match active stream sample rate so dual-deck mix is 1:1
    uint32_t targetSr = mOutputSampleRate.load(std::memory_order_acquire);
    if (targetSr == 0) targetSr = mDecoder->getSampleRate();

    if (!mNextDecoder->openFd(fd, targetSr)) {
        LOG_OBOE_E("preloadNextFd: Failed to open fd %d in mNextDecoder", fd);
        return false;
    }

    if (!initNextDeckAfterLoad()) {
        resetNextDeckLocked();
        return false;
    }

    mNextPreloaded.store(true, std::memory_order_release);
    return true;
}

bool AudioEngine::preloadNextFile(const std::string& filePath) {
    if (filePath.rfind("/proc/self/fd/", 0) == 0) {
        int fd = -1;
        if (sscanf(filePath.c_str(), "/proc/self/fd/%d", &fd) == 1 && fd >= 0) {
            return preloadNextFd(fd);
        }
    }

    std::lock_guard<std::mutex> lock(mLock);
    if (!mDecoder || mDecoder->getSampleRate() == 0) {
        LOG_OBOE_W("preloadNextFile: No primary track active");
        return false;
    }

    resetNextDeckLocked();
    if (!mNextDecoder || !mNextRingBuffer) return false;

    uint32_t targetSr = mOutputSampleRate.load(std::memory_order_acquire);
    if (targetSr == 0) targetSr = mDecoder->getSampleRate();

    if (!mNextDecoder->openFile(filePath, targetSr)) {
        LOG_OBOE_E("preloadNextFile: Failed to open '%s' in mNextDecoder", filePath.c_str());
        return false;
    }

    if (!initNextDeckAfterLoad()) {
        resetNextDeckLocked();
        return false;
    }

    mNextPreloaded.store(true, std::memory_order_release);
    return true;
}

bool AudioEngine::startCrossfade(int durationMs) {
    std::lock_guard<std::mutex> lock(mLock);
    if (!mNextPreloaded.load(std::memory_order_acquire) || !mNextDecoder || mNextDecoder->getSampleRate() == 0) {
        LOG_OBOE_W("startCrossfade: Next track not preloaded");
        return false;
    }
    if (mCrossfadeActive.load(std::memory_order_acquire) || mCrossfadeCompleted.load(std::memory_order_acquire)) {
        return false;
    }

    uint32_t sr = mOutputSampleRate.load(std::memory_order_acquire);
    if (sr == 0) sr = mDecoder ? mDecoder->getSampleRate() : 44100;
    if (sr == 0) sr = 44100;

    int64_t clampedMs = std::max(100, durationMs);
    uint64_t fadeFrames = static_cast<uint64_t>((clampedMs * static_cast<int64_t>(sr)) / 1000LL);
    if (fadeFrames == 0) fadeFrames = 1;

    stopNextDecodeThread();
    mCrossfadeElapsedFrames = 0;
    mCrossfadeDurationFrames.store(fadeFrames, std::memory_order_release);
    mCrossfadeCompleted.store(false, std::memory_order_release);
    mNextIsActive.store(true, std::memory_order_release);
    mCrossfadeActive.store(true, std::memory_order_release);

    if (mIsPlaying.load(std::memory_order_acquire)) {
        mNextDecodeThreadRunning.store(true, std::memory_order_release);
        mNextDecodeThread = std::thread(&AudioEngine::nextDecodeLoop, this);
    }

    LOG_OBOE_I("startCrossfade: Started dual-deck crossfade (%lld ms, %llu frames @ %u Hz)",
               (long long)clampedMs, (unsigned long long)fadeFrames, sr);
    return true;
}

void AudioEngine::cancelCrossfade() {
    std::lock_guard<std::mutex> lock(mLock);
    resetNextDeckLocked();
}

bool AudioEngine::isCrossfadeCompleted() const {
    return mCrossfadeCompleted.load(std::memory_order_acquire);
}

bool AudioEngine::promotePreloadedTrack() {
    std::lock_guard<std::mutex> lock(mLock);
    if (!mNextDecoder || mNextDecoder->getSampleRate() == 0) {
        return false;
    }

    stopDecodeThread();
    stopNextDecodeThread();

    // Allocate replacement decoder outside the RT reset fence
    auto freshNextDecoder = std::make_unique<AudioDecoder>();
    std::unique_ptr<AudioDecoder> oldPrimaryDecoder;

    beginBufferReset();
    oldPrimaryDecoder = std::move(mDecoder);
    mDecoder = std::move(mNextDecoder);
    mNextDecoder = std::move(freshNextDecoder);
    std::swap(mRingBuffer, mNextRingBuffer);

    mPlayedSampleCount.store(mNextPlayedSampleCount.load(std::memory_order_acquire), std::memory_order_release);
    mNextPlayedSampleCount.store(0, std::memory_order_release);

    mDecoderReachedEof.store(mNextDecoderReachedEof.load(std::memory_order_acquire), std::memory_order_release);
    mNextDecoderReachedEof.store(false, std::memory_order_release);

    mIsEof.store(mNextEof.load(std::memory_order_acquire), std::memory_order_release);
    mNextEof.store(false, std::memory_order_release);

    mOutputSampleRate.store(mNextOutputSampleRate.load(std::memory_order_acquire), std::memory_order_release);
    mNextOutputSampleRate.store(0, std::memory_order_release);

    mTrackReplayGainLinear.store(mNextTrackReplayGainLinear.load(std::memory_order_acquire), std::memory_order_release);
    mNextTrackReplayGainLinear.store(1.0f, std::memory_order_release);

    mCrossfadeActive.store(false, std::memory_order_release);
    mCrossfadeCompleted.store(false, std::memory_order_release);
    mNextIsActive.store(false, std::memory_order_release);
    mNextPreloaded.store(false, std::memory_order_release);
    mCrossfadeDurationFrames.store(0, std::memory_order_release);
    mCrossfadeElapsedFrames = 0;
    endBufferReset();

    if (mNextRingBuffer) {
        mNextRingBuffer->clear();
    }
    if (oldPrimaryDecoder) {
        oldPrimaryDecoder->close();
    }

    if (mIsPlaying.load(std::memory_order_acquire) && mDecoder && mDecoder->getSampleRate() > 0) {
        mDecodeThreadRunning.store(true, std::memory_order_release);
        mDecodeThread = std::thread(&AudioEngine::decodeLoop, this);
    }

    LOG_OBOE_I("promotePreloadedTrack: Promoted Deck B to primary (SR=%u, PlayedFrames=%lld)",
               mOutputSampleRate.load(std::memory_order_relaxed), (long long)getCurrentFrame());
    return true;
}
