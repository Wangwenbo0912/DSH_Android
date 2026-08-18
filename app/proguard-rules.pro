# DSH WebUI is served over local HTTP; keep WebView and JS bridge classes.
-keep class com.dshbox.app.bridge.** { *; }
-keepclassmembers class com.dshbox.app.bridge.** { *; }

# SnakeYAML (used by DshConfigWriter to write settings.yaml / .credentials.yaml).
# Its JavaBeans introspection classes reference java.beans.*, which is not on
# Android; we only use Map-based load/dump so those are unnecessary.
-keep class org.yaml.snakeyaml.** { *; }
-keepclassmembers class org.yaml.snakeyaml.** { *; }
-dontwarn java.beans.**
-dontwarn org.yaml.snakeyaml.introspector.**
-dontwarn sun.misc.**
-dontwarn org.yaml.snakeyaml.constructor.SafeConstructor$
