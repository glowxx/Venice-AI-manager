# Venice AI Manager for Android

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026--34)-green.svg)](https://android.com)
[![Version](https://img.shields.io/badge/Version-1.0.7-blue.svg)](https://github.com/glowxx/Venice-AI-manager/releases)
[![License](https://img.shields.io/badge/License-Apache%202.0-lightgrey.svg)](LICENSE)

A native Android wrapper, session manager, and background generation runner for [Venice.ai](https://venice.ai).

Standard mobile browsers suspend background JavaScript processing when the app is minimized or the screen turns off, terminating long-running AI stream generation and losing context. Furthermore, managing multiple Venice AI accounts requires tedious logout/login cycles. **Venice AI Manager** solves these issues with generation-only background execution, isolated profile switching, authentic Venice.ai aesthetics, and live automatic backups.

---

## What's New in v1.0.7

1. **Hardware-Level Sandboxed Browser Environments per Account (`ProfileStore`)**:
   - Upgraded to modern `androidx.webkit.ProfileStore` multi-profile containerization.
   - Each account operates in its own completely separate, physically isolated Chromium browser storage sandbox (`Profile.getOrCreateProfile()`).
   - Dedicated, unshared storage per account:
     - Independent Cookies (`profile.getCookieManager()`)
     - Independent `localStorage` and `sessionStorage`
     - Independent `IndexedDB` (where Venice.ai stores authentication tokens and session caches)
     - Independent Service Worker controllers and HTTP caches
   - Completely eliminates session bleeding and sticky accounts: switching accounts destroys the active renderer and instantiates a clean WebView bound strictly to the target profile's isolated container.
2. **Physical Disk Deletion for Deleted and Incognito Profiles**:
   - Deleting an account or leaving an incognito session triggers `ProfileStore.deleteProfile()`, physically removing all corresponding browser files, databases, and caches from disk.

---

## What's New in v1.0.6

1. **Temporary Incognito Profiles (Disposable Sessions)**:
   - Create disposable incognito sessions in the Account Manager with a single tap.
   - **Zero-Trace Auto-Purge**: All cookies, session storage, and profile credentials are completely purged immediately upon switching to another profile or exiting/closing the application.
   - **Backup Isolation**: Incognito sessions are strictly excluded from automated ZIP backup archives (`venice_backup_*.zip`) and local profile lists, preventing accidental privacy leaks.
   - Visual indicator badge (`🕶 Incognito`) in dialogs and account switcher.

2. **Refined Session Lifecycle Protection**:
   - Web storage (`localStorage` & `sessionStorage`) cleared and re-injected per account switch.
   - Automatic cleanup of any stale temporary sessions upon application cold boot.

---

## What's New in v1.0.5

1. **Keep-Alive Strictly During Active Generation**:
   - The background keep-alive service runs **only** while Venice AI is actively generating responses or rendering images.
   - The moment generation completes, the wake lock is released, completion notifications fire, and the background service shuts down immediately.
   - Zero background battery drain while idle.
2. **Authentic Venice.ai Design Language**:
   - Completely redesigned settings and profile dialogs using official Venice.ai color tokens:
     - Deep Sea (`#06090D`) & Midnight Blue (`#0E1217`) surfaces
     - Stucco Light (`#EEEDE4`) typography
     - Stucco Sand Gold (`#BEA989`) section headers and badges
     - Ocean Accent (`#125DA3`) buttons
   - Eliminates generic clutter and "AI aesthetic" in favor of sleek, minimalist editorial styling.
3. **Continuous Live Auto-Backup with Custom Folder Selection**:
   - Choose any folder on your device (SD Card, Downloads, Documents, etc.) via Android's Storage Access Framework (`OpenDocumentTree`).
   - Automatically writes a complete `.zip` backup (`manifest.json`, `accounts.json`, `cookies/`, `storage/`) whenever profiles, cookies, or sessions update.
   - Includes automatic rotation of timestamped archives (preserves latest 5).

---

## Key Features

### 1. Isolated Multi-Account Manager (`AccountManager`)
- Create and switch between multiple independent Venice AI profiles in 1 tap.
- Preserves full session authentication:
  - Live session cookies (`CookieManager`)
  - Browser `localStorage`
  - Browser `sessionStorage`
- Never get logged out or forced through repeated 2FA/login screens when switching accounts.

### 2. Task-Bound Background Keep-Alive Engine (`BackgroundKeepAliveService`)
- Real-time DOM and SSE stream observer (`VeniceJsBridge`) detects when Venice AI is actively generating responses.
- Automatically acquires a `PARTIAL_WAKE_LOCK` and engages an Android Foreground Service during stream generation.
- Completion alerts: Plays haptic vibration and displays a system notification when the AI finishes writing.

### 3. ZIP-Based Complete Backup & Restore System (`BackupManager`)
- **Full ZIP Export**: Packages all accounts, live session cookies, and local storage into a standard `.zip` archive:
  ```text
  venice_backup_YYYYMMDD_HHMMSS.zip
  ├── manifest.json              # Schema version 3, account summary, active ID, timestamp
  ├── accounts.json              # Full account profiles payload
  ├── cookies/                   # Raw session cookie dumps per profile
  ├── storage/                   # LocalStorage & SessionStorage snapshots per profile
  └── README.txt                 # Archive information
  ```
- **Custom Location & Live Sync**: Point auto-backup to any folder. Backups trigger automatically on cookie updates, profile changes, and when minimizing the app.
- **Instant Restore**: Restoring from a backup archive immediately injects cookies, restores storage, and re-authenticates without manual login.

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
