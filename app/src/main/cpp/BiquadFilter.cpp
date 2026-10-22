#include "BiquadFilter.h"

BiquadFilter::BiquadFilter() : b0(1), b1(0), b2(0), a1(0), a2(0), x1(0), x2(0), y1(0), y2(0) {}

BiquadFilter::~BiquadFilter() {}

void BiquadFilter::setParameters(FilterType type, float sampleRate, float frequency, float q, float gainDb) {
    if (sampleRate <= 0.0f) return;

    float A = std::pow(10.0f, gainDb / 40.0f);
    float w0 = 2.0f * M_PI * frequency / sampleRate;
    float alpha = std::sin(w0) / (2.0f * q);
    
    float a0 = 1.0f;

    switch (type) {
        case FilterType::PEAKING_EQ:
            b0 = 1.0f + alpha * A;
            b1 = -2.0f * std::cos(w0);
            b2 = 1.0f - alpha * A;
            a0 = 1.0f + alpha / A;
            a1 = -2.0f * std::cos(w0);
            a2 = 1.0f - alpha / A;
            break;
            
        case FilterType::LOW_SHELF:
            b0 = A * ((A + 1.0f) - (A - 1.0f) * std::cos(w0) + 2.0f * std::sqrt(A) * alpha);
            b1 = 2.0f * A * ((A - 1.0f) - (A + 1.0f) * std::cos(w0));
            b2 = A * ((A + 1.0f) - (A - 1.0f) * std::cos(w0) - 2.0f * std::sqrt(A) * alpha);
            a0 = (A + 1.0f) + (A - 1.0f) * std::cos(w0) + 2.0f * std::sqrt(A) * alpha;
            a1 = -2.0f * ((A - 1.0f) + (A + 1.0f) * std::cos(w0));
            a2 = (A + 1.0f) + (A - 1.0f) * std::cos(w0) - 2.0f * std::sqrt(A) * alpha;
            break;
            
        case FilterType::HIGH_SHELF:
            b0 = A * ((A + 1.0f) + (A - 1.0f) * std::cos(w0) + 2.0f * std::sqrt(A) * alpha);
            b1 = -2.0f * A * ((A - 1.0f) + (A + 1.0f) * std::cos(w0));
            b2 = A * ((A + 1.0f) + (A - 1.0f) * std::cos(w0) - 2.0f * std::sqrt(A) * alpha);
            a0 = (A + 1.0f) - (A - 1.0f) * std::cos(w0) + 2.0f * std::sqrt(A) * alpha;
            a1 = 2.0f * ((A - 1.0f) - (A + 1.0f) * std::cos(w0));
            a2 = (A + 1.0f) - (A - 1.0f) * std::cos(w0) - 2.0f * std::sqrt(A) * alpha;
            break;
    }

    // Normalize coefficients
    this->b0 = b0 / a0;
    this->b1 = b1 / a0;
    this->b2 = b2 / a0;
    this->a1 = a1 / a0;
    this->a2 = a2 / a0;
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
