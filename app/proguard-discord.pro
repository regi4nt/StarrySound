# Java half dari Discord Social SDK dipakai lewat JNI -> jangan dibuang R8
-keep class com.discord.** { *; }
-keepclasseswithmembers class * { native <methods>; }
