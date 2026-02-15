# ═══ JustGuide ProGuard Rules ═══

# Manter line numbers para crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ═══ GSON (serialização JSON) ═══
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.milton.justguide.LocationLog { *; }
-keepclassmembers class com.milton.justguide.LocationLog { *; }

# ═══ Google Maps ═══
-keep class com.google.android.gms.maps.** { *; }
-keep class com.google.android.gms.location.** { *; }

# ═══ Google Places ═══
-keep class com.google.android.libraries.places.** { *; }

# ═══ CameraX ═══
-keep class androidx.camera.** { *; }

# ═══ ViewBinding ═══
-keep class com.milton.justguide.databinding.** { *; }

# ═══ Manter TODAS as classes do app ═══
-keep class com.milton.justguide.** { *; }