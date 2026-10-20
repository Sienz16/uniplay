# Proguard rules for Uniplay app
-keepattributes *Annotation*
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn javax.annotation.**
-keep class com.andrerinas.openheadunit.aap.protocol.proto.** { *; }
-keep class com.google.protobuf.** { *; }
