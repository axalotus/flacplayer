#include "EqualizerEngine.h"
#include <algorithm>
#include <cmath>

constexpr float EqualizerEngine::BAND_FREQUENCIES[NUM_BANDS];

EqualizerEngine::EqualizerEngine() {
    mSampleRate = 44100.0f;
    mChannels = 2;
    mEnabled = true;
    mBassBoostStrength = 0.0f;

    for (int i = 0; i < NUM_BANDS; ++i) {
        mBandGains[i] = 0.0f;
    }

    mFilters.resize(MAX_CHANNELS);
    for (int ch = 0; ch < MAX_CHANNELS; ++ch) {
        mFilters[ch].resize(NUM_BANDS);
    }
    mBassFilters.resize(MAX_CHANNELS);

    updateFilters();
}

EqualizerEngine::~EqualizerEngine() {}

void EqualizerEngine::init(float sampleRate, int channels) {
    bool formatChanged = false;
    {
        std::lock_guard<std::mutex> lock(mConfigLock);
        if (sampleRate > 0.0f && sampleRate != mSampleRate) {
            mSampleRate = sampleRate;
            formatChanged = true;
        }
        if (channels > 0 && channels != mChannels) {
            mChannels = channels;
            formatChanged = true;
        }
        updateFilters();
    }
    if (formatChanged) {
        mResetRequested.store(true, std::memory_order_release);
    }
}

void EqualizerEngine::setEnabled(bool enabled) {
    std::lock_guard<std::mutex> lock(mConfigLock);
    mEnabled = enabled;
    updateFilters();
}

void EqualizerEngine::setBandGain(int bandIndex, float gainDb) {
    if (bandIndex < 0 || bandIndex >= NUM_BANDS) return;
    
    std::lock_guard<std::mutex> lock(mConfigLock);
    mBandGains[bandIndex] = gainDb;
    updateFilters();
}

void EqualizerEngine::setBassBoost(float strength) {
    std::lock_guard<std::mutex> lock(mConfigLock);
    mBassBoostStrength = strength;
    updateFilters();
}

void EqualizerEngine::updateFilters() {
    if (mSampleRate <= 0.0f || mChannels <= 0) return;

    EqCoeffsSnapshot nextSnap;
    nextSnap.enabled = mEnabled;
    nextSnap.sampleRate = mSampleRate;
    nextSnap.channels = mChannels;
    nextSnap.hasActiveFilter = false;

    float bassGainDb = (mBassBoostStrength > 0.01f) ? (mBassBoostStrength * 8.0f) : 0.0f;
    nextSnap.bassCoeff = BiquadFilter::computeCoefficients(
        FilterType::LOW_SHELF, mSampleRate, 80.0f, 0.707f, bassGainDb
    );
    if (nextSnap.bassCoeff.active) {
        nextSnap.hasActiveFilter = true;
    }

    float maxSingleBandDb = 0.0f;
    for (int b = 0; b < NUM_BANDS; ++b) {
        float freq = BAND_FREQUENCIES[b];
        float gain = mBandGains[b];
        if (gain > maxSingleBandDb) maxSingleBandDb = gain;

        FilterType type = FilterType::PEAKING_EQ;
        float q = 1.414f;
        if (b == 0) {
            type = FilterType::LOW_SHELF;
            q = 0.7071f;
        } else if (b == NUM_BANDS - 1) {
            type = FilterType::HIGH_SHELF;
            q = 0.7071f;
        }

        nextSnap.bandCoeffs[b] = BiquadFilter::computeCoefficients(
            type, mSampleRate, freq, q, gain
        );
        if (nextSnap.bandCoeffs[b].active) {
            nextSnap.hasActiveFilter = true;
        }
    }

    // Gentle automatic headroom reduction when heavy positive bass/EQ boost is active,
    // keeping boosted bass dynamic and clean without slamming into digital 0 dBFS.
    float lowSumDb = std::max(0.0f, mBandGains[0]) +
                     std::max(0.0f, mBandGains[1]) * 0.65f +
                     std::max(0.0f, bassGainDb) * 0.85f;
    float peakBoostDb = std::max(lowSumDb, maxSingleBandDb);
    float headroomDb = (peakBoostDb > 2.5f) ? -std::min(12.0f, (peakBoostDb - 2.5f) * 0.55f) : 0.0f;
    nextSnap.preampLinear = (headroomDb < -0.02f) ? std::pow(10.0f, headroomDb / 20.0f) : 1.0f;

    // Publish precomputed coefficients lock-free into triple buffer
    mSnapshots[mWriterSlot] = nextSnap;
    uint32_t newMiddle = static_cast<uint32_t>(mWriterSlot) | TRIPLE_DIRTY_BIT;
    uint32_t prevMiddle = mMiddleState.exchange(newMiddle, std::memory_order_acq_rel);
    mWriterSlot = static_cast<int>(prevMiddle & 0xFFu);
}

void EqualizerEngine::processBuffer(float* buffer, int32_t numSamples) {
    if (buffer == nullptr || numSamples <= 0) return;

    // Wait-free swap for latest snapshot if writer published an update
    uint32_t middle = mMiddleState.load(std::memory_order_relaxed);
    if (middle & TRIPLE_DIRTY_BIT) {
        uint32_t prev = mMiddleState.exchange(static_cast<uint32_t>(mReaderSlot), std::memory_order_acq_rel);
        mReaderSlot = static_cast<int>(prev & 0xFFu);
    }
    const EqCoeffsSnapshot& snap = mSnapshots[mReaderSlot];

    int channels = std::clamp(snap.channels, 0, MAX_CHANNELS);
    if (channels <= 0) return;

    if (mResetRequested.exchange(false, std::memory_order_acq_rel)) {
        for (auto& chFilters : mFilters) {
            for (auto& filter : chFilters) {
                filter.reset();
            }
        }
        for (auto& filter : mBassFilters) {
            filter.reset();
        }
    }

    if (!snap.enabled || !snap.hasActiveFilter) {
        return;
    }

    for (int ch = 0; ch < channels; ++ch) {
        mBassFilters[ch].setCoefficients(snap.bassCoeff);
        for (int b = 0; b < NUM_BANDS; ++b) {
            mFilters[ch][b].setCoefficients(snap.bandCoeffs[b]);
        }
    }

    int frames = numSamples / channels;
    float preamp = snap.preampLinear;

    for (int i = 0; i < frames; ++i) {
        for (int ch = 0; ch < channels; ++ch) {
            int index = i * channels + ch;
            float sample = buffer[index] * preamp;

            // Apply Bass Boost first
            if (snap.bassCoeff.active) {
                sample = mBassFilters[ch].process(sample);
            }

            // Apply 10-band EQ (only active bands strictly below Nyquist)
            for (int b = 0; b < NUM_BANDS; ++b) {
                if (snap.bandCoeffs[b].active) {
                    sample = mFilters[ch][b].process(sample);
                }
            }

            buffer[index] = sample;
        }
    }
}

void EqualizerEngine::reset() {
    mResetRequested.store(true, std::memory_order_release);
}
