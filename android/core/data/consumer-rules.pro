# kotlinx.serialization models are in :core:network (pure JVM)
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class io.wrtpilot.core.network.model.**$$serializer { *; }
-keepclassmembers class io.wrtpilot.core.network.model.** {
    *** Companion;
}
-keepclasseswithmembers class io.wrtpilot.core.network.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
