# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.yaz.voice.** {
    *** Companion;
}
-keepclasseswithmembers class com.yaz.voice.** {
    kotlinx.serialization.KSerializer serializer(...);
}
