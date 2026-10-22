#pragma once

#include <string>
#include <vector>
#include <cstdint>

enum class AudioFormat {
    UNKNOWN,
    WAV,
    MP3,
    FLAC
};

class AudioDecoder {
public:
    AudioDecoder();
    ~AudioDecoder();

    bool openFile(const std::string& filePath);
    void close();

    // Decodes into interleaved 16-bit PCM (converts 24-bit/32-bit if necessary for standard output)
    size_t readSamples(int16_t* buffer, size_t framesToRead);
    bool seekToFrame(uint64_t frameIndex);

    int getSampleRate() const { return m_sampleRate; }
    int getChannels() const { return m_channels; }
    int getBitDepth() const { return m_bitDepth; }
    uint64_t getTotalFrames() const { return m_totalFrames; }
    int64_t getDurationMs() const {
        return m_sampleRate > 0 ? static_cast<int64_t>((m_totalFrames * 1000ULL) / m_sampleRate) : 0;
    }
    AudioFormat getFormat() const { return m_format; }
    std::string getFormatString() const;
    bool isHiRes() const {
        return (m_format == AudioFormat::FLAC || m_format == AudioFormat::WAV) &&
               (m_bitDepth >= 24 || m_sampleRate >= 88200);
    }

private:
    void* m_decoderHandle = nullptr;
    AudioFormat m_format = AudioFormat::UNKNOWN;
    int m_sampleRate = 44100;
    int m_channels = 2;
    int m_bitDepth = 16;
    uint64_t m_totalFrames = 0;
};
