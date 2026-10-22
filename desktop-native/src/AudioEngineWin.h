#pragma once
#include <windows.h>
#include <mmsystem.h>
#include <atomic>
#include <string>
#include <vector>
#include <thread>
#include <mutex>
#include "AudioDecoder.h"
#include "EqualizerEngine.h"

enum class PlaybackState { STOPPED, PLAYING, PAUSED };

// RepeatMode is defined in LibraryManager.h

struct TrackInfo {
    std::string filePath;
    std::string title;
    std::string artist;
    std::string album;
    int64_t durationMs  = 0;
    int sampleRate      = 44100;
    int bitDepth        = 16;
    int channels        = 2;
    std::string format;
    bool isHiRes        = false;
};

struct AudioDeviceInfo {
    UINT id;
    std::string name;
};


class AudioEngineWin {
public:
    static AudioEngineWin& getInstance();

    bool init();
    void shutdown();

    bool loadAndPlay(const std::string& filePath);
    void togglePlayPause();
    void pause();
    void resume();
    void stop();
    void seekTo(int64_t positionMs);

    void  setVolume(float v);
    float getVolume()  const { return m_volume.load(); }
    void  toggleMute();
    bool  isMuted()    const { return m_isMuted.load(); }

    PlaybackState getState()      const { return m_state.load(); }
    int64_t       getPositionMs() const { return m_positionMs.load(); }
    int64_t       getDurationMs() const { return m_currentTrack.durationMs; }
    const TrackInfo& getCurrentTrack() const { return m_currentTrack; }

    // Audio Devices
    std::vector<AudioDeviceInfo> getAudioDevices() const;
    UINT getCurrentDevice()           const { return m_deviceIndex; }
    void setOutputDevice(UINT id);

    EqualizerEngine& getEqualizer() { return m_equalizer; }

private:
    AudioEngineWin();
    ~AudioEngineWin();

    void playbackThreadFunc();
    static void CALLBACK waveOutProc(HWAVEOUT, UINT, DWORD_PTR, DWORD_PTR, DWORD_PTR);

    std::atomic<PlaybackState> m_state{PlaybackState::STOPPED};
    std::atomic<int64_t>  m_positionMs{0};
    std::atomic<float>    m_volume{0.80f};
    std::atomic<bool>     m_isMuted{false};
    std::atomic<bool>     m_seekRequested{false};
    std::atomic<int64_t>  m_seekTargetMs{0};
    std::atomic<bool>     m_running{false};
    std::atomic<bool>     m_stopPlayback{false};

    UINT       m_deviceIndex = WAVE_MAPPER;
    TrackInfo  m_currentTrack;
    AudioDecoder      m_decoder;
    EqualizerEngine   m_equalizer;

    HWAVEOUT    m_hWaveOut  = nullptr;
    std::thread m_playbackThread;
    std::mutex  m_engineMutex;

    static constexpr int NUM_BUFFERS  = 4;
    static constexpr int BUFFER_FRAMES = 8192;
    WAVEHDR   m_waveHeaders[NUM_BUFFERS];
    int16_t*  m_audioBuffers[NUM_BUFFERS];
    std::atomic<int> m_freeBuffers{NUM_BUFFERS};
};
