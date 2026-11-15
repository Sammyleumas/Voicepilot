# kotlinx.serialization: keep generated serializers for core models (routines are stored as JSON).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep,includedescriptorclasses class com.voicepilot.core.**$$serializer { *; }
-keepclassmembers class com.voicepilot.core.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Android framework entry points referenced only from the manifest are kept by AAPT rules.
# Strip debug logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
