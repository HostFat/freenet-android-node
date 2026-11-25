# JNI entry points are resolved by name from the native library.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class org.freenet.androidnode.NativeBridge { *; }

# Keep line numbers so a crash report can be mapped back to the source.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
