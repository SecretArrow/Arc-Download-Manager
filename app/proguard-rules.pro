# Arc Download Manager ProGuard rules

# Keep crash/debug info for our own code
-keepattributes SourceFile,LineNumberTable

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**

# Room entities keep stable names for DB debugging
-keep class com.arc.downloadmanager.data.db.** { *; }

# WebView JS bridge attribute (no bridge is exposed, kept for clarity)
-keepattributes JavascriptInterface
