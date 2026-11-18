#pragma once

#include <atomic>
#include <chrono>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <thread>

struct MprisTrackState {
    bool hasTrack = false;
    std::string trackId;
    std::string title;
    std::string artist;
    std::string album;
    std::string artUrl;
    std::string trackUrl;
    int64_t durationUs = 0;
    int64_t positionUs = 0;
    std::string playbackStatus = "Stopped"; // "Playing" | "Paused" | "Stopped"
    std::string loopStatus = "None";        // "None" | "Track" | "Playlist"
    bool shuffle = false;
    double volume = 0.85;
    bool seeked = false;
};

struct MprisActionEvent {
    std::string type;       // "play" | "pause" | "toggle" | "stop" | "next" | "prev" | "seekRelative" | "seekAbsolute" | "setVolume" | "setLoopStatus" | "setShuffle" | "raise" | "quit"
    double numberValue = 0.0;
    std::string stringValue;
    bool boolValue = false;
};

class LinuxMprisService {
public:
    using ActionCallback = std::function<void(const MprisActionEvent&)>;

    LinuxMprisService();
    ~LinuxMprisService();

    LinuxMprisService(const LinuxMprisService&) = delete;
    LinuxMprisService& operator=(const LinuxMprisService&) = delete;

    bool start(ActionCallback cb);
    void stop();
    void updateState(const MprisTrackState& nextState);
    bool isRunning() const { return mRunning.load(std::memory_order_acquire); }

private:
    std::atomic<bool> mRunning { false };
    std::atomic<bool> mStopRequested { false };

#if !defined(_WIN32) && !defined(__APPLE__)
    struct Impl;
    Impl* mImpl = nullptr;
#endif
};
