#include "EqualizerEngine.h"
#include <algorithm>

EqualizerEngine::EqualizerEngine() {
    for (int i = 0; i < NUM_BANDS; ++i) {
        mBandGains[i] = 0.0f;
    }
    init(44100.0f, 2);
}

EqualizerEngine::~EqualizerEngine() = default;

void EqualizerEngine::init(float sampleRate, int channels) {
    std::lock_guard<std::mutex> lock(mLock);
    mSampleRate = sampleRate;
    mChannels = channels;

    mFilters.resize(mChannels);
    for (int ch = 0; ch < mChannels; ++ch) {
        mFilters[ch].resize(NUM_BANDS);
    }
    mBassFilters.resize(mChannels);

    updateFilters();
}

void EqualizerEngine::setSampleRate(float sampleRate) {
    if (sampleRate > 0 && sampleRate != mSampleRate) {
        init(sampleRate, mChannels);
    }
}

void EqualizerEngine::setEnabled(bool enabled) {
    std::lock_guard<std::mutex> lock(mLock);
    mEnabled = enabled;
}

void EqualizerEngine::setBandGain(int bandIndex, float gainDb) {
    if (bandIndex < 0 || bandIndex >= NUM_BANDS) return;
    
    std::lock_guard<std::mutex> lock(mLock);
    mBandGains[bandIndex] = (std::max)(-12.0f, (std::min)(12.0f, gainDb));
    updateFilters();
}

float EqualizerEngine::getBandGain(int bandIndex) const {
    if (bandIndex < 0 || bandIndex >= NUM_BANDS) return 0.0f;
    std::lock_guard<std::mutex> lock(mLock);
    return mBandGains[bandIndex];
}

void EqualizerEngine::setBassBoost(float strength) {
    std::lock_guard<std::mutex> lock(mLock);
    mBassBoostStrength = (std::max)(0.0f, (std::min)(1.0f, strength));
    updateFilters();
}

void EqualizerEngine::loadStockPreset(int presetIndex) {
    // 0: Flat, 1: Bass Boost, 2: Vocal, 3: Rock, 4: Jazz, 5: Studio
    static const float presets[6][10] = {
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 },             // Flat
        { 6, 5, 4, 2, 0, 0, 0, 0, 0, 0 },             // Bass Boost
        { -2, -1, 0, 2, 4, 4, 3, 1, 0, -1 },          // Vocal
        { 4, 3, 2, 0, -1, 1, 2, 3, 4, 4 },            // Rock
        { 3, 2, 0, 1, 2, 2, 1, 2, 3, 3 },             // Jazz
        { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 }              // Studio
    };

    if (presetIndex < 0 || presetIndex >= 6) return;

    std::lock_guard<std::mutex> lock(mLock);
    for (int i = 0; i < NUM_BANDS; ++i) {
        mBandGains[i] = presets[presetIndex][i];
    }
    updateFilters();
}

void EqualizerEngine::updateFilters() {
    float q = 1.414f;

    for (int ch = 0; ch < mChannels; ++ch) {
        for (int band = 0; band < NUM_BANDS; ++band) {
            mFilters[ch][band].setPeakingEQ(mSampleRate, BAND_FREQUENCIES[band], mBandGains[band], q);
        }
        
        float bassGainDb = mBassBoostStrength * 9.0f;
        mBassFilters[ch].setLowShelf(mSampleRate, 80.0f, bassGainDb, 0.707f);
    }
}

void EqualizerEngine::process(float* buffer, int numFrames, int channels) {
    if (channels != mChannels) {
        init(mSampleRate, channels);
    }
    processBuffer(buffer, numFrames * channels);
}

void EqualizerEngine::processBuffer(float* buffer, int32_t numSamples) {
    if (!mEnabled || mChannels <= 0) return;

    std::lock_guard<std::mutex> lock(mLock);
    int numFrames = numSamples / mChannels;

    for (int ch = 0; ch < mChannels; ++ch) {
        for (int i = 0; i < numFrames; ++i) {
            int sampleIdx = i * mChannels + ch;
            float sample = buffer[sampleIdx];

            for (int band = 0; band < NUM_BANDS; ++band) {
                if (mBandGains[band] != 0.0f) {
                    sample = mFilters[ch][band].process(sample);
                }
            }

            if (mBassBoostStrength > 0.0f) {
                sample = mBassFilters[ch].process(sample);
            }

            buffer[sampleIdx] = sample;
        }
    }
}
