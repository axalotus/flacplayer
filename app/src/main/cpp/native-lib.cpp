#include <jni.h>
#include <memory>
#include "AudioEngine.h"

// Global pointer to our audio engine instance
static std::unique_ptr<AudioEngine> gAudioEngine;

extern "C"
JNIEXPORT void JNICALL
Java_com_antigravity_audioplayer_audio_NativeAudioEngine_initEngine(JNIEnv *env, jobject thiz) {
    if (!gAudioEngine) {
        gAudioEngine = std::make_unique<AudioEngine>();
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_antigravity_audioplayer_audio_NativeAudioEngine_openFile(JNIEnv *env, jobject thiz, jstring file_path) {
    if (!gAudioEngine) return JNI_FALSE;
    const char *path = env->GetStringUTFChars(file_path, nullptr);
    bool success = gAudioEngine->loadFile(path);
    env->ReleaseStringUTFChars(file_path, path);
    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_antigravity_audioplayer_audio_NativeAudioEngine_openFd(JNIEnv *env, jobject thiz, jint fd) {
    if (!gAudioEngine) return JNI_FALSE;
    bool success = gAudioEngine->loadFd(fd);
    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_antigravity_audioplayer_audio_NativeAudioEngine_seekTo(JNIEnv *env, jobject thiz, jlong target_frame) {
    if (!gAudioEngine) return -1;
    return gAudioEngine->seekTo(target_frame);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_antigravity_audioplayer_audio_NativeAudioEngine_getPosition(JNIEnv *env, jobject thiz) {
    if (!gAudioEngine) return 0;
    return gAudioEngine->getCurrentFrame();
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_antigravity_audioplayer_audio_NativeAudioEngine_getDuration(JNIEnv *env, jobject thiz) {
    if (!gAudioEngine) return 0;
    return gAudioEngine->getTotalFrames();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_antigravity_audioplayer_audio_NativeAudioEngine_setEqEnabled(JNIEnv *env, jobject thiz, jboolean enabled) {
    if (gAudioEngine && gAudioEngine->getEqualizer()) {
        gAudioEngine->getEqualizer()->setEnabled(enabled == JNI_TRUE);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_antigravity_audioplayer_audio_NativeAudioEngine_setBandGain(JNIEnv *env, jobject thiz, jint band_index, jfloat gain_db) {
    if (gAudioEngine && gAudioEngine->getEqualizer()) {
        gAudioEngine->getEqualizer()->setBandGain(band_index, gain_db);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_antigravity_audioplayer_audio_NativeAudioEngine_setBassBoost(JNIEnv *env, jobject thiz, jfloat strength) {
    if (gAudioEngine && gAudioEngine->getEqualizer()) {
        gAudioEngine->getEqualizer()->setBassBoost(strength);
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_antigravity_audioplayer_audio_NativeAudioEngine_start(JNIEnv *env, jobject thiz) {
    if (gAudioEngine) {
        return gAudioEngine->start() ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_antigravity_audioplayer_audio_NativeAudioEngine_pause(JNIEnv *env, jobject thiz) {
    if (gAudioEngine) {
        gAudioEngine->pause();
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_antigravity_audioplayer_audio_NativeAudioEngine_stop(JNIEnv *env, jobject thiz) {
    if (gAudioEngine) {
        gAudioEngine->stop();
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_antigravity_audioplayer_audio_NativeAudioEngine_destroyEngine(JNIEnv *env, jobject thiz) {
    if (gAudioEngine) {
        gAudioEngine->stop();
        gAudioEngine.reset();
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_antigravity_audioplayer_audio_NativeAudioEngine_recreateStream(JNIEnv *env, jobject thiz, jint mode) {
    if (!gAudioEngine) return JNI_FALSE;
    return gAudioEngine->recreateStream((int)mode) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_antigravity_audioplayer_audio_NativeAudioEngine_setSafeBufferMode(JNIEnv *env, jobject thiz, jboolean enabled) {
    if (!gAudioEngine) return JNI_FALSE;
    return gAudioEngine->setSafeBufferMode(enabled == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_antigravity_audioplayer_audio_NativeAudioEngine_reopenStream(JNIEnv *env, jobject thiz, jboolean restart_if_playing) {
    if (!gAudioEngine) return JNI_FALSE;
    return gAudioEngine->reopenStream(restart_if_playing == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_antigravity_audioplayer_audio_NativeAudioEngine_setAudioDeviceId(JNIEnv *env, jobject thiz, jint device_id) {
    if (!gAudioEngine) return JNI_FALSE;
    return gAudioEngine->setAudioDeviceId((int)device_id) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_antigravity_audioplayer_audio_NativeAudioEngine_isEof(JNIEnv *env, jobject thiz) {
    if (!gAudioEngine) return JNI_FALSE;
    return gAudioEngine->isEof() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_antigravity_audioplayer_audio_NativeAudioEngine_setVolume(JNIEnv *env, jobject thiz, jfloat volume) {
    if (gAudioEngine) {
        gAudioEngine->setVolume((float)volume);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_antigravity_audioplayer_audio_NativeAudioEngine_setReplayGainEnabled(JNIEnv *env, jobject thiz, jboolean enabled) {
    if (gAudioEngine) {
        gAudioEngine->setReplayGainEnabled(enabled == JNI_TRUE);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_antigravity_audioplayer_audio_NativeAudioEngine_setGaplessEnabled(JNIEnv *env, jobject thiz, jboolean enabled) {
    if (gAudioEngine) {
        gAudioEngine->setGaplessEnabled(enabled == JNI_TRUE);
    }
}


