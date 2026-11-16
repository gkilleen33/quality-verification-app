# Keep kotlinx.serialization generated serializers for the wire shapes.
-keepclassmembers class com.qualityverifier.tracker.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.qualityverifier.tracker.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
