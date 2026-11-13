# Proguard rules for FireCast Local (WebRTC + Java-WebSocket)

# WebRTC native and JNI bindings
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Java-WebSocket
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**

# ZXing
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# FireCast Models
-keep class com.firecast.local.** { *; }
