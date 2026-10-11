# GoreBox uses reflection only for optional native-core detection. Keep app model classes
# available for local JSON profile serialization in optimized builds.
-keep class com.goresense.gorebox.data.ProxyProfile { *; }
