#include "LyricsEngine.h"
#include <windows.h>
#include <fstream>
#include <sstream>
#include <algorithm>
#include <regex>

std::string LyricsEngine::readFileTextWithEncoding(const std::string& filePath) {
    std::ifstream file(filePath, std::ios::binary);
    if (!file.is_open()) return "";

    std::vector<char> buffer((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
    if (buffer.empty()) return "";

    // Check UTF-8 BOM (EF BB BF)
    if (buffer.size() >= 3 && (uint8_t)buffer[0] == 0xEF && (uint8_t)buffer[1] == 0xBB && (uint8_t)buffer[2] == 0xBF) {
        return std::string(buffer.begin() + 3, buffer.end());
    }

    // Check if it is valid UTF-8
    int wideLen = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, buffer.data(), (int)buffer.size(), nullptr, 0);
    if (wideLen > 0) {
        return std::string(buffer.begin(), buffer.end());
    }

    // If not valid UTF-8, convert from Windows-1251 (Cyrillic) to UTF-8
    wideLen = MultiByteToWideChar(1251, 0, buffer.data(), (int)buffer.size(), nullptr, 0);
    if (wideLen > 0) {
        std::wstring wideStr(wideLen, 0);
        MultiByteToWideChar(1251, 0, buffer.data(), (int)buffer.size(), &wideStr[0], wideLen);

        int utf8Len = WideCharToMultiByte(CP_UTF8, 0, wideStr.data(), wideLen, nullptr, 0, nullptr, nullptr);
        if (utf8Len > 0) {
            std::string utf8Str(utf8Len, 0);
            WideCharToMultiByte(CP_UTF8, 0, wideStr.data(), wideLen, &utf8Str[0], utf8Len, nullptr, nullptr);
            return utf8Str;
        }
    }

    return std::string(buffer.begin(), buffer.end());
}

LyricsData LyricsEngine::parseLrcOrTxt(const std::string& rawContent, const std::string& sourceName) {
    LyricsData result;
    result.source = sourceName;
    if (rawContent.empty()) return result;

    std::istringstream stream(rawContent);
    std::string line;
    std::regex timeRegex(R"(\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\])");
    std::regex offsetRegex(R"(\[offset:\s*([+-]?\d+)\])", std::regex::icase);
    std::regex headerRegex(R"(^\[(ar|ti|al|by|length|offset|re|ve|id|tool):.*\]$)", std::regex::icase);

    int64_t offsetMs = 0;
    std::vector<LyricLine> parsedLines;
    std::vector<std::string> plainLines;

    while (std::getline(stream, line)) {
        // Trim line
        while (!line.empty() && (line.back() == '\r' || line.back() == ' ' || line.back() == '\t')) {
            line.pop_back();
        }
        while (!line.empty() && (line.front() == ' ' || line.front() == '\t')) {
            line.erase(line.begin());
        }

        if (line.empty()) {
            plainLines.push_back("");
            continue;
        }

        // Offset tag
        std::smatch offsetMatch;
        if (std::regex_search(line, offsetMatch, offsetRegex)) {
            try { offsetMs = std::stoll(offsetMatch[1].str()); } catch (...) {}
            continue;
        }

        // Skip metadata header tags
        if (std::regex_match(line, headerRegex)) {
            continue;
        }

        // Time tags
        std::vector<int64_t> timestamps;
        auto words_begin = std::sregex_iterator(line.begin(), line.end(), timeRegex);
        auto words_end = std::sregex_iterator();

        size_t lastTagEnd = 0;
        for (std::sregex_iterator i = words_begin; i != words_end; ++i) {
            std::smatch match = *i;
            int64_t min = 0, sec = 0, ms = 0;
            try {
                min = std::stoll(match[1].str());
                sec = std::stoll(match[2].str());
                std::string msStr = match[3].str();
                if (msStr.length() == 1) ms = std::stoll(msStr) * 100;
                else if (msStr.length() == 2) ms = std::stoll(msStr) * 10;
                else if (msStr.length() == 3) ms = std::stoll(msStr);
            } catch (...) {}

            int64_t ts = (min * 60 * 1000) + (sec * 1000) + ms;
            timestamps.push_back(ts);
            lastTagEnd = match.position() + match.length();
        }

        if (!timestamps.empty()) {
            std::string text = line.substr(lastTagEnd);
            while (!text.empty() && (text.front() == ' ' || text.front() == '\t')) text.erase(text.begin());
            for (int64_t ts : timestamps) {
                LyricLine ll;
                ll.timestampMs = (std::max)(int64_t(0), ts + offsetMs);
                ll.text = text;
                parsedLines.push_back(ll);
            }
            plainLines.push_back(text);
        } else {
            plainLines.push_back(line);
        }
    }

    if (!parsedLines.empty()) {
        std::sort(parsedLines.begin(), parsedLines.end(), [](const LyricLine& a, const LyricLine& b) {
            return a.timestampMs < b.timestampMs;
        });

        result.isSynced = true;
        result.lines = parsedLines;
        for (const auto& l : parsedLines) {
            if (!l.text.empty()) {
                if (!result.plainText.empty()) result.plainText += "\n";
                result.plainText += l.text;
            }
        }
    } else {
        result.isSynced = false;
        for (const auto& pl : plainLines) {
            if (!pl.empty()) {
                LyricLine ll;
                ll.timestampMs = 0;
                ll.text = pl;
                result.lines.push_back(ll);
            }
            if (!result.plainText.empty()) result.plainText += "\n";
            result.plainText += pl;
        }
    }

    return result;
}

LyricsData LyricsEngine::findLyricsForTrack(const std::string& audioFilePath, const std::string& customFolder) {
    // 1. Check sidecar file beside audio file (.lrc or .txt)
    size_t lastDot = audioFilePath.find_last_of('.');
    if (lastDot != std::string::npos) {
        std::string base = audioFilePath.substr(0, lastDot);
        for (const char* ext : {".lrc", ".LRC", ".txt", ".TXT"}) {
            std::string sidecar = base + ext;
            std::string text = readFileTextWithEncoding(sidecar);
            if (!text.empty()) {
                return parseLrcOrTxt(text, std::string("Файл рядом (") + ext + ")");
            }
        }
    }

    // 2. Check in custom lyrics directory
    if (!customFolder.empty()) {
        std::string fileName = audioFilePath;
        size_t lastSlash = fileName.find_last_of("\\/");
        if (lastSlash != std::string::npos) fileName = fileName.substr(lastSlash + 1);
        size_t dPos = fileName.find_last_of('.');
        if (dPos != std::string::npos) fileName = fileName.substr(0, dPos);

        for (const char* ext : {".lrc", ".LRC", ".txt", ".TXT"}) {
            std::string path = customFolder + "\\" + fileName + ext;
            std::string text = readFileTextWithEncoding(path);
            if (!text.empty()) {
                return parseLrcOrTxt(text, std::string("Папка текстов (") + ext + ")");
            }
        }
    }

    return LyricsData();
}
