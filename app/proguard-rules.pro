# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# BouncyCastle (used by libadb / spake2 and for generating the self-signed
# certificate which identifies this app as an ADB 'host').
# ---------------------------------------------------------------------------
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.** { *; }

# libadb / spake2 are looked up reflectively in places; keep them intact.
-dontwarn io.github.muntashirakon.**
-keep class io.github.muntashirakon.** { *; }

# Conscrypt is instantiated by name ("org.conscrypt.OpenSSLProvider")
-keep class org.conscrypt.** { *; }

# These platform classes are only referenced by Conscrypt's legacy adapters.
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl