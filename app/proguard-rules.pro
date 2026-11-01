# Keep audio effect callback classes
-keep class com.fxsoundlite.app.** { *; }
-dontwarn com.fxsoundlite.app.**

# Official YouTube player library (WebView JS bridge)
-keep class com.pierfrancescosoffritti.androidyoutubeplayer.** { *; }
-dontwarn com.pierfrancescosoffritti.androidyoutubeplayer.**

# NewPipeExtractor (+ Rhino, jsoup, protobuf)
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn org.jsoup.**
-dontwarn com.google.re2j.**
-dontwarn org.schabi.newpipe.extractor.**

# Rhino (used by NewPipeExtractor) references JDK-only dynalink classes that don't exist on Android
-dontwarn jdk.dynalink.**

-keepclasseswithmembers class * { native <methods>; }
