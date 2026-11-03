# Wosux Stream - R8 rules for the release build (Phase 38).
# Media3, Coil, coroutines, AndroidX and OkHttp ship their own consumer rules;
# only the app-specific and "optional class" bits are listed here.

# OkHttp / Okio reference optional TLS providers and annotations that are not on Android.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**

# Fragments (also SourcePicker, a DialogFragment) are re-created by the system
# through their public no-argument constructor after rotation / process death.
-keep class * extends androidx.fragment.app.Fragment {
    public <init>();
}
