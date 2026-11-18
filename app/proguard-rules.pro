# ProGuard / R8 rules for Zuna
-keep class com.zuna.player.audio.NativeAudioEngine { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn kotlinx.serialization.**
