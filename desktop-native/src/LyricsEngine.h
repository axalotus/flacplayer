#pragma once

#include <string>
#include <vector>
#include <cstdint>

struct LyricLine {
    int64_t timestampMs = 0;
    std::string text;
};

struct LyricsData {
    bool isSynced = false;
    std::vector<LyricLine> lines;
    std::string plainText;
    std::string source;
};

class LyricsEngine {
public:
    static LyricsData findLyricsForTrack(const std::string& audioFilePath, const std::string& customFolder = "");
    static LyricsData parseLrcOrTxt(const std::string& rawContent, const std::string& sourceName = "Файл");
    static std::string readFileTextWithEncoding(const std::string& filePath);
};
