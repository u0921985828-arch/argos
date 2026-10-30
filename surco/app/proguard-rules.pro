-keep class org.jaudiotagger.** { *; }
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**
-dontwarn javax.sound.**
-dontwarn java.beans.**
-dontwarn javax.xml.bind.**
-dontwarn org.jaudiotagger.**

# Solo lo nombra la meta-data del manifiesto (Chromecast): R8 no lo vería.
-keep class com.eddie.surco.player.CastOptionsProvider { <init>(); }
