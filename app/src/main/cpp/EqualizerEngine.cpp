#include "EqualizerEngine.h"

constexpr float EqualizerEngine::BAND_FREQUENCIES[NUM_BANDS];

EqualizerEngine::EqualizerEngine() {
    mSampleRate = 44100.0f;
    mChannels = 2;
    mEnabled = true;
    mBassBoostStrength = 0.0f;

    for (int i = 0; i < NUM_BANDS; ++i) {
        mBandGains[i] = 0.0f;
    }

    mFilters.resize(mChannels);
    for (int ch = 0; ch < mChannels; ++ch) {
        mFilters[ch].resize(NUM_BANDS);
    }
    mBassFilters.resize(mChannels);

    updateFilters();
}

EqualizerEngine::~EqualizerEngine() {}

void EqualizerEngine::init(float sampleRate, int channels) {
    std::lock_guard<std::mutex> lock(mLock);
    if (sampleRate > 0.0f) mSampleRate = sampleRate;
    if (channels > 0) mChannels = channels;
    
    mFilters.resize(mChannels);
    for (int ch = 0; ch < mChannels; ++ch) {
        mFilters[ch].resize(NUM_BANDS);
    }
    mBassFilters.resize(mChannels);
    
    updateFilters();
}

void EqualizerEngine::setEnabled(bool enabled) {
    std::lock_guard<std::mutex> lock(mLock);
    mEnabled = enabled;
}

void EqualizerEngine::setBandGain(int bandIndex, float gainDb) {
    if (bandIndex < 0 || bandIndex >= NUM_BANDS) return;
    
    std::lock_guard<std::mutex> lock(mLock);
    mBandGains[bandIndex] = gainDb;
    updateFilters();
}

void EqualizerEngine::setBassBoost(float strength) {
    std::lock_guard<std::mutex> lock(mLock);
    mBassBoostStrength = strength;
    updateFilters();
}

void EqualizerEngine::updateFilters() {
    if (mSampleRate <= 0.0f || mChannels <= 0) return;
    if (mFilters.size() < static_cast<size_t>(mChannels) || mBassFilters.size() < static_cast<size_t>(mChannels)) return;
    
    for (int ch = 0; ch < mChannels; ++ch) {
        if (mFilters[ch].size() < static_cast<size_t>(NUM_BANDS)) continue;

        // Bass boost filter (Low Shelf at 80Hz)
        // Gain from 0 to +12dB depending on strength (0.0 to 1.0)
        float bassGainDb = mBassBoostStrength * 12.0f;
        mBassFilters[ch].setParameters(FilterType::LOW_SHELF, mSampleRate, 80.0f, 0.707f, bassGainDb);
        
        // 10-band EQ
        for (int b = 0; b < NUM_BANDS; ++b) {
            float freq = BAND_FREQUENCIES[b];
            float gain = mBandGains[b];
            
            FilterType type = FilterType::PEAKING_EQ;
            if (b == 0) type = FilterType::LOW_SHELF;
            else if (b == NUM_BANDS - 1) type = FilterType::HIGH_SHELF;
            
            mFilters[ch][b].setParameters(type, mSampleRate, freq, 1.414f, gain);
        }
    }
}

void EqualizerEngine::processBuffer(float* buffer, int32_t numSamples) {
    if (!mEnabled || buffer == nullptr || numSamples <= 0 || mChannels <= 0) return;
    if (mFilters.size() < static_cast<size_t>(mChannels) || mBassFilters.size() < static_cast<size_t>(mChannels)) return;
    
    if (!mLock.try_lock()) {
        return; 
    }

    bool hasActiveFilter = (mBassBoostStrength > 0.01f);
    if (!hasActiveFilter) {
        for (int b = 0; b < NUM_BANDS; ++b) {
            if (std::abs(mBandGains[b]) > 0.01f && BAND_FREQUENCIES[b] < mSampleRate * 0.45f) {
                hasActiveFilter = true;
                break;
            }
        }
    }
    if (!hasActiveFilter) {
        mLock.unlock();
        return;
    }
    
    int frames = numSamples / mChannels;
    
    for (int i = 0; i < frames; ++i) {
        for (int ch = 0; ch < mChannels; ++ch) {
            int index = i * mChannels + ch;
            float sample = buffer[index];
            
            // Apply Bass Boost first
            if (mBassBoostStrength > 0.01f) {
                sample = mBassFilters[ch].process(sample);
            }
            
            // Apply 10-band EQ (only bands strictly below Nyquist)
            for (int b = 0; b < NUM_BANDS; ++b) {
                if (std::abs(mBandGains[b]) > 0.01f && BAND_FREQUENCIES[b] < mSampleRate * 0.45f) {
                    sample = mFilters[ch][b].process(sample);
                }
            }
            
            buffer[index] = sample;
        }
    }
    
    mLock.unlock();
}

void EqualizerEngine::reset() {
    std::lock_guard<std::mutex> lock(mLock);
    for (auto& chFilters : mFilters) {
        for (auto& filter : chFilters) {
            filter.reset();
        }
    }
    for (auto& filter : mBassFilters) {
        filter.reset();
    }
}
