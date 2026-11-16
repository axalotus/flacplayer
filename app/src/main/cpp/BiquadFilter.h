#ifndef AUDIOPLAYER_BIQUADFILTER_H
#define AUDIOPLAYER_BIQUADFILTER_H

#include <cmath>

enum class FilterType {
    PEAKING_EQ,
    LOW_SHELF,
    HIGH_SHELF
};

struct BiquadCoeffs {
    float b0 = 1.0f;
    float b1 = 0.0f;
    float b2 = 0.0f;
    float a1 = 0.0f;
    float a2 = 0.0f;
    bool active = false;
};

class BiquadFilter {
public:
    BiquadFilter();
    ~BiquadFilter();

    static BiquadCoeffs computeCoefficients(FilterType type, float sampleRate, float frequency, float q, float gainDb);
    void setCoefficients(const BiquadCoeffs& coeffs);
    void setParameters(FilterType type, float sampleRate, float frequency, float q, float gainDb);
    float process(float inSample);
    void reset();

private:
    float b0, b1, b2, a1, a2;
    float x1, x2, y1, y2;
};

#endif //AUDIOPLAYER_BIQUADFILTER_H
