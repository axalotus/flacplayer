#ifndef AUDIOPLAYER_EQUALIZERENGINE_H
#define AUDIOPLAYER_EQUALIZERENGINE_H

#include "BiquadFilter.h"
#include <vector>
#include <mutex>
#include <atomic>

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
    
    void setEnabled(bool enabled);
    void setBandGain(int bandIndex, float gainDb);
    void setBassBoost(float strength);

    void processBuffer(float* buffer, int32_t numSamples);
    void reset();

private:
    struct EqCoeffsSnapshot {
        bool enabled = true;
        float sampleRate = 44100.0f;
        int channels = 2;
        bool hasActiveFilter = false;
        BiquadCoeffs bassCoeff;
        BiquadCoeffs bandCoeffs[NUM_BANDS];
    };

    std::mutex mConfigLock;
    std::mutex mSnapshotLock;
    std::atomic<bool> mResetRequested{false};
    
    bool mEnabled = true;
    float mSampleRate = 44100.0f;
    int mChannels = 2;
    
    float mBandGains[NUM_BANDS];
    float mBassBoostStrength = 0.0f;

    EqCoeffsSnapshot mPublishedSnapshot;
    
    // Filter delay states owned exclusively by the audio processing thread
    std::vector<std::vector<BiquadFilter>> mFilters;
    std::vector<BiquadFilter> mBassFilters;

    void updateFilters();
};

#endif //AUDIOPLAYER_EQUALIZERENGINE_H
