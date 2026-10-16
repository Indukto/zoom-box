# Preserve attributes needed by Compose and Kotlin at runtime.
# Removing any of these causes silent runtime failures in reflection-based libraries.
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Compose, CameraX and the other Jetpack libraries ship their own consumer
# ProGuard rules via AAR metadata. No broad -keep for androidx.* here: a single
# `-keep class androidx.compose.**` / `androidx.camera.**` pins megabytes of
# reachable DEX and drives Play's DEX-Codeoptimierung (Shrinking/Optimierung)
# under the 25 % threshold. R8 strips, optimizes and obfuscates everything
# not otherwise kept.

# Keep Kotlin coroutines internals
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Keep app data classes — Robolectric reflection + DataStore serialisation targets.
# allowobfuscation so R8 may still rename/obfuscate while keeping the members
# reachable for org.json / DataStore reflection. A plain -keep pins the names
# and lowers the Play obfuscation factor for no runtime gain.
-keep,allowobfuscation class eu.indukto.zoombox.zoom.LensProfile { *; }
-keep,allowobfuscation class eu.indukto.zoombox.zoom.LensRole { *; }
-keep,allowobfuscation class eu.indukto.zoombox.zoom.CaptureExtension { *; }
-keep,allowobfuscation class eu.indukto.zoombox.zoom.BindResult { *; }
-keep,allowobfuscation class eu.indukto.zoombox.zoom.AspectRatio { *; }
-keep,allowobfuscation class eu.indukto.zoombox.ExifData { *; }
-keep,allowobfuscation class eu.indukto.zoombox.FilmPreset { *; }
-keep,allowobfuscation class eu.indukto.zoombox.color.CubeLut { *; }
-keep,allowobfuscation class eu.indukto.zoombox.zoom.LensCatalog$CatalogResult { *; }

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

