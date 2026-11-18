#define DR_FLAC_IMPLEMENTATION
#define MINIMP3_IMPLEMENTATION
#define MINIMP3_FLOAT_OUTPUT
#define MINIMP3_ONLY_MP3
#define MINIMP3_ALLOW_MONO_STEREO_TRANSITION
#define DR_WAV_IMPLEMENTATION
#include "AudioDecoder.h"
#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <strings.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <cstdio>

#include <android/log.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "AudioDecoder", __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "AudioDecoder", __VA_ARGS__)

static constexpr size_t RESAMPLE_CHUNK_FRAMES = 512;

static size_t mp3ReadCallback(void* buf, size_t size, void* userData) {
    FILE* f = static_cast<FILE*>(userData);
    if (!f || !buf || size == 0) return 0;
    return std::fread(buf, 1, size, f);
}

static int mp3SeekCallback(uint64_t position, void* userData) {
    FILE* f = static_cast<FILE*>(userData);
    if (!f) return -1;
    return fseeko(f, static_cast<off_t>(position), SEEK_SET);
}

AudioDecoder::AudioDecoder() {
    mMp3 = new mp3dec_ex_t();
    memset(mMp3, 0, sizeof(mp3dec_ex_t));
    mWav = new drwav();
    memset(mWav, 0, sizeof(drwav));
    mMp3FrameStereoBuf.resize(MINIMP3_MAX_SAMPLES_PER_FRAME * 2);
    mResampleSrcBuf.resize(RESAMPLE_CHUNK_FRAMES * 2);
}

AudioDecoder::~AudioDecoder() {
    close();
    delete mMp3;
    delete mWav;
}

void AudioDecoder::resetResamplerState() {
    mMp3FrameStereoPos = 0;
    mMp3FrameStereoAvail = 0;
    mMcStereoPos = 0;
    mMcStereoAvail = 0;
    mResampleSrcPos = 0;
    mResampleSrcAvail = 0;
    std::memset(mResampleRing, 0, sizeof(mResampleRing));
    mResampleValidCount = 0;
    mResampleSourceEof = false;
    mResampleFrac = 0.0;
    mResamplePrimed = false;
}

void AudioDecoder::applyReplayGainTag(ReplayGainTagData& rg, const char* key, const char* val) {
    if (!key || !val || !*val) return;
    char* endPtr = nullptr;
    float parsed = std::strtof(val, &endPtr);
    if (endPtr == val || !std::isfinite(parsed)) return;

    if (strcasecmp(key, "REPLAYGAIN_TRACK_GAIN") == 0) {
        rg.hasTrackGain = true;
        rg.trackGainDb = parsed;
    } else if (strcasecmp(key, "REPLAYGAIN_TRACK_PEAK") == 0) {
        rg.hasTrackPeak = true;
        rg.trackPeak = parsed;
    } else if (strcasecmp(key, "REPLAYGAIN_ALBUM_GAIN") == 0) {
        rg.hasAlbumGain = true;
        rg.albumGainDb = parsed;
    } else if (strcasecmp(key, "REPLAYGAIN_ALBUM_PEAK") == 0) {
        rg.hasAlbumPeak = true;
        rg.albumPeak = parsed;
    } else if (strcasecmp(key, "R128_TRACK_GAIN") == 0 && !rg.hasTrackGain) {
        // R128_* tags use Q7.8 fixed-point dB referenced to -23 LUFS (+5 dB aligns to ReplayGain -18 LUFS)
        rg.hasTrackGain = true;
        rg.trackGainDb = (parsed / 256.0f) + 5.0f;
    } else if (strcasecmp(key, "R128_ALBUM_GAIN") == 0 && !rg.hasAlbumGain) {
        rg.hasAlbumGain = true;
        rg.albumGainDb = (parsed / 256.0f) + 5.0f;
    }
}

void AudioDecoder::finalizeReplayGain(const ReplayGainTagData& rg) {
    if (!rg.hasTrackGain && !rg.hasAlbumGain) {
        mHasReplayGain = false;
        mReplayGainLinear = 1.0f;
        return;
    }
    float gainDb = rg.hasTrackGain ? rg.trackGainDb : rg.albumGainDb;
    float gain = std::pow(10.0f, gainDb / 20.0f);
    gain = std::clamp(gain, 0.15f, 4.0f);

    float peak = rg.hasTrackPeak ? rg.trackPeak : (rg.hasAlbumPeak ? rg.albumPeak : 0.0f);
    if (peak > 0.01f && gain * peak > 0.98f) {
        gain = 0.98f / peak;
    }
    mHasReplayGain = true;
    mReplayGainLinear = gain;
}

void AudioDecoder::flacMetaCallback(void* pUserData, drflac_metadata* pMetadata) {
    if (!pUserData || !pMetadata) return;
    if (pMetadata->type != DRFLAC_METADATA_BLOCK_TYPE_VORBIS_COMMENT) return;

    auto* dec = static_cast<AudioDecoder*>(pUserData);
    drflac_vorbis_comment_iterator iter;
    drflac_init_vorbis_comment_iterator(
        &iter,
        pMetadata->data.vorbis_comment.commentCount,
        pMetadata->data.vorbis_comment.pComments
    );
    drflac_uint32 len = 0;
    const char* comment = nullptr;
    while ((comment = drflac_next_vorbis_comment(&iter, &len)) != nullptr) {
        if (len == 0 || len > 512) continue;
        std::string entry(comment, len);
        size_t eq = entry.find('=');
        if (eq != std::string::npos && eq > 0) {
            std::string key = entry.substr(0, eq);
            std::string val = entry.substr(eq + 1);
            applyReplayGainTag(dec->mPendingFlacRg, key.c_str(), val.c_str());
        }
    }
}

void AudioDecoder::parseId3v2ReplayGain(FILE* file, ReplayGainTagData& rg) {
    if (!file) return;
    fseeko(file, 0, SEEK_SET);
    uint8_t hdr[10];
    if (std::fread(hdr, 1, 10, file) != 10) {
        fseeko(file, 0, SEEK_SET);
        return;
    }
    if (hdr[0] != 'I' || hdr[1] != 'D' || hdr[2] != '3') {
        fseeko(file, 0, SEEK_SET);
        return;
    }
    uint8_t majorVer = hdr[3];
    uint8_t flags = hdr[5];
    uint32_t tagSize = ((uint32_t)(hdr[6] & 0x7F) << 21) |
                       ((uint32_t)(hdr[7] & 0x7F) << 14) |
                       ((uint32_t)(hdr[8] & 0x7F) << 7) |
                       (uint32_t)(hdr[9] & 0x7F);
    if (majorVer < 3 || majorVer > 4 || tagSize == 0 || tagSize > 2u * 1024u * 1024u) {
        fseeko(file, 0, SEEK_SET);
        return;
    }

    std::vector<uint8_t> tagBuf(tagSize);
    if (std::fread(tagBuf.data(), 1, tagSize, file) != tagSize) {
        fseeko(file, 0, SEEK_SET);
        return;
    }
    fseeko(file, 0, SEEK_SET);

    auto removeUnsync = [](const uint8_t* src, size_t len) {
        std::vector<uint8_t> out;
        out.reserve(len);
        for (size_t i = 0; i < len; ++i) {
            out.push_back(src[i]);
            if (src[i] == 0xFF && i + 1 < len && src[i + 1] == 0x00) {
                ++i; // Skip inserted unsynchronisation zero byte
            }
        }
        return out;
    };

    // ID3v2.3 global unsynchronisation applies across the whole tag body after header
    if (majorVer == 3 && (flags & 0x80) != 0) {
        tagBuf = removeUnsync(tagBuf.data(), tagBuf.size());
    }

    size_t pos = 0;
    if ((flags & 0x40) != 0 && tagBuf.size() >= 4) {
        uint32_t extSize = (majorVer == 4)
            ? (((uint32_t)(tagBuf[0] & 0x7F) << 21) | ((uint32_t)(tagBuf[1] & 0x7F) << 14) |
               ((uint32_t)(tagBuf[2] & 0x7F) << 7) | (uint32_t)(tagBuf[3] & 0x7F))
            : (((uint32_t)tagBuf[0] << 24) | ((uint32_t)tagBuf[1] << 16) |
               ((uint32_t)tagBuf[2] << 8) | (uint32_t)tagBuf[3]);
        if (majorVer == 3) pos += 4 + extSize;
        else pos += extSize;
    }

    auto readAsciiStringFromUtf16 = [](const uint8_t* data, size_t len, bool bigEndian, size_t& outNext) {
        std::string out;
        size_t i = 0;
        while (i + 1 < len) {
            uint16_t ch = bigEndian
                ? static_cast<uint16_t>((data[i] << 8) | data[i + 1])
                : static_cast<uint16_t>(data[i] | (data[i + 1] << 8));
            i += 2;
            if (ch == 0) break;
            if (ch < 0x80) out.push_back(static_cast<char>(ch));
        }
        outNext = i;
        return out;
    };

    while (pos + 10 <= tagBuf.size()) {
        if (tagBuf[pos] == 0) break;
        uint32_t frameSize = (majorVer == 4)
            ? (((uint32_t)(tagBuf[pos + 4] & 0x7F) << 21) | ((uint32_t)(tagBuf[pos + 5] & 0x7F) << 14) |
               ((uint32_t)(tagBuf[pos + 6] & 0x7F) << 7) | (uint32_t)(tagBuf[pos + 7] & 0x7F))
            : (((uint32_t)tagBuf[pos + 4] << 24) | ((uint32_t)tagBuf[pos + 5] << 16) |
               ((uint32_t)tagBuf[pos + 6] << 8) | (uint32_t)tagBuf[pos + 7]);
        if (frameSize == 0 || pos + 10 + frameSize > tagBuf.size()) break;

        uint8_t frameFlags2 = tagBuf[pos + 9];
        bool isCompressedOrEncrypted = (majorVer == 3)
            ? ((frameFlags2 & 0xC0) != 0)
            : ((frameFlags2 & 0x0C) != 0);

        if (!isCompressedOrEncrypted && std::memcmp(&tagBuf[pos], "TXXX", 4) == 0 && frameSize > 1) {
            const uint8_t* rawFrame = &tagBuf[pos + 10];
            size_t rawLen = frameSize;
            std::vector<uint8_t> decodedFrame;
            if (majorVer == 4 && ((flags & 0x80) != 0 || (frameFlags2 & 0x02) != 0)) {
                decodedFrame = removeUnsync(rawFrame, rawLen);
                rawFrame = decodedFrame.data();
                rawLen = decodedFrame.size();
            }
            if (rawLen > 1) {
                uint8_t enc = rawFrame[0];
                size_t payloadLen = rawLen - 1;
                const uint8_t* payload = rawFrame + 1;
                std::string desc;
                std::string val;

                if (enc == 0 || enc == 3) {
                    size_t sep = 0;
                    while (sep < payloadLen && payload[sep] != 0) sep++;
                    desc.assign(reinterpret_cast<const char*>(payload), sep);
                    if (sep + 1 < payloadLen) {
                        size_t valStart = sep + 1;
                        size_t valEnd = valStart;
                        while (valEnd < payloadLen && payload[valEnd] != 0) valEnd++;
                        val.assign(reinterpret_cast<const char*>(payload + valStart), valEnd - valStart);
                    }
                } else if (enc == 1 || enc == 2) {
                    bool bigEndian = (enc == 2);
                    size_t start = 0;
                    if (enc == 1 && payloadLen >= 2) {
                        if (payload[0] == 0xFE && payload[1] == 0xFF) { bigEndian = true; start = 2; }
                        else if (payload[0] == 0xFF && payload[1] == 0xFE) { bigEndian = false; start = 2; }
                    }
                    size_t nextOff = 0;
                    desc = readAsciiStringFromUtf16(payload + start, payloadLen - start, bigEndian, nextOff);
                    size_t valStart = start + nextOff;
                    if (valStart < payloadLen) {
                        bool valBe = bigEndian;
                        if (enc == 1 && payloadLen - valStart >= 2) {
                            if (payload[valStart] == 0xFE && payload[valStart + 1] == 0xFF) { valBe = true; valStart += 2; }
                            else if (payload[valStart] == 0xFF && payload[valStart + 1] == 0xFE) { valBe = false; valStart += 2; }
                        }
                        size_t dummy = 0;
                        val = readAsciiStringFromUtf16(payload + valStart, payloadLen - valStart, valBe, dummy);
                    }
                }
                if (!desc.empty() && !val.empty()) {
                    applyReplayGainTag(rg, desc.c_str(), val.c_str());
                }
            }
        }
        pos += 10 + frameSize;
    }
}

void AudioDecoder::parseOggReplayGain(FILE* file, ReplayGainTagData& rg) {
    if (!file) return;
    fseeko(file, 0, SEEK_SET);
    std::vector<uint8_t> buf(65536);
    size_t n = std::fread(buf.data(), 1, buf.size(), file);
    fseeko(file, 0, SEEK_SET);
    if (n < 32 || std::memcmp(buf.data(), "OggS", 4) != 0) return;

    // Locate either OpusTags or \x03vorbis comment header inside first 64 KB
    size_t startOff = std::string::npos;
    for (size_t i = 0; i + 8 < n; ++i) {
        if (std::memcmp(buf.data() + i, "OpusTags", 8) == 0) {
            startOff = i + 8;
            break;
        }
        if (buf[i] == 0x03 && std::memcmp(buf.data() + i + 1, "vorbis", 6) == 0) {
            startOff = i + 7;
            break;
        }
    }
    if (startOff == std::string::npos || startOff + 4 > n) return;

    auto readLe32 = [&](size_t off) -> uint32_t {
        return static_cast<uint32_t>(buf[off]) |
               (static_cast<uint32_t>(buf[off + 1]) << 8) |
               (static_cast<uint32_t>(buf[off + 2]) << 16) |
               (static_cast<uint32_t>(buf[off + 3]) << 24);
    };

    uint32_t vendorLen = readLe32(startOff);
    size_t pos = startOff + 4 + static_cast<size_t>(vendorLen);
    if (pos + 4 > n) return;

    uint32_t commentCount = readLe32(pos);
    pos += 4;
    if (commentCount > 512) commentCount = 512;

    for (uint32_t c = 0; c < commentCount && pos + 4 <= n; ++c) {
        uint32_t len = readLe32(pos);
        pos += 4;
        if (len == 0 || pos + len > n) break;
        if (len <= 512) {
            std::string entry(reinterpret_cast<const char*>(buf.data() + pos), len);
            size_t eq = entry.find('=');
            if (eq != std::string::npos && eq > 0) {
                applyReplayGainTag(rg, entry.substr(0, eq).c_str(), entry.substr(eq + 1).c_str());
            }
        }
        pos += len;
    }
}

bool AudioDecoder::hasNonMp3ContainerSignature(FILE* file) {
    if (!file) return false;
    fseeko(file, 0, SEEK_SET);
    uint8_t h[16]{};
    size_t nr = std::fread(h, 1, sizeof(h), file);
    if (nr < 4) {
        fseeko(file, 0, SEEK_SET);
        return false;
    }
    if (std::memcmp(h, "fLaC", 4) == 0 ||
        std::memcmp(h, "RIFF", 4) == 0 ||
        std::memcmp(h, "FORM", 4) == 0 ||
        std::memcmp(h, "OggS", 4) == 0 ||
        std::memcmp(h, "caff", 4) == 0 ||
        (nr >= 8 && std::memcmp(h + 4, "ftyp", 4) == 0)) {
        fseeko(file, 0, SEEK_SET);
        return true;
    }

    off_t audioOff = 0;
    if (nr >= 10 && std::memcmp(h, "ID3", 3) == 0) {
        audioOff = static_cast<off_t>(10 +
            ((static_cast<uint32_t>(h[6] & 0x7F) << 21) |
             (static_cast<uint32_t>(h[7] & 0x7F) << 14) |
             (static_cast<uint32_t>(h[8] & 0x7F) << 7) |
             static_cast<uint32_t>(h[9] & 0x7F)));
        if (fseeko(file, audioOff, SEEK_SET) == 0) {
            nr = std::fread(h, 1, 2, file);
        } else {
            nr = 0;
        }
    }
    fseeko(file, 0, SEEK_SET);
    if (nr >= 2 && h[0] == 0xFF && (h[1] & 0xF6) == 0xF0) {
        // ADTS AAC sync word (layer bits == 00)
        return true;
    }
    return false;
}

size_t AudioDecoder::flacIoRead(void* pUserData, void* bufferOut, size_t bytesToRead) {
    auto* dec = static_cast<AudioDecoder*>(pUserData);
    return (dec && dec->mOwnedFile) ? std::fread(bufferOut, 1, bytesToRead, dec->mOwnedFile) : 0;
}

drflac_bool32 AudioDecoder::flacIoSeek(void* pUserData, int offset, drflac_seek_origin origin) {
    auto* dec = static_cast<AudioDecoder*>(pUserData);
    if (!dec || !dec->mOwnedFile) return DRFLAC_FALSE;
    int whence = (origin == DRFLAC_SEEK_CUR) ? SEEK_CUR : ((origin == DRFLAC_SEEK_END) ? SEEK_END : SEEK_SET);
    return fseeko(dec->mOwnedFile, static_cast<off_t>(offset), whence) == 0 ? DRFLAC_TRUE : DRFLAC_FALSE;
}

drflac_bool32 AudioDecoder::flacIoTell(void* pUserData, drflac_int64* pCursor) {
    auto* dec = static_cast<AudioDecoder*>(pUserData);
    if (!dec || !dec->mOwnedFile || !pCursor) return DRFLAC_FALSE;
    off_t pos = ftello(dec->mOwnedFile);
    if (pos < 0) return DRFLAC_FALSE;
    *pCursor = static_cast<drflac_int64>(pos);
    return DRFLAC_TRUE;
}

void AudioDecoder::configureOutputFormat() {
    resetResamplerState();

    // Always output 2-channel Stereo to Oboe and EqualizerEngine
    mChannels = 2;

    if (mSourceSampleRate == 0) {
        mSampleRate = 44100;
        mTotalFrames = 0;
        return;
    }

    // If caller forced a target sample rate (e.g., matching Deck A during crossfade),
    // use it directly; otherwise upsample < 44100 Hz tracks for clean hardware playback.
    if (mForcedSampleRate > 0) {
        mSampleRate = mForcedSampleRate;
        mTotalFrames = static_cast<uint64_t>(
            (static_cast<double>(mSourceTotalFrames) * static_cast<double>(mSampleRate)) /
            static_cast<double>(mSourceSampleRate)
        );
    } else if (mSourceSampleRate < 44100) {
        if (mSourceSampleRate == 11025 || mSourceSampleRate == 22050) {
            mSampleRate = 44100;
        } else {
            mSampleRate = 48000;
        }
        mTotalFrames = static_cast<uint64_t>(
            (static_cast<double>(mSourceTotalFrames) * static_cast<double>(mSampleRate)) /
            static_cast<double>(mSourceSampleRate)
        );
    } else {
        mSampleRate = mSourceSampleRate;
        mTotalFrames = mSourceTotalFrames;
    }
}

bool AudioDecoder::openMediaCodecFd(int fd) {
    if (fd < 0) return false;
    struct stat st{};
    if (fstat(fd, &st) != 0 || st.st_size <= 0) return false;

    int dupFd = dup(fd);
    if (dupFd < 0) return false;
    lseek(dupFd, 0, SEEK_SET);

    AMediaExtractor* extractor = AMediaExtractor_new();
    if (!extractor) {
        ::close(dupFd);
        return false;
    }

    media_status_t stEx = AMediaExtractor_setDataSourceFd(
        extractor, dupFd, 0, static_cast<off64_t>(st.st_size)
    );
    ::close(dupFd);
    if (stEx != AMEDIA_OK) {
        AMediaExtractor_delete(extractor);
        return false;
    }

    size_t trackCount = AMediaExtractor_getTrackCount(extractor);
    int audioTrackIdx = -1;
    AMediaFormat* selectedFmt = nullptr;
    const char* selectedMime = nullptr;

    for (size_t i = 0; i < trackCount; ++i) {
        AMediaFormat* fmt = AMediaExtractor_getTrackFormat(extractor, i);
        if (!fmt) continue;
        const char* mime = nullptr;
        if (AMediaFormat_getString(fmt, AMEDIAFORMAT_KEY_MIME, &mime) && mime &&
            std::strncmp(mime, "audio/", 6) == 0) {
            audioTrackIdx = static_cast<int>(i);
            selectedFmt = fmt;
            selectedMime = mime;
            break;
        }
        AMediaFormat_delete(fmt);
    }

    if (audioTrackIdx < 0 || !selectedFmt || !selectedMime) {
        if (selectedFmt) AMediaFormat_delete(selectedFmt);
        AMediaExtractor_delete(extractor);
        return false;
    }

    AMediaExtractor_selectTrack(extractor, static_cast<size_t>(audioTrackIdx));

    AMediaCodec* codec = AMediaCodec_createDecoderByType(selectedMime);
    if (!codec) {
        AMediaFormat_delete(selectedFmt);
        AMediaExtractor_delete(extractor);
        return false;
    }

    if (AMediaCodec_configure(codec, selectedFmt, nullptr, nullptr, 0) != AMEDIA_OK ||
        AMediaCodec_start(codec) != AMEDIA_OK) {
        AMediaCodec_delete(codec);
        AMediaFormat_delete(selectedFmt);
        AMediaExtractor_delete(extractor);
        return false;
    }

    int32_t sr = 0;
    int32_t ch = 0;
    int64_t durUs = 0;
    int32_t pcmEnc = 2; // ENCODING_PCM_16BIT
    AMediaFormat_getInt32(selectedFmt, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr);
    AMediaFormat_getInt32(selectedFmt, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch);
    AMediaFormat_getInt64(selectedFmt, AMEDIAFORMAT_KEY_DURATION, &durUs);
    AMediaFormat_getInt32(selectedFmt, "pcm-encoding", &pcmEnc);
    AMediaFormat_delete(selectedFmt);

    if (sr <= 0) sr = 44100;
    if (ch <= 0) ch = 2;

    // Also inspect initial output format from codec if already resolved
    if (AMediaFormat* outFmt = AMediaCodec_getOutputFormat(codec)) {
        int32_t outSr = 0, outCh = 0, outEnc = 0;
        if (AMediaFormat_getInt32(outFmt, AMEDIAFORMAT_KEY_SAMPLE_RATE, &outSr) && outSr > 0) sr = outSr;
        if (AMediaFormat_getInt32(outFmt, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &outCh) && outCh > 0) ch = outCh;
        if (AMediaFormat_getInt32(outFmt, "pcm-encoding", &outEnc) && outEnc > 0) pcmEnc = outEnc;
        AMediaFormat_delete(outFmt);
    }

    // Parse ReplayGain from Ogg (Opus/Vorbis) or ID3v2 if present
    ReplayGainTagData mcRg{};
    if (FILE* rgFile = openDupFd(fd)) {
        parseOggReplayGain(rgFile, mcRg);
        if (!mcRg.hasTrackGain && !mcRg.hasAlbumGain) {
            parseId3v2ReplayGain(rgFile, mcRg);
        }
        std::fclose(rgFile);
    }
    finalizeReplayGain(mcRg);

    mMediaExtractor = extractor;
    mMediaCodec = codec;
    mMcPcmEncoding = pcmEnc;
    mMcExtractorEof = false;
    mMcCodecEof = false;
    mMcDropBeforeUs = -1;
    mMcStereoPos = 0;
    mMcStereoAvail = 0;

    mFormat = AudioFormatType::MEDIACODEC;
    mSourceSampleRate = static_cast<uint32_t>(sr);
    mSourceChannels = static_cast<uint32_t>(ch);
    mSourceTotalFrames = (durUs > 0)
        ? static_cast<uint64_t>((static_cast<double>(durUs) * static_cast<double>(sr)) / 1000000.0)
        : 0u;
    configureOutputFormat();
    LOGD("Opened MediaCodec (%s) via FD %d: SrcSR=%u->%u Ch=%u Frames=%llu PcmEnc=%d RG=%.2f",
         selectedMime ? selectedMime : "audio", fd, mSourceSampleRate, mSampleRate,
         mSourceChannels, (unsigned long long)mTotalFrames, mMcPcmEncoding, mReplayGainLinear);
    return true;
}

bool AudioDecoder::openFile(const std::string& filePath, uint32_t targetSampleRate) {
    if (filePath.rfind("/proc/self/fd/", 0) == 0) {
        int fd = -1;
        if (sscanf(filePath.c_str(), "/proc/self/fd/%d", &fd) == 1 && fd >= 0) {
            LOGD("openFile: detected proc fd path '%s' -> delegating to openFd(%d)", filePath.c_str(), fd);
            return openFd(fd, targetSampleRate);
        }
    }

    close();
    mForcedSampleRate = targetSampleRate;
    mCurrentFrame = 0;

    // 1. Try FLAC first (magic: fLaC)
    mPendingFlacRg = ReplayGainTagData{};
    mFlac = drflac_open_file_with_metadata(filePath.c_str(), flacMetaCallback, this, nullptr);
    if (mFlac) {
        finalizeReplayGain(mPendingFlacRg);
        mFormat = AudioFormatType::FLAC;
        mSourceSampleRate = mFlac->sampleRate;
        mSourceChannels = mFlac->channels;
        mSourceTotalFrames = mFlac->totalPCMFrameCount;
        configureOutputFormat();
        LOGD("Opened FLAC: %s (SrcSR: %u->%u, SrcCh: %u->%u, Frames: %llu, RG=%.2f)",
             filePath.c_str(), mSourceSampleRate, mSampleRate, mSourceChannels, mChannels, (unsigned long long)mTotalFrames, mReplayGainLinear);
        return true;
    }

    // 2. Try WAV / AIFF (dr_wav handles RIFF WAVE & FORM AIFF/AIFC)
    if (drwav_init_file(mWav, filePath.c_str(), nullptr)) {
        mHasReplayGain = false;
        mReplayGainLinear = 1.0f;
        mFormat = (mWav->container == drwav_container_aiff) ? AudioFormatType::AIFF : AudioFormatType::WAV;
        mSourceSampleRate = mWav->sampleRate;
        mSourceChannels = mWav->channels;
        mSourceTotalFrames = mWav->totalPCMFrameCount;
        configureOutputFormat();
        LOGD("Opened %s: %s (SrcSR: %u->%u, SrcCh: %u->%u, Frames: %llu)",
             (mFormat == AudioFormatType::AIFF ? "AIFF" : "WAV"),
             filePath.c_str(), mSourceSampleRate, mSampleRate, mSourceChannels, mChannels, (unsigned long long)mTotalFrames);
        return true;
    }

    // 3. Try ALAC (MP4/M4A 'alac' lossless)
    if (FILE* alacFile = std::fopen(filePath.c_str(), "rb")) {
        if (mAlac.open(alacFile)) {
            mOwnedFile = alacFile;
            const auto& rg = mAlac.getReplayGain();
            ReplayGainTagData rgData{};
            rgData.hasTrackGain = rg.hasTrackGain;
            rgData.trackGainDb = rg.trackGainDb;
            rgData.hasTrackPeak = rg.hasTrackPeak;
            rgData.trackPeak = rg.trackPeak;
            rgData.hasAlbumGain = rg.hasAlbumGain;
            rgData.albumGainDb = rg.albumGainDb;
            rgData.hasAlbumPeak = rg.hasAlbumPeak;
            rgData.albumPeak = rg.albumPeak;
            finalizeReplayGain(rgData);

            mFormat = AudioFormatType::ALAC;
            mSourceSampleRate = mAlac.getSampleRate();
            mSourceChannels = mAlac.getChannels();
            mSourceTotalFrames = mAlac.getTotalFrames();
            configureOutputFormat();
            LOGD("Opened ALAC: %s (SrcSR: %u->%u, Ch: %u, Bits: %u, Frames: %llu)",
                 filePath.c_str(), mSourceSampleRate, mSampleRate, mSourceChannels, mAlac.getBitDepth(), (unsigned long long)mTotalFrames);
            return true;
        }
        std::fclose(alacFile);
    }

    // 4. Try MP3 (skip if file signature is Ogg, MP4, AIFF, CAF, or ADTS AAC)
    FILE* mp3File = std::fopen(filePath.c_str(), "rb");
    if (mp3File) {
        if (!hasNonMp3ContainerSignature(mp3File)) {
            ReplayGainTagData mp3Rg{};
            parseId3v2ReplayGain(mp3File, mp3Rg);
            mMp3Io.read = mp3ReadCallback;
            mMp3Io.read_data = mp3File;
            mMp3Io.seek = mp3SeekCallback;
            mMp3Io.seek_data = mp3File;

            int mp3Flags = MP3D_SEEK_TO_SAMPLE | MP3D_ALLOW_MONO_STEREO_TRANSITION;
            if (mp3dec_ex_open_cb(mMp3, &mMp3Io, mp3Flags) == 0 && mMp3->info.hz > 0 && mMp3->info.channels > 0) {
                mOwnedFile = mp3File;
                finalizeReplayGain(mp3Rg);
                mFormat = AudioFormatType::MP3;
                mSourceSampleRate = static_cast<uint32_t>(mMp3->info.hz);
                mSourceChannels = static_cast<uint32_t>(mMp3->info.channels);
                mSourceTotalFrames = mMp3->samples / (mSourceChannels > 0 ? mSourceChannels : 1);
                configureOutputFormat();
                LOGD("Opened MP3: %s (SrcSR: %u->%u, SrcCh: %u->%u, Frames: %llu, RG=%.2f)",
                     filePath.c_str(), mSourceSampleRate, mSampleRate, mSourceChannels, mChannels, (unsigned long long)mTotalFrames, mReplayGainLinear);
                return true;
            }
            mp3dec_ex_close(mMp3);
        }
        std::fclose(mp3File);
    }

    // 5. Fallback to Android NDK MediaExtractor + MediaCodec (AAC/M4A, Ogg Vorbis, Opus, etc.)
    int rawFd = ::open(filePath.c_str(), O_RDONLY);
    if (rawFd >= 0) {
        bool ok = openMediaCodecFd(rawFd);
        ::close(rawFd);
        if (ok) return true;
    }

    LOGE("Failed to open file: %s", filePath.c_str());
    return false;
}

FILE* AudioDecoder::openDupFd(int fd) {
    int dupFd = dup(fd);
    if (dupFd < 0) return nullptr;
    if (lseek(dupFd, 0, SEEK_SET) < 0) {
        ::close(dupFd);
        return nullptr;
    }
    FILE* file = fdopen(dupFd, "rb");
    if (!file) {
        ::close(dupFd);
        return nullptr;
    }
    return file;
}

bool AudioDecoder::openFd(int fd, uint32_t targetSampleRate) {
    close();
    mForcedSampleRate = targetSampleRate;
    mCurrentFrame = 0;

    if (fd < 0) {
        LOGE("openFd: Invalid fd %d", fd);
        return false;
    }

    // 1. Try FLAC first
    if (FILE* flacFile = openDupFd(fd)) {
        mPendingFlacRg = ReplayGainTagData{};
        mOwnedFile = flacFile;
        mFlac = drflac_open_with_metadata(flacIoRead, flacIoSeek, flacIoTell, flacMetaCallback, this, nullptr);
        if (mFlac) {
            finalizeReplayGain(mPendingFlacRg);
            mFormat = AudioFormatType::FLAC;
            mSourceSampleRate = mFlac->sampleRate;
            mSourceChannels = mFlac->channels;
            mSourceTotalFrames = mFlac->totalPCMFrameCount;
            configureOutputFormat();
            LOGD("Opened FLAC via FD %d: SrcSR: %u->%u, SrcCh: %u->%u, Frames: %llu, RG=%.2f",
                 fd, mSourceSampleRate, mSampleRate, mSourceChannels, mChannels, (unsigned long long)mTotalFrames, mReplayGainLinear);
            return true;
        }
        mOwnedFile = nullptr;
        std::fclose(flacFile);
    }

    // 2. Try WAV / AIFF second
    if (FILE* wavFile = openDupFd(fd)) {
        if (drwav_init(mWav, drwav__on_read_stdio, drwav__on_seek_stdio, drwav__on_tell_stdio, (void*)wavFile, nullptr)) {
            mOwnedFile = wavFile;
            mHasReplayGain = false;
            mReplayGainLinear = 1.0f;
            mFormat = (mWav->container == drwav_container_aiff) ? AudioFormatType::AIFF : AudioFormatType::WAV;
            mSourceSampleRate = mWav->sampleRate;
            mSourceChannels = mWav->channels;
            mSourceTotalFrames = mWav->totalPCMFrameCount;
            configureOutputFormat();
            LOGD("Opened %s via FD %d: SrcSR: %u->%u, SrcCh: %u->%u, Frames: %llu",
                 (mFormat == AudioFormatType::AIFF ? "AIFF" : "WAV"),
                 fd, mSourceSampleRate, mSampleRate, mSourceChannels, mChannels, (unsigned long long)mTotalFrames);
            return true;
        }
        std::fclose(wavFile);
    }

    // 3. Try ALAC (bit-perfect MP4/M4A ALAC)
    if (FILE* alacFile = openDupFd(fd)) {
        if (mAlac.open(alacFile)) {
            mOwnedFile = alacFile;
            const auto& rg = mAlac.getReplayGain();
            ReplayGainTagData rgData{};
            rgData.hasTrackGain = rg.hasTrackGain;
            rgData.trackGainDb = rg.trackGainDb;
            rgData.hasTrackPeak = rg.hasTrackPeak;
            rgData.trackPeak = rg.trackPeak;
            rgData.hasAlbumGain = rg.hasAlbumGain;
            rgData.albumGainDb = rg.albumGainDb;
            rgData.hasAlbumPeak = rg.hasAlbumPeak;
            rgData.albumPeak = rg.albumPeak;
            finalizeReplayGain(rgData);

            mFormat = AudioFormatType::ALAC;
            mSourceSampleRate = mAlac.getSampleRate();
            mSourceChannels = mAlac.getChannels();
            mSourceTotalFrames = mAlac.getTotalFrames();
            configureOutputFormat();
            LOGD("Opened ALAC via FD %d: SrcSR: %u->%u, Ch: %u, Bits: %u, Frames: %llu",
                 fd, mSourceSampleRate, mSampleRate, mSourceChannels, mAlac.getBitDepth(), (unsigned long long)mTotalFrames);
            return true;
        }
        std::fclose(alacFile);
    }

    // 4. Try MP3 (skip if file has Ogg/MP4/AIFF/CAF/ADTS-AAC container header)
    if (FILE* mp3File = openDupFd(fd)) {
        if (!hasNonMp3ContainerSignature(mp3File)) {
            ReplayGainTagData mp3Rg{};
            parseId3v2ReplayGain(mp3File, mp3Rg);
            mMp3Io.read = mp3ReadCallback;
            mMp3Io.read_data = mp3File;
            mMp3Io.seek = mp3SeekCallback;
            mMp3Io.seek_data = mp3File;

            int mp3Flags = MP3D_SEEK_TO_SAMPLE | MP3D_ALLOW_MONO_STEREO_TRANSITION;
            if (mp3dec_ex_open_cb(mMp3, &mMp3Io, mp3Flags) == 0 &&
                mMp3->info.hz > 0 && mMp3->info.channels > 0) {
                mOwnedFile = mp3File;
                finalizeReplayGain(mp3Rg);
                mFormat = AudioFormatType::MP3;
                mSourceSampleRate = static_cast<uint32_t>(mMp3->info.hz);
                mSourceChannels = static_cast<uint32_t>(mMp3->info.channels);
                mSourceTotalFrames = mMp3->samples / (mSourceChannels > 0 ? mSourceChannels : 1);
                configureOutputFormat();
                LOGD("Opened MP3 via FD %d: SrcSR: %u->%u, SrcCh: %u->%u, Frames: %llu, RG=%.2f",
                     fd, mSourceSampleRate, mSampleRate, mSourceChannels, mChannels, (unsigned long long)mTotalFrames, mReplayGainLinear);
                return true;
            }
            mp3dec_ex_close(mMp3);
        }
        std::fclose(mp3File);
    }

    // 5. Try Android NDK MediaExtractor + MediaCodec (AAC/M4A, Ogg Vorbis, Opus)
    if (openMediaCodecFd(fd)) {
        return true;
    }

    LOGE("Failed to open FD %d", fd);
    return false;
}

void AudioDecoder::close() {
    if (mFormat == AudioFormatType::FLAC && mFlac) {
        drflac_close(mFlac);
        mFlac = nullptr;
    } else if (mFormat == AudioFormatType::WAV || mFormat == AudioFormatType::AIFF) {
        drwav_uninit(mWav);
    } else if (mFormat == AudioFormatType::ALAC) {
        mAlac.close();
    } else if (mFormat == AudioFormatType::MP3) {
        mp3dec_ex_close(mMp3);
    } else if (mFormat == AudioFormatType::MEDIACODEC) {
        if (mMediaCodec) {
            AMediaCodec_stop(mMediaCodec);
            AMediaCodec_delete(mMediaCodec);
            mMediaCodec = nullptr;
        }
        if (mMediaExtractor) {
            AMediaExtractor_delete(mMediaExtractor);
            mMediaExtractor = nullptr;
        }
    }
    mMcPcmEncoding = 2;
    mMcExtractorEof = false;
    mMcCodecEof = false;
    mMcDropBeforeUs = -1;
    mMcStereoBuf.clear();
    if (mOwnedFile) {
        std::fclose(mOwnedFile);
        mOwnedFile = nullptr;
    }
    mFormat = AudioFormatType::UNKNOWN;
    mHasReplayGain = false;
    mReplayGainLinear = 1.0f;
    mPendingFlacRg = ReplayGainTagData{};
    mSourceSampleRate = 0;
    mSourceChannels = 0;
    mSourceTotalFrames = 0;
    mSampleRate = 0;
    mForcedSampleRate = 0;
    mTotalFrames = 0;
    mCurrentFrame = 0;
    resetResamplerState();
}

bool AudioDecoder::reconfigureOutputSampleRate(uint32_t targetSampleRate) {
    if (mFormat == AudioFormatType::UNKNOWN || mSourceSampleRate == 0 || targetSampleRate == 0) {
        return false;
    }
    uint32_t oldSr = (mSampleRate > 0) ? mSampleRate : mSourceSampleRate;
    uint64_t prevOutFrame = mCurrentFrame;
    mForcedSampleRate = targetSampleRate;
    configureOutputFormat();
    int64_t scaledFrame = static_cast<int64_t>(
        (static_cast<double>(prevOutFrame) * static_cast<double>(mSampleRate)) /
        static_cast<double>(oldSr)
    );
    seek(scaledFrame);
    return true;
}

void AudioDecoder::downmixToStereo(const float* src, float* dst, uint64_t frames, uint32_t srcCh) {
    const float invSqrt2 = 0.70710678f;
    if (srcCh == 3) {
        for (uint64_t i = 0; i < frames; ++i) {
            const float* f = src + i * 3;
            dst[2 * i]     = 0.707f * (f[0] + invSqrt2 * f[2]);
            dst[2 * i + 1] = 0.707f * (f[1] + invSqrt2 * f[2]);
        }
    } else if (srcCh == 4) {
        for (uint64_t i = 0; i < frames; ++i) {
            const float* f = src + i * 4;
            dst[2 * i]     = 0.585f * (f[0] + invSqrt2 * f[2]);
            dst[2 * i + 1] = 0.585f * (f[1] + invSqrt2 * f[3]);
        }
    } else if (srcCh == 5) {
        for (uint64_t i = 0; i < frames; ++i) {
            const float* f = src + i * 5;
            dst[2 * i]     = 0.45f * (f[0] + invSqrt2 * f[2] + invSqrt2 * f[3]);
            dst[2 * i + 1] = 0.45f * (f[1] + invSqrt2 * f[2] + invSqrt2 * f[4]);
        }
    } else {
        // 5.1 / 7.1 surround layout: FL(0), FR(1), FC(2), LFE(3), BL(4), BR(5), [SL(6), SR(7)]
        for (uint64_t i = 0; i < frames; ++i) {
            const float* f = src + i * srcCh;
            float l = f[0] + invSqrt2 * f[2] + 0.35f * f[3] + invSqrt2 * f[4];
            float r = f[1] + invSqrt2 * f[2] + 0.35f * f[3] + invSqrt2 * f[5];
            if (srcCh >= 8) {
                l += invSqrt2 * f[6];
                r += invSqrt2 * f[7];
            }
            dst[2 * i]     = 0.42f * l;
            dst[2 * i + 1] = 0.42f * r;
        }
    }
}

int64_t AudioDecoder::seek(int64_t targetFrame) {
    if (mFormat == AudioFormatType::UNKNOWN || mSampleRate == 0 || mSourceSampleRate == 0) return 0;
    
    if (targetFrame < 0) targetFrame = 0;
    if (mTotalFrames > 0 && targetFrame >= (int64_t)mTotalFrames) {
        targetFrame = (int64_t)mTotalFrames - 1;
    }

    int64_t sourceTargetFrame = targetFrame;
    if (mSampleRate != mSourceSampleRate) {
        sourceTargetFrame = static_cast<int64_t>(
            (static_cast<double>(targetFrame) * static_cast<double>(mSourceSampleRate)) /
            static_cast<double>(mSampleRate)
        );
    }
    if (sourceTargetFrame < 0) sourceTargetFrame = 0;
    if (mSourceTotalFrames > 0 && sourceTargetFrame >= (int64_t)mSourceTotalFrames) {
        sourceTargetFrame = (int64_t)mSourceTotalFrames - 1;
    }

    resetResamplerState();

    if (mFormat == AudioFormatType::FLAC) {
        bool success = drflac_seek_to_pcm_frame(mFlac, (drflac_uint64)sourceTargetFrame);
        if (success) {
            mCurrentFrame = (uint64_t)targetFrame;
            return targetFrame;
        }
    } else if (mFormat == AudioFormatType::WAV || mFormat == AudioFormatType::AIFF) {
        drwav_bool32 ok = drwav_seek_to_pcm_frame(mWav, (drwav_uint64)sourceTargetFrame);
        if (ok) {
            mCurrentFrame = (uint64_t)targetFrame;
            return targetFrame;
        }
    } else if (mFormat == AudioFormatType::ALAC) {
        if (mAlac.seek((uint64_t)sourceTargetFrame)) {
            mCurrentFrame = (uint64_t)targetFrame;
            return targetFrame;
        }
    } else if (mFormat == AudioFormatType::MP3) {
        uint64_t srcCh = (mSourceChannels > 0) ? mSourceChannels : 1;
        if (mp3dec_ex_seek(mMp3, (uint64_t)sourceTargetFrame * srcCh) == 0) {
            mCurrentFrame = (uint64_t)targetFrame;
            return targetFrame;
        }
    } else if (mFormat == AudioFormatType::MEDIACODEC && mMediaExtractor && mMediaCodec) {
        int64_t targetUs = static_cast<int64_t>(
            (static_cast<double>(sourceTargetFrame) * 1000000.0) /
            static_cast<double>(mSourceSampleRate)
        );
        AMediaExtractor_seekTo(mMediaExtractor, targetUs, AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC);
        AMediaCodec_flush(mMediaCodec);
        mMcExtractorEof = false;
        mMcCodecEof = false;
        mMcDropBeforeUs = targetUs;
        mMcStereoPos = 0;
        mMcStereoAvail = 0;
        mCurrentFrame = (uint64_t)targetFrame;
        return targetFrame;
    }
    
    return -1;
}

uint64_t AudioDecoder::decodeSourceStereoFrames(float* pStereoOut, uint64_t maxSourceFrames) {
    if (mFormat == AudioFormatType::UNKNOWN || maxSourceFrames == 0) return 0;

    if (mFormat == AudioFormatType::FLAC) {
        if (mSourceChannels == 2) {
            return drflac_read_pcm_frames_f32(mFlac, maxSourceFrames, pStereoOut);
        } else if (mSourceChannels == 1) {
            uint64_t decoded = drflac_read_pcm_frames_f32(mFlac, maxSourceFrames, pStereoOut);
            for (int64_t i = (int64_t)decoded - 1; i >= 0; --i) {
                float s = pStereoOut[i];
                pStereoOut[2 * i]     = s;
                pStereoOut[2 * i + 1] = s;
            }
            return decoded;
        } else {
            size_t needed = static_cast<size_t>(maxSourceFrames * mSourceChannels);
            if (mMultiChScratchBuf.size() < needed) {
                mMultiChScratchBuf.resize(needed);
            }
            uint64_t decoded = drflac_read_pcm_frames_f32(mFlac, maxSourceFrames, mMultiChScratchBuf.data());
            downmixToStereo(mMultiChScratchBuf.data(), pStereoOut, decoded, mSourceChannels);
            return decoded;
        }
    } else if (mFormat == AudioFormatType::WAV || mFormat == AudioFormatType::AIFF) {
        if (mSourceChannels == 2) {
            return drwav_read_pcm_frames_f32(mWav, maxSourceFrames, pStereoOut);
        } else if (mSourceChannels == 1) {
            uint64_t decoded = drwav_read_pcm_frames_f32(mWav, maxSourceFrames, pStereoOut);
            for (int64_t i = (int64_t)decoded - 1; i >= 0; --i) {
                float s = pStereoOut[i];
                pStereoOut[2 * i]     = s;
                pStereoOut[2 * i + 1] = s;
            }
            return decoded;
        } else {
            size_t needed = static_cast<size_t>(maxSourceFrames * mSourceChannels);
            if (mMultiChScratchBuf.size() < needed) {
                mMultiChScratchBuf.resize(needed);
            }
            uint64_t decoded = drwav_read_pcm_frames_f32(mWav, maxSourceFrames, mMultiChScratchBuf.data());
            downmixToStereo(mMultiChScratchBuf.data(), pStereoOut, decoded, mSourceChannels);
            return decoded;
        }
    } else if (mFormat == AudioFormatType::ALAC) {
        return mAlac.readStereoFrames(pStereoOut, maxSourceFrames);
    } else if (mFormat == AudioFormatType::MEDIACODEC && mMediaExtractor && mMediaCodec) {
        uint64_t framesWritten = 0;
        int emptyTries = 0;
        while (framesWritten < maxSourceFrames) {
            if (mMcStereoPos < mMcStereoAvail) {
                size_t availFrames = mMcStereoAvail - mMcStereoPos;
                size_t toCopy = std::min<size_t>(availFrames, static_cast<size_t>(maxSourceFrames - framesWritten));
                std::memcpy(
                    pStereoOut + framesWritten * 2,
                    mMcStereoBuf.data() + mMcStereoPos * 2,
                    toCopy * 2 * sizeof(float)
                );
                mMcStereoPos += toCopy;
                framesWritten += toCopy;
                continue;
            }

            if (mMcCodecEof) break;

            if (!mMcExtractorEof) {
                ssize_t inIdx = AMediaCodec_dequeueInputBuffer(mMediaCodec, 2000);
                if (inIdx >= 0) {
                    size_t inCap = 0;
                    uint8_t* inBuf = AMediaCodec_getInputBuffer(mMediaCodec, static_cast<size_t>(inIdx), &inCap);
                    ssize_t sampleSize = inBuf ? AMediaExtractor_readSampleData(mMediaExtractor, inBuf, inCap) : -1;
                    if (sampleSize <= 0) {
                        AMediaCodec_queueInputBuffer(
                            mMediaCodec, static_cast<size_t>(inIdx), 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM
                        );
                        mMcExtractorEof = true;
                    } else {
                        int64_t ptsUs = AMediaExtractor_getSampleTime(mMediaExtractor);
                        AMediaCodec_queueInputBuffer(
                            mMediaCodec,
                            static_cast<size_t>(inIdx),
                            0,
                            static_cast<size_t>(sampleSize),
                            static_cast<uint64_t>(ptsUs > 0 ? ptsUs : 0),
                            0
                        );
                        AMediaExtractor_advance(mMediaExtractor);
                    }
                }
            }

            AMediaCodecBufferInfo outInfo{};
            ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(mMediaCodec, &outInfo, 3000);
            if (outIdx >= 0) {
                emptyTries = 0;
                if (outInfo.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
                    mMcCodecEof = true;
                }
                if (outInfo.size > 0) {
                    size_t outCap = 0;
                    const uint8_t* rawPtr = AMediaCodec_getOutputBuffer(mMediaCodec, static_cast<size_t>(outIdx), &outCap);
                    if (rawPtr && static_cast<size_t>(outInfo.offset + outInfo.size) <= outCap) {
                        const uint8_t* pcm = rawPtr + outInfo.offset;
                        uint32_t ch = (mSourceChannels > 0) ? mSourceChannels : 2u;
                        size_t bytesPerSample = 2;
                        if (mMcPcmEncoding == 4 || mMcPcmEncoding == 22) bytesPerSample = 4; // FLOAT or PCM_32BIT
                        else if (mMcPcmEncoding == 21) bytesPerSample = 3; // PCM_24BIT_PACKED
                        else if (mMcPcmEncoding == 3) bytesPerSample = 1; // PCM_8BIT

                        size_t totalFrames = static_cast<size_t>(outInfo.size) / (bytesPerSample * ch);
                        size_t dropFrames = 0;
                        if (mMcDropBeforeUs >= 0 && mSourceSampleRate > 0) {
                            int64_t deltaUs = mMcDropBeforeUs - outInfo.presentationTimeUs;
                            if (deltaUs <= 0) {
                                mMcDropBeforeUs = -1;
                            } else {
                                size_t skip = static_cast<size_t>((static_cast<double>(deltaUs) * mSourceSampleRate) / 1000000.0);
                                if (skip >= totalFrames) {
                                    dropFrames = totalFrames;
                                } else {
                                    dropFrames = skip;
                                    mMcDropBeforeUs = -1;
                                }
                            }
                        }

                        if (dropFrames < totalFrames) {
                            size_t keepFrames = totalFrames - dropFrames;
                            if (mMultiChScratchBuf.size() < keepFrames * ch) {
                                mMultiChScratchBuf.resize(keepFrames * ch);
                            }
                            if (mMcStereoBuf.size() < keepFrames * 2) {
                                mMcStereoBuf.resize(keepFrames * 2);
                            }

                            size_t startSampleIdx = dropFrames * ch;
                            size_t totalSamples = keepFrames * ch;
                            float* floatDst = (ch == 2) ? mMcStereoBuf.data() : mMultiChScratchBuf.data();

                            if (mMcPcmEncoding == 4) {
                                const float* fsrc = reinterpret_cast<const float*>(pcm) + startSampleIdx;
                                std::memcpy(floatDst, fsrc, totalSamples * sizeof(float));
                            } else if (mMcPcmEncoding == 22) {
                                const int32_t* isrc = reinterpret_cast<const int32_t*>(pcm) + startSampleIdx;
                                constexpr float inv24 = 1.0f / 2147483648.0f;
                                for (size_t i = 0; i < totalSamples; ++i) floatDst[i] = static_cast<float>(isrc[i]) * inv24;
                            } else if (mMcPcmEncoding == 21) {
                                const uint8_t* bsrc = pcm + startSampleIdx * 3;
                                constexpr float inv24 = 1.0f / 8388608.0f;
                                for (size_t i = 0; i < totalSamples; ++i) {
                                    int32_t v = static_cast<int32_t>(bsrc[i * 3]) |
                                                (static_cast<int32_t>(bsrc[i * 3 + 1]) << 8) |
                                                (static_cast<int32_t>(static_cast<int8_t>(bsrc[i * 3 + 2])) << 16);
                                    floatDst[i] = static_cast<float>(v) * inv24;
                                }
                            } else if (mMcPcmEncoding == 3) {
                                const uint8_t* usrc = pcm + startSampleIdx;
                                for (size_t i = 0; i < totalSamples; ++i) floatDst[i] = (static_cast<float>(usrc[i]) - 128.0f) / 128.0f;
                            } else {
                                const int16_t* ssrc = reinterpret_cast<const int16_t*>(pcm) + startSampleIdx;
                                constexpr float inv16 = 1.0f / 32768.0f;
                                for (size_t i = 0; i < totalSamples; ++i) floatDst[i] = static_cast<float>(ssrc[i]) * inv16;
                            }

                            if (ch == 1) {
                                for (size_t i = 0; i < keepFrames; ++i) {
                                    float s = floatDst[i];
                                    mMcStereoBuf[2 * i] = s;
                                    mMcStereoBuf[2 * i + 1] = s;
                                }
                            } else if (ch > 2) {
                                downmixToStereo(floatDst, mMcStereoBuf.data(), keepFrames, ch);
                            }

                            mMcStereoPos = 0;
                            mMcStereoAvail = keepFrames;
                        }
                    }
                }
                AMediaCodec_releaseOutputBuffer(mMediaCodec, static_cast<size_t>(outIdx), false);
            } else if (outIdx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                emptyTries = 0;
                if (AMediaFormat* outFmt = AMediaCodec_getOutputFormat(mMediaCodec)) {
                    int32_t outSr = 0, outCh = 0, outEnc = 0;
                    if (AMediaFormat_getInt32(outFmt, AMEDIAFORMAT_KEY_SAMPLE_RATE, &outSr) && outSr > 0) {
                        mSourceSampleRate = static_cast<uint32_t>(outSr);
                    }
                    if (AMediaFormat_getInt32(outFmt, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &outCh) && outCh > 0) {
                        mSourceChannels = static_cast<uint32_t>(outCh);
                    }
                    if (AMediaFormat_getInt32(outFmt, "pcm-encoding", &outEnc) && outEnc > 0) {
                        mMcPcmEncoding = outEnc;
                    }
                    AMediaFormat_delete(outFmt);
                }
            } else {
                if (++emptyTries > 80) break;
            }
        }
        return framesWritten;
    } else if (mFormat == AudioFormatType::MP3) {
        // Read MP3 frame-by-frame and normalize each frame's actual channel count (1ch Mono or 2ch Stereo) to 2ch Stereo
        uint64_t framesWritten = 0;
        while (framesWritten < maxSourceFrames) {
            if (mMp3FrameStereoPos < mMp3FrameStereoAvail) {
                size_t availFrames = mMp3FrameStereoAvail - mMp3FrameStereoPos;
                size_t toCopy = std::min<size_t>(availFrames, static_cast<size_t>(maxSourceFrames - framesWritten));
                std::memcpy(
                    pStereoOut + framesWritten * 2,
                    mMp3FrameStereoBuf.data() + mMp3FrameStereoPos * 2,
                    toCopy * 2 * sizeof(float)
                );
                mMp3FrameStereoPos += toCopy;
                framesWritten += toCopy;
                continue;
            }

            mp3d_sample_t* bufFrame = nullptr;
            mp3dec_frame_info_t frameInfo;
            std::memset(&frameInfo, 0, sizeof(frameInfo));
            size_t samplesRead = mp3dec_ex_read_frame(mMp3, &bufFrame, &frameInfo, MINIMP3_MAX_SAMPLES_PER_FRAME);
            if (samplesRead == 0 || bufFrame == nullptr) {
                break;
            }

            int frameCh = (frameInfo.channels > 0) ? frameInfo.channels : (int)mSourceChannels;
            if (frameCh <= 0) frameCh = 2;

            size_t decodedFrames = samplesRead / (size_t)frameCh;
            if (decodedFrames == 0) {
                break;
            }
            if (mMp3FrameStereoBuf.size() < decodedFrames * 2) {
                mMp3FrameStereoBuf.resize(decodedFrames * 2);
            }

            if (frameCh == 1) {
                for (size_t i = 0; i < decodedFrames; ++i) {
                    float s = bufFrame[i];
                    mMp3FrameStereoBuf[2 * i]     = s;
                    mMp3FrameStereoBuf[2 * i + 1] = s;
                }
            } else {
                for (size_t i = 0; i < decodedFrames; ++i) {
                    mMp3FrameStereoBuf[2 * i]     = bufFrame[i * frameCh];
                    mMp3FrameStereoBuf[2 * i + 1] = bufFrame[i * frameCh + 1];
                }
            }

            mMp3FrameStereoPos = 0;
            mMp3FrameStereoAvail = decodedFrames;
        }
        return framesWritten;
    }

    return 0;
}

uint64_t AudioDecoder::decodeFrames(float* pOutput, uint64_t maxFrames) {
    if (mFormat == AudioFormatType::UNKNOWN || maxFrames == 0) return 0;

    // Fast path when no resampling is needed (e.g., 44.1kHz, 48kHz, 96kHz, 192kHz)
    if (mSampleRate == mSourceSampleRate || mSourceSampleRate == 0) {
        uint64_t decoded = decodeSourceStereoFrames(pOutput, maxFrames);
        mCurrentFrame += decoded;
        return decoded;
    }

    // Windowed-sinc (Lanczos-3, 6-tap) resampler with anti-aliasing cutoff for both upsampling & downsampling
    const double step = static_cast<double>(mSourceSampleRate) / static_cast<double>(mSampleRate);
    const double cutoff = std::min(1.0, static_cast<double>(mSampleRate) / static_cast<double>(mSourceSampleRate));
    constexpr double PI = 3.14159265358979323846;
    auto sinc = [PI](double x) -> double {
        if (std::fabs(x) < 1e-6) return 1.0;
        double pix = PI * x;
        return std::sin(pix) / pix;
    };

    auto pullNextSourceFrame = [this](float outFrame[2]) -> bool {
        if (mResampleSourceEof) return false;
        if (mResampleSrcPos >= mResampleSrcAvail) {
            mResampleSrcPos = 0;
            mResampleSrcAvail = static_cast<size_t>(
                decodeSourceStereoFrames(mResampleSrcBuf.data(), RESAMPLE_CHUNK_FRAMES)
            );
            if (mResampleSrcAvail == 0) {
                mResampleSourceEof = true;
                return false;
            }
        }
        outFrame[0] = mResampleSrcBuf[mResampleSrcPos * 2];
        outFrame[1] = mResampleSrcBuf[mResampleSrcPos * 2 + 1];
        mResampleSrcPos++;
        return true;
    };

    if (!mResamplePrimed) {
        float first[2] = {0.0f, 0.0f};
        if (!pullNextSourceFrame(first)) {
            return 0;
        }
        mResampleRing[0][0] = first[0]; mResampleRing[0][1] = first[1];
        mResampleRing[1][0] = first[0]; mResampleRing[1][1] = first[1];
        mResampleRing[2][0] = first[0]; mResampleRing[2][1] = first[1];
        mResampleValidCount = 1; // Center frame at ring[2] is valid
        for (int k = 3; k < 6; ++k) {
            float nxt[2] = {0.0f, 0.0f};
            if (pullNextSourceFrame(nxt)) {
                mResampleRing[k][0] = nxt[0];
                mResampleRing[k][1] = nxt[1];
                mResampleValidCount++;
            } else {
                mResampleRing[k][0] = 0.0f;
                mResampleRing[k][1] = 0.0f;
            }
        }
        mResampleFrac = 0.0;
        mResamplePrimed = true;
    }

    uint64_t outFrames = 0;
    while (outFrames < maxFrames) {
        while (mResampleFrac >= 1.0) {
            if (mResampleValidCount <= 0) {
                mCurrentFrame += outFrames;
                return outFrames;
            }
            for (int k = 0; k < 5; ++k) {
                mResampleRing[k][0] = mResampleRing[k + 1][0];
                mResampleRing[k][1] = mResampleRing[k + 1][1];
            }
            float nxt[2] = {0.0f, 0.0f};
            if (pullNextSourceFrame(nxt)) {
                mResampleRing[5][0] = nxt[0];
                mResampleRing[5][1] = nxt[1];
            } else {
                mResampleRing[5][0] = 0.0f;
                mResampleRing[5][1] = 0.0f;
                mResampleValidCount--;
            }
            mResampleFrac -= 1.0;
        }

        if (mResampleValidCount <= 0) {
            break;
        }

        double accL = 0.0;
        double accR = 0.0;
        double sumW = 0.0;
        for (int idx = 0; idx < 6; ++idx) {
            int offset = idx - 2; // -2, -1, 0, +1, +2, +3
            double x = static_cast<double>(offset) - mResampleFrac;
            if (std::fabs(x) < 3.0) {
                double w = cutoff * sinc(x * cutoff) * sinc(x / 3.0);
                accL += w * mResampleRing[idx][0];
                accR += w * mResampleRing[idx][1];
                sumW += w;
            }
        }
        float invSum = (sumW > 1e-6) ? static_cast<float>(1.0 / sumW) : 1.0f;
        pOutput[outFrames * 2]     = static_cast<float>(accL) * invSum;
        pOutput[outFrames * 2 + 1] = static_cast<float>(accR) * invSum;

        mResampleFrac += step;
        outFrames++;
    }

    mCurrentFrame += outFrames;
    return outFrames;
}

uint32_t AudioDecoder::getSampleRate() const { return mSampleRate; }
uint32_t AudioDecoder::getChannels() const { return mChannels; }
uint64_t AudioDecoder::getTotalFrames() const { return mTotalFrames; }
uint64_t AudioDecoder::getCurrentFrame() const { return mCurrentFrame; }
