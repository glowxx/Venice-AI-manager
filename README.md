# Venice AI Manager for Android

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026--34)-green.svg)](https://android.com)
[![Version](https://img.shields.io/badge/Version-1.0.4-blue.svg)](https://github.com/glowxx/Venice-AI-manager/releases)
[![License](https://img.shields.io/badge/License-Apache%202.0-lightgrey.svg)](LICENSE)

A native Android wrapper, session manager, and background generation runner for [Venice.ai](https://venice.ai).

Standard mobile browsers suspend background JavaScript processing when the app is minimized or the screen turns off, terminating long-running AI stream generation and losing context. Furthermore, managing multiple Venice AI accounts requires tedious logout/login cycles. **Venice AI Manager** solves these issues with native background execution and isolated profile switching.

---

## Key Features

### 1. Isolated Multi-Account Manager (`AccountManager`)
- Create and switch between multiple independent Venice AI profiles in 1 tap.
- Preserves full session authentication:
  - Live session cookies (`CookieManager`)
  - Browser `localStorage`
  - Browser `sessionStorage`
- Never get logged out or forced through repeated 2FA/login screens when switching accounts.

### 2. Background Keep-Alive Engine (`BackgroundKeepAliveService`)
- Real-time DOM and SSE stream observer (`VeniceJsBridge`) detects when Venice AI is actively generating responses or rendering artwork.
- Automatically acquires a `PARTIAL_WAKE_LOCK` and engages an Android Foreground Service with low-priority notification to prevent Chromium V8 throttling.
- Completion alerts: Plays haptic vibration and displays a system notification when the AI finishes writing.

### 3. ZIP-Based Complete Backup & Restore System (`BackupManager`)
- **Full ZIP Export**: Packages all accounts, live session cookies, and local storage into an encrypted/standard `.zip` archive:
  ```text
  venice_backup_YYYYMMDD_HHMMSS.zip
  ├── manifest.json              # Schema version 3, account summary, active ID, timestamp
  ├── accounts.json              # Full account profiles payload
  ├── cookies/                   # Raw session cookie dumps per profile
  ├── storage/                   # LocalStorage & SessionStorage snapshots per profile
  └── README.txt                 # Archive information
  ```
- **Automatic Background Backup**: Rotated automated backups (keeps latest 5) are mirrored to internal storage and public `Documents/VeniceAI_Backups/` to survive updates and reinstalls.
- **Instant Restore**: Restoring from a backup archive immediately injects cookies, restores storage, and logs into your accounts without manual login.

### 4. Native Android Integration
- **File Attachments**: Web file chooser supporting camera capture and gallery selection for vision and prompt attachments.
- **Download Manager**: Native download listener that saves generated images directly to device public `Downloads` with progress notifications.
- **Ergonomic Fullscreen UX**:
  - Non-intrusive, draggable floating menu (snaps to screen edges).
  - Optional stealth mode: Hide floating button completely and access settings by long-pressing the device Back button.
  - Pull-to-refresh toggle.

---

## Architecture Overview

```
com.venicelauncher.app/
├── MainActivity.java                 # Core WebView container, SAF launchers, floating UI
├── BackgroundKeepAliveService.java   # Android Foreground Service + WakeLock lifecycle
├── AccountManager.java               # Profile state, session snapshotting & cookie syncing
├── BackupManager.java                # ZIP archive compression/decompression & auto-backup
├── VeniceJsBridge.java               # JavaScript interface & DOM mutation stream watcher
└── AccountsAdapter.java              # Material RecyclerView adapter for profile switching
```

---

## Building from Source

### Prerequisites
- JDK 17
- Android SDK (API Level 34, Build Tools 34.0.0)
- Or **Docker** (recommended for reproducible builds)

### Build with Docker
```bash
./build_apk.sh
```
The compiled, signed release APK will be placed at `./VeniceLauncher.apk`.

### Build with Gradle CLI
```bash
./gradlew assembleRelease
```
The APK output is located at `app/build/outputs/apk/release/app-release.apk`.

---

## Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | Load Venice AI web application |
| `ACCESS_NETWORK_STATE` | Detect network transitions |
| `WAKE_LOCK` | Prevent CPU sleep during background response generation |
| `FOREGROUND_SERVICE` | Run persistent generation service |
| `POST_NOTIFICATIONS` | Generation status and completion notifications |
| `VIBRATE` | Haptic feedback upon response completion |
| `CAMERA` | Take photo attachments for prompts |
| `READ_MEDIA_IMAGES` | Select image attachments from device gallery |

---

## License

This project is licensed under the Apache 2.0 License.
