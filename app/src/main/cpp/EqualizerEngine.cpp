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

    float bassGainDb = (mBassBoostStrength > 0.01f) ? (mBassBoostStrength * 12.0f) : 0.0f;
    nextSnap.bassCoeff = BiquadFilter::computeCoefficients(
        FilterType::LOW_SHELF, mSampleRate, 80.0f, 0.707f, bassGainDb
    );
    if (nextSnap.bassCoeff.active) {
        nextSnap.hasActiveFilter = true;
    }

    for (int b = 0; b < NUM_BANDS; ++b) {
        float freq = BAND_FREQUENCIES[b];
        float gain = mBandGains[b];

        FilterType type = FilterType::PEAKING_EQ;
        if (b == 0) type = FilterType::LOW_SHELF;
        else if (b == NUM_BANDS - 1) type = FilterType::HIGH_SHELF;

        nextSnap.bandCoeffs[b] = BiquadFilter::computeCoefficients(
            type, mSampleRate, freq, 1.414f, gain
        );
        if (nextSnap.bandCoeffs[b].active) {
            nextSnap.hasActiveFilter = true;
        }
    }

    // Publish precomputed coefficients (~220 bytes copy, no trig math inside lock)
    {
        std::lock_guard<std::mutex> snapLock(mSnapshotLock);
        mPublishedSnapshot = nextSnap;
    }
}

void EqualizerEngine::processBuffer(float* buffer, int32_t numSamples) {
    if (buffer == nullptr || numSamples <= 0) return;

    EqCoeffsSnapshot snap;
    {
        std::lock_guard<std::mutex> snapLock(mSnapshotLock);
        snap = mPublishedSnapshot;
    }

    int channels = snap.channels;
    if (channels <= 0) return;

    // Ensure audio-thread filter delay arrays match channel count
    if (mFilters.size() != static_cast<size_t>(channels) ||
        mBassFilters.size() != static_cast<size_t>(channels)) {
        mFilters.resize(channels);
        for (int ch = 0; ch < channels; ++ch) {
            mFilters[ch].resize(NUM_BANDS);
        }
        mBassFilters.resize(channels);
    }

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

    for (int i = 0; i < frames; ++i) {
        for (int ch = 0; ch < channels; ++ch) {
            int index = i * channels + ch;
            float sample = buffer[index];

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
