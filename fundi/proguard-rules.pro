# Keep kotlinx.serialization generated serializers for our DTOs.
-keepclassmembers class com.qualityverifier.data.chat.dto.** {
    *** Companion;
}
-keepclasseswithmembers class com.qualityverifier.data.chat.dto.** {
    kotlinx.serialization.KSerializer serializer(...);
}
