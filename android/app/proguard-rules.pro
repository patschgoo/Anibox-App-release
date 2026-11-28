# Network shares (smbj): its event bus (mbassador) finds handler methods by reflection.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
# BouncyCastle: smbj's BCSecurityProvider uses its digest/cipher classes directly (no reflection), so
# R8 may shrink it; only the optional parts it can't find on Android are silenced below.
# Optional parts of those libraries that Android doesn't have (Kerberos sign-in, EL filters, logging backend).
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**

# libmpv: the native code calls back into these by name (the AAR's own rules keep MPVLib itself).
-keep class dev.jdtech.mpv.MPVLib { *; }
-keep class dev.jdtech.mpv.MPVLib$** { *; }
-keep interface dev.jdtech.mpv.MPVLib$EventObserver { *; }
-keep interface dev.jdtech.mpv.MPVLib$LogObserver { *; }
-keep class * implements dev.jdtech.mpv.MPVLib$EventObserver { *; }
