#define DR_FLAC_IMPLEMENTATION
#define MINIMP3_IMPLEMENTATION
#define MINIMP3_FLOAT_OUTPUT
#define DR_WAV_IMPLEMENTATION
#include "AudioDecoder.h"
#include <algorithm>
#include <cctype>

#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "AudioDecoder", __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "AudioDecoder", __VA_ARGS__)

AudioDecoder::AudioDecoder() {
    mMp3 = new mp3dec_ex_t();
    mWav = new drwav();
}

AudioDecoder::~AudioDecoder() {
    close();
    delete mMp3;
    delete mWav;
}

bool AudioDecoder::openFile(const std::string& filePath) {
    close();
    mCurrentFrame = 0;

    // Try FLAC first (magic: fLaC)
    mFlac = drflac_open_file(filePath.c_str(), nullptr);
    if (mFlac) {
        mFormat = AudioFormatType::FLAC;
        mSampleRate = mFlac->sampleRate;
        mChannels = mFlac->channels;
        mTotalFrames = mFlac->totalPCMFrameCount;
        LOGD("Opened FLAC: %s (SR: %d, Ch: %d, Frames: %llu)",
             filePath.c_str(), mSampleRate, mChannels, (unsigned long long)mTotalFrames);
        return true;
    }

    // Try WAV (magic: RIFF)
    if (drwav_init_file(mWav, filePath.c_str(), nullptr)) {
        mFormat = AudioFormatType::WAV;
        mSampleRate = mWav->sampleRate;
        mChannels = mWav->channels;
        mTotalFrames = mWav->totalPCMFrameCount;
        LOGD("Opened WAV: %s (SR: %d, Ch: %d, Frames: %llu)",
             filePath.c_str(), mSampleRate, mChannels, (unsigned long long)mTotalFrames);
        return true;
    }

    // Try MP3 last (ID3v2 or raw MPEG)
    if (mp3dec_ex_open(mMp3, filePath.c_str(), MP3D_SEEK_TO_SAMPLE) == 0) {
        mFormat = AudioFormatType::MP3;
        mSampleRate = mMp3->info.hz;
        mChannels = mMp3->info.channels;
        mTotalFrames = mMp3->samples / (mChannels > 0 ? mChannels : 1);
        LOGD("Opened MP3: %s (SR: %d, Ch: %d, Frames: %llu)",
             filePath.c_str(), mSampleRate, mChannels, (unsigned long long)mTotalFrames);
        return true;
    }

    LOGE("Failed to open file as FLAC, WAV, or MP3: %s", filePath.c_str());
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
    mCurrentFrame = 0;
}

int64_t AudioDecoder::seek(int64_t targetFrame) {
    if (mFormat == AudioFormatType::UNKNOWN) return 0;
    
    if (targetFrame < 0) targetFrame = 0;
    if (targetFrame >= (int64_t)mTotalFrames) targetFrame = mTotalFrames - 1;

    if (mFormat == AudioFormatType::FLAC) {
        bool success = drflac_seek_to_pcm_frame(mFlac, (drflac_uint64)targetFrame);
        if (success) {
            mCurrentFrame = mFlac->currentPCMFrame;
            return (int64_t)mCurrentFrame;
        }
    } else if (mFormat == AudioFormatType::WAV) {
        drwav_bool32 ok = drwav_seek_to_pcm_frame(mWav, (drwav_uint64)targetFrame);
        if (ok) {
            mCurrentFrame = targetFrame;
            return (int64_t)mCurrentFrame;
        }
    } else if (mFormat == AudioFormatType::MP3) {
        if (mp3dec_ex_seek(mMp3, (uint64_t)(targetFrame * mChannels)) == 0) {
            mCurrentFrame = targetFrame;
            return (int64_t)mCurrentFrame;
        }
    }
    
    return -1;
}

uint64_t AudioDecoder::decodeFrames(float* pOutput, uint64_t maxFrames) {
    if (mFormat == AudioFormatType::UNKNOWN) return 0;
    
    if (mFormat == AudioFormatType::FLAC) {
        uint64_t decoded = drflac_read_pcm_frames_f32(mFlac, maxFrames, pOutput);
        mCurrentFrame = mFlac->currentPCMFrame;
        return decoded;
    } else if (mFormat == AudioFormatType::WAV) {
        uint64_t decoded = drwav_read_pcm_frames_f32(mWav, maxFrames, pOutput);
        mCurrentFrame += decoded;
        return decoded;
    } else if (mFormat == AudioFormatType::MP3) {
        uint64_t ch = (mChannels > 0) ? mChannels : 1;
        size_t decodedSamples = mp3dec_ex_read(mMp3, pOutput, maxFrames * ch);
        uint64_t decodedFrames = decodedSamples / ch;
        mCurrentFrame += decodedFrames;
        return decodedFrames;
    }
    
    return 0;
}

uint32_t AudioDecoder::getSampleRate() const { return mSampleRate; }
uint32_t AudioDecoder::getChannels() const { return mChannels; }
uint64_t AudioDecoder::getTotalFrames() const { return mTotalFrames; }
uint64_t AudioDecoder::getCurrentFrame() const { return mCurrentFrame; }
