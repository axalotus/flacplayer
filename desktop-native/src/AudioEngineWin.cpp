#include "AudioEngineWin.h"
#include <algorithm>
#include <chrono>

AudioEngineWin& AudioEngineWin::getInstance() {
    static AudioEngineWin inst; return inst;
}

AudioEngineWin::AudioEngineWin() {
    for (int i = 0; i < NUM_BUFFERS; ++i) {
        m_audioBuffers[i] = new int16_t[BUFFER_FRAMES * 2];
        memset(&m_waveHeaders[i], 0, sizeof(WAVEHDR));
    }
}
AudioEngineWin::~AudioEngineWin() {
    shutdown();
    for (int i = 0; i < NUM_BUFFERS; ++i) delete[] m_audioBuffers[i];
}

bool AudioEngineWin::init() {
    if (m_running.load()) return true;
    m_running.store(true);
    m_playbackThread = std::thread(&AudioEngineWin::playbackThreadFunc, this);
    return true;
}

void AudioEngineWin::shutdown() {
    m_running.store(false);
    m_stopPlayback.store(true);
    stop();
    if (m_playbackThread.joinable()) m_playbackThread.join();
    if (m_hWaveOut) {
        waveOutReset(m_hWaveOut);
        for (int i = 0; i < NUM_BUFFERS; ++i)
            waveOutUnprepareHeader(m_hWaveOut, &m_waveHeaders[i], sizeof(WAVEHDR));
        waveOutClose(m_hWaveOut);
        m_hWaveOut = nullptr;
    }
}

std::vector<AudioDeviceInfo> AudioEngineWin::getAudioDevices() const {
    std::vector<AudioDeviceInfo> list;
    list.push_back({WAVE_MAPPER, "Windows Default Output"});
    UINT n = waveOutGetNumDevs();
    for (UINT i = 0; i < n; ++i) {
        WAVEOUTCAPSW caps{};
        if (waveOutGetDevCapsW(i, &caps, sizeof(caps)) == MMSYSERR_NOERROR) {
            AudioDeviceInfo info;
            info.id = i;
            int len = WideCharToMultiByte(CP_UTF8, 0, caps.szPname, -1, nullptr, 0, nullptr, nullptr);
            if (len > 0) { info.name.resize(len - 1); WideCharToMultiByte(CP_UTF8, 0, caps.szPname, -1, &info.name[0], len, nullptr, nullptr); }
            list.push_back(info);
        }
    }
    return list;
}

void AudioEngineWin::setOutputDevice(UINT id) {
    std::lock_guard<std::mutex> lk(m_engineMutex);
    m_deviceIndex = id;
    // Reopen on next loadAndPlay
}

void CALLBACK AudioEngineWin::waveOutProc(HWAVEOUT, UINT msg, DWORD_PTR inst, DWORD_PTR, DWORD_PTR) {
    if (msg == WOM_DONE) reinterpret_cast<AudioEngineWin*>(inst)->m_freeBuffers.fetch_add(1);
}

bool AudioEngineWin::loadAndPlay(const std::string& filePath) {
    // Signal playback thread to stop current stream
    m_stopPlayback.store(true);
    std::this_thread::sleep_for(std::chrono::milliseconds(30));

    std::lock_guard<std::mutex> lk(m_engineMutex);
    m_stopPlayback.store(false);

    if (!m_decoder.openFile(filePath)) return false;

    // Build TrackInfo from filename (metadata tags are done by LibraryManager)
    m_currentTrack.filePath   = filePath;
    m_currentTrack.sampleRate = m_decoder.getSampleRate();
    m_currentTrack.channels   = m_decoder.getChannels();
    m_currentTrack.bitDepth   = m_decoder.getBitDepth();
    m_currentTrack.durationMs = m_decoder.getDurationMs();
    m_currentTrack.format     = m_decoder.getFormatString();
    m_currentTrack.isHiRes    = m_decoder.isHiRes();

    // Title/artist fallback from filename
    std::string base = filePath;
    size_t sl = base.find_last_of("\\/");
    if (sl != std::string::npos) base = base.substr(sl + 1);
    size_t dot = base.find_last_of('.'); if (dot != std::string::npos) base.resize(dot);
    size_t sep = base.find(" - ");
    if (sep != std::string::npos) {
        m_currentTrack.artist = base.substr(0, sep);
        m_currentTrack.title  = base.substr(sep + 3);
    } else {
        m_currentTrack.artist = "Unknown Artist";
        m_currentTrack.title  = base;
    }

    m_equalizer.setSampleRate(m_currentTrack.sampleRate);

    // Reconfigure waveOut
    if (m_hWaveOut) {
        waveOutReset(m_hWaveOut);
        for (int i = 0; i < NUM_BUFFERS; ++i)
            waveOutUnprepareHeader(m_hWaveOut, &m_waveHeaders[i], sizeof(WAVEHDR));
        waveOutClose(m_hWaveOut);
        m_hWaveOut = nullptr;
    }

    WAVEFORMATEX wfx{};
    wfx.wFormatTag      = WAVE_FORMAT_PCM;
    wfx.nChannels       = (WORD)m_currentTrack.channels;
    wfx.nSamplesPerSec  = m_currentTrack.sampleRate;
    wfx.wBitsPerSample  = 16;
    wfx.nBlockAlign     = wfx.nChannels * 2;
    wfx.nAvgBytesPerSec = wfx.nSamplesPerSec * wfx.nBlockAlign;

    if (waveOutOpen(&m_hWaveOut, m_deviceIndex, &wfx,
                    (DWORD_PTR)waveOutProc, (DWORD_PTR)this,
                    CALLBACK_FUNCTION) != MMSYSERR_NOERROR)
        return false;

    for (int i = 0; i < NUM_BUFFERS; ++i) {
        m_waveHeaders[i].lpData        = (LPSTR)m_audioBuffers[i];
        m_waveHeaders[i].dwBufferLength= BUFFER_FRAMES * wfx.nBlockAlign;
        m_waveHeaders[i].dwFlags       = 0;
        waveOutPrepareHeader(m_hWaveOut, &m_waveHeaders[i], sizeof(WAVEHDR));
    }
    m_freeBuffers.store(NUM_BUFFERS);
    m_positionMs.store(0);
    m_state.store(PlaybackState::PLAYING);
    return true;
}

void AudioEngineWin::togglePlayPause() {
    if (m_state.load() == PlaybackState::PLAYING) pause();
    else if (m_state.load() == PlaybackState::PAUSED) resume();
}
void AudioEngineWin::pause()  { if (m_state.load()==PlaybackState::PLAYING)  { if(m_hWaveOut) waveOutPause(m_hWaveOut);   m_state.store(PlaybackState::PAUSED);  } }
void AudioEngineWin::resume() { if (m_state.load()==PlaybackState::PAUSED)   { if(m_hWaveOut) waveOutRestart(m_hWaveOut); m_state.store(PlaybackState::PLAYING); } }
void AudioEngineWin::stop()   { m_state.store(PlaybackState::STOPPED); m_positionMs.store(0); if(m_hWaveOut) waveOutReset(m_hWaveOut); }

void AudioEngineWin::seekTo(int64_t ms) {
    if (m_currentTrack.durationMs <= 0) return;
    int64_t t = std::max(0LL, std::min(ms, m_currentTrack.durationMs));
    m_seekTargetMs.store(t);
    m_seekRequested.store(true);
    m_positionMs.store(t);
}
void AudioEngineWin::setVolume(float v) { m_volume.store(std::max(0.f, std::min(v, 1.f))); m_isMuted.store(false); }
void AudioEngineWin::toggleMute() { m_isMuted.store(!m_isMuted.load()); }

void AudioEngineWin::playbackThreadFunc() {
    int bufIdx = 0;
    std::vector<float> fBuf(BUFFER_FRAMES * 2);

    while (m_running.load()) {
        if (m_state.load() != PlaybackState::PLAYING) {
            std::this_thread::sleep_for(std::chrono::milliseconds(8));
            continue;
        }
        if (m_stopPlayback.load()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(8));
            continue;
        }

        // Handle seek
        if (m_seekRequested.load()) {
            std::lock_guard<std::mutex> lk(m_engineMutex);
            if (m_hWaveOut) waveOutReset(m_hWaveOut);
            uint64_t frame = (uint64_t)(m_seekTargetMs.load()) * m_currentTrack.sampleRate / 1000ULL;
            m_decoder.seekToFrame(frame);
            m_seekRequested.store(false);
            m_freeBuffers.store(NUM_BUFFERS);
        }

        if (m_freeBuffers.load() <= 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(4));
            continue;
        }

        size_t framesRead = 0;
        {
            std::lock_guard<std::mutex> lk(m_engineMutex);
            framesRead = m_decoder.readSamples(m_audioBuffers[bufIdx], BUFFER_FRAMES);
        }

        if (framesRead == 0) {
            m_state.store(PlaybackState::STOPPED);
            m_positionMs.store(m_currentTrack.durationMs);
            continue;
        }

        float vol = m_isMuted.load() ? 0.f : m_volume.load();
        size_t total = framesRead * (size_t)m_currentTrack.channels;
        if (fBuf.size() < total) fBuf.resize(total);

        for (size_t i = 0; i < total; ++i) fBuf[i] = m_audioBuffers[bufIdx][i] / 32768.f;
        if (m_equalizer.isEnabled()) m_equalizer.process(fBuf.data(), (int)framesRead, m_currentTrack.channels);
        for (size_t i = 0; i < total; ++i) {
            float s = std::max(-1.f, std::min(fBuf[i] * vol, 1.f));
            m_audioBuffers[bufIdx][i] = (int16_t)(s * 32767.f);
        }

        WAVEHDR* hdr = &m_waveHeaders[bufIdx];
        hdr->dwBufferLength = (DWORD)(framesRead * m_currentTrack.channels * sizeof(int16_t));
        m_freeBuffers.fetch_sub(1);
        if (m_hWaveOut) waveOutWrite(m_hWaveOut, hdr, sizeof(WAVEHDR));

        bufIdx = (bufIdx + 1) % NUM_BUFFERS;
        if (m_currentTrack.sampleRate > 0)
            m_positionMs.fetch_add((int64_t)(framesRead * 1000ULL / m_currentTrack.sampleRate));
    }
}
