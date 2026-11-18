#define MINIAUDIO_IMPLEMENTATION
#include "DesktopAudioEngine.h"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#ifndef _WIN32
#include <fcntl.h>
#include <signal.h>
#include <sys/wait.h>
#include <unistd.h>
#endif

static const int32_t RING_BUFFER_CAPACITY = 384000;
static const int32_t PREBUFFER_SIZE = 16384;

static std::string bytesToHex(const void* data, size_t len) {
    static const char hex[] = "0123456789abcdef";
    const auto* bytes = static_cast<const uint8_t*>(data);
    std::string out;
    out.reserve(len * 2);
    for (size_t i = 0; i < len; ++i) {
        out.push_back(hex[(bytes[i] >> 4) & 0xF]);
        out.push_back(hex[bytes[i] & 0xF]);
    }
    return out;
}

static bool hexToBytes(const std::string& hexStr, void* outData, size_t expectedLen) {
    if (hexStr.size() != expectedLen * 2) return false;
    auto* out = static_cast<uint8_t*>(outData);
    auto nibble = [](char c) -> int {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    };
    for (size_t i = 0; i < expectedLen; ++i) {
        int hi = nibble(hexStr[i * 2]);
        int lo = nibble(hexStr[i * 2 + 1]);
        if (hi < 0 || lo < 0) return false;
        out[i] = static_cast<uint8_t>((hi << 4) | lo);
    }
    return true;
}

static std::string toLowerAscii(const std::string& s) {
    std::string out = s;
    for (char& c : out) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return out;
}

static int scoreDeviceNameOverlap(const std::string& a, const std::string& b) {
    std::string la = toLowerAscii(a);
    std::string lb = toLowerAscii(b);
    if (la.empty() || lb.empty()) return 0;
    int score = 0;
    size_t i = 0;
    while (i < la.size()) {
        while (i < la.size() && !std::isalnum(static_cast<unsigned char>(la[i]))) ++i;
        size_t j = i;
        while (j < la.size() && std::isalnum(static_cast<unsigned char>(la[j]))) ++j;
        if (j > i + 2) {
            std::string tok = la.substr(i, j - i);
            if (tok != "usb" && tok != "audio" && tok != "analog" && tok != "stereo" && tok != "digital") {
                if (lb.find(tok) != std::string::npos) score += static_cast<int>(tok.size());
            }
        }
        i = j;
    }
    return score;
}

#if !defined(_WIN32) && !defined(__APPLE__)
// Parse ALSA ":card,dev" ID format
static bool parseAlsaCardNumber(const char* alsaId, int& outCard) {
    if (!alsaId || alsaId[0] != ':') return false;
    char* endPtr = nullptr;
    long val = std::strtol(alsaId + 1, &endPtr, 10);
    if (endPtr == alsaId + 1 || val < 0 || val > 64) return false;
    outCard = static_cast<int>(val);
    return true;
}

static bool runPactlSuspendSink(const std::string& sinkName, bool suspend) {
    if (sinkName.empty()) return false;
    pid_t pid = fork();
    if (pid == 0) {
        execlp(
            "pactl",
            "pactl",
            "suspend-sink",
            sinkName.c_str(),
            suspend ? "1" : "0",
            static_cast<char*>(nullptr)
        );
        _exit(127);
    } else if (pid > 0) {
        int status = 0;
        if (waitpid(pid, &status, 0) > 0) {
            return WIFEXITED(status) && WEXITSTATUS(status) == 0;
        }
    }
    return false;
}

static bool capturePipeCommandOutput(const char* const argv[], std::string& outStr) {
    outStr.clear();
    int pipefd[2];
    if (pipe(pipefd) != 0) return false;
    pid_t pid = fork();
    if (pid == 0) {
        close(pipefd[0]);
        dup2(pipefd[1], STDOUT_FILENO);
        int devnull = open("/dev/null", O_WRONLY);
        if (devnull >= 0) {
            dup2(devnull, STDERR_FILENO);
            close(devnull);
        }
        close(pipefd[1]);
        setenv("LC_ALL", "C", 1);
        execvp(argv[0], const_cast<char* const*>(argv));
        _exit(127);
    }
    close(pipefd[1]);
    if (pid < 0) {
        close(pipefd[0]);
        return false;
    }
    char buf[1024];
    ssize_t n = 0;
    while ((n = read(pipefd[0], buf, sizeof(buf))) > 0) {
        outStr.append(buf, static_cast<size_t>(n));
    }
    close(pipefd[0]);
    int status = 0;
    waitpid(pid, &status, 0);
    return WIFEXITED(status) && WEXITSTATUS(status) == 0;
}

static float queryPipeWireSinkLinearVolume(const std::string& sinkName) {
    if (sinkName.empty()) return 1.0f;

    std::string muteOut;
    const char* muteArgs[] = { "pactl", "get-sink-mute", sinkName.c_str(), nullptr };
    if (capturePipeCommandOutput(muteArgs, muteOut)) {
        if (muteOut.find("yes") != std::string::npos) return 0.0f;
    }

    std::string volOut;
    const char* volArgs[] = { "pactl", "get-sink-volume", sinkName.c_str(), nullptr };
    if (!capturePipeCommandOutput(volArgs, volOut) || volOut.empty()) return 1.0f;

    // Parse dB values printed by pactl (e.g. "Volume: front-left: 44564 /  68% / -10.05 dB, ...")
    double dbSum = 0.0;
    int dbCnt = 0;
    size_t pos = 0;
    while ((pos = volOut.find(" dB", pos)) != std::string::npos) {
        size_t start = pos;
        while (start > 0 && (std::isdigit(static_cast<unsigned char>(volOut[start - 1])) ||
                             volOut[start - 1] == '.' || volOut[start - 1] == '-' || volOut[start - 1] == '+')) {
            --start;
        }
        if (start < pos) {
            double db = std::strtod(volOut.substr(start, pos - start).c_str(), nullptr);
            dbSum += db;
            ++dbCnt;
        } else {
            // Look for "-inf dB"
            if (pos >= 4 && volOut.substr(pos - 4, 4) == "-inf") {
                return 0.0f;
            }
        }
        pos += 3;
    }
    if (dbCnt == 0) {
        if (volOut.find("-inf") != std::string::npos) return 0.0f;
        return 1.0f;
    }
    double avgDb = dbSum / static_cast<double>(dbCnt);
    if (avgDb >= -0.05) return 1.0f;
    if (avgDb <= -95.0) return 0.0f;
    return std::clamp(static_cast<float>(std::pow(10.0, avgDb / 20.0)), 0.0f, 1.0f);
}
#endif

void DesktopAudioEngine::suspendPipeWireSinkLocked(int cardNum) {
#if !defined(_WIN32) && !defined(__APPLE__)
    if (cardNum < 0 || cardNum > 64) return;

    std::string sinksOut;
    const char* listArgs[] = { "pactl", "list", "sinks", nullptr };
    if (!capturePipeCommandOutput(listArgs, sinksOut)) return;

    std::string curName;
    std::string curAlsaCard;
    bool curHasHwVol = false;
    std::string foundSink;
    bool foundHasHwVol = false;
    const std::string targetStr = std::to_string(cardNum);

    auto checkBlock = [&]() {
        if (!curName.empty() && curAlsaCard == targetStr) {
            foundSink = curName;
            foundHasHwVol = curHasHwVol;
        }
    };

    size_t pos = 0;
    while (pos < sinksOut.size()) {
        size_t nextNl = sinksOut.find('\n', pos);
        if (nextNl == std::string::npos) nextNl = sinksOut.size();
        std::string s = sinksOut.substr(pos, nextNl - pos);
        pos = nextNl + 1;

        if (s.rfind("Sink #", 0) == 0) {
            checkBlock();
            if (!foundSink.empty()) break;
            curName.clear();
            curAlsaCard.clear();
            curHasHwVol = false;
            continue;
        }
        size_t posFlags = s.find("Flags:");
        if (posFlags != std::string::npos && posFlags <= 2) {
            if (s.find("HW_VOLUME_CTRL") != std::string::npos) {
                curHasHwVol = true;
            }
            continue;
        }
        size_t posName = s.find("Name:");
        if (posName != std::string::npos && posName <= 2) {
            std::string val = s.substr(posName + 5);
            size_t first = val.find_first_not_of(" \t");
            if (first != std::string::npos) curName = val.substr(first);
            continue;
        }
        size_t posAlsa = s.find("alsa.card = \"");
        if (posAlsa != std::string::npos) {
            size_t start = posAlsa + 13;
            size_t end = s.find('"', start);
            if (end != std::string::npos) curAlsaCard = s.substr(start, end - start);
        }
    }
    if (foundSink.empty()) checkBlock();
    if (foundSink.empty()) return;

    if (!mSuspendedPwSink.empty() && mSuspendedPwSink != foundSink) {
        resumePipeWireSinkLocked();
    }

    float initialSysVol = queryPipeWireSinkLinearVolume(foundSink);
    mSuspendedSinkHasHwVol = foundHasHwVol;
    if (foundHasHwVol) {
        // DAC already has its ALSA hardware mixer (HW_VOLUME_CTRL) set by PipeWire.
        // Applying initialSysVol in software too would double-attenuate by that dB amount.
        mSuspendedSinkHwBase = (initialSysVol > 0.001f) ? initialSysVol : 1.0f;
        float effVol = (initialSysVol <= 0.0001f) ? 0.0f : 1.0f;
        mSystemSinkVolume.store(effVol, std::memory_order_release);
        mCurrentSystemVolume = effVol;
    } else {
        mSuspendedSinkHwBase = 1.0f;
        mSystemSinkVolume.store(initialSysVol, std::memory_order_release);
        mCurrentSystemVolume = initialSysVol;
    }

    if (mSuspendedPwSink.empty()) {
        // Suspend only the sink so PipeWire closes /dev/snd/pcmC*p and blocks other apps,
        // while keeping the DAC visible in system sound controls for volume/mute adjustments.
        if (runPactlSuspendSink(foundSink, true)) {
            mSuspendedPwSink = foundSink;
            usleep(25000);
        }
    }
#else
    (void)cardNum;
#endif
}

void DesktopAudioEngine::startSystemVolumeWatcherLocked() {
#if !defined(_WIN32) && !defined(__APPLE__)
    stopSystemVolumeWatcherLocked();
    if (mSuspendedPwSink.empty()) return;

    int pipefd[2];
    if (pipe(pipefd) != 0) return;

    pid_t pid = fork();
    if (pid == 0) {
        close(pipefd[0]);
        dup2(pipefd[1], STDOUT_FILENO);
        int devnull = open("/dev/null", O_WRONLY);
        if (devnull >= 0) {
            dup2(devnull, STDERR_FILENO);
            close(devnull);
        }
        close(pipefd[1]);
        setenv("LC_ALL", "C", 1);
        execlp("pactl", "pactl", "subscribe", static_cast<char*>(nullptr));
        _exit(127);
    }
    close(pipefd[1]);
    if (pid < 0) {
        close(pipefd[0]);
        return;
    }

    mSysVolSubPid = static_cast<int>(pid);
    mSysVolPipeFd = pipefd[0];
    mSysVolRunning.store(true, std::memory_order_release);
    std::string targetSink = mSuspendedPwSink;
    int readFd = pipefd[0];

    mSysVolThread = std::thread([this, targetSink, readFd]() {
        char buf[1024];
        std::string accum;
        while (mSysVolRunning.load(std::memory_order_acquire)) {
            ssize_t n = read(readFd, buf, sizeof(buf));
            if (n <= 0) break;
            accum.append(buf, static_cast<size_t>(n));
            size_t nl = std::string::npos;
            bool sinkChanged = false;
            while ((nl = accum.find('\n')) != std::string::npos) {
                std::string line = accum.substr(0, nl);
                accum.erase(0, nl + 1);
                if (line.find("on sink") != std::string::npos) {
                    sinkChanged = true;
                }
            }
            if (sinkChanged && mSysVolRunning.load(std::memory_order_acquire)) {
                float v = queryPipeWireSinkLinearVolume(targetSink);
                if (mSuspendedSinkHasHwVol) {
                    if (v <= 0.0001f) {
                        v = 0.0f;
                    } else if (mSuspendedSinkHwBase > 0.001f && v < mSuspendedSinkHwBase) {
                        v = std::clamp(v / mSuspendedSinkHwBase, 0.0f, 1.0f);
                    } else {
                        v = 1.0f;
                    }
                }
                mSystemSinkVolume.store(v, std::memory_order_release);
            }
        }
    });
#endif
}

void DesktopAudioEngine::stopSystemVolumeWatcherLocked() {
#if !defined(_WIN32) && !defined(__APPLE__)
    mSysVolRunning.store(false, std::memory_order_release);
    if (mSysVolSubPid > 0) {
        kill(static_cast<pid_t>(mSysVolSubPid), SIGTERM);
    }
    if (mSysVolPipeFd >= 0) {
        close(mSysVolPipeFd);
        mSysVolPipeFd = -1;
    }
    if (mSysVolThread.joinable()) {
        mSysVolThread.join();
    }
    if (mSysVolSubPid > 0) {
        int st = 0;
        waitpid(static_cast<pid_t>(mSysVolSubPid), &st, 0);
        mSysVolSubPid = -1;
    }
#endif
}

void DesktopAudioEngine::resumePipeWireSinkLocked() {
#if !defined(_WIN32) && !defined(__APPLE__)
    stopSystemVolumeWatcherLocked();
    if (!mSuspendedPwSink.empty()) {
        runPactlSuspendSink(mSuspendedPwSink, false);
        mSuspendedPwSink.clear();
    }
    mSuspendedSinkHasHwVol = false;
    mSuspendedSinkHwBase = 1.0f;
    mSystemSinkVolume.store(1.0f, std::memory_order_release);
    mCurrentSystemVolume = 1.0f;
#endif
}

DesktopAudioEngine::DesktopAudioEngine()
    : mPreBuffer(PREBUFFER_SIZE),
      mCallbackFloatBuf(32768),
      mNextPreBuffer(PREBUFFER_SIZE * 2),
      mNextMixBuffer(PREBUFFER_SIZE) {
    mDecoder = std::make_unique<DesktopDecoder>();
    mRingBuffer = std::make_unique<RingBuffer<float>>(RING_BUFFER_CAPACITY);
    mNextDecoder = std::make_unique<DesktopDecoder>();
    mNextRingBuffer = std::make_unique<RingBuffer<float>>(RING_BUFFER_CAPACITY);
    mEqualizer = std::make_unique<DesktopEqualizer>();
    for (int i = 0; i < 16; ++i) {
        mSpectrum[i].store(0.0f, std::memory_order_relaxed);
    }
}

DesktopAudioEngine::~DesktopAudioEngine() {
    stop();
    std::lock_guard<std::mutex> lock(mLock);
    closeStream();
    uninitContextLocked();
}

void DesktopAudioEngine::uninitContextLocked() {
    if (mContextReady) {
        ma_context_uninit(&mContext);
        mContextReady = false;
    }
}

bool DesktopAudioEngine::ensureContextLocked(bool exclusiveBackend) {
    if (mContextReady && mContextExclusiveBackend == exclusiveBackend) return true;
    uninitContextLocked();

    ma_context_config ctxCfg = ma_context_config_init();
    if (exclusiveBackend) {
#if defined(_WIN32)
        ma_backend backends[] = { ma_backend_wasapi };
        if (ma_context_init(backends, 1, &ctxCfg, &mContext) == MA_SUCCESS) {
            mContextReady = true;
            mContextExclusiveBackend = true;
            return true;
        }
#elif !defined(__APPLE__)
        ma_backend backends[] = { ma_backend_alsa };
        if (ma_context_init(backends, 1, &ctxCfg, &mContext) == MA_SUCCESS) {
            mContextReady = true;
            mContextExclusiveBackend = true;
            return true;
        }
#endif
    }

    if (ma_context_init(nullptr, 0, &ctxCfg, &mContext) == MA_SUCCESS) {
        mContextReady = true;
        mContextExclusiveBackend = exclusiveBackend;
        return true;
    }
    return false;
}

std::vector<AudioDeviceEntry> DesktopAudioEngine::enumerateDevicesLocked() {
    std::vector<AudioDeviceEntry> out;
    if (!ensureContextLocked(mExclusiveMode)) return out;

    ma_device_info* pPlaybackInfos = nullptr;
    ma_uint32 playbackCount = 0;
    if (ma_context_get_devices(&mContext, &pPlaybackInfos, &playbackCount, nullptr, nullptr) != MA_SUCCESS) {
        return out;
    }

    out.reserve(playbackCount);
    for (ma_uint32 i = 0; i < playbackCount; ++i) {
#if !defined(_WIN32) && !defined(__APPLE__)
        bool isHw = (mContext.backend == ma_backend_alsa)
                        ? (pPlaybackInfos[i].id.alsa[0] == ':')
                        : true;
        if (mExclusiveMode && mContext.backend == ma_backend_alsa && !isHw) {
            continue;
        }
#else
        bool isHw = true;
#endif
        AudioDeviceEntry entry;
        entry.id = bytesToHex(&pPlaybackInfos[i].id, sizeof(ma_device_id));
        entry.name = pPlaybackInfos[i].name;
        entry.isDefault = pPlaybackInfos[i].isDefault != 0;
        entry.isHardware = isHw;
        out.push_back(std::move(entry));
    }
    return out;
}

std::vector<AudioDeviceEntry> DesktopAudioEngine::enumerateDevices() {
    std::lock_guard<std::mutex> lock(mLock);
    return enumerateDevicesLocked();
}

std::string DesktopAudioEngine::resolveBestDeviceNameHintLocked() {
    if (!mContextReady || mContextExclusiveBackend) return "";
    ma_device_info* pPlay = nullptr;
    ma_uint32 cnt = 0;
    if (ma_context_get_devices(&mContext, &pPlay, &cnt, nullptr, nullptr) != MA_SUCCESS) return "";

    ma_device_id target{};
    bool hasChosen = (!mDeviceId.empty() && mDeviceId != "default" &&
                      hexToBytes(mDeviceId, &target, sizeof(ma_device_id)));
    for (ma_uint32 i = 0; i < cnt; ++i) {
        if (hasChosen && std::memcmp(&pPlay[i].id, &target, sizeof(ma_device_id)) == 0) {
            return pPlay[i].name;
        }
    }
    for (ma_uint32 i = 0; i < cnt; ++i) {
        if (pPlay[i].isDefault) return pPlay[i].name;
    }
    return (cnt > 0) ? pPlay[0].name : "";
}

void DesktopAudioEngine::stopDecodeThread() {
    mDecodeThreadRunning.store(false, std::memory_order_release);
    mDecodeCv.notify_all();
    if (mDecodeThread.joinable()) mDecodeThread.join();
}

void DesktopAudioEngine::stopNextDecodeThread() {
    mNextDecodeThreadRunning.store(false, std::memory_order_release);
    mNextDecodeCv.notify_all();
    if (mNextDecodeThread.joinable()) mNextDecodeThread.join();
}

void DesktopAudioEngine::resetNextDeckLocked() {
    mCrossfadeActive.store(false, std::memory_order_release);
    mCrossfadeCompleted.store(false, std::memory_order_release);
    mNextIsActive.store(false, std::memory_order_release);
    mNextPreloaded.store(false, std::memory_order_release);
    stopNextDecodeThread();
    beginBufferReset();
    if (mNextRingBuffer) mNextRingBuffer->clear();
    mNextPlayedSampleCount.store(0, std::memory_order_release);
    mNextOutputSampleRate.store(0, std::memory_order_release);
    mNextTrackReplayGainLinear.store(1.0f, std::memory_order_release);
    mNextDecoderReachedEof.store(false, std::memory_order_release);
    mNextEof.store(false, std::memory_order_release);
    mCrossfadeDurationFrames.store(0, std::memory_order_release);
    mCrossfadeElapsedFrames = 0;
    endBufferReset();
    if (mNextDecoder) mNextDecoder->close();
}

float DesktopAudioEngine::estimatePrebufferReplayGain(const float* buffer, int32_t totalSamples) {
    if (!buffer || totalSamples <= 0) return 1.0f;
    double sumSq = 0.0;
    float peak = 0.0f;
    int32_t nonSilent = 0;
    for (int32_t i = 0; i < totalSamples; ++i) {
        float absVal = std::fabs(buffer[i]);
        if (absVal > 0.001f) {
            sumSq += static_cast<double>(absVal) * static_cast<double>(absVal);
            nonSilent++;
        }
        if (absVal > peak) peak = absVal;
    }
    if (nonSilent <= 256) return 1.0f;

    float rms = static_cast<float>(std::sqrt(sumSq / static_cast<double>(nonSilent)));
    float desiredGain = (rms > 0.005f) ? (0.18f / rms) : 1.0f;
    desiredGain = std::clamp(desiredGain, 0.5f, 2.0f);
    if (peak * desiredGain > 0.98f && peak > 0.01f) {
        desiredGain = 0.98f / peak;
    }
    return desiredGain;
}

void DesktopAudioEngine::beginBufferReset() {
    mIsResettingBuffer.store(true, std::memory_order_seq_cst);
    while (mActiveCallbacks.load(std::memory_order_acquire) > 0) {
        std::this_thread::yield();
    }
}

void DesktopAudioEngine::endBufferReset() {
    mIsResettingBuffer.store(false, std::memory_order_release);
}

void DesktopAudioEngine::syncDecoderSampleRateToStreamLocked() {
    if (!mDeviceOpen || !mDecoder) return;
    uint32_t hwSr = mDevice.sampleRate;
    uint32_t decSr = mDecoder->getSampleRate();
    if (hwSr > 0 && decSr > 0 && hwSr != decSr) {
        int64_t prevFrame = static_cast<int64_t>(
            mPlayedSampleCount.load(std::memory_order_acquire) / std::max<uint32_t>(1, mDecoder->getChannels())
        );
        mDecoder->reconfigureOutputSampleRate(hwSr);
        mOutputSampleRate.store(hwSr, std::memory_order_release);
        uint32_t ch = mDecoder->getChannels() > 0 ? mDecoder->getChannels() : 2u;
        int64_t scaledFrame = (prevFrame * static_cast<int64_t>(hwSr)) / static_cast<int64_t>(decSr);
        if (scaledFrame < 0) scaledFrame = 0;
        mPlayedSampleCount.store(static_cast<uint64_t>(scaledFrame) * ch, std::memory_order_release);
        if (mNextPreloaded.load(std::memory_order_acquire) ||
            mNextIsActive.load(std::memory_order_acquire) ||
            mCrossfadeActive.load(std::memory_order_acquire) ||
            mCrossfadeCompleted.load(std::memory_order_acquire)) {
            resetNextDeckLocked();
        }
    }
}

bool DesktopAudioEngine::openStream(int channelCount, int sampleRate) {
    mLastError.clear();
    mActualExclusive = false;

    std::string sharedHintNow = resolveBestDeviceNameHintLocked();
    if (!sharedHintNow.empty()) {
        mLastSharedDeviceHint = sharedHintNow;
    }
    if (!ensureContextLocked(mExclusiveMode)) {
        mLastError = "Не удалось инициализировать аудиоконтекст";
        return false;
    }

    ma_device_info* pPlayInfos = nullptr;
    ma_uint32 playCount = 0;
    ma_context_get_devices(&mContext, &pPlayInfos, &playCount, nullptr, nullptr);

    struct CandidateDev {
        ma_device_id id{};
        std::string name;
        bool hasId = false;
        int score = 0;
    };
    std::vector<CandidateDev> candidates;
    bool strictChosenDevice = false;

    if (!mDeviceId.empty() && mDeviceId != "default") {
        ma_device_id target{};
        if (hexToBytes(mDeviceId, &target, sizeof(ma_device_id))) {
            for (ma_uint32 i = 0; i < playCount; ++i) {
                if (std::memcmp(&pPlayInfos[i].id, &target, sizeof(ma_device_id)) == 0) {
                    CandidateDev c;
                    c.id = pPlayInfos[i].id;
                    c.name = pPlayInfos[i].name;
                    c.hasId = true;
                    candidates.push_back(std::move(c));
                    strictChosenDevice = true;
                    break;
                }
            }
        }
    }

#if !defined(_WIN32) && !defined(__APPLE__)
    // In Linux ALSA Direct Exclusive mode with "default", select ONE best DAC endpoint
    // without touching/probing unrelated USB headsets or microphones in a brute-force loop.
    if (mExclusiveMode && mContext.backend == ma_backend_alsa && candidates.empty()) {
        int bestScore = INT32_MIN;
        ma_uint32 bestIdx = UINT32_MAX;
        for (ma_uint32 i = 0; i < playCount; ++i) {
            if (pPlayInfos[i].id.alsa[0] != ':') continue;
            std::string lname = toLowerAscii(pPlayInfos[i].name);
            int sc = 0;
            bool isHdmi = (lname.find("hdmi") != std::string::npos ||
                           lname.find("nvidia") != std::string::npos ||
                           lname.find("displayport") != std::string::npos);
            if (!isHdmi) {
                sc += scoreDeviceNameOverlap(mLastSharedDeviceHint, pPlayInfos[i].name) * 2;
            }
            if (lname.find("dac") != std::string::npos || lname.find("hifi") != std::string::npos ||
                lname.find("cx31993") != std::string::npos) {
                sc += 60;
            }
            if (lname.find("usb") != std::string::npos) sc += 25;
            if (lname.find("analog") != std::string::npos || lname.find("alc") != std::string::npos) sc += 15;
            if (isHdmi) sc -= 50;

            if (sc > bestScore) {
                bestScore = sc;
                bestIdx = i;
            }
        }
        if (bestIdx != UINT32_MAX) {
            CandidateDev c;
            c.id = pPlayInfos[bestIdx].id;
            c.name = pPlayInfos[bestIdx].name;
            c.hasId = true;
            c.score = bestScore;
            candidates.push_back(std::move(c));
        } else {
            mLastError = "Не найдено аппаратное ALSA-устройство для эксклюзивного захвата";
            return false;
        }
    }
#endif

    if (candidates.empty()) {
        CandidateDev def;
        def.hasId = false;
        def.name = "";
        candidates.push_back(std::move(def));
    }

    ma_result lastRes = MA_ERROR;
    std::string chosenName;

    for (const auto& cand : candidates) {
#if !defined(_WIN32) && !defined(__APPLE__)
        if (mExclusiveMode && mContext.backend == ma_backend_alsa && cand.hasId) {
            int cardNum = -1;
            if (parseAlsaCardNumber(cand.id.alsa, cardNum)) {
                suspendPipeWireSinkLocked(cardNum);
            }
        }
#endif
        auto tryInit = [&](bool exclusive, ma_format fmt, ma_uint32 reqRate) -> ma_result {
            ma_device_config cfg = ma_device_config_init(ma_device_type_playback);
            cfg.playback.format = fmt;
            cfg.playback.channels = static_cast<ma_uint32>(channelCount);
            cfg.playback.pDeviceID = cand.hasId ? const_cast<ma_device_id*>(&cand.id) : nullptr;
            cfg.playback.shareMode = exclusive ? ma_share_mode_exclusive : ma_share_mode_shared;
            cfg.sampleRate = reqRate;
            cfg.dataCallback = maDataCallback;
            cfg.pUserData = this;
            cfg.performanceProfile = ma_performance_profile_low_latency;
            return ma_device_init(&mContext, &cfg, &mDevice);
        };

        if (mExclusiveMode) {
            // Strict hardware exclusive mode: NEVER silently fall back to OS shared mixer!
            // Try audiophile formats in priority order: 32-bit int -> 24-bit int -> float32 -> 16-bit int.
            // Never push unsolicited ultra-high rates (e.g. 192kHz) to generic USB hardware; fallback only to 48000 / 44100.
            const ma_format exclFmts[] = { ma_format_s32, ma_format_s24, ma_format_f32, ma_format_s16 };
            std::vector<ma_uint32> candidateRates;
            candidateRates.push_back(static_cast<ma_uint32>(sampleRate));
            for (ma_uint32 r : { 48000u, 44100u }) {
                if (std::find(candidateRates.begin(), candidateRates.end(), r) == candidateRates.end()) {
                    candidateRates.push_back(r);
                }
            }

            ma_result res = MA_ERROR;
            for (ma_format fmt : exclFmts) {
                res = tryInit(true, fmt, static_cast<ma_uint32>(sampleRate));
                if (res == MA_SUCCESS) break;
            }
            if (res != MA_SUCCESS) {
                for (size_t ri = 1; ri < candidateRates.size() && res != MA_SUCCESS; ++ri) {
                    for (ma_format fmt : exclFmts) {
                        res = tryInit(true, fmt, candidateRates[ri]);
                        if (res == MA_SUCCESS) break;
                    }
                }
            }
            lastRes = res;
            if (res == MA_SUCCESS) {
                mActualExclusive = true;
                chosenName = cand.name;
                startSystemVolumeWatcherLocked();
                break;
            } else {
                resumePipeWireSinkLocked();
            }
            (void)strictChosenDevice;
        } else {
            ma_result res = tryInit(false, ma_format_f32, static_cast<ma_uint32>(sampleRate));
            if (res != MA_SUCCESS && cand.hasId) {
                ma_device_config cfg = ma_device_config_init(ma_device_type_playback);
                cfg.playback.format = ma_format_f32;
                cfg.playback.channels = static_cast<ma_uint32>(channelCount);
                cfg.playback.pDeviceID = nullptr;
                cfg.playback.shareMode = ma_share_mode_shared;
                cfg.sampleRate = static_cast<ma_uint32>(sampleRate);
                cfg.dataCallback = maDataCallback;
                cfg.pUserData = this;
                cfg.performanceProfile = ma_performance_profile_low_latency;
                res = ma_device_init(&mContext, &cfg, &mDevice);
                chosenName.clear();
            } else {
                chosenName = cand.name;
            }
            lastRes = res;
            if (res == MA_SUCCESS) break;
        }
    }

    if (lastRes != MA_SUCCESS) {
        if (mExclusiveMode) {
            mLastError = std::string("Эксклюзивный захват ЦАП отклонён (устройство занято другой программой): ") +
                         ma_result_description(lastRes);
        } else {
            mLastError = std::string("Ошибка открытия аудиоустройства: ") + ma_result_description(lastRes);
        }
        return false;
    }

    mDeviceOpen = true;
    mDeviceStarted = false;
    mActualFormat = mDevice.playback.format;

    if (chosenName.empty() && mDevice.playback.name[0] != '\0') {
        chosenName = mDevice.playback.name;
    }
    mActiveDeviceName = chosenName.empty() ? "По умолчанию" : chosenName;

    uint32_t actualSr = mDevice.sampleRate > 0 ? mDevice.sampleRate : static_cast<uint32_t>(sampleRate);
    int actualCh = mDevice.playback.channels > 0 ? static_cast<int>(mDevice.playback.channels) : channelCount;
    size_t neededScratch = static_cast<size_t>(std::max<int>(16384, actualSr / 4)) * static_cast<size_t>(std::max(1, actualCh));
    if (mCallbackFloatBuf.size() < neededScratch) {
        mCallbackFloatBuf.resize(neededScratch);
    }

    mOutputSampleRate.store(actualSr, std::memory_order_release);
    if (mEqualizer) {
        mEqualizer->init(static_cast<float>(actualSr), actualCh);
    }
    return true;
}

void DesktopAudioEngine::closeStream() {
    if (mDeviceOpen) {
        if (mDeviceStarted) {
            ma_device_stop(&mDevice);
            mDeviceStarted = false;
        }
        ma_device_uninit(&mDevice);
        mDeviceOpen = false;
        mActualExclusive = false;
    }
    resumePipeWireSinkLocked();
}

bool DesktopAudioEngine::initStreamAfterLoad() {
    int sampleRate = static_cast<int>(mDecoder->getSampleRate());
    int channels = static_cast<int>(mDecoder->getChannels());
    mOutputSampleRate.store((sampleRate > 0) ? static_cast<uint32_t>(sampleRate) : 0u, std::memory_order_release);

    bool gapless = mGaplessEnabled.load(std::memory_order_acquire);
    bool rateMatch = mDeviceOpen && (static_cast<int>(mDevice.sampleRate) == sampleRate);
    bool chMatch = mDeviceOpen && (static_cast<int>(mDevice.playback.channels) == channels);
    bool modeMatch = (mActualExclusive == mExclusiveMode);
    bool needNewStream = (!mDeviceOpen || !gapless || !rateMatch || !chMatch || !modeMatch);

    if (needNewStream) {
        closeStream();
        if (!openStream(channels, sampleRate)) return false;
    }

    syncDecoderSampleRateToStreamLocked();

    uint32_t ch = (channels > 0) ? static_cast<uint32_t>(channels) : 2u;
    uint64_t framesToRead = PREBUFFER_SIZE / ch;
    uint64_t framesDecoded = mDecoder->decodeFrames(mPreBuffer.data(), framesToRead);
    if (framesDecoded > 0) {
        int32_t totalSamples = static_cast<int32_t>(framesDecoded * ch);
        mRingBuffer->write(mPreBuffer.data(), totalSamples);
        float rgLinear = mDecoder->hasReplayGain()
                             ? mDecoder->getReplayGainLinear()
                             : estimatePrebufferReplayGain(mPreBuffer.data(), totalSamples);
        mTrackReplayGainLinear.store(rgLinear, std::memory_order_release);
    } else {
        float rgLinear = mDecoder->hasReplayGain() ? mDecoder->getReplayGainLinear() : 1.0f;
        mTrackReplayGainLinear.store(rgLinear, std::memory_order_release);
    }
    return true;
}

bool DesktopAudioEngine::loadFile(const std::string& filePath) {
    std::lock_guard<std::mutex> lock(mLock);

    mIsPlaying.store(false, std::memory_order_release);
    stopDecodeThread();
    resetNextDeckLocked();

    beginBufferReset();
    mIsEof = false;
    mDecoderReachedEof = false;
    mPlayedSampleCount.store(0, std::memory_order_release);
    mOutputSampleRate.store(0, std::memory_order_release);
    mVolumeRampCoeff.store(0.0015f, std::memory_order_release);
    mPauseFadeTarget.store(1.0f, std::memory_order_release);
    mCurrentPauseFade = 1.0f;
    mLimiterGain = 1.0f;
    mRingBuffer->clear();
    if (mEqualizer) mEqualizer->reset();

    if (!mDecoder->openFile(filePath)) {
        endBufferReset();
        mLastError = "Не удалось декодировать аудиофайл";
        return false;
    }

    bool ok = initStreamAfterLoad();
    endBufferReset();
    return ok;
}

int64_t DesktopAudioEngine::seekTo(int64_t targetFrame) {
    std::lock_guard<std::mutex> lock(mLock);
    if (!mDecoder || mDecoder->getSampleRate() == 0) return -1;

    if (targetFrame < 0) targetFrame = 0;
    int64_t totalFrames = static_cast<int64_t>(mDecoder->getTotalFrames());
    if (totalFrames > 0 && targetFrame >= totalFrames) targetFrame = totalFrames - 1;

    bool wasRunning = mDecodeThreadRunning.load(std::memory_order_acquire);
    if (wasRunning) stopDecodeThread();

    if (mCrossfadeActive.load(std::memory_order_acquire) ||
        mCrossfadeCompleted.load(std::memory_order_acquire) ||
        mNextIsActive.load(std::memory_order_acquire)) {
        resetNextDeckLocked();
    }

    beginBufferReset();
    mIsEof = false;
    mDecoderReachedEof = false;

    int64_t result = mDecoder->seek(targetFrame);
    int64_t actualFrame = (result >= 0) ? result : targetFrame;
    if (actualFrame < 0) actualFrame = 0;
    uint32_t ch = mDecoder->getChannels() > 0 ? mDecoder->getChannels() : 2u;
    mPlayedSampleCount.store(static_cast<uint64_t>(actualFrame) * ch, std::memory_order_release);

    mRingBuffer->clear();
    if (mEqualizer) mEqualizer->reset();

    uint64_t framesToRead = PREBUFFER_SIZE / ch;
    uint64_t framesDecoded = mDecoder->decodeFrames(mPreBuffer.data(), framesToRead);
    if (framesDecoded > 0) {
        mRingBuffer->write(mPreBuffer.data(), static_cast<int32_t>(framesDecoded * ch));
    }
    endBufferReset();

    if ((wasRunning || mIsPlaying.load(std::memory_order_acquire)) && mDecoder->getSampleRate() > 0) {
        mDecodeThreadRunning.store(true, std::memory_order_release);
        mDecodeThread = std::thread(&DesktopAudioEngine::decodeLoop, this);
    }
    return result;
}

bool DesktopAudioEngine::seekToSeconds(double seconds) {
    uint32_t sr = mOutputSampleRate.load(std::memory_order_acquire);
    if (sr == 0) return false;
    int64_t targetFrame = static_cast<int64_t>(std::max(0.0, seconds) * static_cast<double>(sr));
    return seekTo(targetFrame) >= 0;
}

bool DesktopAudioEngine::start() {
    std::lock_guard<std::mutex> lock(mLock);
    if (!mDecoder || mDecoder->getSampleRate() == 0) return false;

    if (!mDeviceOpen) {
        if (!openStream(static_cast<int>(mDecoder->getChannels()), static_cast<int>(mDecoder->getSampleRate()))) {
            return false;
        }
        syncDecoderSampleRateToStreamLocked();
        beginBufferReset();
        uint32_t ch = std::max<uint32_t>(1, mDecoder->getChannels());
        int64_t curFrame = static_cast<int64_t>(mPlayedSampleCount.load(std::memory_order_acquire) / ch);
        mDecoder->seek(curFrame);
        mRingBuffer->clear();
        endBufferReset();
    }

    if (!mDeviceStarted) {
        if (ma_device_start(&mDevice) != MA_SUCCESS) {
            mLastError = "Не удалось запустить аудиопоток";
            return false;
        }
        mDeviceStarted = true;
    }

    mIsPlaying.store(true, std::memory_order_release);
    mVolumeRampCoeff.store(0.0015f, std::memory_order_release);
    mPauseFadeTarget.store(1.0f, std::memory_order_release);
    mIsEof = false;
    mDecoderReachedEof = false;

    stopDecodeThread();
    mDecodeThreadRunning.store(true, std::memory_order_release);
    mDecodeThread = std::thread(&DesktopAudioEngine::decodeLoop, this);

    if (mNextIsActive.load(std::memory_order_acquire) && mNextDecoder && mNextDecoder->getSampleRate() > 0) {
        stopNextDecodeThread();
        mNextDecodeThreadRunning.store(true, std::memory_order_release);
        mNextDecodeThread = std::thread(&DesktopAudioEngine::nextDecodeLoop, this);
    }
    return true;
}

void DesktopAudioEngine::pause() {
    std::lock_guard<std::mutex> lock(mLock);
    if (!mIsPlaying.load(std::memory_order_acquire)) return;

    mIsPlaying.store(false, std::memory_order_release);
    // Keep mDeviceOpen in Exclusive mode so other apps cannot hijack the DAC while paused;
    // stopping playback simply outputs silence / pauses the hardware endpoint cleanly.
    if (mDeviceOpen && mDeviceStarted && !mActualExclusive) {
        ma_device_stop(&mDevice);
        mDeviceStarted = false;
    }
    stopDecodeThread();
    stopNextDecodeThread();
    mVolumeRampCoeff.store(0.0015f, std::memory_order_release);
    mPauseFadeTarget.store(1.0f, std::memory_order_release);
    for (int i = 0; i < 16; ++i) mSpectrum[i].store(0.0f, std::memory_order_relaxed);
}

void DesktopAudioEngine::stop() {
    std::lock_guard<std::mutex> lock(mLock);
    mIsPlaying.store(false, std::memory_order_release);
    stopDecodeThread();
    resetNextDeckLocked();
    mVolumeRampCoeff.store(0.0015f, std::memory_order_release);
    mPauseFadeTarget.store(1.0f, std::memory_order_release);
    closeStream();
    for (int i = 0; i < 16; ++i) mSpectrum[i].store(0.0f, std::memory_order_relaxed);
}

bool DesktopAudioEngine::reopenStreamLocked(bool restartIfPlaying) {
    if (!mDecoder || mDecoder->getSampleRate() == 0) {
        // Still switch context / validate availability cleanly when idle
        closeStream();
        return ensureContextLocked(mExclusiveMode);
    }

    stopDecodeThread();
    stopNextDecodeThread();
    bool shouldPlay = restartIfPlaying && mIsPlaying.load(std::memory_order_acquire);
    mIsPlaying.store(false, std::memory_order_release);

    closeStream();
    if (!openStream(static_cast<int>(mDecoder->getChannels()), static_cast<int>(mDecoder->getSampleRate()))) {
        return false;
    }
    syncDecoderSampleRateToStreamLocked();
    beginBufferReset();
    uint32_t ch = std::max<uint32_t>(1, mDecoder->getChannels());
    int64_t curFrame = static_cast<int64_t>(mPlayedSampleCount.load(std::memory_order_acquire) / ch);
    mDecoder->seek(curFrame);
    mRingBuffer->clear();
    endBufferReset();

    if (shouldPlay) {
        if (ma_device_start(&mDevice) != MA_SUCCESS) return false;
        mDeviceStarted = true;
        mIsPlaying.store(true, std::memory_order_release);
        mDecodeThreadRunning.store(true, std::memory_order_release);
        mDecodeThread = std::thread(&DesktopAudioEngine::decodeLoop, this);
        if (mNextIsActive.load(std::memory_order_acquire) && mNextDecoder && mNextDecoder->getSampleRate() > 0) {
            mNextDecodeThreadRunning.store(true, std::memory_order_release);
            mNextDecodeThread = std::thread(&DesktopAudioEngine::nextDecodeLoop, this);
        }
    }
    return true;
}

bool DesktopAudioEngine::setOutputDeviceId(const std::string& deviceId) {
    std::lock_guard<std::mutex> lock(mLock);
    mDeviceId = deviceId.empty() ? "default" : deviceId;
    return reopenStreamLocked(mIsPlaying.load(std::memory_order_acquire));
}

bool DesktopAudioEngine::setExclusiveMode(bool enabled) {
    std::lock_guard<std::mutex> lock(mLock);
    if (mExclusiveMode == enabled) return true;
    std::string hint = resolveBestDeviceNameHintLocked();
    if (!hint.empty()) {
        mLastSharedDeviceHint = hint;
    }
    mExclusiveMode = enabled;
#if !defined(_WIN32) && !defined(__APPLE__)
    mDeviceId = "default";
#endif
    closeStream();
    ensureContextLocked(enabled);
    if (mDecoder && mDecoder->getSampleRate() > 0) {
        return reopenStreamLocked(mIsPlaying.load(std::memory_order_acquire));
    }
    return true;
}

void DesktopAudioEngine::setUserVolume(float volume) {
    mUserVolume.store(std::clamp(volume, 0.0f, 1.0f), std::memory_order_release);
}

void DesktopAudioEngine::beginPauseFade(int durationMs) {
    uint32_t sr = mOutputSampleRate.load(std::memory_order_acquire);
    if (sr == 0) sr = 44100;
    float durSec = static_cast<float>(std::clamp(durationMs, 30, 500)) / 1000.0f;
    float coeff = std::clamp(5.8f / (durSec * static_cast<float>(sr) * 2.0f), 0.0001f, 0.01f);
    mVolumeRampCoeff.store(coeff, std::memory_order_release);
    mPauseFadeTarget.store(0.0f, std::memory_order_release);
}

void DesktopAudioEngine::cancelPauseFade() {
    mVolumeRampCoeff.store(0.0015f, std::memory_order_release);
    mPauseFadeTarget.store(1.0f, std::memory_order_release);
}

void DesktopAudioEngine::setReplayGainEnabled(bool enabled) {
    mReplayGainEnabled.store(enabled, std::memory_order_release);
}

void DesktopAudioEngine::setGaplessEnabled(bool enabled) {
    mGaplessEnabled.store(enabled, std::memory_order_release);
}

bool DesktopAudioEngine::initNextDeckAfterLoad() {
    uint32_t nextSr = mNextDecoder->getSampleRate();
    uint32_t nextCh = mNextDecoder->getChannels();
    if (nextSr == 0 || nextCh == 0) return false;

    mNextOutputSampleRate.store(nextSr, std::memory_order_release);
    uint64_t framesToRead = static_cast<uint64_t>(mNextPreBuffer.size() / nextCh);
    uint64_t framesDecoded = mNextDecoder->decodeFrames(mNextPreBuffer.data(), framesToRead);
    if (framesDecoded > 0) {
        int32_t totalSamples = static_cast<int32_t>(framesDecoded * nextCh);
        mNextRingBuffer->write(mNextPreBuffer.data(), totalSamples);
        float rgLinear = mNextDecoder->hasReplayGain()
                             ? mNextDecoder->getReplayGainLinear()
                             : estimatePrebufferReplayGain(mNextPreBuffer.data(), totalSamples);
        mNextTrackReplayGainLinear.store(rgLinear, std::memory_order_release);
    } else {
        float rgLinear = mNextDecoder->hasReplayGain() ? mNextDecoder->getReplayGainLinear() : 1.0f;
        mNextTrackReplayGainLinear.store(rgLinear, std::memory_order_release);
    }
    return true;
}

bool DesktopAudioEngine::preloadNextFile(const std::string& filePath) {
    std::lock_guard<std::mutex> lock(mLock);
    if (!mDecoder || mDecoder->getSampleRate() == 0) return false;

    resetNextDeckLocked();
    if (!mNextDecoder || !mNextRingBuffer) return false;

    uint32_t targetSr = mOutputSampleRate.load(std::memory_order_acquire);
    if (targetSr == 0) targetSr = mDecoder->getSampleRate();

    if (!mNextDecoder->openFile(filePath, targetSr)) return false;
    if (!initNextDeckAfterLoad()) {
        resetNextDeckLocked();
        return false;
    }
    mNextPreloaded.store(true, std::memory_order_release);
    return true;
}

bool DesktopAudioEngine::startCrossfade(int durationMs) {
    std::lock_guard<std::mutex> lock(mLock);
    if (!mNextPreloaded.load(std::memory_order_acquire) || !mNextDecoder || mNextDecoder->getSampleRate() == 0) {
        return false;
    }
    if (mCrossfadeActive.load(std::memory_order_acquire) || mCrossfadeCompleted.load(std::memory_order_acquire)) {
        return false;
    }

    uint32_t sr = mOutputSampleRate.load(std::memory_order_acquire);
    if (sr == 0) sr = mDecoder ? mDecoder->getSampleRate() : 44100;
    if (sr == 0) sr = 44100;

    int64_t clampedMs = std::max(100, durationMs);
    uint64_t fadeFrames = static_cast<uint64_t>((clampedMs * static_cast<int64_t>(sr)) / 1000LL);
    if (fadeFrames == 0) fadeFrames = 1;

    stopNextDecodeThread();
    mCrossfadeElapsedFrames = 0;
    mCrossfadeDurationFrames.store(fadeFrames, std::memory_order_release);
    mCrossfadeCompleted.store(false, std::memory_order_release);
    mNextIsActive.store(true, std::memory_order_release);
    mCrossfadeActive.store(true, std::memory_order_release);

    if (mIsPlaying.load(std::memory_order_acquire)) {
        mNextDecodeThreadRunning.store(true, std::memory_order_release);
        mNextDecodeThread = std::thread(&DesktopAudioEngine::nextDecodeLoop, this);
    }
    return true;
}

void DesktopAudioEngine::cancelCrossfade() {
    std::lock_guard<std::mutex> lock(mLock);
    resetNextDeckLocked();
}

bool DesktopAudioEngine::promotePreloadedTrack() {
    std::lock_guard<std::mutex> lock(mLock);
    if (!mNextDecoder || mNextDecoder->getSampleRate() == 0) return false;

    stopDecodeThread();
    stopNextDecodeThread();

    auto freshNextDecoder = std::make_unique<DesktopDecoder>();
    std::unique_ptr<DesktopDecoder> oldPrimary;

    beginBufferReset();
    oldPrimary = std::move(mDecoder);
    mDecoder = std::move(mNextDecoder);
    mNextDecoder = std::move(freshNextDecoder);
    std::swap(mRingBuffer, mNextRingBuffer);

    mPlayedSampleCount.store(mNextPlayedSampleCount.load(std::memory_order_acquire), std::memory_order_release);
    mNextPlayedSampleCount.store(0, std::memory_order_release);

    mDecoderReachedEof.store(mNextDecoderReachedEof.load(std::memory_order_acquire), std::memory_order_release);
    mNextDecoderReachedEof.store(false, std::memory_order_release);

    mIsEof.store(mNextEof.load(std::memory_order_acquire), std::memory_order_release);
    mNextEof.store(false, std::memory_order_release);

    mOutputSampleRate.store(mNextOutputSampleRate.load(std::memory_order_acquire), std::memory_order_release);
    mNextOutputSampleRate.store(0, std::memory_order_release);

    mTrackReplayGainLinear.store(mNextTrackReplayGainLinear.load(std::memory_order_acquire), std::memory_order_release);
    mNextTrackReplayGainLinear.store(1.0f, std::memory_order_release);

    mCrossfadeActive.store(false, std::memory_order_release);
    mCrossfadeCompleted.store(false, std::memory_order_release);
    mNextIsActive.store(false, std::memory_order_release);
    mNextPreloaded.store(false, std::memory_order_release);
    mCrossfadeDurationFrames.store(0, std::memory_order_release);
    mCrossfadeElapsedFrames = 0;
    endBufferReset();

    if (mNextRingBuffer) mNextRingBuffer->clear();
    if (oldPrimary) oldPrimary->close();

    if (mIsPlaying.load(std::memory_order_acquire) && mDecoder && mDecoder->getSampleRate() > 0) {
        mDecodeThreadRunning.store(true, std::memory_order_release);
        mDecodeThread = std::thread(&DesktopAudioEngine::decodeLoop, this);
    }
    return true;
}

void DesktopAudioEngine::runDecodeLoop(
    DesktopDecoder* decoder,
    RingBuffer<float>* ringBuffer,
    std::atomic<bool>& runningFlag,
    std::atomic<bool>& eofFlag,
    std::mutex& cvMutex,
    std::condition_variable& cv
) {
    const int32_t BUFFER_SIZE = 8192;
    const int32_t MIN_WRITE_CHUNK = 2048;
    std::vector<float> tempBuffer(BUFFER_SIZE);

    while (runningFlag.load(std::memory_order_acquire)) {
        if (eofFlag.load(std::memory_order_acquire)) {
            std::unique_lock<std::mutex> lock(cvMutex);
            cv.wait_for(lock, std::chrono::milliseconds(80), [&]() {
                return !runningFlag.load(std::memory_order_acquire) ||
                       !eofFlag.load(std::memory_order_acquire);
            });
            continue;
        }

        int32_t availableWrite = ringBuffer->getAvailableWrite();
        if (availableWrite >= MIN_WRITE_CHUNK) {
            uint32_t ch = decoder->getChannels();
            if (ch == 0) ch = 2;
            int32_t samplesToDecode = std::min<int32_t>(availableWrite, BUFFER_SIZE);
            uint64_t framesToRead = static_cast<uint64_t>(samplesToDecode / ch);
            if (framesToRead == 0) framesToRead = 1;

            uint64_t framesDecoded = decoder->decodeFrames(tempBuffer.data(), framesToRead);
            if (framesDecoded > 0) {
                ringBuffer->write(tempBuffer.data(), static_cast<int32_t>(framesDecoded * ch));
            } else {
                eofFlag.store(true, std::memory_order_release);
            }
        } else {
            std::unique_lock<std::mutex> lock(cvMutex);
            cv.wait_for(lock, std::chrono::milliseconds(4), [&]() {
                return !runningFlag.load(std::memory_order_acquire) ||
                       ringBuffer->getAvailableWrite() >= MIN_WRITE_CHUNK;
            });
        }
    }
}

void DesktopAudioEngine::decodeLoop() {
    runDecodeLoop(mDecoder.get(), mRingBuffer.get(), mDecodeThreadRunning, mDecoderReachedEof, mDecodeCvMutex, mDecodeCv);
}

void DesktopAudioEngine::nextDecodeLoop() {
    runDecodeLoop(mNextDecoder.get(), mNextRingBuffer.get(), mNextDecodeThreadRunning, mNextDecoderReachedEof, mNextDecodeCvMutex, mNextDecodeCv);
}

void DesktopAudioEngine::maDataCallback(ma_device* pDevice, void* pOutput, const void* /*pInput*/, ma_uint32 frameCount) {
    auto* engine = static_cast<DesktopAudioEngine*>(pDevice->pUserData);
    if (!engine || !pOutput || frameCount == 0) return;
    int32_t ch = static_cast<int32_t>(std::max<ma_uint32>(1, pDevice->playback.channels));
    engine->onAudioReady(pOutput, static_cast<int32_t>(frameCount), ch, pDevice->playback.format);
}

void DesktopAudioEngine::updateSpectrum(const float* floatData, int32_t numFrames, int32_t channels, uint32_t sampleRate) {
    if (numFrames <= 0 || channels <= 0 || sampleRate == 0) return;
    static constexpr float kBandHz[16] = {
        45.0f, 70.0f, 110.0f, 165.0f, 240.0f, 360.0f, 540.0f, 800.0f,
        1200.0f, 1800.0f, 2600.0f, 3800.0f, 5400.0f, 7600.0f, 10500.0f, 14000.0f
    };
    const int n = std::min<int>(numFrames, 128);
    const float invN = 1.0f / static_cast<float>(n);
    constexpr float kTwoPi = 6.28318530718f;

    for (int b = 0; b < 16; ++b) {
        if (kBandHz[b] >= static_cast<float>(sampleRate) * 0.45f) {
            mSpectrum[b].store(0.0f, std::memory_order_relaxed);
            continue;
        }
        float w = kTwoPi * kBandHz[b] / static_cast<float>(sampleRate);
        float coeff = 2.0f * std::cos(w);
        float q1 = 0.0f, q2 = 0.0f;
        for (int i = 0; i < n; ++i) {
            float mono = (channels >= 2)
                ? 0.5f * (floatData[i * channels] + floatData[i * channels + 1])
                : floatData[i * channels];
            float q0 = coeff * q1 - q2 + mono;
            q2 = q1;
            q1 = q0;
        }
        float power = q1 * q1 + q2 * q2 - q1 * q2 * coeff;
        float mag = std::sqrt(std::max(0.0f, power)) * invN * 2.2f;
        float level = std::clamp(std::log1p(mag * 4.5f) / 1.7f, 0.0f, 1.0f);
        float prev = mSpectrum[b].load(std::memory_order_relaxed);
        float smoothed = (level > prev) ? (prev * 0.35f + level * 0.65f) : (prev * 0.78f + level * 0.22f);
        mSpectrum[b].store(smoothed, std::memory_order_relaxed);
    }
}

void DesktopAudioEngine::onAudioReady(void* pOutput, int32_t numFrames, int32_t channelCount, ma_format outFormat) {
    struct CallbackGuard {
        std::atomic<int32_t>& c;
        explicit CallbackGuard(std::atomic<int32_t>& ref) : c(ref) { c.fetch_add(1, std::memory_order_acq_rel); }
        ~CallbackGuard() { c.fetch_sub(1, std::memory_order_acq_rel); }
    } guard(mActiveCallbacks);

    int32_t totalSamples = numFrames * channelCount;
    float* floatData = (outFormat == ma_format_f32)
                           ? static_cast<float*>(pOutput)
                           : ((static_cast<size_t>(totalSamples) <= mCallbackFloatBuf.size())
                                  ? mCallbackFloatBuf.data()
                                  : nullptr);

    if (!floatData ||
        !mIsPlaying.load(std::memory_order_acquire) ||
        mIsResettingBuffer.load(std::memory_order_acquire) ||
        !mRingBuffer) {
        size_t bytesPerSample = ma_get_bytes_per_sample(outFormat);
        if (bytesPerSample == 0) bytesPerSample = sizeof(float);
        std::memset(pOutput, 0, static_cast<size_t>(totalSamples) * bytesPerSample);
        return;
    }

    bool rgEnabled = mReplayGainEnabled.load(std::memory_order_relaxed);
    float rgA = rgEnabled ? mTrackReplayGainLinear.load(std::memory_order_relaxed) : 1.0f;
    float rgB = rgEnabled ? mNextTrackReplayGainLinear.load(std::memory_order_relaxed) : 1.0f;
    float postRgGain = 1.0f;

    bool crossfadeDone = mCrossfadeCompleted.load(std::memory_order_acquire);
    bool crossfadeNow = !crossfadeDone &&
                        mCrossfadeActive.load(std::memory_order_acquire) &&
                        mNextIsActive.load(std::memory_order_acquire) &&
                        mNextRingBuffer;

    if (crossfadeDone && mNextIsActive.load(std::memory_order_acquire) && mNextRingBuffer) {
        int32_t samplesRead = mNextRingBuffer->read(floatData, totalSamples);
        if (samplesRead > 0 && mNextRingBuffer->getAvailableWrite() >= 8192) mNextDecodeCv.notify_one();
        if (samplesRead > 0) mNextPlayedSampleCount.fetch_add(static_cast<uint64_t>(samplesRead), std::memory_order_relaxed);
        for (int32_t i = samplesRead; i < totalSamples; ++i) floatData[i] = 0.0f;
        if (samplesRead < totalSamples &&
            mNextDecoderReachedEof.load(std::memory_order_acquire) &&
            mNextRingBuffer->getAvailableRead() == 0 &&
            mNextPlayedSampleCount.load(std::memory_order_relaxed) > 0) {
            mNextEof.store(true, std::memory_order_release);
        }
        if (mEqualizer && samplesRead > 0) mEqualizer->processBuffer(floatData, samplesRead);
        postRgGain = rgB;
    } else if (crossfadeNow) {
        int32_t samplesA = mRingBuffer->read(floatData, totalSamples);
        if (samplesA > 0 && mRingBuffer->getAvailableWrite() >= 8192) mDecodeCv.notify_one();
        if (samplesA > 0) mPlayedSampleCount.fetch_add(static_cast<uint64_t>(samplesA), std::memory_order_relaxed);
        for (int32_t i = samplesA; i < totalSamples; ++i) floatData[i] = 0.0f;
        if (samplesA < totalSamples &&
            mDecoderReachedEof.load(std::memory_order_acquire) &&
            mRingBuffer->getAvailableRead() == 0 &&
            mPlayedSampleCount.load(std::memory_order_relaxed) > 0) {
            mIsEof.store(true, std::memory_order_release);
        }

        uint64_t fadeTotal = std::max<uint64_t>(1, mCrossfadeDurationFrames.load(std::memory_order_acquire));
        int32_t safeCh = std::max(1, channelCount);
        int32_t maxChunkFrames = std::max(1, static_cast<int32_t>(mNextMixBuffer.size()) / safeCh);
        int32_t framesProcessed = 0;

        while (framesProcessed < numFrames) {
            int32_t chunkFrames = std::min(numFrames - framesProcessed, maxChunkFrames);
            int32_t chunkSamples = chunkFrames * safeCh;
            int32_t samplesB = mNextRingBuffer->read(mNextMixBuffer.data(), chunkSamples);
            if (samplesB > 0) mNextPlayedSampleCount.fetch_add(static_cast<uint64_t>(samplesB), std::memory_order_relaxed);
            for (int32_t s = samplesB; s < chunkSamples; ++s) mNextMixBuffer[s] = 0.0f;
            if (samplesB < chunkSamples &&
                mNextDecoderReachedEof.load(std::memory_order_acquire) &&
                mNextRingBuffer->getAvailableRead() == 0 &&
                mNextPlayedSampleCount.load(std::memory_order_relaxed) > 0) {
                mNextEof.store(true, std::memory_order_release);
            }

            int32_t baseSample = framesProcessed * safeCh;
            for (int32_t f = 0; f < chunkFrames; ++f) {
                float t = (mCrossfadeElapsedFrames < fadeTotal)
                              ? (static_cast<float>(mCrossfadeElapsedFrames) / static_cast<float>(fadeTotal))
                              : 1.0f;
                float angle = t * 1.57079632679f;
                float gainA = std::cos(angle);
                float gainB = std::sin(angle);
                for (int32_t ch = 0; ch < safeCh; ++ch) {
                    int32_t idx = baseSample + f * safeCh + ch;
                    floatData[idx] = (floatData[idx] * rgA) * gainA + (mNextMixBuffer[f * safeCh + ch] * rgB) * gainB;
                }
                if (mCrossfadeElapsedFrames < fadeTotal) mCrossfadeElapsedFrames++;
            }
            framesProcessed += chunkFrames;
        }
        if (mNextRingBuffer->getAvailableWrite() >= 8192) mNextDecodeCv.notify_one();
        if (mEqualizer) mEqualizer->processBuffer(floatData, totalSamples);
        if (mCrossfadeElapsedFrames >= fadeTotal) {
            mCrossfadeActive.store(false, std::memory_order_release);
            mCrossfadeCompleted.store(true, std::memory_order_release);
        }
        postRgGain = 1.0f;
    } else {
        int32_t samplesRead = mRingBuffer->read(floatData, totalSamples);
        if (samplesRead > 0 && mRingBuffer->getAvailableWrite() >= 8192) mDecodeCv.notify_one();
        if (samplesRead > 0) mPlayedSampleCount.fetch_add(static_cast<uint64_t>(samplesRead), std::memory_order_relaxed);
        if (samplesRead < totalSamples) {
            for (int32_t i = samplesRead; i < totalSamples; ++i) floatData[i] = 0.0f;
            if (mDecoderReachedEof.load(std::memory_order_acquire) &&
                mRingBuffer->getAvailableRead() == 0 &&
                mPlayedSampleCount.load(std::memory_order_relaxed) > 0) {
                mIsEof.store(true, std::memory_order_release);
            }
        }
        if (mEqualizer && samplesRead > 0) mEqualizer->processBuffer(floatData, samplesRead);
        postRgGain = rgA;
    }

    float targetPauseFade = mPauseFadeTarget.load(std::memory_order_relaxed);
    float rampCoeff = mVolumeRampCoeff.load(std::memory_order_relaxed);
    float targetUserVol = mUserVolume.load(std::memory_order_relaxed);
    bool useSysVol = mActualExclusive && !mSuspendedPwSink.empty();
    float targetSysVol = useSysVol ? mSystemSinkVolume.load(std::memory_order_relaxed) : 1.0f;

    uint32_t outSr = mOutputSampleRate.load(std::memory_order_relaxed);
    float srF = (outSr > 0) ? static_cast<float>(outSr) : 48000.0f;
    float limAttack = 1.0f - std::exp(-1.0f / (0.0003f * srF));
    float limRelease = 1.0f - std::exp(-1.0f / (0.120f * srF));
    constexpr float kEnvThresh = 0.96f;
    constexpr float kHardCeil = 0.996f;

    int32_t safeCh = std::max<int32_t>(1, channelCount);
    for (int32_t f = 0; f < numFrames; ++f) {
        int32_t base = f * safeCh;
        float framePeak = 0.0f;
        for (int32_t ch = 0; ch < safeCh; ++ch) {
            mCurrentPauseFade += (targetPauseFade - mCurrentPauseFade) * rampCoeff;
            mCurrentUserVolume += (targetUserVol - mCurrentUserVolume) * 0.002f;
            if (useSysVol) {
                mCurrentSystemVolume += (targetSysVol - mCurrentSystemVolume) * 0.002f;
            } else {
                mCurrentSystemVolume = 1.0f;
            }
            float s = floatData[base + ch] * mCurrentPauseFade * mCurrentUserVolume * mCurrentSystemVolume * postRgGain;
            if (!std::isfinite(s)) s = 0.0f;
            floatData[base + ch] = s;
            float a = std::fabs(s);
            if (a > framePeak) framePeak = a;
        }

        float desiredLim = (framePeak > kEnvThresh) ? (kEnvThresh / framePeak) : 1.0f;
        if (desiredLim < mLimiterGain) {
            mLimiterGain += (desiredLim - mLimiterGain) * limAttack;
            if (framePeak * mLimiterGain > kHardCeil && framePeak > 0.0f) {
                mLimiterGain = kHardCeil / framePeak;
            }
        } else {
            mLimiterGain += (desiredLim - mLimiterGain) * limRelease;
        }

        if (mLimiterGain < 0.9999f) {
            for (int32_t ch = 0; ch < safeCh; ++ch) {
                floatData[base + ch] = std::clamp(floatData[base + ch] * mLimiterGain, -0.9999f, 0.9999f);
            }
        }
    }

    updateSpectrum(floatData, numFrames, channelCount, mOutputSampleRate.load(std::memory_order_relaxed));

    // Convert float32 [-1..1] to negotiated direct hardware DAC PCM format when not float32
    if (outFormat == ma_format_s32) {
        auto* out = static_cast<int32_t*>(pOutput);
        for (int32_t i = 0; i < totalSamples; ++i) {
            double v = std::clamp(static_cast<double>(floatData[i]), -1.0, 1.0) * 2147483647.0;
            out[i] = static_cast<int32_t>(std::llround(v));
        }
    } else if (outFormat == ma_format_s24) {
        auto* out = static_cast<uint8_t*>(pOutput);
        for (int32_t i = 0; i < totalSamples; ++i) {
            double v = std::clamp(static_cast<double>(floatData[i]), -1.0, 1.0) * 8388607.0;
            int32_t iv = static_cast<int32_t>(std::llround(v));
            out[i * 3]     = static_cast<uint8_t>(iv & 0xFF);
            out[i * 3 + 1] = static_cast<uint8_t>((iv >> 8) & 0xFF);
            out[i * 3 + 2] = static_cast<uint8_t>((iv >> 16) & 0xFF);
        }
    } else if (outFormat == ma_format_s16) {
        auto* out = static_cast<int16_t*>(pOutput);
        for (int32_t i = 0; i < totalSamples; ++i) {
            double v = std::clamp(static_cast<double>(floatData[i]), -1.0, 1.0) * 32767.0;
            out[i] = static_cast<int16_t>(std::llround(v));
        }
    }
}

EngineStatusSnapshot DesktopAudioEngine::getStatus() const {
    EngineStatusSnapshot s;
    s.isPlaying = mIsPlaying.load(std::memory_order_acquire);
    s.isEof = mIsEof.load(std::memory_order_acquire);
    s.nextEof = mNextEof.load(std::memory_order_acquire);
    s.crossfadeActive = mCrossfadeActive.load(std::memory_order_acquire);
    s.crossfadeCompleted = mCrossfadeCompleted.load(std::memory_order_acquire);
    s.exclusiveRequested = mExclusiveMode;
    s.exclusiveActual = mActualExclusive;
    s.sampleRate = mOutputSampleRate.load(std::memory_order_acquire);
    uint32_t ch = mDecoder ? mDecoder->getChannels() : 2u;
    if (ch == 0) ch = 2;
    s.channels = ch;
    s.bitDepthOut = (mActualFormat == ma_format_s16) ? 16u : (mActualFormat == ma_format_s24 ? 24u : 32u);
    {
        std::lock_guard<std::mutex> lock(mLock);
        s.activeDeviceName = mActiveDeviceName;
        s.lastError = mLastError;
        if (mActualExclusive) {
#if defined(_WIN32)
            s.backendName = "WASAPI Exclusive";
#else
            s.backendName = "ALSA Direct Exclusive";
#endif
        } else if (mContextReady) {
            s.backendName = ma_get_backend_name(mContext.backend);
        } else {
            s.backendName = "Shared";
        }
    }
    s.currentFrame = static_cast<int64_t>(mPlayedSampleCount.load(std::memory_order_acquire) / ch);
    s.totalFrames = mDecoder ? static_cast<int64_t>(mDecoder->getTotalFrames()) : 0;
    for (int i = 0; i < 16; ++i) {
        s.spectrum[i] = mSpectrum[i].load(std::memory_order_relaxed);
    }
    return s;
}
