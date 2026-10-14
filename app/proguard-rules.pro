# Preserve attributes needed by Compose and Kotlin at runtime.
# Removing any of these causes silent runtime failures in reflection-based libraries.
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Keep Compose — narrow: allow obfuscation but keep classes
-keep,allowobfuscation class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Keep CameraX — narrow: allow obfuscation
-keep,allowobfuscation class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# Keep Kotlin coroutines internals
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Keep app data classes — Robolectric reflection + DataStore serialisation targets.
# Removing any of these can break test reflection or runtime deserialisation.
-keep class eu.indukto.zoombox.zoom.LensProfile { *; }
-keep class eu.indukto.zoombox.zoom.LensRole { *; }
-keep class eu.indukto.zoombox.zoom.CaptureExtension { *; }
-keep class eu.indukto.zoombox.zoom.BindResult { *; }
-keep class eu.indukto.zoombox.zoom.AspectRatio { *; }
-keep class eu.indukto.zoombox.ExifData { *; }
-keep class eu.indukto.zoombox.FilmPreset { *; }
-keep class eu.indukto.zoombox.color.CubeLut { *; }
-keep class eu.indukto.zoombox.zoom.LensCatalog$CatalogResult { *; }

# Keep Compose runtime (reflection-heavy)
-keep class androidx.compose.runtime.** { *; }

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

