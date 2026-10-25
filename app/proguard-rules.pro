# ProGuard / R8 rules for FlacPlayer
-keep class com.antigravity.audioplayer.audio.NativeAudioEngine { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn kotlinx.serialization.**
