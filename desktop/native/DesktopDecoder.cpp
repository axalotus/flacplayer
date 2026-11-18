#define DR_FLAC_IMPLEMENTATION
#define MINIMP3_IMPLEMENTATION
#define MINIMP3_FLOAT_OUTPUT
#define MINIMP3_ONLY_MP3
#define MINIMP3_ALLOW_MONO_STEREO_TRANSITION
#define DR_WAV_IMPLEMENTATION

#include "DesktopDecoder.h"
#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdlib>
#include <cstring>

#ifdef _WIN32
#include <windows.h>
#endif

#ifdef HAVE_FFMPEG
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/dict.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
}
#endif

static constexpr size_t RESAMPLE_CHUNK_FRAMES = 512;

static int asciiStrCaseCmp(const char* a, const char* b) {
    if (!a || !b) return (a == b) ? 0 : (a ? 1 : -1);
    while (*a && *b) {
        int ca = std::tolower(static_cast<unsigned char>(*a));
        int cb = std::tolower(static_cast<unsigned char>(*b));
        if (ca != cb) return ca - cb;
        ++a;
        ++b;
    }
    return std::tolower(static_cast<unsigned char>(*a)) - std::tolower(static_cast<unsigned char>(*b));
}

FILE* DesktopDecoder::openUtf8File(const std::string& filePath) {
#ifdef _WIN32
    if (filePath.empty()) return nullptr;
    int wlen = MultiByteToWideChar(CP_UTF8, 0, filePath.c_str(), -1, nullptr, 0);
    if (wlen <= 0) return nullptr;
    std::wstring wpath(static_cast<size_t>(wlen), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, filePath.c_str(), -1, wpath.data(), wlen);
    return _wfopen(wpath.c_str(), L"rb");
#else
    return std::fopen(filePath.c_str(), "rb");
#endif
}

int DesktopDecoder::seek64(FILE* file, int64_t offset, int origin) {
    if (!file) return -1;
#ifdef _WIN32
    return _fseeki64(file, offset, origin);
#else
    return fseeko(file, static_cast<off_t>(offset), origin);
#endif
}

int64_t DesktopDecoder::tell64(FILE* file) {
    if (!file) return -1;
#ifdef _WIN32
    return static_cast<int64_t>(_ftelli64(file));
#else
    return static_cast<int64_t>(ftello(file));
#endif
}

static size_t mp3ReadCallback(void* buf, size_t size, void* userData) {
    FILE* f = static_cast<FILE*>(userData);
    if (!f || !buf || size == 0) return 0;
    return std::fread(buf, 1, size, f);
}

static int mp3SeekCallback(uint64_t position, void* userData) {
    FILE* f = static_cast<FILE*>(userData);
    if (!f) return -1;
#ifdef _WIN32
    return _fseeki64(f, static_cast<int64_t>(position), SEEK_SET);
#else
    return fseeko(f, static_cast<off_t>(position), SEEK_SET);
#endif
}

DesktopDecoder::DesktopDecoder() {
    mMp3 = new mp3dec_ex_t();
    std::memset(mMp3, 0, sizeof(mp3dec_ex_t));
    mWav = new drwav();
    std::memset(mWav, 0, sizeof(drwav));
    mMp3FrameStereoBuf.resize(MINIMP3_MAX_SAMPLES_PER_FRAME * 2);
    mResampleSrcBuf.resize(RESAMPLE_CHUNK_FRAMES * 2);
}

DesktopDecoder::~DesktopDecoder() {
    close();
    delete mMp3;
    delete mWav;
}

void DesktopDecoder::resetResamplerState() {
    mMp3FrameStereoPos = 0;
    mMp3FrameStereoAvail = 0;
    mAvStereoPos = 0;
    mAvStereoAvail = 0;
    mResampleSrcPos = 0;
    mResampleSrcAvail = 0;
    std::memset(mResampleRing, 0, sizeof(mResampleRing));
    mResampleValidCount = 0;
    mResampleSourceEof = false;
    mResampleFrac = 0.0;
    mResamplePrimed = false;
}

void DesktopDecoder::applyReplayGainTag(ReplayGainTagData& rg, const char* key, const char* val) {
    if (!key || !val || !*val) return;
    char* endPtr = nullptr;
    float parsed = std::strtof(val, &endPtr);
    if (endPtr == val || !std::isfinite(parsed)) return;

    if (asciiStrCaseCmp(key, "REPLAYGAIN_TRACK_GAIN") == 0) {
        rg.hasTrackGain = true;
        rg.trackGainDb = parsed;
    } else if (asciiStrCaseCmp(key, "REPLAYGAIN_TRACK_PEAK") == 0) {
        rg.hasTrackPeak = true;
        rg.trackPeak = parsed;
    } else if (asciiStrCaseCmp(key, "REPLAYGAIN_ALBUM_GAIN") == 0) {
        rg.hasAlbumGain = true;
        rg.albumGainDb = parsed;
    } else if (asciiStrCaseCmp(key, "REPLAYGAIN_ALBUM_PEAK") == 0) {
        rg.hasAlbumPeak = true;
        rg.albumPeak = parsed;
    } else if (asciiStrCaseCmp(key, "R128_TRACK_GAIN") == 0 && !rg.hasTrackGain) {
        rg.hasTrackGain = true;
        rg.trackGainDb = (parsed / 256.0f) + 5.0f;
    } else if (asciiStrCaseCmp(key, "R128_ALBUM_GAIN") == 0 && !rg.hasAlbumGain) {
        rg.hasAlbumGain = true;
        rg.albumGainDb = (parsed / 256.0f) + 5.0f;
    }
}

void DesktopDecoder::finalizeReplayGain(const ReplayGainTagData& rg) {
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

void DesktopDecoder::flacMetaCallback(void* pUserData, drflac_metadata* pMetadata) {
    if (!pUserData || !pMetadata) return;
    if (pMetadata->type != DRFLAC_METADATA_BLOCK_TYPE_VORBIS_COMMENT) return;

    auto* dec = static_cast<DesktopDecoder*>(pUserData);
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

void DesktopDecoder::parseId3v2ReplayGain(FILE* file, ReplayGainTagData& rg) {
    if (!file) return;
    seek64(file, 0, SEEK_SET);
    uint8_t hdr[10];
    if (std::fread(hdr, 1, 10, file) != 10) {
        seek64(file, 0, SEEK_SET);
        return;
    }
    if (hdr[0] != 'I' || hdr[1] != 'D' || hdr[2] != '3') {
        seek64(file, 0, SEEK_SET);
        return;
    }
    uint8_t majorVer = hdr[3];
    uint8_t flags = hdr[5];
    uint32_t tagSize = ((uint32_t)(hdr[6] & 0x7F) << 21) |
                       ((uint32_t)(hdr[7] & 0x7F) << 14) |
                       ((uint32_t)(hdr[8] & 0x7F) << 7) |
                       (uint32_t)(hdr[9] & 0x7F);
    if (majorVer < 3 || majorVer > 4 || tagSize == 0 || tagSize > 2u * 1024u * 1024u) {
        seek64(file, 0, SEEK_SET);
        return;
    }

    std::vector<uint8_t> tagBuf(tagSize);
    if (std::fread(tagBuf.data(), 1, tagSize, file) != tagSize) {
        seek64(file, 0, SEEK_SET);
        return;
    }
    seek64(file, 0, SEEK_SET);

    auto removeUnsync = [](const uint8_t* src, size_t len) {
        std::vector<uint8_t> out;
        out.reserve(len);
        for (size_t i = 0; i < len; ++i) {
            out.push_back(src[i]);
            if (src[i] == 0xFF && i + 1 < len && src[i + 1] == 0x00) {
                ++i;
            }
        }
        return out;
    };

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

size_t DesktopDecoder::flacIoRead(void* pUserData, void* bufferOut, size_t bytesToRead) {
    auto* dec = static_cast<DesktopDecoder*>(pUserData);
    return (dec && dec->mOwnedFile) ? std::fread(bufferOut, 1, bytesToRead, dec->mOwnedFile) : 0;
}

drflac_bool32 DesktopDecoder::flacIoSeek(void* pUserData, int offset, drflac_seek_origin origin) {
    auto* dec = static_cast<DesktopDecoder*>(pUserData);
    if (!dec || !dec->mOwnedFile) return DRFLAC_FALSE;
    int whence = (origin == DRFLAC_SEEK_CUR) ? SEEK_CUR : ((origin == DRFLAC_SEEK_END) ? SEEK_END : SEEK_SET);
    return seek64(dec->mOwnedFile, static_cast<int64_t>(offset), whence) == 0 ? DRFLAC_TRUE : DRFLAC_FALSE;
}

drflac_bool32 DesktopDecoder::flacIoTell(void* pUserData, drflac_int64* pCursor) {
    auto* dec = static_cast<DesktopDecoder*>(pUserData);
    if (!dec || !dec->mOwnedFile || !pCursor) return DRFLAC_FALSE;
    int64_t pos = tell64(dec->mOwnedFile);
    if (pos < 0) return DRFLAC_FALSE;
    *pCursor = static_cast<drflac_int64>(pos);
    return DRFLAC_TRUE;
}

size_t DesktopDecoder::wavIoRead(void* pUserData, void* pBufferOut, size_t bytesToRead) {
    auto* dec = static_cast<DesktopDecoder*>(pUserData);
    return (dec && dec->mOwnedFile) ? std::fread(pBufferOut, 1, bytesToRead, dec->mOwnedFile) : 0;
}

drwav_bool32 DesktopDecoder::wavIoSeek(void* pUserData, int offset, drwav_seek_origin origin) {
    auto* dec = static_cast<DesktopDecoder*>(pUserData);
    if (!dec || !dec->mOwnedFile) return DRWAV_FALSE;
    int whence = (origin == DRWAV_SEEK_CUR) ? SEEK_CUR : ((origin == DRWAV_SEEK_END) ? SEEK_END : SEEK_SET);
    return seek64(dec->mOwnedFile, static_cast<int64_t>(offset), whence) == 0 ? DRWAV_TRUE : DRWAV_FALSE;
}

drwav_bool32 DesktopDecoder::wavIoTell(void* pUserData, drwav_int64* pCursor) {
    auto* dec = static_cast<DesktopDecoder*>(pUserData);
    if (!dec || !dec->mOwnedFile || !pCursor) return DRWAV_FALSE;
    int64_t pos = tell64(dec->mOwnedFile);
    if (pos < 0) return DRWAV_FALSE;
    *pCursor = static_cast<drwav_int64>(pos);
    return DRWAV_TRUE;
}

void DesktopDecoder::configureOutputFormat() {
    resetResamplerState();
    mChannels = 2;

    if (mSourceSampleRate == 0) {
        mSampleRate = 44100;
        mTotalFrames = 0;
        return;
    }

    if (mForcedSampleRate > 0) {
        mSampleRate = mForcedSampleRate;
        mTotalFrames = static_cast<uint64_t>(
            (static_cast<double>(mSourceTotalFrames) * static_cast<double>(mSampleRate)) /
            static_cast<double>(mSourceSampleRate)
        );
    } else if (mSourceSampleRate < 44100) {
        mSampleRate = (mSourceSampleRate == 11025 || mSourceSampleRate == 22050) ? 44100 : 48000;
        mTotalFrames = static_cast<uint64_t>(
            (static_cast<double>(mSourceTotalFrames) * static_cast<double>(mSampleRate)) /
            static_cast<double>(mSourceSampleRate)
        );
    } else {
        mSampleRate = mSourceSampleRate;
        mTotalFrames = mSourceTotalFrames;
    }
}

bool DesktopDecoder::hasNonMp3ContainerSignature(FILE* file) {
    if (!file) return false;
    seek64(file, 0, SEEK_SET);
    uint8_t h[16]{};
    size_t nr = std::fread(h, 1, sizeof(h), file);
    if (nr < 4) {
        seek64(file, 0, SEEK_SET);
        return false;
    }
    if (std::memcmp(h, "fLaC", 4) == 0 ||
        std::memcmp(h, "RIFF", 4) == 0 ||
        std::memcmp(h, "FORM", 4) == 0 ||
        std::memcmp(h, "OggS", 4) == 0 ||
        std::memcmp(h, "caff", 4) == 0 ||
        (nr >= 8 && std::memcmp(h + 4, "ftyp", 4) == 0)) {
        seek64(file, 0, SEEK_SET);
        return true;
    }
    int64_t audioOff = 0;
    if (nr >= 10 && std::memcmp(h, "ID3", 3) == 0) {
        audioOff = static_cast<int64_t>(10 +
            ((static_cast<uint32_t>(h[6] & 0x7F) << 21) |
             (static_cast<uint32_t>(h[7] & 0x7F) << 14) |
             (static_cast<uint32_t>(h[8] & 0x7F) << 7) |
             static_cast<uint32_t>(h[9] & 0x7F)));
        if (seek64(file, audioOff, SEEK_SET) == 0) {
            nr = std::fread(h, 1, 2, file);
        } else {
            nr = 0;
        }
    }
    seek64(file, 0, SEEK_SET);
    if (nr >= 2 && h[0] == 0xFF && (h[1] & 0xF6) == 0xF0) {
        return true;
    }
    return false;
}

bool DesktopDecoder::openFfmpeg(const std::string& filePath) {
#ifdef HAVE_FFMPEG
    AVFormatContext* fmtCtx = nullptr;
    if (avformat_open_input(&fmtCtx, filePath.c_str(), nullptr, nullptr) < 0 || !fmtCtx) {
        return false;
    }
    if (avformat_find_stream_info(fmtCtx, nullptr) < 0) {
        avformat_close_input(&fmtCtx);
        return false;
    }

    const AVCodec* codec = nullptr;
    int streamIdx = av_find_best_stream(fmtCtx, AVMEDIA_TYPE_AUDIO, -1, -1, &codec, 0);
    if (streamIdx < 0 || !codec) {
        avformat_close_input(&fmtCtx);
        return false;
    }

    AVStream* st = fmtCtx->streams[streamIdx];
    AVCodecContext* codecCtx = avcodec_alloc_context3(codec);
    if (!codecCtx || avcodec_parameters_to_context(codecCtx, st->codecpar) < 0 ||
        avcodec_open2(codecCtx, codec, nullptr) < 0) {
        if (codecCtx) avcodec_free_context(&codecCtx);
        avformat_close_input(&fmtCtx);
        return false;
    }

    uint32_t sr = codecCtx->sample_rate > 0 ? static_cast<uint32_t>(codecCtx->sample_rate) : 44100u;
    uint32_t ch = codecCtx->ch_layout.nb_channels > 0
                      ? static_cast<uint32_t>(codecCtx->ch_layout.nb_channels)
                      : 2u;

    AVChannelLayout outLayout{};
    av_channel_layout_default(&outLayout, 2);

    AVChannelLayout inLayout{};
    if (codecCtx->ch_layout.order != AV_CHANNEL_ORDER_UNSPEC && codecCtx->ch_layout.nb_channels > 0) {
        av_channel_layout_copy(&inLayout, &codecCtx->ch_layout);
    } else {
        av_channel_layout_default(&inLayout, static_cast<int>(ch));
    }

    SwrContext* swr = nullptr;
    if (swr_alloc_set_opts2(
            &swr,
            &outLayout,
            AV_SAMPLE_FMT_FLT,
            static_cast<int>(sr),
            &inLayout,
            codecCtx->sample_fmt,
            static_cast<int>(sr),
            0,
            nullptr) < 0 ||
        !swr ||
        swr_init(swr) < 0) {
        av_channel_layout_uninit(&inLayout);
        av_channel_layout_uninit(&outLayout);
        if (swr) swr_free(&swr);
        avcodec_free_context(&codecCtx);
        avformat_close_input(&fmtCtx);
        return false;
    }
    av_channel_layout_uninit(&inLayout);
    av_channel_layout_uninit(&outLayout);

    AVFrame* frame = av_frame_alloc();
    AVPacket* pkt = av_packet_alloc();
    if (!frame || !pkt) {
        if (frame) av_frame_free(&frame);
        if (pkt) av_packet_free(&pkt);
        swr_free(&swr);
        avcodec_free_context(&codecCtx);
        avformat_close_input(&fmtCtx);
        return false;
    }

    ReplayGainTagData rg{};
    auto readDictRg = [&](AVDictionary* dict) {
        if (!dict) return;
        const AVDictionaryEntry* e = nullptr;
        while ((e = av_dict_iterate(dict, e)) != nullptr) {
            if (e->key && e->value) {
                applyReplayGainTag(rg, e->key, e->value);
            }
        }
    };
    readDictRg(fmtCtx->metadata);
    readDictRg(st->metadata);
    finalizeReplayGain(rg);

    uint64_t totalFrames = 0;
    if (st->duration > 0 && st->duration != AV_NOPTS_VALUE) {
        int64_t rescaled = av_rescale_q(st->duration, st->time_base, AVRational{1, static_cast<int>(sr)});
        if (rescaled > 0) totalFrames = static_cast<uint64_t>(rescaled);
    } else if (fmtCtx->duration > 0 && fmtCtx->duration != AV_NOPTS_VALUE) {
        int64_t rescaled = av_rescale(fmtCtx->duration, static_cast<int64_t>(sr), AV_TIME_BASE);
        if (rescaled > 0) totalFrames = static_cast<uint64_t>(rescaled);
    }

    mAvFmt = fmtCtx;
    mAvCodec = codecCtx;
    mSwr = swr;
    mAvFrame = frame;
    mAvPkt = pkt;
    mAvStreamIdx = streamIdx;
    mAvEof = false;
    mAvSeekTargetPts = -1;
    mAvStereoPos = 0;
    mAvStereoAvail = 0;

    mFormat = AudioFormatType::FFMPEG;
    mSourceSampleRate = sr;
    mSourceChannels = ch;
    mSourceTotalFrames = totalFrames;
    configureOutputFormat();
    return true;
#else
    (void)filePath;
    return false;
#endif
}

bool DesktopDecoder::openFile(const std::string& filePath, uint32_t targetSampleRate) {
    close();
    mForcedSampleRate = targetSampleRate;
    mCurrentFrame = 0;

    FILE* file = openUtf8File(filePath);
    if (!file) return false;
    mOwnedFile = file;

    // 1. Try FLAC
    mPendingFlacRg = ReplayGainTagData{};
    mFlac = drflac_open_with_metadata(flacIoRead, flacIoSeek, flacIoTell, flacMetaCallback, this, nullptr);
    if (mFlac) {
        finalizeReplayGain(mPendingFlacRg);
        mFormat = AudioFormatType::FLAC;
        mSourceSampleRate = mFlac->sampleRate;
        mSourceChannels = mFlac->channels;
        mSourceTotalFrames = mFlac->totalPCMFrameCount;
        configureOutputFormat();
        return true;
    }

    // 2. Try WAV / AIFF (dr_wav handles RIFF WAVE & uncompressed/sowt/float FORM AIFF/AIFC)
    seek64(mOwnedFile, 0, SEEK_SET);
    if (drwav_init(mWav, wavIoRead, wavIoSeek, wavIoTell, this, nullptr)) {
        mHasReplayGain = false;
        mReplayGainLinear = 1.0f;
        mFormat = (mWav->container == drwav_container_aiff) ? AudioFormatType::AIFF : AudioFormatType::WAV;
        mSourceSampleRate = mWav->sampleRate;
        mSourceChannels = mWav->channels;
        mSourceTotalFrames = mWav->totalPCMFrameCount;
        configureOutputFormat();
        return true;
    }

    // 3. Try ALAC (pure C++ bit-perfect MP4 'alac')
    seek64(mOwnedFile, 0, SEEK_SET);
    if (mAlac.open(mOwnedFile)) {
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
        return true;
    }

    // 4. Try MP3 (skip container formats & ADTS AAC so minimp3 never mis-syncs)
    if (!hasNonMp3ContainerSignature(mOwnedFile)) {
        seek64(mOwnedFile, 0, SEEK_SET);
        ReplayGainTagData mp3Rg{};
        parseId3v2ReplayGain(mOwnedFile, mp3Rg);
        seek64(mOwnedFile, 0, SEEK_SET);

        mMp3Io.read = mp3ReadCallback;
        mMp3Io.read_data = mOwnedFile;
        mMp3Io.seek = mp3SeekCallback;
        mMp3Io.seek_data = mOwnedFile;

        int mp3Flags = MP3D_SEEK_TO_SAMPLE | MP3D_ALLOW_MONO_STEREO_TRANSITION;
        if (mp3dec_ex_open_cb(mMp3, &mMp3Io, mp3Flags) == 0 && mMp3->info.hz > 0 && mMp3->info.channels > 0) {
            finalizeReplayGain(mp3Rg);
            mFormat = AudioFormatType::MP3;
            mSourceSampleRate = static_cast<uint32_t>(mMp3->info.hz);
            mSourceChannels = static_cast<uint32_t>(mMp3->info.channels);
            mSourceTotalFrames = mMp3->samples / (mSourceChannels > 0 ? mSourceChannels : 1);
            configureOutputFormat();
            return true;
        }
        mp3dec_ex_close(mMp3);
    }

    // 5. Fallback to FFmpeg (AAC/M4A, Ogg Vorbis, Opus, AIFC, CAF, etc.)
    std::fclose(mOwnedFile);
    mOwnedFile = nullptr;
    if (openFfmpeg(filePath)) {
        return true;
    }

    close();
    return false;
}

void DesktopDecoder::close() {
    if (mFormat == AudioFormatType::FLAC && mFlac) {
        drflac_close(mFlac);
        mFlac = nullptr;
    } else if (mFormat == AudioFormatType::WAV || mFormat == AudioFormatType::AIFF) {
        drwav_uninit(mWav);
    } else if (mFormat == AudioFormatType::ALAC) {
        mAlac.close();
    } else if (mFormat == AudioFormatType::MP3) {
        mp3dec_ex_close(mMp3);
    } else if (mFormat == AudioFormatType::FFMPEG) {
#ifdef HAVE_FFMPEG
        if (mAvFrame) av_frame_free(&mAvFrame);
        if (mAvPkt) av_packet_free(&mAvPkt);
        if (mSwr) swr_free(&mSwr);
        if (mAvCodec) avcodec_free_context(&mAvCodec);
        if (mAvFmt) avformat_close_input(&mAvFmt);
#endif
    }
    mAvStreamIdx = -1;
    mAvEof = false;
    mAvSeekTargetPts = -1;
    mAvStereoBuf.clear();
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

bool DesktopDecoder::reconfigureOutputSampleRate(uint32_t targetSampleRate) {
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

void DesktopDecoder::downmixToStereo(const float* src, float* dst, uint64_t frames, uint32_t srcCh) {
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

int64_t DesktopDecoder::seek(int64_t targetFrame) {
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
        if (drflac_seek_to_pcm_frame(mFlac, (drflac_uint64)sourceTargetFrame)) {
            mCurrentFrame = (uint64_t)targetFrame;
            return targetFrame;
        }
    } else if (mFormat == AudioFormatType::WAV || mFormat == AudioFormatType::AIFF) {
        if (drwav_seek_to_pcm_frame(mWav, (drwav_uint64)sourceTargetFrame)) {
            mCurrentFrame = (uint64_t)targetFrame;
            return targetFrame;
        }
    } else if (mFormat == AudioFormatType::ALAC) {
        if (mAlac.seek((uint64_t)sourceTargetFrame)) {
            mCurrentFrame = (uint64_t)targetFrame;
            return targetFrame;
        }
    } else if (mFormat == AudioFormatType::FFMPEG) {
#ifdef HAVE_FFMPEG
        if (mAvFmt && mAvCodec && mAvStreamIdx >= 0) {
            AVStream* st = mAvFmt->streams[mAvStreamIdx];
            int64_t basePts = (st->start_time != AV_NOPTS_VALUE) ? st->start_time : 0;
            int64_t ts = basePts + av_rescale_q(sourceTargetFrame, AVRational{1, static_cast<int>(mSourceSampleRate)}, st->time_base);
            if (av_seek_frame(mAvFmt, mAvStreamIdx, ts, AVSEEK_FLAG_BACKWARD) >= 0) {
                avcodec_flush_buffers(mAvCodec);
                if (mSwr) {
                    swr_close(mSwr);
                    swr_init(mSwr);
                }
                mAvEof = false;
                mAvStereoPos = 0;
                mAvStereoAvail = 0;
                mAvSeekTargetPts = sourceTargetFrame;
                mCurrentFrame = static_cast<uint64_t>(targetFrame);
                return targetFrame;
            }
        }
#endif
    } else if (mFormat == AudioFormatType::MP3) {
        uint64_t srcCh = (mSourceChannels > 0) ? mSourceChannels : 1;
        if (mp3dec_ex_seek(mMp3, (uint64_t)sourceTargetFrame * srcCh) == 0) {
            mCurrentFrame = (uint64_t)targetFrame;
            return targetFrame;
        }
    }

    return -1;
}

uint64_t DesktopDecoder::decodeSourceStereoFrames(float* pStereoOut, uint64_t maxSourceFrames) {
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
            if (mMultiChScratchBuf.size() < needed) mMultiChScratchBuf.resize(needed);
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
            if (mMultiChScratchBuf.size() < needed) mMultiChScratchBuf.resize(needed);
            uint64_t decoded = drwav_read_pcm_frames_f32(mWav, maxSourceFrames, mMultiChScratchBuf.data());
            downmixToStereo(mMultiChScratchBuf.data(), pStereoOut, decoded, mSourceChannels);
            return decoded;
        }
    } else if (mFormat == AudioFormatType::ALAC) {
        return mAlac.readStereoFrames(pStereoOut, maxSourceFrames);
    } else if (mFormat == AudioFormatType::FFMPEG) {
#ifdef HAVE_FFMPEG
        if (!mAvFmt || !mAvCodec || !mSwr || !mAvFrame || !mAvPkt) return 0;
        uint64_t framesWritten = 0;
        AVStream* st = mAvFmt->streams[mAvStreamIdx];
        int64_t basePts = (st->start_time != AV_NOPTS_VALUE) ? st->start_time : 0;

        while (framesWritten < maxSourceFrames) {
            if (mAvStereoPos < mAvStereoAvail) {
                size_t availFrames = mAvStereoAvail - mAvStereoPos;
                size_t toCopy = std::min<size_t>(availFrames, static_cast<size_t>(maxSourceFrames - framesWritten));
                std::memcpy(
                    pStereoOut + framesWritten * 2,
                    mAvStereoBuf.data() + mAvStereoPos * 2,
                    toCopy * 2 * sizeof(float)
                );
                mAvStereoPos += toCopy;
                framesWritten += toCopy;
                continue;
            }

            if (mAvEof) break;

            int rec = avcodec_receive_frame(mAvCodec, mAvFrame);
            if (rec == AVERROR(EAGAIN)) {
                bool sentPacket = false;
                while (av_read_frame(mAvFmt, mAvPkt) >= 0) {
                    if (mAvPkt->stream_index == mAvStreamIdx) {
                        avcodec_send_packet(mAvCodec, mAvPkt);
                        av_packet_unref(mAvPkt);
                        sentPacket = true;
                        break;
                    }
                    av_packet_unref(mAvPkt);
                }
                if (!sentPacket) {
                    avcodec_send_packet(mAvCodec, nullptr);
                    mAvEof = true;
                }
                continue;
            } else if (rec == AVERROR_EOF) {
                int rem = swr_get_out_samples(mSwr, 0);
                if (rem > 0) {
                    if (mAvStereoBuf.size() < static_cast<size_t>(rem) * 2) {
                        mAvStereoBuf.resize(static_cast<size_t>(rem) * 2);
                    }
                    uint8_t* outArr[1] = { reinterpret_cast<uint8_t*>(mAvStereoBuf.data()) };
                    int conv = swr_convert(mSwr, outArr, rem, nullptr, 0);
                    if (conv > 0) {
                        mAvStereoPos = 0;
                        mAvStereoAvail = static_cast<size_t>(conv);
                        continue;
                    }
                }
                mAvEof = true;
                break;
            } else if (rec < 0) {
                break;
            }

            int outCap = swr_get_out_samples(mSwr, mAvFrame->nb_samples);
            if (outCap > 0) {
                if (mAvStereoBuf.size() < static_cast<size_t>(outCap) * 2) {
                    mAvStereoBuf.resize(static_cast<size_t>(outCap) * 2);
                }
                uint8_t* outArr[1] = { reinterpret_cast<uint8_t*>(mAvStereoBuf.data()) };
                int conv = swr_convert(
                    mSwr,
                    outArr,
                    outCap,
                    const_cast<const uint8_t**>(mAvFrame->extended_data),
                    mAvFrame->nb_samples
                );
                if (conv > 0) {
                    size_t startPos = 0;
                    if (mAvSeekTargetPts >= 0) {
                        int64_t fPts = (mAvFrame->pts != AV_NOPTS_VALUE) ? mAvFrame->pts : mAvFrame->best_effort_timestamp;
                        if (fPts != AV_NOPTS_VALUE) {
                            int64_t frameStartSample = av_rescale_q(fPts - basePts, st->time_base, AVRational{1, static_cast<int>(mSourceSampleRate)});
                            int64_t frameEndSample = frameStartSample + conv;
                            if (frameEndSample <= mAvSeekTargetPts) {
                                av_frame_unref(mAvFrame);
                                continue;
                            }
                            if (frameStartSample < mAvSeekTargetPts) {
                                startPos = static_cast<size_t>(mAvSeekTargetPts - frameStartSample);
                            }
                        }
                        mAvSeekTargetPts = -1;
                    }
                    if (startPos < static_cast<size_t>(conv)) {
                        mAvStereoPos = startPos;
                        mAvStereoAvail = static_cast<size_t>(conv);
                    }
                }
            }
            av_frame_unref(mAvFrame);
        }
        return framesWritten;
#else
        return 0;
#endif
    } else if (mFormat == AudioFormatType::MP3) {
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
            if (samplesRead == 0 || !bufFrame) break;

            int frameCh = (frameInfo.channels > 0) ? frameInfo.channels : (int)mSourceChannels;
            if (frameCh <= 0) frameCh = 2;

            size_t decodedFrames = samplesRead / (size_t)frameCh;
            if (decodedFrames == 0) break;

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

uint64_t DesktopDecoder::decodeFrames(float* pOutput, uint64_t maxFrames) {
    if (mFormat == AudioFormatType::UNKNOWN || maxFrames == 0) return 0;

    if (mSampleRate == mSourceSampleRate || mSourceSampleRate == 0) {
        uint64_t decoded = decodeSourceStereoFrames(pOutput, maxFrames);
        mCurrentFrame += decoded;
        return decoded;
    }

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
        if (!pullNextSourceFrame(first)) return 0;
        mResampleRing[0][0] = first[0]; mResampleRing[0][1] = first[1];
        mResampleRing[1][0] = first[0]; mResampleRing[1][1] = first[1];
        mResampleRing[2][0] = first[0]; mResampleRing[2][1] = first[1];
        mResampleValidCount = 1;
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

        if (mResampleValidCount <= 0) break;

        double accL = 0.0, accR = 0.0, sumW = 0.0;
        for (int idx = 0; idx < 6; ++idx) {
            int offset = idx - 2;
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

uint32_t DesktopDecoder::getSampleRate() const { return mSampleRate; }
uint32_t DesktopDecoder::getChannels() const { return mChannels; }
uint64_t DesktopDecoder::getTotalFrames() const { return mTotalFrames; }
uint64_t DesktopDecoder::getCurrentFrame() const { return mCurrentFrame; }
