#define NAPI_VERSION 8
#include <node_api.h>
#include "DesktopAudioEngine.h"
#include "LinuxMprisService.h"

#include <memory>
#include <string>

static DesktopAudioEngine* gEngine = nullptr;
static LinuxMprisService* gMpris = nullptr;
static napi_threadsafe_function gMprisTsfn = nullptr;

static void stopMprisInternal() {
    if (gMpris) {
        gMpris->stop();
        delete gMpris;
        gMpris = nullptr;
    }
    if (gMprisTsfn) {
        napi_release_threadsafe_function(gMprisTsfn, napi_tsfn_abort);
        gMprisTsfn = nullptr;
    }
}

static void cleanupEngine(void* /*arg*/) {
    stopMprisInternal();
    delete gEngine;
    gEngine = nullptr;
}

static bool getArgString(napi_env env, napi_value val, std::string& out) {
    napi_valuetype t;
    if (napi_typeof(env, val, &t) != napi_ok || t != napi_string) return false;
    size_t len = 0;
    napi_get_value_string_utf8(env, val, nullptr, 0, &len);
    out.resize(len);
    if (len > 0) {
        napi_get_value_string_utf8(env, val, out.data(), len + 1, &len);
    }
    return true;
}

static napi_value makeBool(napi_env env, bool b) {
    napi_value v;
    napi_get_boolean(env, b, &v);
    return v;
}

static napi_value fnGetDevices(napi_env env, napi_callback_info /*info*/) {
    napi_value arr;
    napi_create_array(env, &arr);
    if (!gEngine) return arr;

    auto list = gEngine->enumerateDevices();
    for (size_t i = 0; i < list.size(); ++i) {
        napi_value obj, vId, vName, vDef;
        napi_create_object(env, &obj);
        napi_create_string_utf8(env, list[i].id.c_str(), NAPI_AUTO_LENGTH, &vId);
        napi_create_string_utf8(env, list[i].name.c_str(), NAPI_AUTO_LENGTH, &vName);
        napi_get_boolean(env, list[i].isDefault, &vDef);
        napi_set_named_property(env, obj, "id", vId);
        napi_set_named_property(env, obj, "name", vName);
        napi_set_named_property(env, obj, "isDefault", vDef);
        napi_set_element(env, arr, static_cast<uint32_t>(i), obj);
    }
    return arr;
}

static napi_value fnLoadFile(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    std::string path;
    if (!gEngine || argc < 1 || !getArgString(env, args[0], path)) return makeBool(env, false);
    return makeBool(env, gEngine->loadFile(path));
}

static napi_value fnPreloadNextFile(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    std::string path;
    if (!gEngine || argc < 1 || !getArgString(env, args[0], path)) return makeBool(env, false);
    return makeBool(env, gEngine->preloadNextFile(path));
}

static napi_value fnStart(napi_env env, napi_callback_info /*info*/) {
    return makeBool(env, gEngine && gEngine->start());
}

static napi_value fnPause(napi_env env, napi_callback_info /*info*/) {
    if (gEngine) gEngine->pause();
    return makeBool(env, true);
}

static napi_value fnStop(napi_env env, napi_callback_info /*info*/) {
    if (gEngine) gEngine->stop();
    return makeBool(env, true);
}

static napi_value fnSeekToSeconds(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    double sec = 0.0;
    if (!gEngine || argc < 1 || napi_get_value_double(env, args[0], &sec) != napi_ok) {
        return makeBool(env, false);
    }
    return makeBool(env, gEngine->seekToSeconds(sec));
}

static napi_value fnSetOutputDevice(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    std::string id = "default";
    if (argc >= 1) getArgString(env, args[0], id);
    return makeBool(env, gEngine && gEngine->setOutputDeviceId(id));
}

static napi_value fnSetExclusiveMode(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    bool b = false;
    if (!gEngine || argc < 1 || napi_get_value_bool(env, args[0], &b) != napi_ok) return makeBool(env, false);
    return makeBool(env, gEngine->setExclusiveMode(b));
}

static napi_value fnSetUserVolume(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    double v = 0.85;
    if (gEngine && argc >= 1 && napi_get_value_double(env, args[0], &v) == napi_ok) {
        gEngine->setUserVolume(static_cast<float>(v));
    }
    return makeBool(env, true);
}

static napi_value fnBeginPauseFade(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    int32_t ms = 110;
    if (gEngine && argc >= 1) napi_get_value_int32(env, args[0], &ms);
    if (gEngine) gEngine->beginPauseFade(ms);
    return makeBool(env, true);
}

static napi_value fnCancelPauseFade(napi_env env, napi_callback_info /*info*/) {
    if (gEngine) gEngine->cancelPauseFade();
    return makeBool(env, true);
}

static napi_value fnSetReplayGain(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    bool b = false;
    if (gEngine && argc >= 1 && napi_get_value_bool(env, args[0], &b) == napi_ok) {
        gEngine->setReplayGainEnabled(b);
    }
    return makeBool(env, true);
}

static napi_value fnSetGapless(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    bool b = true;
    if (gEngine && argc >= 1 && napi_get_value_bool(env, args[0], &b) == napi_ok) {
        gEngine->setGaplessEnabled(b);
    }
    return makeBool(env, true);
}

static napi_value fnStartCrossfade(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    int32_t ms = 3000;
    if (argc >= 1) napi_get_value_int32(env, args[0], &ms);
    return makeBool(env, gEngine && gEngine->startCrossfade(ms));
}

static napi_value fnCancelCrossfade(napi_env env, napi_callback_info /*info*/) {
    if (gEngine) gEngine->cancelCrossfade();
    return makeBool(env, true);
}

static napi_value fnPromotePreloaded(napi_env env, napi_callback_info /*info*/) {
    return makeBool(env, gEngine && gEngine->promotePreloadedTrack());
}

static napi_value fnUpdateEq(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (!gEngine || argc < 1) return makeBool(env, false);

    auto* eq = gEngine->getEqualizer();
    if (!eq) return makeBool(env, false);

    napi_value cfg = args[0];
    napi_value prop;
    bool enabled = false;
    if (napi_get_named_property(env, cfg, "enabled", &prop) == napi_ok &&
        napi_get_value_bool(env, prop, &enabled) == napi_ok) {
        eq->setEnabled(enabled);
    }
    double preamp = 0.0;
    if (napi_get_named_property(env, cfg, "preampDb", &prop) == napi_ok &&
        napi_get_value_double(env, prop, &preamp) == napi_ok) {
        eq->setPreampDb(static_cast<float>(preamp));
    }
    double bass = 0.0;
    if (napi_get_named_property(env, cfg, "bassBoost", &prop) == napi_ok &&
        napi_get_value_double(env, prop, &bass) == napi_ok) {
        eq->setBassBoost(static_cast<float>(bass));
    }
    if (napi_get_named_property(env, cfg, "bandGains", &prop) == napi_ok) {
        bool isArr = false;
        napi_is_array(env, prop, &isArr);
        if (isArr) {
            uint32_t len = 0;
            napi_get_array_length(env, prop, &len);
            for (uint32_t i = 0; i < len && i < DesktopEqualizer::NUM_BANDS; ++i) {
                napi_value el;
                double g = 0.0;
                if (napi_get_element(env, prop, i, &el) == napi_ok &&
                    napi_get_value_double(env, el, &g) == napi_ok) {
                    eq->setBandGain(static_cast<int>(i), static_cast<float>(g));
                }
            }
        }
    }
    return makeBool(env, true);
}

static napi_value fnGetStatus(napi_env env, napi_callback_info /*info*/) {
    napi_value obj;
    napi_create_object(env, &obj);
    if (!gEngine) return obj;

    auto s = gEngine->getStatus();
    double sr = s.sampleRate > 0 ? static_cast<double>(s.sampleRate) : 44100.0;
    double posSec = static_cast<double>(s.currentFrame) / sr;
    double durSec = static_cast<double>(s.totalFrames) / sr;

    napi_value v;
    napi_get_boolean(env, s.isPlaying, &v); napi_set_named_property(env, obj, "isPlaying", v);
    napi_get_boolean(env, s.isEof, &v); napi_set_named_property(env, obj, "isEof", v);
    napi_get_boolean(env, s.nextEof, &v); napi_set_named_property(env, obj, "nextEof", v);
    napi_get_boolean(env, s.crossfadeActive, &v); napi_set_named_property(env, obj, "crossfadeActive", v);
    napi_get_boolean(env, s.crossfadeCompleted, &v); napi_set_named_property(env, obj, "crossfadeCompleted", v);
    napi_get_boolean(env, s.exclusiveRequested, &v); napi_set_named_property(env, obj, "exclusiveRequested", v);
    napi_get_boolean(env, s.exclusiveActual, &v); napi_set_named_property(env, obj, "exclusiveActual", v);
    napi_create_double(env, posSec, &v); napi_set_named_property(env, obj, "positionSec", v);
    napi_create_double(env, durSec, &v); napi_set_named_property(env, obj, "durationSec", v);
    napi_create_uint32(env, s.sampleRate, &v); napi_set_named_property(env, obj, "sampleRate", v);
    napi_create_uint32(env, s.channels, &v); napi_set_named_property(env, obj, "channels", v);
    napi_create_uint32(env, s.bitDepthOut, &v); napi_set_named_property(env, obj, "bitDepthOut", v);
    napi_create_string_utf8(env, s.backendName.c_str(), NAPI_AUTO_LENGTH, &v); napi_set_named_property(env, obj, "backendName", v);
    napi_create_string_utf8(env, s.activeDeviceName.c_str(), NAPI_AUTO_LENGTH, &v); napi_set_named_property(env, obj, "activeDeviceName", v);
    napi_create_string_utf8(env, s.lastError.c_str(), NAPI_AUTO_LENGTH, &v); napi_set_named_property(env, obj, "lastError", v);

    napi_value specArr;
    napi_create_array_with_length(env, 16, &specArr);
    for (uint32_t i = 0; i < 16; ++i) {
        napi_create_double(env, static_cast<double>(s.spectrum[i]), &v);
        napi_set_element(env, specArr, i, v);
    }
    napi_set_named_property(env, obj, "spectrum", specArr);
    return obj;
}

static void callMprisActionJs(napi_env env, napi_value jsCb, void* /*ctx*/, void* data) {
    auto* ev = static_cast<MprisActionEvent*>(data);
    if (!ev) return;

    if (env && jsCb) {
        napi_value payload, v;
        napi_create_object(env, &payload);
        napi_create_string_utf8(env, ev->type.c_str(), NAPI_AUTO_LENGTH, &v);
        napi_set_named_property(env, payload, "type", v);
        napi_create_double(env, ev->numberValue, &v);
        napi_set_named_property(env, payload, "numberValue", v);
        napi_create_string_utf8(env, ev->stringValue.c_str(), NAPI_AUTO_LENGTH, &v);
        napi_set_named_property(env, payload, "stringValue", v);
        napi_get_boolean(env, ev->boolValue, &v);
        napi_set_named_property(env, payload, "boolValue", v);

        napi_value global;
        napi_get_global(env, &global);
        napi_call_function(env, global, jsCb, 1, &payload, nullptr);
    }

    delete ev;
}

static napi_value fnMprisInit(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 1) return makeBool(env, false);

    napi_valuetype fnType = napi_undefined;
    if (napi_typeof(env, args[0], &fnType) != napi_ok || fnType != napi_function) {
        return makeBool(env, false);
    }

    stopMprisInternal();

    napi_value resourceName;
    napi_create_string_utf8(env, "ZunaMprisAction", NAPI_AUTO_LENGTH, &resourceName);

    napi_threadsafe_function tsfn = nullptr;
    if (napi_create_threadsafe_function(
            env,
            args[0],
            nullptr,
            resourceName,
            0,
            1,
            nullptr,
            nullptr,
            nullptr,
            callMprisActionJs,
            &tsfn) != napi_ok) {
        return makeBool(env, false);
    }
    napi_unref_threadsafe_function(env, tsfn);

    gMprisTsfn = tsfn;
    gMpris = new LinuxMprisService();
    bool ok = gMpris->start([](const MprisActionEvent& ev) {
        if (!gMprisTsfn) return;
        auto* copy = new MprisActionEvent(ev);
        if (napi_call_threadsafe_function(gMprisTsfn, copy, napi_tsfn_nonblocking) != napi_ok) {
            delete copy;
        }
    });
    if (!ok) {
        stopMprisInternal();
        return makeBool(env, false);
    }
    return makeBool(env, true);
}

static napi_value fnMprisUpdate(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (!gMpris || argc < 1) return makeBool(env, false);

    napi_value obj = args[0];
    napi_value prop;
    MprisTrackState st;

    if (napi_get_named_property(env, obj, "hasTrack", &prop) == napi_ok) {
        napi_get_value_bool(env, prop, &st.hasTrack);
    }
    if (napi_get_named_property(env, obj, "trackId", &prop) == napi_ok) getArgString(env, prop, st.trackId);
    if (napi_get_named_property(env, obj, "title", &prop) == napi_ok) getArgString(env, prop, st.title);
    if (napi_get_named_property(env, obj, "artist", &prop) == napi_ok) getArgString(env, prop, st.artist);
    if (napi_get_named_property(env, obj, "album", &prop) == napi_ok) getArgString(env, prop, st.album);
    if (napi_get_named_property(env, obj, "artUrl", &prop) == napi_ok) getArgString(env, prop, st.artUrl);
    if (napi_get_named_property(env, obj, "trackUrl", &prop) == napi_ok) getArgString(env, prop, st.trackUrl);
    if (napi_get_named_property(env, obj, "playbackStatus", &prop) == napi_ok) getArgString(env, prop, st.playbackStatus);
    if (napi_get_named_property(env, obj, "loopStatus", &prop) == napi_ok) getArgString(env, prop, st.loopStatus);
    if (napi_get_named_property(env, obj, "shuffle", &prop) == napi_ok) napi_get_value_bool(env, prop, &st.shuffle);
    if (napi_get_named_property(env, obj, "seeked", &prop) == napi_ok) napi_get_value_bool(env, prop, &st.seeked);

    double dVal = 0.0;
    if (napi_get_named_property(env, obj, "durationSec", &prop) == napi_ok &&
        napi_get_value_double(env, prop, &dVal) == napi_ok) {
        st.durationUs = static_cast<int64_t>(dVal * 1000000.0);
    }
    if (napi_get_named_property(env, obj, "positionSec", &prop) == napi_ok &&
        napi_get_value_double(env, prop, &dVal) == napi_ok) {
        st.positionUs = static_cast<int64_t>(dVal * 1000000.0);
    }
    if (napi_get_named_property(env, obj, "volume", &prop) == napi_ok &&
        napi_get_value_double(env, prop, &dVal) == napi_ok) {
        st.volume = dVal;
    }

    if (st.playbackStatus.empty()) st.playbackStatus = "Stopped";
    if (st.loopStatus.empty()) st.loopStatus = "None";

    gMpris->updateState(st);
    return makeBool(env, true);
}

static napi_value fnMprisShutdown(napi_env env, napi_callback_info /*info*/) {
    stopMprisInternal();
    return makeBool(env, true);
}

static napi_value initAddon(napi_env env, napi_value exports) {
    if (!gEngine) {
        gEngine = new DesktopAudioEngine();
        napi_add_env_cleanup_hook(env, cleanupEngine, nullptr);
    }

    napi_property_descriptor props[] = {
        { "getDevices", nullptr, fnGetDevices, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "loadFile", nullptr, fnLoadFile, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "preloadNextFile", nullptr, fnPreloadNextFile, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "start", nullptr, fnStart, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "pause", nullptr, fnPause, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "stop", nullptr, fnStop, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "seekToSeconds", nullptr, fnSeekToSeconds, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "setOutputDevice", nullptr, fnSetOutputDevice, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "setExclusiveMode", nullptr, fnSetExclusiveMode, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "setUserVolume", nullptr, fnSetUserVolume, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "beginPauseFade", nullptr, fnBeginPauseFade, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "cancelPauseFade", nullptr, fnCancelPauseFade, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "setReplayGainEnabled", nullptr, fnSetReplayGain, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "setGaplessEnabled", nullptr, fnSetGapless, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "startCrossfade", nullptr, fnStartCrossfade, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "cancelCrossfade", nullptr, fnCancelCrossfade, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "promotePreloadedTrack", nullptr, fnPromotePreloaded, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "updateEq", nullptr, fnUpdateEq, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "getStatus", nullptr, fnGetStatus, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "mprisInit", nullptr, fnMprisInit, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "mprisUpdate", nullptr, fnMprisUpdate, nullptr, nullptr, nullptr, napi_default, nullptr },
        { "mprisShutdown", nullptr, fnMprisShutdown, nullptr, nullptr, nullptr, napi_default, nullptr }
    };

    napi_define_properties(env, exports, sizeof(props) / sizeof(props[0]), props);
    return exports;
}

NAPI_MODULE(NODE_GYP_MODULE_NAME, initAddon)
