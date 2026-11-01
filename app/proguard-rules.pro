# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.yaz.language.** {
    *** Companion;
}
-keepclasseswithmembers class com.yaz.language.** {
    kotlinx.serialization.KSerializer serializer(...);
}
