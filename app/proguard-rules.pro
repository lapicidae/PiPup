# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html


# --- General Android / Kotlin Rules ---

# Preserve line numbers and source file names for meaningful stack traces in logs
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile


# --- Jackson (JSON Parsing) Rules ---

# Preserve Jackson's core classes and annotations
-keep class com.fasterxml.jackson.** { *; }
-keepnames class com.fasterxml.jackson.** { *; }
-keep @com.fasterxml.jackson.annotation.JsonIgnoreProperties class * { *; }
-keepattributes *Annotation*,EnclosingMethod,Signature


# --- Project Specific Data Models ---

# IMPORTANT: Keep your data classes (like PopupProps) because Jackson uses reflection 
# to map JSON keys to these class properties.
-keep class nl.rogro82.pipup.PopupProps { *; }
-keep class nl.rogro82.pipup.PopupProps$** { *; }

# If you have other models in this package, keep them all:
-keep class nl.rogro82.pipup.models.** { *; }


# --- Glide (Image Loading) Rules ---

# Preserve Glide's generated API and integration modules
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep public class * extends com.bumptech.glide.module.LibraryGlideModule
-keep class com.bumptech.glide.GeneratedAppGlideModuleImpl { *; }

# Preserve Glide's annotations
-keep @com.bumptech.glide.annotation.GlideModule class * { *; }


# --- NanoHTTPD Rules ---

# Ensure the web server components are not stripped or renamed
-keep class fi.iki.elonen.NanoHTTPD* { *; }
-keepclassmembers class fi.iki.elonen.NanoHTTPD* { *; }
