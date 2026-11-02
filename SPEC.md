# Spec: Venice AI Mobile Launcher (Android APK)

## Objective
Build a native Android launcher and wrapper application for Venice.ai (`https://venice.ai`) to provide a mobile app experience on Android devices.
The application solves two major shortcomings of using Venice.ai in standard mobile browsers:
1. **Background Execution**: Prevents generation interruption when the user locks their screen or switches apps. Automatically detects active AI streaming / text generation / image creation, initiates an Android Foreground Service with a WakeLock, keeps the WebView and network running, and notifies the user with sound/vibration when generation completes.
2. **Account Manager**: Seamlessly manages multiple accounts/profiles (cookies, localStorage, sessions) and allows 1-tap switching between accounts without manual logout/login cycles.

## Tech Stack
- **Language**: Java / Android SDK (API Level 26 - 34)
- **Architecture**: Single Activity (`MainActivity`) with modular controller architecture:
  - `BackgroundService`: Android Foreground Service with notification & WakeLock management.
  - `AccountManager`: Profile & session storage, cookie jar & web storage backup/restore.
  - `VeniceWebViewClient` & `VeniceChromeClient`: Modern WebView handling, file chooser (for prompt attachments), download listener (for saving generated images).
  - `VeniceJsBridge`: JavaScript interface (`@JavascriptInterface`) communicating generation status between WebView DOM and native Android service.
- **Build System**: Android Gradle Plugin / Gradle or standalone Dockerized SDK builder targeting a release `.apk`.

## Commands
- **Build APK**: `./gradlew assembleRelease` or `./build_docker.sh`
- **Sign APK**: `apksigner sign --ks release.keystore ...`
- **Verify APK**: `apksigner verify VeniceLauncher.apk` && `aapt dump badging VeniceLauncher.apk`
- **Upload**: Upload to temporary file hosting (`tmpfiles.org`, `0x0.st`, `catbox/litterbox`) and output direct download link.

## Project Structure
```
venice-launcher/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/venicelauncher/app/
│       │   ├── MainActivity.java
│       │   ├── BackgroundKeepAliveService.java
│       │   ├── AccountManager.java
│       │   ├── VeniceJsBridge.java
│       │   └── UIHelper.java
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml
│           │   ├── dialog_accounts.xml
│           │   └── dialog_settings.xml
│           ├── values/
│           │   ├── strings.xml
│           │   ├── colors.xml
│           │   └── styles.xml
│           └── drawable/
│               ├── ic_launcher_foreground.xml
│               └── ...
├── build.gradle
├── settings.gradle
├── gradle.properties
├── Dockerfile.builder
└── build_apk.sh
```

## Boundaries
- **Always**:
  - Keep CPU wake lock properly scoped with safety timeouts to avoid battery drain.
  - Cleanly isolate cookies and local storage between distinct account profiles.
  - Handle file upload permissions (`READ_MEDIA_IMAGES` / `CAMERA`) gracefully with permission checks.
- **Never**:
  - Never freeze or cancel active AI generations on activity pause when background mode is active.
  - Never overwrite other account data when switching profiles.

## Success Criteria
1. Complete, compile-ready Android project created with Java and modern Android components.
2. Background Service with WakeLock and Foreground Notification keeping WebView JS execution alive when minimized.
3. Account Manager allowing creating, listing, switching, and deleting accounts with session/cookie isolation.
4. Working JavaScript bridge detecting generation start/end and dispatching completion notifications.
5. APK compiled, signed, and validated with `aapt` / `apksigner`.
6. APK uploaded to temporary cloud hosting with valid, tested download link.
