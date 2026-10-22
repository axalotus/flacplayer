#ifndef AUDIOPLAYER_BIQUADFILTER_H
#define AUDIOPLAYER_BIQUADFILTER_H

#include <cmath>

enum class FilterType {
    PEAKING_EQ,
    LOW_SHELF,
    HIGH_SHELF
};

class BiquadFilter {
public:
    BiquadFilter();
    ~BiquadFilter();

    void setParameters(FilterType type, float sampleRate, float frequency, float q, float gainDb);
    float process(float inSample);

private:
    float b0, b1, b2, a1, a2;
    float x1, x2, y1, y2;
};

#endif //AUDIOPLAYER_BIQUADFILTER_H
