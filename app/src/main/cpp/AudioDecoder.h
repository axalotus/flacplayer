#ifndef AUDIODECODER_H
#define AUDIODECODER_H

#include <string>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "dr_flac.h"
#include "minimp3_ex.h"
#include "dr_wav.h"

enum class AudioFormatType {
    UNKNOWN,
    FLAC,
    MP3,
    WAV
};

class AudioDecoder {
public:
    AudioDecoder();
    ~AudioDecoder();

    bool openFile(const std::string& filePath);
    bool openFd(int fd);
    void close();

    int64_t seek(int64_t targetFrame);
    
    // Decodes maxFrames (Stereo PCM frames: 2 floats per frame)
    // Returns the number of output frames actually decoded.
    uint64_t decodeFrames(float* pOutput, uint64_t maxFrames);

    uint32_t getSampleRate() const;
    uint32_t getChannels() const;
    uint64_t getTotalFrames() const;
    uint64_t getCurrentFrame() const;

private:
    void configureOutputFormat();
    void resetResamplerState();
    uint64_t decodeSourceStereoFrames(float* pStereoOut, uint64_t maxSourceFrames);

    AudioFormatType mFormat = AudioFormatType::UNKNOWN;
    
    drflac* mFlac = nullptr;
    mp3dec_ex_t* mMp3 = nullptr;
    FILE* mMp3File = nullptr;
    mp3dec_io_t mMp3Io{};
    drwav* mWav = nullptr;

    uint32_t mSourceSampleRate = 0;
    uint32_t mSourceChannels = 0;
    uint64_t mSourceTotalFrames = 0;

    uint32_t mSampleRate = 0;
    uint32_t mChannels = 2;
    uint64_t mTotalFrames = 0;
    uint64_t mCurrentFrame = 0;

    // Internal buffer for MP3 frame-by-frame mono/stereo normalization
    std::vector<float> mMp3FrameStereoBuf;
    size_t mMp3FrameStereoPos = 0;
    size_t mMp3FrameStereoAvail = 0;

    // Internal buffer & state for low-sample-rate (<44.1kHz) stereo resampling
    std::vector<float> mResampleSrcBuf;
    size_t mResampleSrcPos = 0;
    size_t mResampleSrcAvail = 0;
    float mResamplePrev[2] = {0.0f, 0.0f};
    float mResampleCurr[2] = {0.0f, 0.0f};
    double mResampleFrac = 0.0;
    bool mResamplePrimed = false;
};

#endif // AUDIODECODER_H
