# Keep readable stack traces; don't rename (extraction relies on class/method names).
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# ---- NewPipeExtractor (heavy reflection over YouTube's JSON/JS) ----
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**

# Rhino JS engine — runs YouTube's signature / n-parameter deobfuscation JS.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**

# nanojson (extractor's JSON parser) + jsoup (HTML parsing)
-keep class com.grack.nanojson.** { *; }
-dontwarn com.grack.nanojson.**
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# ---- OkHttp / Okio ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- WebView JS interfaces (methods called from injected JS) ----
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---- Our data/model classes (parsed/held via the extractor + JSON) ----
-keep class com.nrknithin.aayt.data.** { *; }
-keep class com.nrknithin.aayt.model.** { *; }
