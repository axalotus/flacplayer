#ifndef AUDIODECODER_H
#define AUDIODECODER_H

#include <string>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "dr_flac.h"
#include "minimp3_ex.h"
#include "dr_wav.h"
#include "AlacDecoder.h"

struct AMediaExtractor;
struct AMediaCodec;

enum class AudioFormatType {
    UNKNOWN,
    FLAC,
    MP3,
    WAV,
    AIFF,
    ALAC,
    MEDIACODEC
};

class AudioDecoder {
public:
    AudioDecoder();
    ~AudioDecoder();

    bool openFile(const std::string& filePath, uint32_t targetSampleRate = 0);
    bool openFd(int fd, uint32_t targetSampleRate = 0);
    void close();

    int64_t seek(int64_t targetFrame);
    bool reconfigureOutputSampleRate(uint32_t targetSampleRate);
    
    // Decodes maxFrames (Stereo PCM frames: 2 floats per frame)
    // Returns the number of output frames actually decoded.
    uint64_t decodeFrames(float* pOutput, uint64_t maxFrames);

    uint32_t getSampleRate() const;
    uint32_t getChannels() const;
    uint64_t getTotalFrames() const;
    uint64_t getCurrentFrame() const;
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
    static FILE* openDupFd(int fd);
    static size_t flacIoRead(void* pUserData, void* bufferOut, size_t bytesToRead);
    static drflac_bool32 flacIoSeek(void* pUserData, int offset, drflac_seek_origin origin);
    static drflac_bool32 flacIoTell(void* pUserData, drflac_int64* pCursor);
    static void flacMetaCallback(void* pUserData, drflac_metadata* pMetadata);
    static void parseOggReplayGain(FILE* file, ReplayGainTagData& rg);
    static bool hasNonMp3ContainerSignature(FILE* file);
    bool openMediaCodecFd(int fd);
    uint64_t decodeSourceStereoFrames(float* pStereoOut, uint64_t maxSourceFrames);

    AudioFormatType mFormat = AudioFormatType::UNKNOWN;
    
    drflac* mFlac = nullptr;
    mp3dec_ex_t* mMp3 = nullptr;
    drwav* mWav = nullptr;
    AlacDecoder mAlac;
    AMediaExtractor* mMediaExtractor = nullptr;
    AMediaCodec* mMediaCodec = nullptr;
    int32_t mMcPcmEncoding = 2;
    bool mMcExtractorEof = false;
    bool mMcCodecEof = false;
    int64_t mMcDropBeforeUs = -1;
    std::vector<float> mMcStereoBuf;
    size_t mMcStereoPos = 0;
    size_t mMcStereoAvail = 0;
    // Owns FILE* handles created via fopen/fdopen (MP3 or FD-backed FLAC/WAV/MP3)
    // so close() always releases them after uninitializing the codec.
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

    // Reusable scratch buffer for multichannel (>2ch) stereo downmix (avoids per-call heap allocs)
    std::vector<float> mMultiChScratchBuf;

    // Internal buffer for MP3 frame-by-frame mono/stereo normalization
    std::vector<float> mMp3FrameStereoBuf;
    size_t mMp3FrameStereoPos = 0;
    size_t mMp3FrameStereoAvail = 0;

    // Internal buffer & state for windowed-sinc (Lanczos-3) stereo resampler
    std::vector<float> mResampleSrcBuf;
    size_t mResampleSrcPos = 0;
    size_t mResampleSrcAvail = 0;
    float mResampleRing[6][2] = {};
    int mResampleValidCount = 0;
    bool mResampleSourceEof = false;
    double mResampleFrac = 0.0;
    bool mResamplePrimed = false;
};

#endif // AUDIODECODER_H
