#pragma once

#include <cmath>

#ifndef M_PI
#define M_PI 3.14159265358979323846f
#endif

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
    
    void setPeakingEQ(float sampleRate, float frequency, float gainDb, float q = 1.414f) {
        setParameters(FilterType::PEAKING_EQ, sampleRate, frequency, q, gainDb);
    }
    
    void setLowShelf(float sampleRate, float frequency, float gainDb, float q = 0.707f) {
        setParameters(FilterType::LOW_SHELF, sampleRate, frequency, q, gainDb);
    }

    float process(float inSample);

private:
    float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
    float x1 = 0.0f, x2 = 0.0f, y1 = 0.0f, y2 = 0.0f;
};
