#include "AlacDecoder.h"

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <cstring>

namespace {

int seekFile64(FILE* f, int64_t offset, int origin) {
#if defined(_WIN32)
    return _fseeki64(f, offset, origin);
#else
    return fseeko(f, static_cast<off_t>(offset), origin);
#endif
}

int64_t tellFile64(FILE* f) {
#if defined(_WIN32)
    return _ftelli64(f);
#else
    return static_cast<int64_t>(ftello(f));
#endif
}

uint16_t readBe16(const uint8_t* p) {
    return static_cast<uint16_t>((static_cast<uint16_t>(p[0]) << 8) | p[1]);
}

uint32_t readBe32(const uint8_t* p) {
    return (static_cast<uint32_t>(p[0]) << 24) |
           (static_cast<uint32_t>(p[1]) << 16) |
           (static_cast<uint32_t>(p[2]) << 8) |
           static_cast<uint32_t>(p[3]);
}

uint64_t readBe64(const uint8_t* p) {
    return (static_cast<uint64_t>(readBe32(p)) << 32) | readBe32(p + 4);
}

bool readAt(FILE* f, uint64_t offset, void* dst, size_t len) {
    if (len == 0) return true;
    if (seekFile64(f, static_cast<int64_t>(offset), SEEK_SET) != 0) return false;
    return std::fread(dst, 1, len, f) == len;
}

constexpr uint32_t makeFourCC(char a, char b, char c, char d) {
    return (static_cast<uint32_t>(static_cast<uint8_t>(a)) << 24) |
           (static_cast<uint32_t>(static_cast<uint8_t>(b)) << 16) |
           (static_cast<uint32_t>(static_cast<uint8_t>(c)) << 8) |
           static_cast<uint32_t>(static_cast<uint8_t>(d));
}

std::string trimLowerAscii(const std::string& s) {
    size_t l = 0;
    while (l < s.size() && (std::isspace(static_cast<unsigned char>(s[l])) || s[l] == '\0')) ++l;
    size_t r = s.size();
    while (r > l && (std::isspace(static_cast<unsigned char>(s[r - 1])) || s[r - 1] == '\0')) --r;
    std::string out = s.substr(l, r - l);
    for (char& ch : out) ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
    return out;
}

class BitReader {
public:
    BitReader(const uint8_t* data, size_t sizeBytes)
        : mData(data), mTotalBits(sizeBytes * 8) {}

    size_t bitsLeft() const {
        return (mBitPos < mTotalBits) ? (mTotalBits - mBitPos) : 0;
    }

    uint32_t peekBits(int n) const {
        if (n <= 0) return 0;
        uint32_t val = 0;
        for (int i = 0; i < n; ++i) {
            size_t pos = mBitPos + static_cast<size_t>(i);
            uint32_t bit = 0;
            if (pos < mTotalBits) {
                bit = (mData[pos >> 3] >> (7 - (pos & 7))) & 1u;
            }
            val = (val << 1) | bit;
        }
        return val;
    }

    void advance(int n) {
        mBitPos += static_cast<size_t>(std::max(0, n));
        if (mBitPos > mTotalBits) mBitPos = mTotalBits;
    }

    uint32_t readBits(int n) {
        uint32_t v = peekBits(n);
        advance(n);
        return v;
    }

    int32_t readSignedBits(int n) {
        uint32_t u = readBits(n);
        if (n > 0 && n < 32 && (u & (1u << (n - 1)))) {
            u |= (~0u << n);
        }
        return static_cast<int32_t>(u);
    }

    uint32_t readUnary(uint32_t maxOnes) {
        uint32_t count = 0;
        while (count < maxOnes && mBitPos < mTotalBits) {
            uint32_t bit = (mData[mBitPos >> 3] >> (7 - (mBitPos & 7))) & 1u;
            ++mBitPos;
            if (bit == 0) break;
            ++count;
        }
        return count;
    }

private:
    const uint8_t* mData = nullptr;
    size_t mTotalBits = 0;
    size_t mBitPos = 0;
};

int32_t signExtend(int32_t val, int bits) {
    if (bits <= 0 || bits >= 32) return val;
    int shift = 32 - bits;
    return static_cast<int32_t>(static_cast<uint32_t>(val) << shift) >> shift;
}

uint32_t leadZeroCount32(uint32_t x) {
    if (x == 0) return 32;
#if defined(__GNUC__) || defined(__clang__)
    return static_cast<uint32_t>(__builtin_clz(x));
#else
    uint32_t n = 0;
    if ((x & 0xFFFF0000u) == 0) { n += 16; x <<= 16; }
    if ((x & 0xFF000000u) == 0) { n += 8; x <<= 8; }
    if ((x & 0xF0000000u) == 0) { n += 4; x <<= 4; }
    if ((x & 0xC0000000u) == 0) { n += 2; x <<= 2; }
    if ((x & 0x80000000u) == 0) { n += 1; }
    return n;
#endif
}

uint32_t lg3a(uint32_t x) {
    uint32_t val = (x >> 9) + 3u;
    return 31u - leadZeroCount32(val);
}

uint32_t dynGet(BitReader& br, uint32_t m, uint32_t k, int bitWidth) {
    uint32_t prefix = br.readUnary(9);
    if (prefix == 9) {
        return br.readBits(bitWidth);
    }
    if (k == 0 || m == 0) {
        return prefix;
    }
    if (k == 1) {
        return prefix;
    }
    uint32_t v = br.peekBits(static_cast<int>(k));
    if (v < 2) {
        br.advance(static_cast<int>(k - 1));
        return prefix * m;
    }
    br.advance(static_cast<int>(k));
    return prefix * m + (v - 1);
}

void dynDecompress(
    BitReader& br,
    int32_t* out,
    uint32_t numSamples,
    uint32_t mb0,
    uint32_t pb,
    uint32_t kb,
    int chanBits
) {
    uint32_t mb = mb0;
    uint32_t zmode = 0;
    uint32_t c = 0;

    while (c < numSamples) {
        uint32_t k = std::min<uint32_t>(lg3a(mb), kb);
        uint32_t m = (1u << k) - 1u;
        uint32_t n = dynGet(br, m, k, chanBits) + zmode;
        zmode = 0;

        int32_t signedVal = (n & 1u)
            ? -static_cast<int32_t>((n + 1u) >> 1)
            : static_cast<int32_t>(n >> 1);
        out[c++] = signedVal;

        if (n > 0xFFFFu) {
            mb = 16384u;
        } else {
            mb = mb - ((pb * mb) >> 9) + (n * pb);
        }

        // Handle zero-run compression when adaptive history drops below threshold
        if (mb < 128u && c < numSamples) {
            uint32_t zLead = leadZeroCount32(mb);
            uint32_t zk = (mb == 0) ? 7u : std::min<uint32_t>(7u, (zLead >= 24u ? zLead - 24u : 0u));
            uint32_t zm = (1u << zk) - 1u;
            uint32_t zeroRun = dynGet(br, zm, zk, 16);
            if (zeroRun > numSamples - c) zeroRun = numSamples - c;
            for (uint32_t z = 0; z < zeroRun; ++z) {
                out[c++] = 0;
            }
            if (zeroRun < 65535u) {
                zmode = 1u;
            }
            mb = 0;
        }
    }
}

void unpcBlockExact(
    const int32_t* pc1,
    int32_t* out,
    uint32_t num,
    const int16_t* coefs,
    int numActive,
    uint32_t chanBits,
    uint32_t denShift
) {
    if (num == 0) return;

    if (numActive == 31) {
        out[0] = pc1[0];
        for (uint32_t j = 1; j < num; ++j) {
            out[j] = signExtend(pc1[j] + out[j - 1], static_cast<int>(chanBits));
        }
        return;
    }

    if (numActive == 0) {
        for (uint32_t j = 0; j < num; ++j) out[j] = pc1[j];
        return;
    }

    int16_t mutCoefs[32];
    for (int i = 0; i < numActive; ++i) mutCoefs[i] = coefs[i];

    out[0] = pc1[0];
    uint32_t lim = std::min<uint32_t>(num, static_cast<uint32_t>(numActive) + 1u);
    for (uint32_t j = 1; j < lim; ++j) {
        out[j] = signExtend(pc1[j] + out[j - 1], static_cast<int>(chanBits));
    }

    const int32_t denHalf = (denShift > 0) ? (1 << (denShift - 1)) : 0;

    for (uint32_t i = static_cast<uint32_t>(numActive) + 1u; i < num; ++i) {
        const int32_t top = out[i - 1 - static_cast<uint32_t>(numActive)];
        int32_t* pout = out + i - 1;
        int32_t sum1 = denHalf;

        for (int j = 0; j < numActive; ++j) {
            sum1 += (pout[-j] - top) * static_cast<int32_t>(mutCoefs[j]);
        }

        int32_t err = pc1[i];
        int32_t val = (sum1 >> denShift) + err + top;
        out[i] = signExtend(val, static_cast<int>(chanBits));

        if (err > 0) {
            int32_t j = numActive - 1;
            while (j >= 0) {
                int32_t dd = top - pout[-j];
                int32_t sgn = (dd > 0) ? 1 : ((dd < 0) ? -1 : 0);
                mutCoefs[j] = static_cast<int16_t>(mutCoefs[j] - sgn);
                if (dd < 0) dd = -dd;
                err -= (dd >> denShift) * (numActive - j);
                if (err <= 0) break;
                --j;
            }
        } else if (err < 0) {
            int32_t j = numActive - 1;
            while (j >= 0) {
                int32_t dd = top - pout[-j];
                int32_t sgn = (dd > 0) ? 1 : ((dd < 0) ? -1 : 0);
                mutCoefs[j] = static_cast<int16_t>(mutCoefs[j] + sgn);
                if (dd > 0) dd = -dd;
                err -= (dd >> denShift) * (numActive - j);
                if (err >= 0) break;
                --j;
            }
        }
    }
}

void unmixStereo(
    const int32_t* u,
    const int32_t* v,
    int32_t* leftOut,
    int32_t* rightOut,
    uint32_t numSamples,
    uint8_t mixBits,
    int8_t mixRes,
    int bitDepth,
    uint32_t bytesShifted,
    const uint16_t* shiftL,
    const uint16_t* shiftR
) {
    const int32_t shiftBits = static_cast<int32_t>(bytesShifted * 8u);
    const int32_t chanBits = bitDepth - shiftBits;

    if (mixRes == 0) {
        for (uint32_t i = 0; i < numSamples; ++i) {
            int32_t l = signExtend(u[i], chanBits);
            int32_t r = signExtend(v[i], chanBits);
            if (shiftBits > 0) {
                l = (l << shiftBits) | static_cast<int32_t>(shiftL[i]);
                r = (r << shiftBits) | static_cast<int32_t>(shiftR[i]);
            }
            leftOut[i] = signExtend(l, bitDepth);
            rightOut[i] = signExtend(r, bitDepth);
        }
        return;
    }

    for (uint32_t i = 0; i < numSamples; ++i) {
        int32_t l = u[i] + v[i] - ((mixRes * v[i]) >> mixBits);
        int32_t r = l - v[i];
        if (shiftBits > 0) {
            l = (l << shiftBits) | static_cast<int32_t>(shiftL[i]);
            r = (r << shiftBits) | static_cast<int32_t>(shiftR[i]);
        }
        leftOut[i] = signExtend(l, bitDepth);
        rightOut[i] = signExtend(r, bitDepth);
    }
}

} // namespace

AlacDecoder::AlacDecoder() = default;

AlacDecoder::~AlacDecoder() {
    close();
}

void AlacDecoder::close() {
    mFile = nullptr;
    mConfig = AlacConfig{};
    mSampleRate = 0;
    mChannels = 0;
    mBitDepth = 16;
    mTotalFrames = 0;
    mReplayGain = ReplayGainInfo{};
    mPackets.clear();
    mNextPacketIdx = 0;
    mDecodedStereoBuf.clear();
    mDecodedStereoPos = 0;
    mDecodedStereoAvail = 0;
}

bool AlacDecoder::open(FILE* file) {
    close();
    if (!file) return false;
    if (seekFile64(file, 0, SEEK_END) != 0) return false;
    int64_t endPos = tellFile64(file);
    if (endPos < 24) return false;
    uint64_t fileSize = static_cast<uint64_t>(endPos);

    uint8_t head[12];
    if (!readAt(file, 0, head, 12)) return false;
    // Quick MP4/M4A box check: top-level must look like a valid ISOBMFF box ('ftyp', 'moov', 'free', 'mdat', 'wide')
    uint32_t firstType = readBe32(head + 4);
    if (firstType != makeFourCC('f', 't', 'y', 'p') &&
        firstType != makeFourCC('m', 'o', 'o', 'v') &&
        firstType != makeFourCC('w', 'i', 'd', 'e') &&
        firstType != makeFourCC('f', 'r', 'e', 'e') &&
        firstType != makeFourCC('m', 'd', 'a', 't')) {
        return false;
    }

    if (!parseMp4(file, fileSize)) {
        close();
        return false;
    }
    mFile = file;
    return true;
}

bool AlacDecoder::parseStsd(FILE* file, uint64_t start, uint64_t end, TrackAtoms& track) {
    if (start + 8 > end) return false;
    uint8_t hdr[8];
    if (!readAt(file, start, hdr, 8)) return false;
    uint32_t entryCount = readBe32(hdr + 4);
    uint64_t pos = start + 8;

    for (uint32_t idx = 0; idx < entryCount && pos + 8 <= end; ++idx) {
        uint8_t entryHdr[8];
        if (!readAt(file, pos, entryHdr, 8)) break;
        uint64_t entrySize = readBe32(entryHdr);
        uint32_t entryType = readBe32(entryHdr + 4);
        if (entrySize < 8 || pos + entrySize > end) break;

        if (entryType == makeFourCC('a', 'l', 'a', 'c') && entrySize >= 36) {
            // AudioSampleEntry is 28 bytes after the 8-byte header = 36 bytes total before child atoms
            uint64_t childPos = pos + 36;
            uint64_t entryEnd = pos + entrySize;
            while (childPos + 8 <= entryEnd) {
                uint8_t chHdr[8];
                if (!readAt(file, childPos, chHdr, 8)) break;
                uint64_t chSize = readBe32(chHdr);
                uint32_t chType = readBe32(chHdr + 4);
                if (chSize < 8 || childPos + chSize > entryEnd) break;

                if (chType == makeFourCC('a', 'l', 'a', 'c') && chSize >= 36) {
                    // 8-byte header + 4-byte fullbox version/flags + 24-byte magic cookie
                    uint8_t cookie[24];
                    if (readAt(file, childPos + 12, cookie, 24)) {
                        track.config.frameLength = readBe32(cookie);
                        track.config.compatibleVersion = cookie[4];
                        track.config.bitDepth = cookie[5];
                        track.config.pb = cookie[6];
                        track.config.mb = cookie[7];
                        track.config.kb = cookie[8];
                        track.config.numChannels = cookie[9];
                        track.config.maxRun = readBe16(cookie + 10);
                        track.config.maxFrameBytes = readBe32(cookie + 12);
                        track.config.avgBitRate = readBe32(cookie + 16);
                        track.config.sampleRate = readBe32(cookie + 20);
                        if (track.config.frameLength > 0 &&
                            track.config.numChannels > 0 &&
                            track.config.sampleRate > 0 &&
                            (track.config.bitDepth == 16 || track.config.bitDepth == 20 ||
                             track.config.bitDepth == 24 || track.config.bitDepth == 32)) {
                            track.hasAlac = true;
                        }
                    }
                    return track.hasAlac;
                }
                childPos += chSize;
            }
        }
        pos += entrySize;
    }
    return false;
}

bool AlacDecoder::parseFreeformTag(FILE* file, uint64_t start, uint64_t end) {
    std::string name;
    std::string val;
    uint64_t pos = start;
    while (pos + 8 <= end) {
        uint8_t hdr[8];
        if (!readAt(file, pos, hdr, 8)) break;
        uint64_t sz = readBe32(hdr);
        uint32_t tp = readBe32(hdr + 4);
        if (sz < 8 || pos + sz > end) break;
        if (tp == makeFourCC('n', 'a', 'm', 'e') && sz > 12) {
            std::string tmp(static_cast<size_t>(sz - 12), '\0');
            if (readAt(file, pos + 12, tmp.data(), tmp.size())) name = trimLowerAscii(tmp);
        } else if (tp == makeFourCC('d', 'a', 't', 'a') && sz > 16) {
            std::string tmp(static_cast<size_t>(sz - 16), '\0');
            if (readAt(file, pos + 16, tmp.data(), tmp.size())) val = trimLowerAscii(tmp);
        }
        pos += sz;
    }
    if (name == "replaygain_track_gain" && !val.empty()) {
        mReplayGain.trackGainDb = std::strtof(val.c_str(), nullptr);
        mReplayGain.hasTrackGain = true;
    } else if (name == "replaygain_track_peak" && !val.empty()) {
        mReplayGain.trackPeak = std::strtof(val.c_str(), nullptr);
        mReplayGain.hasTrackPeak = (mReplayGain.trackPeak > 0.0f);
    } else if (name == "replaygain_album_gain" && !val.empty()) {
        mReplayGain.albumGainDb = std::strtof(val.c_str(), nullptr);
        mReplayGain.hasAlbumGain = true;
    } else if (name == "replaygain_album_peak" && !val.empty()) {
        mReplayGain.albumPeak = std::strtof(val.c_str(), nullptr);
        mReplayGain.hasAlbumPeak = (mReplayGain.albumPeak > 0.0f);
    }
    return true;
}

bool AlacDecoder::walkBoxes(
    FILE* file,
    uint64_t start,
    uint64_t end,
    TrackAtoms& currentTrack,
    std::vector<TrackAtoms>& tracks
) {
    uint64_t pos = start;
    while (pos + 8 <= end) {
        uint8_t hdr[16];
        if (!readAt(file, pos, hdr, 8)) break;
        uint64_t boxSize = readBe32(hdr);
        uint32_t boxType = readBe32(hdr + 4);
        uint64_t hdrSize = 8;

        if (boxSize == 1) {
            if (pos + 16 > end || !readAt(file, pos + 8, hdr + 8, 8)) break;
            boxSize = readBe64(hdr + 8);
            hdrSize = 16;
        } else if (boxSize == 0) {
            boxSize = end - pos;
        }
        if (boxSize < hdrSize || pos + boxSize > end) break;

        uint64_t bodyStart = pos + hdrSize;
        uint64_t boxEnd = pos + boxSize;

        if (boxType == makeFourCC('m', 'o', 'o', 'v') ||
            boxType == makeFourCC('m', 'd', 'i', 'a') ||
            boxType == makeFourCC('m', 'i', 'n', 'f') ||
            boxType == makeFourCC('s', 't', 'b', 'l') ||
            boxType == makeFourCC('u', 'd', 't', 'a') ||
            boxType == makeFourCC('i', 'l', 's', 't')) {
            walkBoxes(file, bodyStart, boxEnd, currentTrack, tracks);
        } else if (boxType == makeFourCC('m', 'e', 't', 'a')) {
            // 'meta' is a FullBox with 4-byte version/flags before children (Apple sometimes omits in old files; detect)
            if (bodyStart + 12 <= boxEnd) {
                uint8_t probe[8];
                if (readAt(file, bodyStart, probe, 8)) {
                    uint32_t childIfFull = readBe32(probe + 4);
                    uint64_t metaChildStart = (probe[0] == 0 && probe[1] == 0 && probe[2] == 0 && probe[3] == 0) ||
                                              childIfFull == makeFourCC('h', 'd', 'l', 'r') ||
                                              childIfFull == makeFourCC('i', 'l', 's', 't')
                                              ? (bodyStart + 4)
                                              : bodyStart;
                    walkBoxes(file, metaChildStart, boxEnd, currentTrack, tracks);
                }
            }
        } else if (boxType == makeFourCC('t', 'r', 'a', 'k')) {
            TrackAtoms trk;
            walkBoxes(file, bodyStart, boxEnd, trk, tracks);
            if (trk.hasAlac) {
                tracks.push_back(std::move(trk));
            }
        } else if (boxType == makeFourCC('-', '-', '-', '-')) {
            parseFreeformTag(file, bodyStart, boxEnd);
        } else if (boxType == makeFourCC('s', 't', 's', 'd')) {
            parseStsd(file, bodyStart, boxEnd, currentTrack);
        } else if (boxType == makeFourCC('s', 't', 't', 's') && bodyStart + 8 <= boxEnd) {
            uint8_t fb[8];
            if (readAt(file, bodyStart, fb, 8)) {
                uint32_t cnt = readBe32(fb + 4);
                size_t bytes = static_cast<size_t>(cnt) * 8u;
                if (cnt < 2000000u && bodyStart + 8 + bytes <= boxEnd) {
                    std::vector<uint8_t> buf(bytes);
                    if (readAt(file, bodyStart + 8, buf.data(), bytes)) {
                        currentTrack.sttsCounts.resize(cnt);
                        currentTrack.sttsDeltas.resize(cnt);
                        for (uint32_t i = 0; i < cnt; ++i) {
                            currentTrack.sttsCounts[i] = readBe32(buf.data() + i * 8);
                            currentTrack.sttsDeltas[i] = readBe32(buf.data() + i * 8 + 4);
                        }
                    }
                }
            }
        } else if (boxType == makeFourCC('s', 't', 's', 'c') && bodyStart + 8 <= boxEnd) {
            uint8_t fb[8];
            if (readAt(file, bodyStart, fb, 8)) {
                uint32_t cnt = readBe32(fb + 4);
                size_t bytes = static_cast<size_t>(cnt) * 12u;
                if (cnt < 2000000u && bodyStart + 8 + bytes <= boxEnd) {
                    std::vector<uint8_t> buf(bytes);
                    if (readAt(file, bodyStart + 8, buf.data(), bytes)) {
                        currentTrack.stscFirstChunk.resize(cnt);
                        currentTrack.stscSamplesPerChunk.resize(cnt);
                        for (uint32_t i = 0; i < cnt; ++i) {
                            currentTrack.stscFirstChunk[i] = readBe32(buf.data() + i * 12);
                            currentTrack.stscSamplesPerChunk[i] = readBe32(buf.data() + i * 12 + 4);
                        }
                    }
                }
            }
        } else if (boxType == makeFourCC('s', 't', 's', 'z') && bodyStart + 12 <= boxEnd) {
            uint8_t fb[12];
            if (readAt(file, bodyStart, fb, 12)) {
                currentTrack.stszConstantSize = readBe32(fb + 4);
                currentTrack.stszCount = readBe32(fb + 8);
                if (currentTrack.stszConstantSize == 0 && currentTrack.stszCount < 5000000u) {
                    size_t bytes = static_cast<size_t>(currentTrack.stszCount) * 4u;
                    if (bodyStart + 12 + bytes <= boxEnd) {
                        std::vector<uint8_t> buf(bytes);
                        if (readAt(file, bodyStart + 12, buf.data(), bytes)) {
                            currentTrack.stszSizes.resize(currentTrack.stszCount);
                            for (uint32_t i = 0; i < currentTrack.stszCount; ++i) {
                                currentTrack.stszSizes[i] = readBe32(buf.data() + i * 4);
                            }
                        }
                    }
                }
            }
        } else if (boxType == makeFourCC('s', 't', 'c', 'o') && bodyStart + 8 <= boxEnd) {
            uint8_t fb[8];
            if (readAt(file, bodyStart, fb, 8)) {
                uint32_t cnt = readBe32(fb + 4);
                size_t bytes = static_cast<size_t>(cnt) * 4u;
                if (cnt < 5000000u && bodyStart + 8 + bytes <= boxEnd) {
                    std::vector<uint8_t> buf(bytes);
                    if (readAt(file, bodyStart + 8, buf.data(), bytes)) {
                        currentTrack.chunkOffsets.resize(cnt);
                        for (uint32_t i = 0; i < cnt; ++i) {
                            currentTrack.chunkOffsets[i] = readBe32(buf.data() + i * 4);
                        }
                    }
                }
            }
        } else if (boxType == makeFourCC('c', 'o', '6', '4') && bodyStart + 8 <= boxEnd) {
            uint8_t fb[8];
            if (readAt(file, bodyStart, fb, 8)) {
                uint32_t cnt = readBe32(fb + 4);
                size_t bytes = static_cast<size_t>(cnt) * 8u;
                if (cnt < 5000000u && bodyStart + 8 + bytes <= boxEnd) {
                    std::vector<uint8_t> buf(bytes);
                    if (readAt(file, bodyStart + 8, buf.data(), bytes)) {
                        currentTrack.chunkOffsets.resize(cnt);
                        for (uint32_t i = 0; i < cnt; ++i) {
                            currentTrack.chunkOffsets[i] = readBe64(buf.data() + i * 8);
                        }
                    }
                }
            }
        }

        pos = boxEnd;
    }
    return true;
}

bool AlacDecoder::buildPacketTable(const TrackAtoms& track) {
    if (!track.hasAlac || track.stszCount == 0 || track.chunkOffsets.empty() || track.stscFirstChunk.empty()) {
        return false;
    }
    if (track.stszConstantSize == 0 && track.stszSizes.size() != track.stszCount) {
        return false;
    }

    mConfig = track.config;
    mSampleRate = mConfig.sampleRate;
    mChannels = mConfig.numChannels;
    mBitDepth = mConfig.bitDepth;

    mPackets.resize(track.stszCount);

    uint32_t packetIdx = 0;
    size_t stscIdx = 0;
    uint32_t numChunks = static_cast<uint32_t>(track.chunkOffsets.size());

    for (uint32_t chunkIdx = 0; chunkIdx < numChunks && packetIdx < track.stszCount; ++chunkIdx) {
        uint32_t chunkNum1 = chunkIdx + 1;
        while (stscIdx + 1 < track.stscFirstChunk.size() &&
               track.stscFirstChunk[stscIdx + 1] <= chunkNum1) {
            ++stscIdx;
        }
        uint32_t spc = track.stscSamplesPerChunk[stscIdx];
        uint64_t offset = track.chunkOffsets[chunkIdx];

        for (uint32_t s = 0; s < spc && packetIdx < track.stszCount; ++s) {
            uint32_t sz = (track.stszConstantSize > 0) ? track.stszConstantSize : track.stszSizes[packetIdx];
            mPackets[packetIdx].offset = offset;
            mPackets[packetIdx].size = sz;
            offset += sz;
            ++packetIdx;
        }
    }

    if (packetIdx != track.stszCount) {
        mPackets.resize(packetIdx);
    }
    if (mPackets.empty()) return false;

    // Assign sample counts and cumulative start frames using stts (fallback to frameLength)
    uint64_t totalSamples = 0;
    size_t sttsIdx = 0;
    uint32_t sttsUsedInRun = 0;
    for (size_t i = 0; i < mPackets.size(); ++i) {
        uint32_t dur = mConfig.frameLength;
        while (sttsIdx < track.sttsCounts.size() && sttsUsedInRun >= track.sttsCounts[sttsIdx]) {
            sttsUsedInRun = 0;
            ++sttsIdx;
        }
        if (sttsIdx < track.sttsDeltas.size() && track.sttsDeltas[sttsIdx] > 0) {
            dur = track.sttsDeltas[sttsIdx];
            ++sttsUsedInRun;
        }
        mPackets[i].startSample = totalSamples;
        mPackets[i].sampleCount = dur;
        totalSamples += dur;
    }

    mTotalFrames = totalSamples;
    return mTotalFrames > 0;
}

bool AlacDecoder::parseMp4(FILE* file, uint64_t fileSize) {
    TrackAtoms dummy;
    std::vector<TrackAtoms> tracks;
    walkBoxes(file, 0, fileSize, dummy, tracks);
    if (tracks.empty()) return false;
    return buildPacketTable(tracks.front());
}

bool AlacDecoder::decodePacket(size_t packetIdx) {
    if (!mFile || packetIdx >= mPackets.size()) return false;
    const PacketEntry& pkt = mPackets[packetIdx];
    if (pkt.size == 0 || pkt.size > 16u * 1024u * 1024u) return false;

    if (mPacketBuf.size() < pkt.size + 4) {
        mPacketBuf.resize(pkt.size + 4);
    }
    if (!readAt(mFile, pkt.offset, mPacketBuf.data(), pkt.size)) return false;
    std::memset(mPacketBuf.data() + pkt.size, 0, 4);

    BitReader br(mPacketBuf.data(), pkt.size);

    uint32_t maxSamples = std::max<uint32_t>(mConfig.frameLength, pkt.sampleCount);
    for (int c = 0; c < 2; ++c) {
        if (mMixBuf[c].size() < maxSamples) mMixBuf[c].resize(maxSamples);
        if (mPredBuf[c].size() < maxSamples) mPredBuf[c].resize(maxSamples);
        if (mShiftBuf[c].size() < maxSamples) mShiftBuf[c].resize(maxSamples);
    }

    const float normScale = 1.0f / static_cast<float>(1u << (mConfig.bitDepth - 1));
    uint32_t totalDecodedThisPacket = 0;

    while (br.bitsLeft() >= 3) {
        uint32_t tag = br.readBits(3);
        if (tag == 7) break; // ID_END

        if (tag == 0 || tag == 1) {
            uint32_t chCount = (tag == 1) ? 2u : 1u;
            br.readBits(4);  // elementInstanceTag
            br.readBits(12); // unused
            uint32_t hasSize = br.readBits(1);
            uint32_t bytesShifted = br.readBits(2);
            uint32_t escapeFlag = br.readBits(1);
            uint32_t numSamples = hasSize ? br.readBits(32) : mConfig.frameLength;
            if (numSamples == 0 || numSamples > maxSamples * 2u) {
                if (numSamples == 0) break;
                for (int c = 0; c < 2; ++c) {
                    mMixBuf[c].resize(numSamples);
                    mPredBuf[c].resize(numSamples);
                    mShiftBuf[c].resize(numSamples);
                }
            }

            if (mDecodedStereoBuf.size() < numSamples * 2) {
                mDecodedStereoBuf.resize(numSamples * 2);
            }

            if (escapeFlag != 0) {
                // Uncompressed raw PCM frames
                for (uint32_t s = 0; s < numSamples; ++s) {
                    int32_t l = br.readSignedBits(mConfig.bitDepth);
                    int32_t r = (chCount == 2) ? br.readSignedBits(mConfig.bitDepth) : l;
                    mDecodedStereoBuf[s * 2] = static_cast<float>(l) * normScale;
                    mDecodedStereoBuf[s * 2 + 1] = static_cast<float>(r) * normScale;
                }
            } else {
                uint8_t mixBits = static_cast<uint8_t>(br.readBits(8));
                int8_t mixRes = static_cast<int8_t>(br.readBits(8));

                uint8_t mode[2]{};
                uint8_t denShift[2]{};
                uint8_t pbFactor[2]{};
                uint8_t numCoefs[2]{};
                int16_t coefs[2][32]{};

                for (uint32_t c = 0; c < chCount; ++c) {
                    mode[c] = static_cast<uint8_t>(br.readBits(4));
                    denShift[c] = static_cast<uint8_t>(br.readBits(4));
                    pbFactor[c] = static_cast<uint8_t>(br.readBits(3));
                    numCoefs[c] = static_cast<uint8_t>(br.readBits(5));
                    for (uint8_t k = 0; k < numCoefs[c]; ++k) {
                        coefs[c][k] = static_cast<int16_t>(br.readBits(16));
                    }
                }

                if (bytesShifted != 0) {
                    int shiftBits = static_cast<int>(bytesShifted * 8u);
                    for (uint32_t s = 0; s < numSamples; ++s) {
                        for (uint32_t c = 0; c < chCount; ++c) {
                            mShiftBuf[c][s] = static_cast<uint16_t>(br.readBits(shiftBits));
                        }
                    }
                }

                int chanBits = static_cast<int>(mConfig.bitDepth) - static_cast<int>(bytesShifted * 8u) +
                               (chCount == 2 ? 1 : 0);

                for (uint32_t c = 0; c < chCount; ++c) {
                    uint32_t pbEff = (static_cast<uint32_t>(mConfig.pb) * pbFactor[c]) >> 2;
                    dynDecompress(
                        br,
                        mPredBuf[c].data(),
                        numSamples,
                        mConfig.mb,
                        pbEff,
                        mConfig.kb,
                        chanBits
                    );

                    if (mode[c] == 0) {
                        unpcBlockExact(
                            mPredBuf[c].data(),
                            mMixBuf[c].data(),
                            numSamples,
                            coefs[c],
                            numCoefs[c],
                            static_cast<uint32_t>(chanBits),
                            denShift[c]
                        );
                    } else {
                        // mode == 15: two-stage FIR + 1st-order difference
                        unpcBlockExact(
                            mPredBuf[c].data(),
                            mMixBuf[c].data(),
                            numSamples,
                            nullptr,
                            31,
                            static_cast<uint32_t>(chanBits),
                            0
                        );
                        unpcBlockExact(
                            mMixBuf[c].data(),
                            mMixBuf[c].data(),
                            numSamples,
                            coefs[c],
                            numCoefs[c],
                            static_cast<uint32_t>(chanBits),
                            denShift[c]
                        );
                    }
                }

                if (chCount == 1) {
                    int shiftBits = static_cast<int>(bytesShifted * 8u);
                    for (uint32_t s = 0; s < numSamples; ++s) {
                        int32_t v = signExtend(mMixBuf[0][s], chanBits);
                        if (shiftBits > 0) {
                            v = (v << shiftBits) | static_cast<int32_t>(mShiftBuf[0][s]);
                        }
                        v = signExtend(v, mConfig.bitDepth);
                        float f = static_cast<float>(v) * normScale;
                        mDecodedStereoBuf[s * 2] = f;
                        mDecodedStereoBuf[s * 2 + 1] = f;
                    }
                } else {
                    std::vector<int32_t> left(numSamples);
                    std::vector<int32_t> right(numSamples);
                    unmixStereo(
                        mMixBuf[0].data(),
                        mMixBuf[1].data(),
                        left.data(),
                        right.data(),
                        numSamples,
                        mixBits,
                        mixRes,
                        mConfig.bitDepth,
                        bytesShifted,
                        mShiftBuf[0].data(),
                        mShiftBuf[1].data()
                    );
                    for (uint32_t s = 0; s < numSamples; ++s) {
                        mDecodedStereoBuf[s * 2] = static_cast<float>(left[s]) * normScale;
                        mDecodedStereoBuf[s * 2 + 1] = static_cast<float>(right[s]) * normScale;
                    }
                }
            }

            // Only emit primary SCE/CPE channels; skip extra surround elements if any
            if (totalDecodedThisPacket == 0) {
                totalDecodedThisPacket = numSamples;
                break;
            }
        } else if (tag == 4 || tag == 6) {
            // DSE / FIL element: stop parsing further audio elements after skipping or break cleanly
            break;
        } else {
            break;
        }
    }

    mDecodedStereoPos = 0;
    mDecodedStereoAvail = totalDecodedThisPacket;
    return totalDecodedThisPacket > 0;
}

bool AlacDecoder::seek(uint64_t targetFrame) {
    if (mPackets.empty()) return false;
    if (targetFrame >= mTotalFrames) {
        targetFrame = (mTotalFrames > 0) ? (mTotalFrames - 1) : 0;
    }

    auto it = std::upper_bound(
        mPackets.begin(),
        mPackets.end(),
        targetFrame,
        [](uint64_t frame, const PacketEntry& p) { return frame < p.startSample; }
    );
    size_t idx = (it == mPackets.begin()) ? 0 : static_cast<size_t>(it - mPackets.begin() - 1);

    mDecodedStereoPos = 0;
    mDecodedStereoAvail = 0;
    mNextPacketIdx = idx;

    if (!decodePacket(idx)) {
        return false;
    }
    mNextPacketIdx = idx + 1;

    uint64_t offsetInPacket = (targetFrame > mPackets[idx].startSample)
                                  ? (targetFrame - mPackets[idx].startSample)
                                  : 0;
    if (offsetInPacket < mDecodedStereoAvail) {
        mDecodedStereoPos = static_cast<size_t>(offsetInPacket);
    } else {
        mDecodedStereoPos = mDecodedStereoAvail;
    }
    return true;
}

uint64_t AlacDecoder::readStereoFrames(float* pOut, uint64_t maxFrames) {
    if (!pOut || maxFrames == 0) return 0;
    uint64_t written = 0;

    while (written < maxFrames) {
        if (mDecodedStereoPos < mDecodedStereoAvail) {
            size_t avail = mDecodedStereoAvail - mDecodedStereoPos;
            size_t toCopy = std::min<size_t>(avail, static_cast<size_t>(maxFrames - written));
            std::memcpy(
                pOut + written * 2,
                mDecodedStereoBuf.data() + mDecodedStereoPos * 2,
                toCopy * 2 * sizeof(float)
            );
            mDecodedStereoPos += toCopy;
            written += toCopy;
            continue;
        }

        if (mNextPacketIdx >= mPackets.size()) {
            break;
        }
        if (!decodePacket(mNextPacketIdx)) {
            ++mNextPacketIdx;
            continue;
        }
        ++mNextPacketIdx;
    }

    return written;
}
