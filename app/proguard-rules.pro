# Keep OkHttp / kotlinx.serialization friendly rules. Minification is disabled
# for the release build of this sample, but these rules are safe to keep.
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class kotlinx.serialization.json.** { *; }
