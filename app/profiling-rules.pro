# Speed up R8 for profiling by disabling time-consuming optimizations
-dontoptimize

# Keep ALL LeakCanary and Shark classes for the Profiler and Studio integration
#noinspection ExpensiveKeepRuleInspection
-keep class leakcanary.** { *; }
-keep interface leakcanary.** { *; }

# Specifically keep the Studio integration bridge
-keep class com.android.tools.studio.leakcanary.** { *; }
-keep interface com.android.tools.studio.leakcanary.** { *; }

# Ensure metadata/service loaders for LeakCanary are preserved
