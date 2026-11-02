# Implementation Plan: Venice AI Mobile Launcher

## Overview
Develop a high-performance, polished Android application ("Venice Launcher") packaged into an installable `.apk`. The application wraps Venice.ai in a hardened native WebView, integrates a Foreground Service with WakeLock to guarantee uninterrupted background text/image generation, and provides a multi-account manager with cookie/localStorage snapshot isolation.

## Architecture Decisions
1. **Background Keep-Alive Architecture**:
   - Use an Android `ForegroundService` with notification channel `venice_background_channel`.
   - Acquire a `PowerManager.WakeLock` (`PARTIAL_WAKE_LOCK`) when Venice is actively generating or when user explicitly enables "Keep Alive".
   - Inject a JavaScript MutationObserver and Fetch/XHR monitor that sends callbacks via `@JavascriptInterface` (`VeniceJsBridge.onGenerationStarted()` and `VeniceJsBridge.onGenerationCompleted()`).
   - Prevent default WebView lifecycle pausing (`do NOT call webView.onPause()` or `pauseTimers()` if background task is active), allowing network streams (Server-Sent Events / SSE / WebSockets) to continue processing smoothly in background.
   - When generation finishes in background, notify the user with a notification ("Venice AI: Twoja odpowiedź jest gotowa!") with vibration and sound.

2. **Account Manager Architecture**:
   - Maintain a list of user profiles stored in `SharedPreferences` (e.g. "Default", "Account 1", "Account 2", etc.).
   - When switching accounts:
     1. Snapshot current profile's cookies via `CookieManager.getInstance().getCookie("https://venice.ai")` and localStorage/sessionStorage via `evaluateJavascript`.
     2. Persist the snapshot into JSON storage per profile ID.
     3. Load target profile's snapshot, inject cookies via `CookieManager.getInstance().setCookie()`, clear old session data, and evaluate injection of localStorage before page load.
     4. Reload WebView with new credentials.
   - Provide intuitive UI modal to add new profile, rename, switch with 1 tap, or delete.

3. **Android UI & WebView Integration**:
   - Dark mode styling (#0E0E10 background, Venice cyan/accent buttons).
   - Bottom floating control bar / drawer:
     - Profile badge & switcher
     - Background mode status pill (animating / glowing when AI is generating)
     - Refresh, Back, Forward buttons
     - Options / Settings modal
   - `WebChromeClient`:
     - File chooser support for image prompt uploads (`onShowFileChooser`).
     - Progress bar display during navigation.
     - Permission requests (Camera / Storage).
   - `DownloadListener`:
     - Android `DownloadManager` integration to save generated Venice images directly into `/sdcard/Download`.

4. **Build System & Toolchain**:
   - Standard Android Gradle project structure with Gradle wrapper / AGP.
   - Docker-based build environment with Android SDK (API 34, build-tools 34.0.0, OpenJDK 17) ensuring 100% reproducible APK compilation.
   - APK signed with release keystore using `apksigner` and zipalign.

## Task List

### Phase 1: Android Project Setup & Core Structure
- [ ] Task 1: Create Android project directory layout, `build.gradle`, `settings.gradle`, `gradle.properties`, and `AndroidManifest.xml` with all necessary permissions (`INTERNET`, `FOREGROUND_SERVICE`, `WAKE_LOCK`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_SPECIAL_USE` / `DATA_SYNC`).
- [ ] Task 2: Implement modern UI layouts (`activity_main.xml`, bottom bar, dialogs for account manager and settings) and resource definitions (`strings.xml`, `colors.xml`, `styles.xml`, vector drawables).

### Checkpoint: Foundation Ready
- [ ] Directory structure verified, XML layouts and manifests validated.

### Phase 2: Core Components Implementation
- [ ] Task 3: Implement `AccountManager.java` for profile management, cookie snapshots, localStorage serialization, and switching logic.
- [ ] Task 4: Implement `BackgroundKeepAliveService.java` with Foreground Service, ongoing notification, WakeLock management, and generation completion alerts.
- [ ] Task 5: Implement `VeniceJsBridge.java` and JavaScript injection scripts for DOM mutation observation, generation state tracking, and storage export/import.
- [ ] Task 6: Implement `MainActivity.java` integrating WebView, WebChromeClient, WebViewClient, DownloadListener, bottom bar interactions, and dialogs.

### Checkpoint: Core Implementation
- [ ] All Java source files compile cleanly and integrate seamlessly.

### Phase 3: Build Pipeline & Packaging
- [ ] Task 7: Set up Docker builder (`Dockerfile.builder` & build script) with Android SDK and Gradle.
- [ ] Task 8: Compile the project into release `.apk`, align and sign with release keystore.
- [ ] Task 9: Verify APK package integrity (`aapt dump badging`, `apksigner verify`, manifest analysis).

### Phase 4: Delivery & Verification
- [ ] Task 10: Upload the final APK to temporary hosting services (`tmpfiles.org`, `0x0.st`, `catbox/litterbox`).
- [ ] Task 11: Validate the download links by downloading and checking hash/size integrity.
