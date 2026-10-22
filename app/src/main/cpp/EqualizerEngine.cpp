#include "EqualizerEngine.h"

constexpr float EqualizerEngine::BAND_FREQUENCIES[NUM_BANDS];

EqualizerEngine::EqualizerEngine() {
    mSampleRate = 44100.0f;
    mChannels = 2;
    mEnabled = true;
    mBassBoostStrength = 0.35f;

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
        // Gain from 0 to +15dB depending on strength (0.0 to 1.0)
        float bassGainDb = mBassBoostStrength * 15.0f;
        mBassFilters[ch].setParameters(FilterType::LOW_SHELF, mSampleRate, 80.0f, 0.707f, bassGainDb);
        
        // 10-band EQ
        for (int b = 0; b < NUM_BANDS; ++b) {
            float freq = BAND_FREQUENCIES[b];
            float gain = mBandGains[b];
            
            // For the lowest band, we could use LowShelf, and for the highest HighShelf.
            // But Peaking EQ works well for standard graphic equalizers.
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
    
    // We expect numSamples to be total floats (frames * channels)
    // The lock prevents parameters changing mid-buffer, but we use try_lock
    // to avoid blocking the audio thread if the UI is currently updating filters.
    if (!mLock.try_lock()) {
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
            
            // Apply 10-band EQ
            for (int b = 0; b < NUM_BANDS; ++b) {
                // Optimization: skip if gain is exactly 0
                if (std::abs(mBandGains[b]) > 0.01f) {
                    sample = mFilters[ch][b].process(sample);
                }
            }
            
            buffer[index] = sample;
        }
    }
    
    mLock.unlock();
}
