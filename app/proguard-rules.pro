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

# Coroutines on classpath.
-keepnames class kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @androidx.hilt.work.HiltWorker class * { *; }

-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.TypeConverter class * { *; }
-keep @androidx.room.TypeConverters class * { *; }

-keep @androidx.work.ListenableWorker class * { *; }
-keep @androidx.work.CoroutineWorker class * { *; }
-keep @androidx.work.Worker class * { *; }

-keep class dev.gamblock.protection.dns.** { *; }
-keep class dev.gamblock.core.release.** { *; }
-keep class dev.gamblock.core.integrity.** { *; }
-keep class dev.gamblock.core.model.** { *; }
-keep class dev.gamblock.data.preferences.** { *; }
-keep class dev.gamblock.**$$serializer { *; }
