#include "AudioEngine.h"
#include <cmath>
#include <android/log.h>

#define OBOE_TAG "OboeAudioEngine"
#define LOG_OBOE_D(...) __android_log_print(ANDROID_LOG_DEBUG, OBOE_TAG, __VA_ARGS__)
#define LOG_OBOE_I(...) __android_log_print(ANDROID_LOG_INFO, OBOE_TAG, __VA_ARGS__)
#define LOG_OBOE_W(...) __android_log_print(ANDROID_LOG_WARN, OBOE_TAG, __VA_ARGS__)
#define LOG_OBOE_E(...) __android_log_print(ANDROID_LOG_ERROR, OBOE_TAG, __VA_ARGS__)

// 1 second of audio at 192kHz (stereo) = 192000 * 2 = 384000 floats.
const int32_t RING_BUFFER_CAPACITY = 384000;

AudioEngine::AudioEngine() {
    mDecoder = std::make_unique<AudioDecoder>();
    mRingBuffer = std::make_unique<RingBuffer<float>>(RING_BUFFER_CAPACITY);
    mEqualizer = std::make_unique<EqualizerEngine>();
    LOG_OBOE_I("AudioEngine created");
}

AudioEngine::~AudioEngine() {
    stop();
    LOG_OBOE_I("AudioEngine destroyed");
}

bool AudioEngine::initStreamAfterLoad() {
    int sampleRate = mDecoder->getSampleRate();
    int channels = mDecoder->getChannels();
    LOG_OBOE_I("initStreamAfterLoad: Successfully opened. SampleRate: %d, Channels: %d, TotalFrames: %lld",
               sampleRate, channels, (long long)mDecoder->getTotalFrames());

    // Pre-decode initial frames into RingBuffer so buffer is never dry on startup
    const int32_t PREBUFFER_SIZE = 16384;
    std::vector<float> preBuffer(PREBUFFER_SIZE);
    uint32_t ch = (channels > 0) ? (uint32_t)channels : 2u;
    uint64_t framesToRead = PREBUFFER_SIZE / ch;
    uint64_t framesDecoded = mDecoder->decodeFrames(preBuffer.data(), framesToRead);
    if (framesDecoded > 0) {
        mRingBuffer->write(preBuffer.data(), framesDecoded * ch);
        LOG_OBOE_D("initStreamAfterLoad: Pre-buffered %lld frames into RingBuffer", (long long)framesDecoded);
    }

    // Check if existing Oboe stream can be reused
    bool needNewStream = (!mStream ||
                          mStream->getState() == oboe::StreamState::Closed ||
                          mStream->getState() == oboe::StreamState::Disconnected ||
                          mStream->getSampleRate() != sampleRate ||
                          mStream->getChannelCount() != channels);

    if (needNewStream) {
        LOG_OBOE_I("initStreamAfterLoad: Stream params changed or stream closed -> creating new Oboe stream (SR: %d, Ch: %d)", sampleRate, channels);
        closeStream();
        if (!openStream(channels, sampleRate)) {
            LOG_OBOE_E("initStreamAfterLoad: openStream failed!");
            return false;
        }
    } else {
        LOG_OBOE_I("initStreamAfterLoad: Reusing existing Oboe stream (Current State: %s)", oboe::convertToText(mStream->getState()));
    }

    return true;
}

bool AudioEngine::loadFd(int fd) {
    std::lock_guard<std::mutex> lock(mLock);
    LOG_OBOE_I("loadFd: requesting to open fd %d", fd);

    mIsPlaying = false;
    mDecodeThreadRunning = false;
    if (mDecodeThread.joinable()) {
        mDecodeThread.join();
    }

    mIsEof = false;
    mDecoderReachedEof = false;
    mPlayedSampleCount.store(0);
    mRingBuffer->clear();
    if (mEqualizer) {
        mEqualizer->reset();
    }

    if (!mDecoder->openFd(fd)) {
        LOG_OBOE_E("loadFd: Failed to open fd %d in AudioDecoder", fd);
        return false;
    }

    return initStreamAfterLoad();
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

    mIsPlaying = false;
    mDecodeThreadRunning = false;
    if (mDecodeThread.joinable()) {
        mDecodeThread.join();
    }

    mIsEof = false;
    mDecoderReachedEof = false;
    mPlayedSampleCount.store(0);
    mRingBuffer->clear();
    if (mEqualizer) {
        mEqualizer->reset();
    }

    if (!mDecoder->openFile(filePath)) {
        LOG_OBOE_E("loadFile: Failed to open '%s' in AudioDecoder", filePath.c_str());
        return false;
    }

    return initStreamAfterLoad();
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
    mIsEof = false;
    mDecoderReachedEof = false;

    // Pause decoding temporarily
    bool wasRunning = mDecodeThreadRunning;
    if (wasRunning) {
        mDecodeThreadRunning = false;
        if (mDecodeThread.joinable()) {
            mDecodeThread.join();
        }
    }

    int64_t result = mDecoder->seek(targetFrame);
    int64_t actualFrame = (result >= 0) ? result : targetFrame;
    if (actualFrame < 0) actualFrame = 0;
    uint32_t ch = mDecoder->getChannels();
    if (ch == 0) ch = 2;
    mPlayedSampleCount.store((uint64_t)(actualFrame * ch));

    mRingBuffer->clear();
    if (mEqualizer) {
        mEqualizer->reset();
    }

    // Pre-decode frames immediately to prevent onAudioReady from returning silence
    const int32_t PREBUFFER_SIZE = 16384;
    std::vector<float> preBuffer(PREBUFFER_SIZE);
    uint64_t framesToRead = PREBUFFER_SIZE / ch;
    uint64_t framesDecoded = mDecoder->decodeFrames(preBuffer.data(), framesToRead);
    if (framesDecoded > 0) {
        mRingBuffer->write(preBuffer.data(), framesDecoded * ch);
    }

    // Resume decoding thread if it was running or player is playing
    if ((wasRunning || mIsPlaying) && mDecoder->getSampleRate() > 0) {
        mDecodeThreadRunning = true;
        mDecodeThread = std::thread(&AudioEngine::decodeLoop, this);
    }

    LOG_OBOE_D("seekTo: Seek completed to frame %lld", (long long)actualFrame);
    return result;
}

int64_t AudioEngine::getCurrentFrame() const {
    if (!mDecoder) return 0;
    uint32_t ch = mDecoder->getChannels();
    if (ch == 0) ch = 1;
    uint64_t count = mPlayedSampleCount.load();
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
                int64_t currentFrame = getCurrentFrame();
                mDecoder->seek(currentFrame);
                mRingBuffer->clear();
                result = mStream->requestStart();
                LOG_OBOE_I("start(): retry requestStart returned %s", oboe::convertToText(result));
            }
            if (result != oboe::Result::OK) {
                LOG_OBOE_E("start(): fatal failed to start audio stream");
                return false;
            }
        }
    }

    mIsPlaying = true;
    mIsEof = false;
    mDecoderReachedEof = false;

    // Safely start decode thread
    mDecodeThreadRunning = false;
    if (mDecodeThread.joinable()) {
        mDecodeThread.join();
    }
    mDecodeThreadRunning = true;
    mDecodeThread = std::thread(&AudioEngine::decodeLoop, this);

    LOG_OBOE_I("start(): AudioEngine playback active and running");
    return true;
}

void AudioEngine::pause() {
    std::lock_guard<std::mutex> lock(mLock);
    LOG_OBOE_I("pause() called. mIsPlaying: %d", (int)mIsPlaying.load());

    if (!mIsPlaying || !mStream) return;

    oboe::Result result = mStream->requestPause();
    LOG_OBOE_I("pause(): requestPause returned %s", oboe::convertToText(result));

    mIsPlaying = false;
    mDecodeThreadRunning = false;
    if (mDecodeThread.joinable()) {
        mDecodeThread.join();
    }
}

void AudioEngine::stop() {
    std::lock_guard<std::mutex> lock(mLock);
    LOG_OBOE_I("stop() called");

    mIsPlaying = false;
    mDecodeThreadRunning = false;
    if (mDecodeThread.joinable()) {
        mDecodeThread.join();
    }

    if (!mStream) return;

    oboe::Result result = mStream->requestStop();
    LOG_OBOE_D("stop(): requestStop returned %s", oboe::convertToText(result));

    closeStream();
}

void AudioEngine::decodeLoop() {
    LOG_OBOE_D("Decode thread started");
    const int32_t BUFFER_SIZE = 8192;
    std::vector<float> tempBuffer(BUFFER_SIZE);

    while (mDecodeThreadRunning) {
        int32_t availableWrite = mRingBuffer->getAvailableWrite();
        if (availableWrite > BUFFER_SIZE) {
            uint32_t ch = mDecoder->getChannels();
            if (ch == 0) ch = 2;
            uint64_t framesToRead = BUFFER_SIZE / ch;
            uint64_t framesDecoded = mDecoder->decodeFrames(tempBuffer.data(), framesToRead);

            if (framesDecoded > 0) {
                mRingBuffer->write(tempBuffer.data(), framesDecoded * ch);
            } else {
                // End of file reached by decoder
                if (!mDecoderReachedEof.load()) {
                    LOG_OBOE_I("decodeLoop: Decoder reached EOF");
                    mDecoderReachedEof = true;
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
            }
        } else {
            // Ring buffer is full, wait a bit
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
    }
    LOG_OBOE_D("Decode thread finished");
}

bool AudioEngine::openStream(int channelCount, int sampleRate) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Shared)
           ->setFormat(oboe::AudioFormat::Float)
           ->setUsage(oboe::Usage::Media)
           ->setContentType(oboe::ContentType::Music)
           ->setChannelCount(channelCount)
           ->setCallback(this);

    // Apply audio mode with automatic fallback for Android < 8.0 (API < 26)
    bool wantsAAudio = (mAudioMode == 0 || mAudioMode == 2);
    if (wantsAAudio && !builder.isAAudioSupported()) {
        LOG_OBOE_I("openStream: AAudio is not supported on this device/Android version (API < 26). Auto-falling back to OpenSL ES.");
        wantsAAudio = false;
    }

    if (!wantsAAudio || mAudioMode == 1) {
        builder.setAudioApi(oboe::AudioApi::OpenSLES);
    } else {
        builder.setAudioApi(oboe::AudioApi::AAudio);
        if (mAudioMode == 2) {
            builder.setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None);
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
        result = builder.openStream(mStream);
    }

    if (result != oboe::Result::OK) {
        LOG_OBOE_E("openStream: Failed to open stream: %s", oboe::convertToText(result));
        return false;
    }

    mEqualizer->init((float)sampleRate, channelCount);

    LOG_OBOE_I("openStream: Stream opened successfully: Mode=%d, API=%s, SampleRate=%d, Channels=%d",
               mAudioMode, oboe::convertToText(mStream->getAudioApi()), mStream->getSampleRate(), mStream->getChannelCount());
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
    float *floatData = static_cast<float *>(audioData);
    int32_t channelCount = oboeStream->getChannelCount();
    int32_t totalSamples = numFrames * channelCount;

    if (!mIsPlaying.load() || !mRingBuffer) {
        memset(audioData, 0, totalSamples * sizeof(float));
        return oboe::DataCallbackResult::Continue;
    }

    int32_t samplesRead = mRingBuffer->read(floatData, totalSamples);

    // Track actual samples played for accurate position
    if (samplesRead > 0) {
        mPlayedSampleCount += samplesRead;
    }

    // Fill the rest with silence if we had an underrun
    if (samplesRead < totalSamples) {
        for (int32_t i = samplesRead; i < totalSamples; ++i) {
            floatData[i] = 0.0f;
        }

        // Only set EOF if decoder finished reading AND ring buffer is completely drained AND we played frames
        if (mDecoderReachedEof.load() && mRingBuffer->getAvailableRead() == 0 && mPlayedSampleCount.load() > 0) {
            if (!mIsEof.load()) {
                mIsEof = true;
                LOG_OBOE_I("onAudioReady: Hardware playback reached EOF! Total samples played: %lld", (long long)mPlayedSampleCount.load());
            }
        }
    }

    // Process DSP (Equalizer & Bass Boost) in-place on actual decoded audio
    if (mEqualizer && samplesRead > 0) {
        mEqualizer->processBuffer(floatData, samplesRead);
    }

    // Safety guard: ensure no NaN/Inf ever reaches audio hardware
    for (int32_t i = 0; i < totalSamples; ++i) {
        if (std::isnan(floatData[i]) || std::isinf(floatData[i])) {
            floatData[i] = 0.0f;
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
        reopenStream(mIsPlaying);
    } else {
        mIsPlaying = false;
        mDecodeThreadRunning = false;
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
    mDecodeThreadRunning = false;
    if (mDecodeThread.joinable()) {
        mDecodeThread.join();
    }

    bool shouldPlay = restartIfPlaying && mIsPlaying;
    mIsPlaying = false;

    // Close old dead stream
    closeStream();

    // Open new audio stream with active hardware route
    if (!openStream(mDecoder->getChannels(), mDecoder->getSampleRate())) {
        LOG_OBOE_E("reopenStream: Failed to open new audio stream");
        return false;
    }

    // Seek decoder back to exact playback frame (preserves mPlayedSampleCount)
    int64_t currentFrame = getCurrentFrame();
    mDecoder->seek(currentFrame);

    mRingBuffer->clear();

    if (shouldPlay) {
        mIsPlaying = true;
        mDecodeThreadRunning = true;
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
    mDecodeThreadRunning = false;
    if (mDecodeThread.joinable()) mDecodeThread.join();

    bool wasPlaying = mIsPlaying;
    mIsPlaying = false;

    if (mStream) {
        mStream->requestStop();
        closeStream();
    }

    if (!openStream(mDecoder->getChannels(), mDecoder->getSampleRate())) return false;

    // Re-sync decoder to current playback frame
    int64_t currentFrame = getCurrentFrame();
    mDecoder->seek(currentFrame);
    mRingBuffer->clear();

    if (wasPlaying) {
        mIsPlaying = true;
        mDecodeThreadRunning = true;
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

bool AudioEngine::isEof() const {
    return mIsEof.load();
}
