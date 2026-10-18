# DJI MSDK v5's own rules come from its aar (keep dji.**, com.dji.**, org.**, com.google.**; -dontshrink).

# kotlinx.serialization: keep our serializers' companions (journal events and samples must stay
# readable by every later version of the app).
-keepclassmembers @kotlinx.serialization.Serializable class org.openutm.djiflight.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class org.openutm.djiflight.**$$serializer { *; }
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-keep class org.openutm.djiflight.app.v5.DjiV5FlightApp { *; }

# MapLibre and ZXing ship their own consumer rules; silence optional-dependency warnings.
-dontwarn org.maplibre.**
-dontwarn com.google.zxing.**
-dontwarn com.journeyapps.**

# DJI's UI Library v5 (uxsdk-v5): iconics 4.0.2 still references Kotlin's old `Experimental` annotation.
# Its typeface aars keep their own classes (found by name at run time).
-dontwarn kotlin.Experimental*
