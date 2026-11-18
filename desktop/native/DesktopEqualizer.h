#ifndef DESKTOP_EQUALIZER_H
#define DESKTOP_EQUALIZER_H

#include "BiquadFilter.h"
#include <atomic>
#include <cstdint>
#include <mutex>
#include <vector>

class DesktopEqualizer {
public:
    static constexpr int NUM_BANDS = 10;
    static constexpr int MAX_CHANNELS = 8;
    static constexpr float BAND_FREQUENCIES[NUM_BANDS] = {
        31.0f, 62.0f, 125.0f, 250.0f, 500.0f,
        1000.0f, 2000.0f, 4000.0f, 8000.0f, 16000.0f
    };

    DesktopEqualizer();
    ~DesktopEqualizer() = default;

    void init(float sampleRate, int channels);
    void setEnabled(bool enabled);
    void setPreampDb(float gainDb);
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
        float preampLinear = 1.0f;
        BiquadCoeffs bassCoeff;
        BiquadCoeffs bandCoeffs[NUM_BANDS];
    };

    std::mutex mConfigLock;
    std::atomic<bool> mResetRequested{false};
    static constexpr uint32_t TRIPLE_DIRTY_BIT = 0x100u;
    std::atomic<uint32_t> mMiddleState{2u};
    int mWriterSlot = 1;
    int mReaderSlot = 0;

    bool mEnabled = false;
    float mSampleRate = 44100.0f;
    int mChannels = 2;
    float mPreampDb = 0.0f;
    float mBandGains[NUM_BANDS]{};
    float mBassBoostStrength = 0.0f;

    EqCoeffsSnapshot mSnapshots[3];
    std::vector<std::vector<BiquadFilter>> mFilters;
    std::vector<BiquadFilter> mBassFilters;

    void updateFilters();
};

#endif
