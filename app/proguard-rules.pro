# Shield release shrinking rules.
# Keep entry points and their annotations for introspection by Hilt / WorkManager bow.
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,*Annotation*

# kotlinx.serialization: keep generated serializers + forbidden reflective access surface.
-keepclassmembers class kotlinx.serialization.json.** {
    static <fields>;
    <methods>;
}
-keep,includedescriptorclasses class dev.gamblock.core.model.** { *; }
-keep,includedescriptorclasses class dev.gamblock.data.preferences.** { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Compose / Navigation reflection.
-dontwarn androidx.compose.**
-dontwarn org.jetbrains.annotations.**

# Hilt / Dagger (processor-generated code is kept automatically; these protect edge cases.).
-dontwarn dagger.hilt.**
-keepclasseswithmembers class * {
    @dagger.hilt.android.qualifiers.* <methods>;
}

# Room generated code (KSP-generated implementations carry the same package names).
-dontwarn androidx.room.**
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }

# Coroutines on classpath.
-dontwarn kotlinx.coroutines.**