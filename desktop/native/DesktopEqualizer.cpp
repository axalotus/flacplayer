#include "DesktopEqualizer.h"
#include <algorithm>
#include <cmath>

constexpr float DesktopEqualizer::BAND_FREQUENCIES[NUM_BANDS];

DesktopEqualizer::DesktopEqualizer() {
    mFilters.resize(MAX_CHANNELS);
    for (int ch = 0; ch < MAX_CHANNELS; ++ch) {
        mFilters[ch].resize(NUM_BANDS);
    }
    mBassFilters.resize(MAX_CHANNELS);
    updateFilters();
}

void DesktopEqualizer::init(float sampleRate, int channels) {
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

void DesktopEqualizer::setEnabled(bool enabled) {
    std::lock_guard<std::mutex> lock(mConfigLock);
    mEnabled = enabled;
    updateFilters();
}

void DesktopEqualizer::setPreampDb(float gainDb) {
    std::lock_guard<std::mutex> lock(mConfigLock);
    mPreampDb = std::clamp(gainDb, -15.0f, 15.0f);
    updateFilters();
}

void DesktopEqualizer::setBandGain(int bandIndex, float gainDb) {
    if (bandIndex < 0 || bandIndex >= NUM_BANDS) return;
    std::lock_guard<std::mutex> lock(mConfigLock);
    mBandGains[bandIndex] = std::clamp(gainDb, -15.0f, 15.0f);
    updateFilters();
}

void DesktopEqualizer::setBassBoost(float strength) {
    std::lock_guard<std::mutex> lock(mConfigLock);
    mBassBoostStrength = std::clamp(strength, 0.0f, 1.0f);
    updateFilters();
}

void DesktopEqualizer::updateFilters() {
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
    if (nextSnap.bassCoeff.active) nextSnap.hasActiveFilter = true;

    float maxSingleBandDb = 0.0f;
    for (int b = 0; b < NUM_BANDS; ++b) {
        if (mBandGains[b] > maxSingleBandDb) maxSingleBandDb = mBandGains[b];
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
            type, mSampleRate, BAND_FREQUENCIES[b], q, mBandGains[b]
        );
        if (nextSnap.bandCoeffs[b].active) nextSnap.hasActiveFilter = true;
    }

    float lowSumDb = std::max(0.0f, mBandGains[0]) +
                     std::max(0.0f, mBandGains[1]) * 0.65f +
                     std::max(0.0f, bassGainDb) * 0.85f;
    float peakBoostDb = std::max(lowSumDb, maxSingleBandDb);
    float autoHeadroomDb = (peakBoostDb > 2.5f) ? -std::min(12.0f, (peakBoostDb - 2.5f) * 0.55f) : 0.0f;
    float totalPreampDb = mPreampDb + autoHeadroomDb;
    nextSnap.preampLinear = (std::abs(totalPreampDb) > 0.02f) ? std::pow(10.0f, totalPreampDb / 20.0f) : 1.0f;
    if (std::abs(totalPreampDb) > 0.02f) nextSnap.hasActiveFilter = true;

    mSnapshots[mWriterSlot] = nextSnap;
    uint32_t newMiddle = static_cast<uint32_t>(mWriterSlot) | TRIPLE_DIRTY_BIT;
    uint32_t prevMiddle = mMiddleState.exchange(newMiddle, std::memory_order_acq_rel);
    mWriterSlot = static_cast<int>(prevMiddle & 0xFFu);
}

void DesktopEqualizer::processBuffer(float* buffer, int32_t numSamples) {
    if (!buffer || numSamples <= 0) return;

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
            for (auto& filter : chFilters) filter.reset();
        }
        for (auto& filter : mBassFilters) filter.reset();
    }

    if (!snap.enabled || !snap.hasActiveFilter) return;

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

            if (snap.bassCoeff.active) {
                sample = mBassFilters[ch].process(sample);
            }
            for (int b = 0; b < NUM_BANDS; ++b) {
                if (snap.bandCoeffs[b].active) {
                    sample = mFilters[ch][b].process(sample);
                }
            }
            buffer[index] = sample;
        }
    }
}

void DesktopEqualizer::reset() {
    mResetRequested.store(true, std::memory_order_release);
}
