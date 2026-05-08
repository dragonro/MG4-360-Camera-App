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
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# -----------------------------
# Drivehub Kamera - decompile zorlaştırma
# -----------------------------

# JNI: CameraProbe native fonksiyonları (JNI name-mangling) nedeniyle
# sınıf ve native method adları korunmalı.
-keep class com.drivehub.kamera.CameraProbe { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# AndroidX core bazen yansıma ile yüklenir; R8 silmesin.
-keep class androidx.core.app.** { *; }
-dontwarn androidx.core.app.**

# @Keep anotasyonu olan üyeler korunur.
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# Kaynak dosya adlarını gizle.
-renamesourcefileattribute SourceFile

# Paket yapısını daha az okunur hale getir.
-repackageclasses ''