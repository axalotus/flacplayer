#pragma once

#include "BiquadFilter.h"
#include <vector>
#include <mutex>
#include <cstdint>

class EqualizerEngine {
public:
    static constexpr int NUM_BANDS = 10;
    static constexpr float BAND_FREQUENCIES[NUM_BANDS] = {
        31.0f, 62.0f, 125.0f, 250.0f, 500.0f, 
        1000.0f, 2000.0f, 4000.0f, 8000.0f, 16000.0f
    };

    EqualizerEngine();
    ~EqualizerEngine();

    void init(float sampleRate, int channels);
    void setSampleRate(float sampleRate);
    
    void setEnabled(bool enabled);
    bool isEnabled() const { return mEnabled; }
    
    void setBandGain(int bandIndex, float gainDb);
    float getBandGain(int bandIndex) const;
    void setBassBoost(float strength);
    float getBassBoost() const { return mBassBoostStrength; }

    void loadStockPreset(int presetIndex);

    void process(float* buffer, int numFrames, int channels);
    void processBuffer(float* buffer, int32_t numSamples);

private:
    mutable std::mutex mLock;
    
    bool mEnabled = true;
    float mSampleRate = 44100.0f;
    int mChannels = 2;
    
    float mBandGains[NUM_BANDS];
    float mBassBoostStrength = 0.0f;
    
    std::vector<std::vector<BiquadFilter>> mFilters;
    std::vector<BiquadFilter> mBassFilters;

    void updateFilters();
};
