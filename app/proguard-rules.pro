# Proguard rules for Venice Launcher
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.venicelauncher.app.** { *; }
