-keep class com.k2fsa.sherpa.onnx.** { *; }

-keepclassmembers class * {
    native <methods>;
}

-dontwarn com.k2fsa.sherpa.onnx.**
