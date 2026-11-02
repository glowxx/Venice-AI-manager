# Task Checklist: Venice AI Mobile Launcher

- [x] Task 1: Create Android project structure, Gradle configs, and AndroidManifest.xml
  - Acceptance: Valid Gradle configs, target SDK 34, required permissions declared.
  - Verify: Syntax validation of Gradle and XML manifests.
  - Files: `build.gradle`, `app/build.gradle`, `settings.gradle`, `app/src/main/AndroidManifest.xml`

- [x] Task 2: Implement UI layouts and Android resources
  - Acceptance: Activity layout with WebView, floating control bar, status badges, account dialog layout.
  - Verify: Valid XML syntax and all string/color/drawable references exist.
  - Files: `app/src/main/res/layout/*.xml`, `app/src/main/res/values/*.xml`, drawables

- [x] Task 3: Implement AccountManager
  - Acceptance: Create, switch, list, delete account profiles; backup and restore cookies + localStorage.
  - Verify: Unit testable logic with JSON serialization in SharedPreferences.
  - Files: `app/src/main/java/com/venicelauncher/app/AccountManager.java`

- [x] Task 4: Implement BackgroundKeepAliveService
  - Acceptance: Foreground Service with notification channel, WakeLock management, and generation completion chime/vibrate.
  - Verify: Service lifecycle methods and safety timeouts implemented.
  - Files: `app/src/main/java/com/venicelauncher/app/BackgroundKeepAliveService.java`

- [x] Task 5: Implement VeniceJsBridge & Injection Scripts
  - Acceptance: Injected JS monitoring streaming tokens / stop button / generation status, storage sync bridge.
  - Verify: JavascriptInterface methods handle generation start/stop and storage capture.
  - Files: `app/src/main/java/com/venicelauncher/app/VeniceJsBridge.java`

- [x] Task 6: Implement MainActivity
  - Acceptance: Coordinates WebView, WebChromeClient (file attachments), DownloadListener (image downloads), bottom bar controls, account switching UI, and background keep-alive toggle.
  - Verify: Comprehensive Activity lifecycle and back button handling.
  - Files: `app/src/main/java/com/venicelauncher/app/MainActivity.java`

- [x] Task 7: Set up Android SDK & Docker Build Pipeline
  - Acceptance: Reproducible build script and Dockerfile configured with Android SDK 34 and Gradle.
  - Verify: Docker image builds or runs Gradle assemble.
  - Files: `Dockerfile.builder`, `build_apk.sh`

- [x] Task 8: Compile & Sign Release APK
  - Acceptance: Signed release APK produced (`VeniceLauncher.apk`).
  - Verify: Build produces 4.4MB signed APK file.
  - Files: `VeniceLauncher.apk`

- [x] Task 9: Verify APK Integrity
  - Acceptance: APK signature valid, package name `com.venicelauncher.app`, activities and services declared properly.
  - Verify: `apksigner verify` and `aapt dump badging` succeed.
  - Files: Verification logs

- [x] Task 10: Upload APK to Temporary File Hosting & Verify Links
  - Acceptance: Uploaded to temporary hosting providers with valid download URLs.
  - Verify: `curl` test downloads matching hash and size verified (HTTP 200, 4,518,549 bytes).
