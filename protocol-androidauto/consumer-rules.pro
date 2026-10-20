# Android Auto consumer proguard rules
-keep class com.andrerinas.openheadunit.aap.protocol.proto.** { *; }
-keep class com.google.protobuf.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageV3 { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { *; }
