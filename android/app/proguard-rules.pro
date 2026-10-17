# RemotePhone ProGuard rules

# Keep WebSocket server (uses reflection internally)
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**

# Desktop mode: Shizuku instantiates the user service by class name in its own process
-keep class com.remotephone.desktop.DesktopUserService { <init>(...); *; }
