# JNI entry points are resolved by name from the native library.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class org.freenet.androidnode.NativeBridge { *; }

# BouncyCastle is selected through provider names.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# The TOML parser loads ANTLR runtimes reflectively.
-keep class org.tomlj.** { *; }
-keep class org.antlr.** { *; }
-dontwarn org.antlr.**

# Keep line numbers so a crash report can be mapped back to the source.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

