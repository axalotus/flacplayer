#ifndef AUDIOPLAYER_AUDIOENGINE_H
#define AUDIOPLAYER_AUDIOENGINE_H

#include <oboe/Oboe.h>
#include <android/log.h>
#include <mutex>
#include <condition_variable>
#include <thread>
#include <atomic>
#include <memory>
#include <vector>
#include "AudioDecoder.h"
#include "RingBuffer.h"
#include "EqualizerEngine.h"

#define LOG_TAG "NativeAudioEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

class AudioEngine : public oboe::AudioStreamCallback {
public:
    AudioEngine();
    ~AudioEngine();

    bool start();
    void pause();
    void stop();

    bool loadFile(const std::string& filePath);
    bool loadFd(int fd);
    int64_t seekTo(int64_t targetFrame);
    int64_t getCurrentFrame() const;
    int64_t getTotalFrames() const;
    uint32_t getSampleRate() const;
    
    // Recreate stream with new audio API mode
    // mode: 0=AAudio(default), 1=OpenSL ES, 2=Hi-Res
    bool recreateStream(int mode);

    // Reopen stream after routing change / disconnect, preserving current frame position
    bool reopenStream(bool restartIfPlaying);

    // Set output audio device ID (oboe::kUnspecified = default)
    bool setAudioDeviceId(int deviceId);
    
    // Configure safe/deep buffer mode to prevent underruns on slow CPUs and Android < 8.0
    bool setSafeBufferMode(bool enabled);
    bool getSafeBufferMode() const { return mSafeBufferMode.load(); }

    // Crossfade volume (0.0 .. 1.0), pause fade, ReplayGain normalization, and Gapless transition mode
    void setVolume(float volume);
    void beginPauseFade(int durationMs);
    void cancelPauseFade();
    void setReplayGainEnabled(bool enabled);
    void setGaplessEnabled(bool enabled);

    // Dual-deck overlapping crossfade controls
    bool preloadNextFile(const std::string& filePath);
    bool preloadNextFd(int fd);
    bool startCrossfade(int durationMs);
    void cancelCrossfade();
    bool isCrossfadeCompleted() const;
    bool promotePreloadedTrack();

    bool isEof() const;

    EqualizerEngine* getEqualizer() const { return mEqualizer.get(); }

    // oboe::AudioStreamCallback overrides
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *oboeStream, void *audioData, int32_t numFrames) override;
    void onErrorBeforeClose(oboe::AudioStream *oboeStream, oboe::Result error) override;
    void onErrorAfterClose(oboe::AudioStream *oboeStream, oboe::Result error) override;

private:
    std::mutex mLock;
    std::shared_ptr<oboe::AudioStream> mStream;
    std::atomic<bool> mIsPlaying{false};

    // Real-time lock-free synchronization between control thread (clear/seek) and onAudioReady callback
    std::atomic<bool> mIsResettingBuffer{false};
    std::atomic<int32_t> mActiveCallbacks{0};

    std::unique_ptr<AudioDecoder> mDecoder;
    std::unique_ptr<RingBuffer<float>> mRingBuffer;
    std::unique_ptr<EqualizerEngine> mEqualizer;
    std::vector<float> mPreBuffer;

    // Secondary deck for true dual-deck overlapping crossfade
    std::unique_ptr<AudioDecoder> mNextDecoder;
    std::unique_ptr<RingBuffer<float>> mNextRingBuffer;
    std::vector<float> mNextPreBuffer;
    std::vector<float> mNextMixBuffer;
    std::thread mNextDecodeThread;
    std::atomic<bool> mNextDecodeThreadRunning{false};
    std::mutex mNextDecodeCvMutex;
    std::condition_variable mNextDecodeCv;
    std::atomic<uint64_t> mNextPlayedSampleCount{0};
    std::atomic<uint32_t> mNextOutputSampleRate{0};
    std::atomic<bool> mNextDecoderReachedEof{false};
    std::atomic<bool> mNextEof{false};
    std::atomic<float> mNextTrackReplayGainLinear{1.0f};
    std::atomic<bool> mNextPreloaded{false};
    std::atomic<bool> mNextIsActive{false};
    std::atomic<bool> mCrossfadeActive{false};
    std::atomic<bool> mCrossfadeCompleted{false};
    std::atomic<uint64_t> mCrossfadeDurationFrames{0};
    uint64_t mCrossfadeElapsedFrames = 0; // Audio-thread local while mCrossfadeActive is true

    std::thread mDecodeThread;
    std::atomic<bool> mDecodeThreadRunning{false};
    std::mutex mDecodeCvMutex;
    std::condition_variable mDecodeCv;

    std::atomic<uint64_t> mPlayedSampleCount{0};
    std::atomic<uint32_t> mOutputSampleRate{0};
    std::atomic<bool> mIsEof{false};
    std::atomic<bool> mDecoderReachedEof{false};
    int mAudioMode = 0; // 0=AAudio, 1=OpenSL ES, 2=Hi-Res
    int mDeviceId = oboe::kUnspecified;
    int mLastRequestedSampleRate = 0;
    std::atomic<bool> mSafeBufferMode{false};
    std::atomic<float> mTargetVolume{1.0f};
    std::atomic<float> mVolumeRampCoeff{0.0015f};
    float mCurrentVolume = 1.0f;
    float mLimiterGain = 1.0f;
    std::atomic<bool> mReplayGainEnabled{false};
    std::atomic<float> mTrackReplayGainLinear{1.0f};
    std::atomic<bool> mGaplessEnabled{true};

    // Async stream recovery worker so onErrorAfterClose never acquires mLock inline (avoids close()/callback deadlock)
    std::thread mRecoveryThread;
    std::mutex mRecoveryMutex;
    std::condition_variable mRecoveryCv;
    bool mRecoveryPending = false;
    bool mRecoveryShutdown = false;
    oboe::AudioStream* mPendingRecoveryStream = nullptr;

    bool openStream(int channelCount, int sampleRate);
    void closeStream();
    bool reopenStreamLocked(bool restartIfPlaying);
    void recoveryLoop();
    void stopDecodeThread();
    void stopNextDecodeThread();
    void resetNextDeckLocked();
    void syncDecoderSampleRateToStreamLocked();
    bool initNextDeckAfterLoad();
    static float estimatePrebufferReplayGain(const float* buffer, int32_t totalSamples);
    void beginBufferReset();
    void endBufferReset();
    void runDecodeLoop(
        AudioDecoder* decoder,
        RingBuffer<float>* ringBuffer,
        std::atomic<bool>& runningFlag,
        std::atomic<bool>& eofFlag,
        std::mutex& cvMutex,
        std::condition_variable& cv
    );
    void decodeLoop();
    void nextDecodeLoop();
    bool initStreamAfterLoad();
};

#endif //AUDIOPLAYER_AUDIOENGINE_H
