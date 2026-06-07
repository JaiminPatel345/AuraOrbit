# libGDX ProGuard rules
-keep class com.badlogic.gdx.** { *; }
-dontwarn com.badlogic.gdx.**
-keep class dev.jaimin.auraorbit.** { *; }

# Keep native method signatures
-keepclasseswithmembers class * {
    native <methods>;
}

# Keep Preference classes for settings
-keep class androidx.preference.** { *; }
