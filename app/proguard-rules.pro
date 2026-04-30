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
-keep class com.fasterxml.jackson.** { *; }
-keepnames class com.fasterxml.jackson.** { *; }
-keep @com.fasterxml.jackson.annotation.JsonIgnoreProperties class * { *; }
-keepattributes *Annotation*,EnclosingMethod,Signature


# --- Application Models ---
# Prevent R8 from stripping or renaming data classes used for JSON reflection
-keep class nl.rogro82.pipup.PopupProps { *; }
-keep class nl.rogro82.pipup.PopupProps$** { *; }
-keep class nl.rogro82.pipup.models.** { *; }


# --- Glide (Image Processing) ---
# Preserve Glide's annotation processor output and integration modules
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep public class * extends com.bumptech.glide.module.LibraryGlideModule
-keep class com.bumptech.glide.GeneratedAppGlideModuleImpl { *; }
-keep @com.bumptech.glide.annotation.GlideModule class * { *; }


# --- NanoHTTPD (Web Server) ---
# Keep the embedded server logic intact
-keep class fi.iki.elonen.NanoHTTPD* { *; }
-keepclassmembers class fi.iki.elonen.NanoHTTPD* { *; }
