#ifndef AUDIODECODER_H
#define AUDIODECODER_H

#include <string>
#include <cstdint>

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
    
    // Decodes maxFrames (PCM frames, which is channels * samples)
    // Returns the number of frames actually decoded.
    uint64_t decodeFrames(float* pOutput, uint64_t maxFrames);

    uint32_t getSampleRate() const;
    uint32_t getChannels() const;
    uint64_t getTotalFrames() const;
    uint64_t getCurrentFrame() const;

private:
    AudioFormatType mFormat = AudioFormatType::UNKNOWN;
    
    drflac* mFlac = nullptr;
    mp3dec_ex_t* mMp3 = nullptr;
    drwav* mWav = nullptr;

    uint32_t mSampleRate = 0;
    uint32_t mChannels = 0;
    uint64_t mTotalFrames = 0;
    uint64_t mCurrentFrame = 0;
};

#endif // AUDIODECODER_H
