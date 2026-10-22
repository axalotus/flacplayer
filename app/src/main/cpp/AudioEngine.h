#ifndef AUDIOPLAYER_AUDIOENGINE_H
#define AUDIOPLAYER_AUDIOENGINE_H

#include <oboe/Oboe.h>
#include <android/log.h>
#include <mutex>
#include <thread>
#include <atomic>
#include <memory>
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
    int64_t seekTo(int64_t targetFrame);
    int64_t getCurrentFrame() const;
    int64_t getTotalFrames() const;
    
    // Recreate stream with new audio API mode
    // mode: 0=AAudio(default), 1=OpenSL ES, 2=Hi-Res
    bool recreateStream(int mode);

    // Reopen stream after routing change / disconnect, preserving current frame position
    bool reopenStream(bool restartIfPlaying);

    // Set output audio device ID (oboe::kUnspecified = default)
    bool setAudioDeviceId(int deviceId);
    
    bool isEof() const;

    EqualizerEngine* getEqualizer() const { return mEqualizer.get(); }

    // oboe::AudioStreamCallback overrides
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *oboeStream, void *audioData, int32_t numFrames) override;
    void onErrorBeforeClose(oboe::AudioStream *oboeStream, oboe::Result error) override;
    void onErrorAfterClose(oboe::AudioStream *oboeStream, oboe::Result error) override;

private:
    std::mutex mLock;
    std::shared_ptr<oboe::AudioStream> mStream;
    bool mIsPlaying = false;

    std::unique_ptr<AudioDecoder> mDecoder;
    std::unique_ptr<RingBuffer<float>> mRingBuffer;
    std::unique_ptr<EqualizerEngine> mEqualizer;

    std::thread mDecodeThread;
    std::atomic<bool> mDecodeThreadRunning{false};
    std::atomic<uint64_t> mPlayedSampleCount{0};
    std::atomic<bool> mIsEof{false};
    std::atomic<bool> mDecoderReachedEof{false};
    int mAudioMode = 0; // 0=AAudio, 1=OpenSL ES, 2=Hi-Res
    int mDeviceId = oboe::kUnspecified;

    bool openStream(int channelCount, int sampleRate);
    void closeStream();
    void decodeLoop();
};

#endif //AUDIOPLAYER_AUDIOENGINE_H
