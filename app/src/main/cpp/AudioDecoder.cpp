#define DR_FLAC_IMPLEMENTATION
#define MINIMP3_IMPLEMENTATION
#define MINIMP3_FLOAT_OUTPUT
#define MINIMP3_ONLY_MP3
#define MINIMP3_ALLOW_MONO_STEREO_TRANSITION
#define DR_WAV_IMPLEMENTATION
#include "AudioDecoder.h"
#include <algorithm>
#include <cctype>
#include <cstring>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <cstdio>

#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "AudioDecoder", __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "AudioDecoder", __VA_ARGS__)

static constexpr size_t RESAMPLE_CHUNK_FRAMES = 512;

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
    mResampleSrcPos = 0;
    mResampleSrcAvail = 0;
    mResamplePrev[0] = 0.0f;
    mResamplePrev[1] = 0.0f;
    mResampleCurr[0] = 0.0f;
    mResampleCurr[1] = 0.0f;
    mResampleFrac = 0.0;
    mResamplePrimed = false;
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

    // Upsample low / non-standard sample rates (< 44100 Hz, common in 80-128 kbps MP3s)
    // to 44100 Hz or 48000 Hz so hardware AudioTrack/OpenSL ES and 10-band EQ operate cleanly.
    if (mSourceSampleRate < 44100) {
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

bool AudioDecoder::openFile(const std::string& filePath) {
    if (filePath.rfind("/proc/self/fd/", 0) == 0) {
        int fd = -1;
        if (sscanf(filePath.c_str(), "/proc/self/fd/%d", &fd) == 1 && fd >= 0) {
            LOGD("openFile: detected proc fd path '%s' -> delegating to openFd(%d)", filePath.c_str(), fd);
            return openFd(fd);
        }
    }

    close();
    mCurrentFrame = 0;

    // Try FLAC first (magic: fLaC)
    mFlac = drflac_open_file(filePath.c_str(), nullptr);
    if (mFlac) {
        mFormat = AudioFormatType::FLAC;
        mSourceSampleRate = mFlac->sampleRate;
        mSourceChannels = mFlac->channels;
        mSourceTotalFrames = mFlac->totalPCMFrameCount;
        configureOutputFormat();
        LOGD("Opened FLAC: %s (SrcSR: %u->%u, SrcCh: %u->%u, Frames: %llu)",
             filePath.c_str(), mSourceSampleRate, mSampleRate, mSourceChannels, mChannels, (unsigned long long)mTotalFrames);
        return true;
    }

    // Try WAV (magic: RIFF)
    if (drwav_init_file(mWav, filePath.c_str(), nullptr)) {
        mFormat = AudioFormatType::WAV;
        mSourceSampleRate = mWav->sampleRate;
        mSourceChannels = mWav->channels;
        mSourceTotalFrames = mWav->totalPCMFrameCount;
        configureOutputFormat();
        LOGD("Opened WAV: %s (SrcSR: %u->%u, SrcCh: %u->%u, Frames: %llu)",
             filePath.c_str(), mSourceSampleRate, mSampleRate, mSourceChannels, mChannels, (unsigned long long)mTotalFrames);
        return true;
    }

    // Try MP3 last (ID3v2 or raw MPEG)
    int mp3Flags = MP3D_SEEK_TO_SAMPLE | MP3D_ALLOW_MONO_STEREO_TRANSITION;
    if (mp3dec_ex_open(mMp3, filePath.c_str(), mp3Flags) == 0 && mMp3->info.hz > 0 && mMp3->info.channels > 0) {
        mFormat = AudioFormatType::MP3;
        mSourceSampleRate = static_cast<uint32_t>(mMp3->info.hz);
        mSourceChannels = static_cast<uint32_t>(mMp3->info.channels);
        mSourceTotalFrames = mMp3->samples / (mSourceChannels > 0 ? mSourceChannels : 1);
        configureOutputFormat();
        LOGD("Opened MP3: %s (SrcSR: %u->%u, SrcCh: %u->%u, Frames: %llu)",
             filePath.c_str(), mSourceSampleRate, mSampleRate, mSourceChannels, mChannels, (unsigned long long)mTotalFrames);
        return true;
    } else {
        mp3dec_ex_close(mMp3);
    }

    LOGE("Failed to open file as FLAC, WAV, or MP3: %s", filePath.c_str());
    return false;
}

bool AudioDecoder::openFd(int fd) {
    close();
    mCurrentFrame = 0;

    if (fd < 0) {
        LOGE("openFd: Invalid fd %d", fd);
        return false;
    }

    // 1. Try FLAC first
    int flacFd = dup(fd);
    if (flacFd >= 0) {
        lseek(flacFd, 0, SEEK_SET);
        FILE* flacFile = fdopen(flacFd, "rb");
        if (flacFile) {
            mFlac = drflac_open(drflac__on_read_stdio, drflac__on_seek_stdio, drflac__on_tell_stdio, (void*)flacFile, nullptr);
            if (mFlac) {
                mFormat = AudioFormatType::FLAC;
                mSourceSampleRate = mFlac->sampleRate;
                mSourceChannels = mFlac->channels;
                mSourceTotalFrames = mFlac->totalPCMFrameCount;
                configureOutputFormat();
                LOGD("Opened FLAC via FD %d: SrcSR: %u->%u, SrcCh: %u->%u, Frames: %llu",
                     fd, mSourceSampleRate, mSampleRate, mSourceChannels, mChannels, (unsigned long long)mTotalFrames);
                return true;
            }
            fclose(flacFile);
        } else {
            ::close(flacFd);
        }
    }

    // 2. Try WAV second
    int wavFd = dup(fd);
    if (wavFd >= 0) {
        lseek(wavFd, 0, SEEK_SET);
        FILE* wavFile = fdopen(wavFd, "rb");
        if (wavFile) {
            if (drwav_init(mWav, drwav__on_read_stdio, drwav__on_seek_stdio, drwav__on_tell_stdio, (void*)wavFile, nullptr)) {
                mFormat = AudioFormatType::WAV;
                mSourceSampleRate = mWav->sampleRate;
                mSourceChannels = mWav->channels;
                mSourceTotalFrames = mWav->totalPCMFrameCount;
                configureOutputFormat();
                LOGD("Opened WAV via FD %d: SrcSR: %u->%u, SrcCh: %u->%u, Frames: %llu",
                     fd, mSourceSampleRate, mSampleRate, mSourceChannels, mChannels, (unsigned long long)mTotalFrames);
                return true;
            }
            fclose(wavFile);
        } else {
            ::close(wavFd);
        }
    }

    // 3. Try MP3 last
    int mp3Fd = dup(fd);
    if (mp3Fd >= 0) {
        struct stat st;
        if (fstat(mp3Fd, &st) == 0 && st.st_size > 0) {
            void* mapped = mmap(NULL, st.st_size, PROT_READ, MAP_PRIVATE, mp3Fd, 0);
            if (mapped != MAP_FAILED) {
                int mp3Flags = MP3D_SEEK_TO_SAMPLE | MP3D_ALLOW_MONO_STEREO_TRANSITION;
                if (mp3dec_ex_open_buf(mMp3, (const uint8_t*)mapped, (size_t)st.st_size, mp3Flags) == 0 &&
                    mMp3->info.hz > 0 && mMp3->info.channels > 0) {
                    mMp3->file.buffer = (const uint8_t*)mapped;
                    mMp3->file.size = (size_t)st.st_size;
                    mMp3->is_file = 1; // triggers munmap in mp3dec_ex_close
                    mFormat = AudioFormatType::MP3;
                    mSourceSampleRate = static_cast<uint32_t>(mMp3->info.hz);
                    mSourceChannels = static_cast<uint32_t>(mMp3->info.channels);
                    mSourceTotalFrames = mMp3->samples / (mSourceChannels > 0 ? mSourceChannels : 1);
                    configureOutputFormat();
                    LOGD("Opened MP3 via FD %d: SrcSR: %u->%u, SrcCh: %u->%u, Frames: %llu",
                         fd, mSourceSampleRate, mSampleRate, mSourceChannels, mChannels, (unsigned long long)mTotalFrames);
                    ::close(mp3Fd);
                    return true;
                }
                mp3dec_ex_close(mMp3);
                munmap(mapped, st.st_size);
            }
        }
        ::close(mp3Fd);
    }

    LOGE("Failed to open FD %d as FLAC, WAV, or MP3", fd);
    return false;
}

void AudioDecoder::close() {
    if (mFormat == AudioFormatType::FLAC && mFlac) {
        drflac_close(mFlac);
        mFlac = nullptr;
    } else if (mFormat == AudioFormatType::WAV) {
        drwav_uninit(mWav);
    } else if (mFormat == AudioFormatType::MP3) {
        mp3dec_ex_close(mMp3);
    }
    mFormat = AudioFormatType::UNKNOWN;
    mSourceSampleRate = 0;
    mSourceChannels = 0;
    mSourceTotalFrames = 0;
    mSampleRate = 0;
    mTotalFrames = 0;
    mCurrentFrame = 0;
    resetResamplerState();
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
    } else if (mFormat == AudioFormatType::WAV) {
        drwav_bool32 ok = drwav_seek_to_pcm_frame(mWav, (drwav_uint64)sourceTargetFrame);
        if (ok) {
            mCurrentFrame = (uint64_t)targetFrame;
            return targetFrame;
        }
    } else if (mFormat == AudioFormatType::MP3) {
        uint64_t srcCh = (mSourceChannels > 0) ? mSourceChannels : 1;
        if (mp3dec_ex_seek(mMp3, (uint64_t)sourceTargetFrame * srcCh) == 0) {
            mCurrentFrame = (uint64_t)targetFrame;
            return targetFrame;
        }
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
            std::vector<float> tmp(maxSourceFrames * mSourceChannels);
            uint64_t decoded = drflac_read_pcm_frames_f32(mFlac, maxSourceFrames, tmp.data());
            for (uint64_t i = 0; i < decoded; ++i) {
                pStereoOut[2 * i]     = tmp[i * mSourceChannels];
                pStereoOut[2 * i + 1] = tmp[i * mSourceChannels + 1];
            }
            return decoded;
        }
    } else if (mFormat == AudioFormatType::WAV) {
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
            std::vector<float> tmp(maxSourceFrames * mSourceChannels);
            uint64_t decoded = drwav_read_pcm_frames_f32(mWav, maxSourceFrames, tmp.data());
            for (uint64_t i = 0; i < decoded; ++i) {
                pStereoOut[2 * i]     = tmp[i * mSourceChannels];
                pStereoOut[2 * i + 1] = tmp[i * mSourceChannels + 1];
            }
            return decoded;
        }
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

    // Stateful linear stereo resampler for low/non-standard sample rates (e.g., 22050Hz, 24000Hz, 32000Hz -> 44100/48000Hz)
    const double step = static_cast<double>(mSourceSampleRate) / static_cast<double>(mSampleRate);
    uint64_t outFrames = 0;

    auto pullNextSourceFrame = [this](float outFrame[2]) -> bool {
        if (mResampleSrcPos >= mResampleSrcAvail) {
            mResampleSrcPos = 0;
            mResampleSrcAvail = static_cast<size_t>(
                decodeSourceStereoFrames(mResampleSrcBuf.data(), RESAMPLE_CHUNK_FRAMES)
            );
            if (mResampleSrcAvail == 0) {
                return false;
            }
        }
        outFrame[0] = mResampleSrcBuf[mResampleSrcPos * 2];
        outFrame[1] = mResampleSrcBuf[mResampleSrcPos * 2 + 1];
        mResampleSrcPos++;
        return true;
    };

    if (!mResamplePrimed) {
        if (!pullNextSourceFrame(mResamplePrev)) {
            return 0;
        }
        if (!pullNextSourceFrame(mResampleCurr)) {
            mResampleCurr[0] = mResamplePrev[0];
            mResampleCurr[1] = mResamplePrev[1];
        }
        mResampleFrac = 0.0;
        mResamplePrimed = true;
    }

    while (outFrames < maxFrames) {
        while (mResampleFrac >= 1.0) {
            mResamplePrev[0] = mResampleCurr[0];
            mResamplePrev[1] = mResampleCurr[1];
            if (!pullNextSourceFrame(mResampleCurr)) {
                mCurrentFrame += outFrames;
                return outFrames;
            }
            mResampleFrac -= 1.0;
        }

        float t = static_cast<float>(mResampleFrac);
        pOutput[outFrames * 2]     = mResamplePrev[0] + t * (mResampleCurr[0] - mResamplePrev[0]);
        pOutput[outFrames * 2 + 1] = mResamplePrev[1] + t * (mResampleCurr[1] - mResamplePrev[1]);

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
