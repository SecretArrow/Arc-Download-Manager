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

# WebView JS bridge: MediaBridge is exposed to pages as window.ArcMediaBridge
-keepattributes JavascriptInterface

# FTP/FTPS (Apache Commons Net)
-dontwarn org.apache.commons.net.**
-keep class org.apache.commons.net.** { *; }

# SFTP (JSch) - crypto classes are loaded reflectively via Class.forName
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# BitTorrent (jlibtorrent) - SWIG JNI bindings resolve symbols by exact name
-keep class com.frostwire.jlibtorrent.** { *; }
-dontwarn com.frostwire.jlibtorrent.**
