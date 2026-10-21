# Tesseract4Android uses JNI; keep its classes and native method names.
-keep class com.googlecode.tesseract.android.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn com.googlecode.tesseract.android.**
