#include "BiquadFilter.h"

BiquadFilter::BiquadFilter() : b0(1.0), b1(0.0), b2(0.0), a1(0.0), a2(0.0), x1(0.0), x2(0.0), y1(0.0), y2(0.0) {}

BiquadFilter::~BiquadFilter() {}

BiquadCoeffs BiquadFilter::computeCoefficients(FilterType type, float sampleRate, float frequency, float q, float gainDb) {
    BiquadCoeffs result;
    // Bypass filter if parameters are invalid, gain is ~0dB, or frequency is near/above Nyquist
    if (sampleRate <= 0.0f || frequency <= 0.0f || q <= 0.0f ||
        frequency >= sampleRate * 0.45f || std::abs(gainDb) < 0.01f) {
        return result;
    }

    const double sr = static_cast<double>(sampleRate);
    const double freq = static_cast<double>(frequency);
    const double qVal = static_cast<double>(q);
    const double A = std::pow(10.0, static_cast<double>(gainDb) / 40.0);
    const double w0 = 2.0 * 3.14159265358979323846 * freq / sr;
    const double cosW0 = std::cos(w0);
    const double sinW0 = std::sin(w0);
    if (sinW0 <= 0.0) {
        return result;
    }
    const double alpha = sinW0 / (2.0 * qVal);

    double a0 = 1.0;
    double localB0 = 1.0, localB1 = 0.0, localB2 = 0.0;
    double localA1 = 0.0, localA2 = 0.0;

    switch (type) {
        case FilterType::PEAKING_EQ:
            localB0 = 1.0 + alpha * A;
            localB1 = -2.0 * cosW0;
            localB2 = 1.0 - alpha * A;
            a0 = 1.0 + alpha / A;
            localA1 = -2.0 * cosW0;
            localA2 = 1.0 - alpha / A;
            break;

        case FilterType::LOW_SHELF: {
            const double twoSqrtAAlpha = 2.0 * std::sqrt(A) * alpha;
            localB0 = A * ((A + 1.0) - (A - 1.0) * cosW0 + twoSqrtAAlpha);
            localB1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosW0);
            localB2 = A * ((A + 1.0) - (A - 1.0) * cosW0 - twoSqrtAAlpha);
            a0 = (A + 1.0) + (A - 1.0) * cosW0 + twoSqrtAAlpha;
            localA1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosW0);
            localA2 = (A + 1.0) + (A - 1.0) * cosW0 - twoSqrtAAlpha;
            break;
        }

        case FilterType::HIGH_SHELF: {
            const double twoSqrtAAlpha = 2.0 * std::sqrt(A) * alpha;
            localB0 = A * ((A + 1.0) + (A - 1.0) * cosW0 + twoSqrtAAlpha);
            localB1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosW0);
            localB2 = A * ((A + 1.0) + (A - 1.0) * cosW0 - twoSqrtAAlpha);
            a0 = (A + 1.0) - (A - 1.0) * cosW0 + twoSqrtAAlpha;
            localA1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosW0);
            localA2 = (A + 1.0) - (A - 1.0) * cosW0 - twoSqrtAAlpha;
            break;
        }
    }

    if (std::abs(a0) < 1e-12) {
        return result;
    }

    const double normB0 = localB0 / a0;
    const double normB1 = localB1 / a0;
    const double normB2 = localB2 / a0;
    const double normA1 = localA1 / a0;
    const double normA2 = localA2 / a0;

    // Exact Jury stability test in 64-bit precision (works cleanly down to 20Hz @ 192kHz without false bypass)
    if (std::abs(normA2) >= 0.999999 ||
        (1.0 + normA1 + normA2) <= 1e-12 ||
        (1.0 - normA1 + normA2) <= 1e-12) {
        return result;
    }

    result.b0 = normB0;
    result.b1 = normB1;
    result.b2 = normB2;
    result.a1 = normA1;
    result.a2 = normA2;
    result.active = true;
    return result;
}

void BiquadFilter::setCoefficients(const BiquadCoeffs& coeffs) {
    this->b0 = coeffs.b0;
    this->b1 = coeffs.b1;
    this->b2 = coeffs.b2;
    this->a1 = coeffs.a1;
    this->a2 = coeffs.a2;
}

void BiquadFilter::setParameters(FilterType type, float sampleRate, float frequency, float q, float gainDb) {
    setCoefficients(computeCoefficients(type, sampleRate, frequency, q, gainDb));
}

void BiquadFilter::reset() {
    x1 = 0.0;
    x2 = 0.0;
    y1 = 0.0;
    y2 = 0.0;
}

float BiquadFilter::process(float inSample) {
    if (std::isnan(inSample) || std::isinf(inSample)) {
        inSample = 0.0f;
    }

    const double inD = static_cast<double>(inSample);
    double outSample = b0 * inD + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;

    if (std::isnan(outSample) || std::isinf(outSample)) {
        reset();
        return inSample;
    }

    if (outSample > 4.0) outSample = 4.0;
    else if (outSample < -4.0) outSample = -4.0;

    x2 = x1;
    x1 = inD;
    y2 = y1;
    y1 = outSample;

    if (std::abs(y1) < 1e-18) y1 = 0.0;
    if (std::abs(y2) < 1e-18) y2 = 0.0;

    return static_cast<float>(outSample);
}
