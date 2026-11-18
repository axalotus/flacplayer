#ifndef DESKTOP_DECODER_H
#define DESKTOP_DECODER_H

#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "dr_flac.h"
#include "dr_wav.h"
#include "minimp3_ex.h"
#include "AlacDecoder.h"

struct AVFormatContext;
struct AVCodecContext;
struct SwrContext;
struct AVFrame;
struct AVPacket;

enum class AudioFormatType {
    UNKNOWN,
    FLAC,
    MP3,
    WAV,
    AIFF,
    ALAC,
    FFMPEG
};

class DesktopDecoder {
public:
    DesktopDecoder();
    ~DesktopDecoder();

    bool openFile(const std::string& filePath, uint32_t targetSampleRate = 0);
    void close();

    int64_t seek(int64_t targetFrame);
    bool reconfigureOutputSampleRate(uint32_t targetSampleRate);

    uint64_t decodeFrames(float* pOutput, uint64_t maxFrames);

    uint32_t getSampleRate() const;
    uint32_t getChannels() const;
    uint64_t getTotalFrames() const;
    uint64_t getCurrentFrame() const;
    AudioFormatType getFormat() const { return mFormat; }
    bool hasReplayGain() const { return mHasReplayGain; }
    float getReplayGainLinear() const { return mReplayGainLinear; }

    struct ReplayGainTagData {
        bool hasTrackGain = false;
        float trackGainDb = 0.0f;
        bool hasTrackPeak = false;
        float trackPeak = 0.0f;
        bool hasAlbumGain = false;
        float albumGainDb = 0.0f;
        bool hasAlbumPeak = false;
        float albumPeak = 0.0f;
    };

private:
    void configureOutputFormat();
    void resetResamplerState();
    void finalizeReplayGain(const ReplayGainTagData& rg);
    static void applyReplayGainTag(ReplayGainTagData& rg, const char* key, const char* val);
    static void parseId3v2ReplayGain(FILE* file, ReplayGainTagData& rg);
    static void downmixToStereo(const float* src, float* dst, uint64_t frames, uint32_t srcCh);

    static FILE* openUtf8File(const std::string& filePath);
    static int seek64(FILE* file, int64_t offset, int origin);
    static int64_t tell64(FILE* file);

    static size_t flacIoRead(void* pUserData, void* bufferOut, size_t bytesToRead);
    static drflac_bool32 flacIoSeek(void* pUserData, int offset, drflac_seek_origin origin);
    static drflac_bool32 flacIoTell(void* pUserData, drflac_int64* pCursor);
    static void flacMetaCallback(void* pUserData, drflac_metadata* pMetadata);

    static size_t wavIoRead(void* pUserData, void* pBufferOut, size_t bytesToRead);
    static drwav_bool32 wavIoSeek(void* pUserData, int offset, drwav_seek_origin origin);
    static drwav_bool32 wavIoTell(void* pUserData, drwav_int64* pCursor);
    static bool hasNonMp3ContainerSignature(FILE* file);
    bool openFfmpeg(const std::string& filePath);

    uint64_t decodeSourceStereoFrames(float* pStereoOut, uint64_t maxSourceFrames);

    AudioFormatType mFormat = AudioFormatType::UNKNOWN;

    drflac* mFlac = nullptr;
    mp3dec_ex_t* mMp3 = nullptr;
    drwav* mWav = nullptr;
    AlacDecoder mAlac;
    AVFormatContext* mAvFmt = nullptr;
    AVCodecContext* mAvCodec = nullptr;
    SwrContext* mSwr = nullptr;
    AVFrame* mAvFrame = nullptr;
    AVPacket* mAvPkt = nullptr;
    int mAvStreamIdx = -1;
    bool mAvEof = false;
    int64_t mAvSeekTargetPts = -1;
    std::vector<float> mAvStereoBuf;
    size_t mAvStereoPos = 0;
    size_t mAvStereoAvail = 0;
    FILE* mOwnedFile = nullptr;
    mp3dec_io_t mMp3Io{};
    ReplayGainTagData mPendingFlacRg{};
    bool mHasReplayGain = false;
    float mReplayGainLinear = 1.0f;

    uint32_t mSourceSampleRate = 0;
    uint32_t mSourceChannels = 0;
    uint64_t mSourceTotalFrames = 0;

    uint32_t mSampleRate = 0;
    uint32_t mForcedSampleRate = 0;
    uint32_t mChannels = 2;
    uint64_t mTotalFrames = 0;
    uint64_t mCurrentFrame = 0;

    std::vector<float> mMultiChScratchBuf;
    std::vector<float> mMp3FrameStereoBuf;
    size_t mMp3FrameStereoPos = 0;
    size_t mMp3FrameStereoAvail = 0;

    std::vector<float> mResampleSrcBuf;
    size_t mResampleSrcPos = 0;
    size_t mResampleSrcAvail = 0;
    float mResampleRing[6][2] = {};
    int mResampleValidCount = 0;
    bool mResampleSourceEof = false;
    double mResampleFrac = 0.0;
    bool mResamplePrimed = false;
};

#endif
