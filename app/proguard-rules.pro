# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html


# --- Code Shrinking & Obfuscation Settings ---

# Maintain line numbers and source file names for readable stack traces in production logs
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile


# --- Dependency Resolution Rules ---

# The following rules suppress warnings regarding optional dependencies or
# classes from the Java Desktop environment that are not present on Android.
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn org.w3c.dom.bootstrap.DOMImplementationRegistry
-dontwarn com.fasterxml.jackson.databind.ext.Java7SupportImpl


# --- Jackson (JSON Framework) ---
# Ensure Jackson can access annotations and handle reflection for JSON mapping
-keepattributes *Annotation*,EnclosingMethod,Signature
-dontwarn com.fasterxml.jackson.databind.**
-keep class com.fasterxml.jackson.annotation.** { *; }
-keep class com.fasterxml.jackson.databind.annotation.** { *; }
-keep class com.fasterxml.jackson.core.type.TypeReference { *; }

# --- Jackson Kotlin Module ---
-keep class com.fasterxml.jackson.module.kotlin.KotlinModule { *; }
-keep class com.fasterxml.jackson.module.kotlin.KotlinModule$Builder { *; }
-keep class com.fasterxml.jackson.module.kotlin.KotlinFeature { *; }


# --- Application Models ---

# Prevent R8 from stripping or renaming data classes used for JSON reflection
-keep class nl.rogro82.pipup.GitHubRelease { *; }
-keep class nl.rogro82.pipup.GitHubAsset { *; }
-keep class nl.rogro82.pipup.PopupProps { *; }
-keep class nl.rogro82.pipup.PopupProps$** { *; }
-keep class nl.rogro82.pipup.models.** { *; }
-keep class nl.rogro82.pipup.AppSettings { *; }
-keep class nl.rogro82.pipup.AppSettings$** { *; }

# CRITICAL: Keep all members (fields and methods) within these classes.
-keepclassmembers class nl.rogro82.pipup.PopupProps { *; }
-keepclassmembers class nl.rogro82.pipup.models.** { *; }
-keepclassmembers class nl.rogro82.pipup.AppSettings { *; }
-keepclassmembers class nl.rogro82.pipup.AppSettings$** { *; }

# Preserve specific Jackson annotations to ensure mapping works at runtime
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.JsonCreator *;
    @com.fasterxml.jackson.annotation.JsonProperty *;
    @com.fasterxml.jackson.annotation.JsonValue *;
}


# --- Glide (Image Processing) ---

# Preserve Glide's annotation processor output and integration modules
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep public class * extends com.bumptech.glide.module.LibraryGlideModule
-keep class com.bumptech.glide.GeneratedAppGlideModule { *; }
-keep @com.bumptech.glide.annotation.GlideModule class * { *; }

# Specifically keep our application's Glide modules
-keep class nl.rogro82.pipup.PipUpGlideAppModule { *; }
-keep class nl.rogro82.pipup.OkHttpLibraryGlideModule { *; }


# --- OkHttp / Conscrypt ---

# Suppress warnings for missing Conscrypt classes. OkHttp uses them if present
# but provides a fallback if they are missing.
-dontwarn org.conscrypt.**


# --- NanoHTTPD (Web Server) ---
# Keep the embedded server logic intact
-keep class fi.iki.elonen.NanoHTTPD* { *; }
-keepclassmembers class fi.iki.elonen.NanoHTTPD* { *; }

# --- WebView Pre-Warming ---
# Ensure the WebView engine isn't stripped during release optimization
-keepclassmembers class nl.rogro82.pipup.service.PipUpService {
    android.webkit.WebView warmWebView;
}
