# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html


# --- Code Shrinking & Obfuscation Settings ---

# Maintain line numbers and source file names for readable stack traces in production logs
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,EnclosingMethod
-renamesourcefileattribute SourceFile


# --- Dependency Resolution Rules ---

# The following rules suppress warnings regarding optional dependencies or
# classes from the Java Desktop environment that are not present on Android.
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn org.w3c.dom.bootstrap.DOMImplementationRegistry


# --- Application Models & Rendering ---

# Prevent R8 from stripping or renaming data classes and rendering components
-keep class nl.rogro82.pipup.GitHubRelease { *; }
-keep class nl.rogro82.pipup.GitHubAsset { *; }
-keep class nl.rogro82.pipup.PopupProps { *; }
-keep class nl.rogro82.pipup.PopupProps$** { *; }
-keep class nl.rogro82.pipup.AppSettings { *; }
-keep class nl.rogro82.pipup.AppSettings$** { *; }

# Keep rendering and animation logic
-keep class nl.rogro82.pipup.ui.** { *; }

# Preserve WebView JavaScript interfaces and annotations
-keepattributes *Annotation*,EnclosingMethod,Signature,JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}


# --- Glide (Image Processing) ---

# Preserve Glide's annotation processor output and integration modules
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep public class * extends com.bumptech.glide.module.LibraryGlideModule
-keep class com.bumptech.glide.GeneratedAppGlideModule { *; }
-keep @com.bumptech.glide.annotation.GlideModule class * { *; }

# Specifically keep our application's Glide modules
-keep class nl.rogro82.pipup.PipUpGlideAppModule { *; }


# --- OkHttp / Conscrypt ---

# Suppress warnings for missing Conscrypt classes. OkHttp uses them if present
# but provides a fallback if they are missing.
-dontwarn org.conscrypt.**


# --- NanoHTTPD (Web Server) ---
# Keep the embedded server logic intact
-keep class fi.iki.elonen.NanoHTTPD* { *; }
-keepclassmembers class fi.iki.elonen.NanoHTTPD* { *; }

# --- WebView Singletons ---
