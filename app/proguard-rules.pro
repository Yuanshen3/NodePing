# Add project specific ProGuard rules here.
# By default the flags in this file are appended to flags specified
# in proguard-android-optimize.txt (see app/build.gradle.kts).

# OkHttp platform used only on JVM and when Conscrypt and other security providers are available.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
