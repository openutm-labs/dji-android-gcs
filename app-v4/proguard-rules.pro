# --- DJI Mobile SDK v4.18: DJI's own keep rules, verbatim from its sample app
# https://github.com/dji-sdk/Mobile-SDK-Android/blob/master/Sample%20Code/app/proguard-rules.pro
-keepattributes Exceptions,InnerClasses,*Annotation*,Signature,EnclosingMethod

-dontoptimize
-dontpreverify
-dontwarn okio.**
-dontwarn org.bouncycastle.**
-dontwarn dji.**
-dontwarn com.dji.**
-dontwarn sun.**
-dontwarn java.**
-dontwarn com.amap.api.**
-dontwarn com.here.**
-dontwarn com.mapbox.**
-dontwarn okhttp3.**
-dontwarn retrofit2.**

-keepclassmembers enum * {
    public static <methods>;
}

-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
-keep class * extends android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

-keep,allowshrinking class * extends dji.publics.DJIUI.** {
    public <methods>;
}

-keep class net.sqlcipher.** { *; }

-keep class net.sqlcipher.database.* { *; }

-keep class dji.** { *; }

-keep class com.dji.** { *; }

-keep class com.google.** { *; }

-keep class org.bouncycastle.** { *; }

-keep,allowshrinking class org.** { *; }

-keep class com.squareup.wire.** { *; }

-keep class sun.misc.Unsafe { *; }

-keep class com.cySdkyc.** { *; }

-keep class org.greenrobot.eventbus.**{*;}

-keep class it.sauronsoftware.ftp4j.**{*;}

-keepclasseswithmembers,allowshrinking class * {
    native <methods>;
}

-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

-keep class androidx.appcompat.widget.SearchView { *; }

-keepclassmembers class * extends android.app.Service
-keepclassmembers public class * extends android.view.View {
    void set*(***);
    *** get*();
}
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}
-keep class androidx.** { *; }
-keep class android.media.** { *; }
-keep class okio.** { *; }
-keep class com.lmax.disruptor.** { *; }
-keep class com.qx.wz.dj.rtcm.* { *; }

-dontwarn com.mapbox.services.android.location.LostLocationEngine
-dontwarn com.mapbox.services.android.location.MockLocationEngine
-keepclassmembers class * implements android.arch.lifecycle.LifecycleObserver {
    <init>(...);
}
# ViewModel's empty constructor is considered to be unused by proguard
-keepclassmembers class * extends android.arch.lifecycle.ViewModel {
    <init>(...);
}
# keep Lifecycle State and Event enums values
-keepclassmembers class android.arch.lifecycle.Lifecycle$State { *; }
-keepclassmembers class android.arch.lifecycle.Lifecycle$Event { *; }
# keep methods annotated with @OnLifecycleEvent even if they seem to be unused
# (Mostly for LiveData.LifecycleBoundObserver.onStateChange(), but who knows)
-keepclassmembers class * {
    @android.arch.lifecycle.OnLifecycleEvent *;
}

-keepclassmembers class * implements android.arch.lifecycle.LifecycleObserver {
    <init>(...);
}

-keep class * implements android.arch.lifecycle.LifecycleObserver {
    <init>(...);
}
-keepclassmembers class android.arch.** { *; }
-keep class android.arch.** { *; }
-dontwarn android.arch.**

-keep class org.apache.commons.** {*;}


#<------------ utmiss config start------------>
-keep class dji.sdk.utmiss.** { *; }
-keep class utmisslib.** { *; }
#<------------ utmiss config end------------>
# --- dji-flight

# kotlinx.serialization: the library ships consumer rules; keep our serializers' companions explicitly too
# (journal events and samples must stay readable by every later version of the app).
-keepclassmembers @kotlinx.serialization.Serializable class org.openutm.djiflight.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class org.openutm.djiflight.**$$serializer { *; }
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Flavour entry points looked up by name in the manifest.
-keep class org.openutm.djiflight.app.v4.DjiFlightApp { *; }
-keep class org.openutm.djiflight.app.v4.SimFlightApp { *; }

# UX SDK v4's MapWidget references map SDKs we exclude (never inflated).
-dontwarn com.google.android.gms.**
-dontwarn com.amap.api.**
-dontwarn com.mapbox.mapboxsdk.**
-keep class dji.ux.** { *; }
# UX SDK 4.16.2 shades a list-adapter helper (com.chad.library, obfuscated as a.a.a.a.a.e.*) without its R
# classes. Debug builds have the same gap and the panels inflate fine; watch for a NoClassDefFoundError in
# UX SDK lists on real aircraft (docs/12, Open items).
-dontwarn com.chad.library.R$id
-dontwarn com.chad.library.R$layout

# MapLibre and ZXing ship their own consumer rules; silence optional-dependency warnings.
-dontwarn org.maplibre.**
-dontwarn com.google.zxing.**
-dontwarn com.journeyapps.**
