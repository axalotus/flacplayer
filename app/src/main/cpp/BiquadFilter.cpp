#include "BiquadFilter.h"

BiquadFilter::BiquadFilter() : b0(1), b1(0), b2(0), a1(0), a2(0), x1(0), x2(0), y1(0), y2(0) {}

BiquadFilter::~BiquadFilter() {}

void BiquadFilter::setParameters(FilterType type, float sampleRate, float frequency, float q, float gainDb) {
    // Bypass filter if parameters are invalid, gain is ~0dB, or frequency is near/above Nyquist
    if (sampleRate <= 0.0f || frequency <= 0.0f || q <= 0.0f ||
        frequency >= sampleRate * 0.45f || std::abs(gainDb) < 0.005f) {
        this->b0 = 1.0f;
        this->b1 = 0.0f;
        this->b2 = 0.0f;
        this->a1 = 0.0f;
        this->a2 = 0.0f;
        return;
    }

    float A = std::pow(10.0f, gainDb / 40.0f);
    float w0 = 2.0f * static_cast<float>(M_PI) * frequency / sampleRate;
    float cosW0 = std::cos(w0);
    float sinW0 = std::sin(w0);
    if (sinW0 <= 0.0f) {
        this->b0 = 1.0f;
        this->b1 = 0.0f;
        this->b2 = 0.0f;
        this->a1 = 0.0f;
        this->a2 = 0.0f;
        return;
    }
    float alpha = sinW0 / (2.0f * q);
    
    float a0 = 1.0f;
    float localB0 = 1.0f, localB1 = 0.0f, localB2 = 0.0f;
    float localA1 = 0.0f, localA2 = 0.0f;

    switch (type) {
        case FilterType::PEAKING_EQ:
            localB0 = 1.0f + alpha * A;
            localB1 = -2.0f * cosW0;
            localB2 = 1.0f - alpha * A;
            a0 = 1.0f + alpha / A;
            localA1 = -2.0f * cosW0;
            localA2 = 1.0f - alpha / A;
            break;
            
        case FilterType::LOW_SHELF:
            localB0 = A * ((A + 1.0f) - (A - 1.0f) * cosW0 + 2.0f * std::sqrt(A) * alpha);
            localB1 = 2.0f * A * ((A - 1.0f) - (A + 1.0f) * cosW0);
            localB2 = A * ((A + 1.0f) - (A - 1.0f) * cosW0 - 2.0f * std::sqrt(A) * alpha);
            a0 = (A + 1.0f) + (A - 1.0f) * cosW0 + 2.0f * std::sqrt(A) * alpha;
            localA1 = -2.0f * ((A - 1.0f) + (A + 1.0f) * cosW0);
            localA2 = (A + 1.0f) + (A - 1.0f) * cosW0 - 2.0f * std::sqrt(A) * alpha;
            break;
            
        case FilterType::HIGH_SHELF:
            localB0 = A * ((A + 1.0f) + (A - 1.0f) * cosW0 + 2.0f * std::sqrt(A) * alpha);
            localB1 = -2.0f * A * ((A - 1.0f) + (A + 1.0f) * cosW0);
            localB2 = A * ((A + 1.0f) + (A - 1.0f) * cosW0 - 2.0f * std::sqrt(A) * alpha);
            a0 = (A + 1.0f) - (A - 1.0f) * cosW0 + 2.0f * std::sqrt(A) * alpha;
            localA1 = 2.0f * ((A - 1.0f) - (A + 1.0f) * cosW0);
            localA2 = (A + 1.0f) - (A - 1.0f) * cosW0 - 2.0f * std::sqrt(A) * alpha;
            break;
    }

    if (std::abs(a0) < 1e-6f) {
        this->b0 = 1.0f;
        this->b1 = 0.0f;
        this->b2 = 0.0f;
        this->a1 = 0.0f;
        this->a2 = 0.0f;
        return;
    }

    float normB0 = localB0 / a0;
    float normB1 = localB1 / a0;
    float normB2 = localB2 / a0;
    float normA1 = localA1 / a0;
    float normA2 = localA2 / a0;

    // Jury stability check: poles must lie strictly inside the unit circle
    if (std::abs(normA2) >= 0.999f || std::abs(normA1) >= (1.0f + normA2 - 0.001f)) {
        this->b0 = 1.0f;
        this->b1 = 0.0f;
        this->b2 = 0.0f;
        this->a1 = 0.0f;
        this->a2 = 0.0f;
        reset();
        return;
    }

    this->b0 = normB0;
    this->b1 = normB1;
    this->b2 = normB2;
    this->a1 = normA1;
    this->a2 = normA2;
}

void BiquadFilter::reset() {
    x1 = 0.0f;
    x2 = 0.0f;
    y1 = 0.0f;
    y2 = 0.0f;
}

float BiquadFilter::process(float inSample) {
    if (std::isnan(inSample) || std::isinf(inSample)) {
        inSample = 0.0f;
    }

    float outSample = b0 * inSample + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
    
    // Check for NaN or Inf in output to prevent IIR filter runaway/muting
    if (std::isnan(outSample) || std::isinf(outSample)) {
        reset();
        return inSample;
    }

    // Clamp extreme resonance values to safe audible dynamic range
    if (outSample > 3.0f) outSample = 3.0f;
    else if (outSample < -3.0f) outSample = -3.0f;

    // Shift state
    x2 = x1;
    x1 = inSample;
    y2 = y1;
    y1 = outSample;
    
    // Prevent denormal issues (extremely small floats that cause CPU spikes)
    if (std::abs(y1) < 1e-15f) y1 = 0.0f;
    if (std::abs(y2) < 1e-15f) y2 = 0.0f;

    return outSample;
}
