#include "AudioDecoder.h"

#define DR_FLAC_IMPLEMENTATION
#include "dr_flac.h"

#define DR_MP3_IMPLEMENTATION
#include "dr_mp3.h"

#define DR_WAV_IMPLEMENTATION
#include "dr_wav.h"

#include <algorithm>
#include <cstring>
#include <cmath>

AudioDecoder::AudioDecoder() = default;

AudioDecoder::~AudioDecoder() {
    close();
}

void AudioDecoder::close() {
    if (!m_decoderHandle) return;

    switch (m_format) {
        case AudioFormat::FLAC:
            drflac_close(static_cast<drflac*>(m_decoderHandle));
            break;
        case AudioFormat::MP3: {
            drmp3* mp3 = static_cast<drmp3*>(m_decoderHandle);
            drmp3_uninit(mp3);
            delete mp3;
            break;
        }
        case AudioFormat::WAV: {
            drwav* wav = static_cast<drwav*>(m_decoderHandle);
            drwav_uninit(wav);
            delete wav;
            break;
        }
        default:
            break;
    }
    m_decoderHandle = nullptr;
    m_format = AudioFormat::UNKNOWN;
}

std::string AudioDecoder::getFormatString() const {
    switch (m_format) {
        case AudioFormat::FLAC: return "FLAC";
        case AudioFormat::MP3: return "MP3";
        case AudioFormat::WAV: return "WAV";
        default: return "AUDIO";
    }
}

static bool endsWithIgnoreCase(const std::string& str, const std::string& suffix) {
    if (str.length() < suffix.length()) return false;
    std::string s1 = str.substr(str.length() - suffix.length());
    std::string s2 = suffix;
    std::transform(s1.begin(), s1.end(), s1.begin(), ::tolower);
    std::transform(s2.begin(), s2.end(), s2.begin(), ::tolower);
    return s1 == s2;
}

bool AudioDecoder::openFile(const std::string& filePath) {
    close();

    // 1. Try FLAC
    if (endsWithIgnoreCase(filePath, ".flac")) {
        drflac* flac = drflac_open_file(filePath.c_str(), nullptr);
        if (flac) {
            m_decoderHandle = flac;
            m_format = AudioFormat::FLAC;
            m_sampleRate = flac->sampleRate;
            m_channels = flac->channels;
            m_bitDepth = flac->bitsPerSample;
            m_totalFrames = flac->totalPCMFrameCount;
            return true;
        }
    }

    // 2. Try MP3
    if (endsWithIgnoreCase(filePath, ".mp3")) {
        drmp3* mp3 = new drmp3();
        if (drmp3_init_file(mp3, filePath.c_str(), nullptr)) {
            m_decoderHandle = mp3;
            m_format = AudioFormat::MP3;
            m_sampleRate = mp3->sampleRate;
            m_channels = mp3->channels;
            m_bitDepth = 16;
            m_totalFrames = drmp3_get_pcm_frame_count(mp3);
            return true;
        } else {
            delete mp3;
        }
    }

    // 3. Try WAV
    if (endsWithIgnoreCase(filePath, ".wav")) {
        drwav* wav = new drwav();
        if (drwav_init_file(wav, filePath.c_str(), nullptr)) {
            m_decoderHandle = wav;
            m_format = AudioFormat::WAV;
            m_sampleRate = wav->sampleRate;
            m_channels = wav->channels;
            m_bitDepth = wav->bitsPerSample;
            m_totalFrames = wav->totalPCMFrameCount;
            return true;
        } else {
            delete wav;
        }
    }

    // Fallback: try all formats by content header
    drflac* flac = drflac_open_file(filePath.c_str(), nullptr);
    if (flac) {
        m_decoderHandle = flac;
        m_format = AudioFormat::FLAC;
        m_sampleRate = flac->sampleRate;
        m_channels = flac->channels;
        m_bitDepth = flac->bitsPerSample;
        m_totalFrames = flac->totalPCMFrameCount;
        return true;
    }

    drmp3* mp3 = new drmp3();
    if (drmp3_init_file(mp3, filePath.c_str(), nullptr)) {
        m_decoderHandle = mp3;
        m_format = AudioFormat::MP3;
        m_sampleRate = mp3->sampleRate;
        m_channels = mp3->channels;
        m_bitDepth = 16;
        m_totalFrames = drmp3_get_pcm_frame_count(mp3);
        return true;
    }
    delete mp3;

    return false;
}

size_t AudioDecoder::readSamples(int16_t* buffer, size_t framesToRead) {
    if (!m_decoderHandle || framesToRead == 0) return 0;

    switch (m_format) {
        case AudioFormat::FLAC: {
            drflac* flac = static_cast<drflac*>(m_decoderHandle);
            return static_cast<size_t>(drflac_read_pcm_frames_s16(flac, framesToRead, buffer));
        }
        case AudioFormat::MP3: {
            drmp3* mp3 = static_cast<drmp3*>(m_decoderHandle);
            return static_cast<size_t>(drmp3_read_pcm_frames_s16(mp3, framesToRead, buffer));
        }
        case AudioFormat::WAV: {
            drwav* wav = static_cast<drwav*>(m_decoderHandle);
            return static_cast<size_t>(drwav_read_pcm_frames_s16(wav, framesToRead, buffer));
        }
        default:
            return 0;
    }
}

bool AudioDecoder::seekToFrame(uint64_t frameIndex) {
    if (!m_decoderHandle) return false;

    switch (m_format) {
        case AudioFormat::FLAC: {
            drflac* flac = static_cast<drflac*>(m_decoderHandle);
            return drflac_seek_to_pcm_frame(flac, frameIndex) == DRFLAC_TRUE;
        }
        case AudioFormat::MP3: {
            drmp3* mp3 = static_cast<drmp3*>(m_decoderHandle);
            return drmp3_seek_to_pcm_frame(mp3, frameIndex) == DRMP3_TRUE;
        }
        case AudioFormat::WAV: {
            drwav* wav = static_cast<drwav*>(m_decoderHandle);
            return drwav_seek_to_pcm_frame(wav, frameIndex) == DRWAV_TRUE;
        }
        default:
            return false;
    }
}
