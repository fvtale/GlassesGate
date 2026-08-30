# The DAT SDK reaches its own types reflectively in places; keeping the public surface avoids
# a release build that works in debug and fails on device.
-keep class com.meta.wearable.** { *; }
-dontwarn com.meta.wearable.**

# ML Kit loads its barcode models through generated registrars.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
