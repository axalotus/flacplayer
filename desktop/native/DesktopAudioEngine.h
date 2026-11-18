#ifndef DESKTOP_AUDIO_ENGINE_H
#define DESKTOP_AUDIO_ENGINE_H

#include "miniaudio.h"
#include "DesktopDecoder.h"
#include "DesktopEqualizer.h"
#include "RingBuffer.h"

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

struct AudioDeviceEntry {
    std::string id;
    std::string name;
    bool isDefault = false;
    bool isHardware = false;
};

struct EngineStatusSnapshot {
    bool isPlaying = false;
    bool isEof = false;
    bool nextEof = false;
    bool crossfadeActive = false;
    bool crossfadeCompleted = false;
    bool exclusiveRequested = false;
    bool exclusiveActual = false;
    int64_t currentFrame = 0;
    int64_t totalFrames = 0;
    uint32_t sampleRate = 0;
    uint32_t channels = 2;
    uint32_t bitDepthOut = 32;
    std::string backendName;
    std::string activeDeviceName;
    std::string lastError;
    float spectrum[16]{};
};

class DesktopAudioEngine {
public:
    DesktopAudioEngine();
    ~DesktopAudioEngine();

    std::vector<AudioDeviceEntry> enumerateDevices();

    bool start();
    void pause();
    void stop();

    bool loadFile(const std::string& filePath);
    int64_t seekTo(int64_t targetFrame);
    bool seekToSeconds(double seconds);

    bool setOutputDeviceId(const std::string& deviceId);
    bool setExclusiveMode(bool enabled);
    void setUserVolume(float volume);
    void beginPauseFade(int durationMs);
    void cancelPauseFade();
    void setReplayGainEnabled(bool enabled);
    void setGaplessEnabled(bool enabled);

    bool preloadNextFile(const std::string& filePath);
    bool startCrossfade(int durationMs);
    void cancelCrossfade();
    bool promotePreloadedTrack();

    DesktopEqualizer* getEqualizer() const { return mEqualizer.get(); }
    EngineStatusSnapshot getStatus() const;

private:
    static void maDataCallback(ma_device* pDevice, void* pOutput, const void* pInput, ma_uint32 frameCount);
    void onAudioReady(void* pOutput, int32_t numFrames, int32_t channelCount, ma_format outFormat);

    mutable std::mutex mLock;
    ma_context mContext{};
    bool mContextReady = false;
    bool mContextExclusiveBackend = false;
    ma_device mDevice{};
    bool mDeviceOpen = false;
    bool mDeviceStarted = false;
    bool mExclusiveMode = false;
    bool mActualExclusive = false;
    ma_format mActualFormat = ma_format_f32;
    std::string mDeviceId = "default";
    std::string mLastSharedDeviceHint;
    std::string mActiveDeviceName;
    std::string mLastError;
    std::string mSuspendedPwSink;

    std::atomic<float> mSystemSinkVolume{1.0f};
    float mCurrentSystemVolume = 1.0f;
    bool mSuspendedSinkHasHwVol = false;
    float mSuspendedSinkHwBase = 1.0f;
    float mLimiterGain = 1.0f;

    std::thread mSysVolThread;
    std::atomic<bool> mSysVolRunning{false};
    int mSysVolSubPid = -1;
    int mSysVolPipeFd = -1;

    std::atomic<bool> mIsPlaying{false};
    std::atomic<bool> mIsResettingBuffer{false};
    std::atomic<int32_t> mActiveCallbacks{0};

    std::unique_ptr<DesktopDecoder> mDecoder;
    std::unique_ptr<RingBuffer<float>> mRingBuffer;
    std::unique_ptr<DesktopEqualizer> mEqualizer;
    std::vector<float> mPreBuffer;
    std::vector<float> mCallbackFloatBuf;

    std::unique_ptr<DesktopDecoder> mNextDecoder;
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
    uint64_t mCrossfadeElapsedFrames = 0;

    std::thread mDecodeThread;
    std::atomic<bool> mDecodeThreadRunning{false};
    std::mutex mDecodeCvMutex;
    std::condition_variable mDecodeCv;

    std::atomic<uint64_t> mPlayedSampleCount{0};
    std::atomic<uint32_t> mOutputSampleRate{0};
    std::atomic<bool> mIsEof{false};
    std::atomic<bool> mDecoderReachedEof{false};

    std::atomic<float> mUserVolume{0.85f};
    float mCurrentUserVolume = 0.85f;
    std::atomic<float> mPauseFadeTarget{1.0f};
    std::atomic<float> mVolumeRampCoeff{0.0015f};
    float mCurrentPauseFade = 1.0f;

    std::atomic<bool> mReplayGainEnabled{false};
    std::atomic<float> mTrackReplayGainLinear{1.0f};
    std::atomic<bool> mGaplessEnabled{true};

    std::atomic<float> mSpectrum[16]{};

    bool ensureContextLocked(bool exclusiveBackend);
    void uninitContextLocked();
    std::vector<AudioDeviceEntry> enumerateDevicesLocked();
    std::string resolveBestDeviceNameHintLocked();
    void suspendPipeWireSinkLocked(int cardNum);
    void resumePipeWireSinkLocked();
    void startSystemVolumeWatcherLocked();
    void stopSystemVolumeWatcherLocked();
    bool openStream(int channelCount, int sampleRate);
    void closeStream();
    bool reopenStreamLocked(bool restartIfPlaying);
    void stopDecodeThread();
    void stopNextDecodeThread();
    void resetNextDeckLocked();
    void syncDecoderSampleRateToStreamLocked();
    bool initNextDeckAfterLoad();
    static float estimatePrebufferReplayGain(const float* buffer, int32_t totalSamples);
    void beginBufferReset();
    void endBufferReset();
    void updateSpectrum(const float* floatData, int32_t numFrames, int32_t channels, uint32_t sampleRate);

    void runDecodeLoop(
        DesktopDecoder* decoder,
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

#endif
