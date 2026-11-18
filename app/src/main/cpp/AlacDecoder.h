#ifndef ALAC_DECODER_H
#define ALAC_DECODER_H

#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

class AlacDecoder {
public:
    struct ReplayGainInfo {
        bool hasTrackGain = false;
        float trackGainDb = 0.0f;
        bool hasTrackPeak = false;
        float trackPeak = 0.0f;
        bool hasAlbumGain = false;
        float albumGainDb = 0.0f;
        bool hasAlbumPeak = false;
        float albumPeak = 0.0f;
    };

    AlacDecoder();
    ~AlacDecoder();

    bool open(FILE* file);
    void close();

    bool seek(uint64_t targetFrame);
    uint64_t readStereoFrames(float* pOut, uint64_t maxFrames);

    uint32_t getSampleRate() const { return mSampleRate; }
    uint32_t getChannels() const { return mChannels; }
    uint32_t getBitDepth() const { return mBitDepth; }
    uint64_t getTotalFrames() const { return mTotalFrames; }
    const ReplayGainInfo& getReplayGain() const { return mReplayGain; }

private:
    struct PacketEntry {
        uint64_t offset = 0;
        uint32_t size = 0;
        uint64_t startSample = 0;
        uint32_t sampleCount = 0;
    };

    struct AlacConfig {
        uint32_t frameLength = 4096;
        uint8_t compatibleVersion = 0;
        uint8_t bitDepth = 16;
        uint8_t pb = 40;
        uint8_t mb = 10;
        uint8_t kb = 14;
        uint8_t numChannels = 2;
        uint16_t maxRun = 255;
        uint32_t maxFrameBytes = 0;
        uint32_t avgBitRate = 0;
        uint32_t sampleRate = 44100;
    };

    struct TrackAtoms {
        bool hasAlac = false;
        AlacConfig config{};
        std::vector<uint32_t> sttsCounts;
        std::vector<uint32_t> sttsDeltas;
        std::vector<uint32_t> stscFirstChunk;
        std::vector<uint32_t> stscSamplesPerChunk;
        uint32_t stszConstantSize = 0;
        uint32_t stszCount = 0;
        std::vector<uint32_t> stszSizes;
        std::vector<uint64_t> chunkOffsets;
    };

    bool parseMp4(FILE* file, uint64_t fileSize);
    bool walkBoxes(FILE* file, uint64_t start, uint64_t end, TrackAtoms& currentTrack, std::vector<TrackAtoms>& tracks);
    bool parseStsd(FILE* file, uint64_t start, uint64_t end, TrackAtoms& track);
    bool parseFreeformTag(FILE* file, uint64_t start, uint64_t end);
    bool buildPacketTable(const TrackAtoms& track);
    bool decodePacket(size_t packetIdx);

    FILE* mFile = nullptr;
    AlacConfig mConfig{};
    uint32_t mSampleRate = 0;
    uint32_t mChannels = 0;
    uint32_t mBitDepth = 16;
    uint64_t mTotalFrames = 0;
    ReplayGainInfo mReplayGain{};

    std::vector<PacketEntry> mPackets;
    size_t mNextPacketIdx = 0;

    std::vector<uint8_t> mPacketBuf;
    std::vector<int32_t> mMixBuf[2];
    std::vector<int32_t> mPredBuf[2];
    std::vector<uint16_t> mShiftBuf[2];

    std::vector<float> mDecodedStereoBuf;
    size_t mDecodedStereoPos = 0;
    size_t mDecodedStereoAvail = 0;
};

#endif // ALAC_DECODER_H
