# Arc Download Manager

Advanced native Android download manager with an integrated browser, a multi-segment download
engine, and session-aware (cookie-preserving) downloads. Built with Kotlin, Jetpack Compose and
Material Design 3.

[![CI](https://github.com/SecretArrow/Arc-Download-Manager/actions/workflows/ci.yml/badge.svg)](https://github.com/SecretArrow/Arc-Download-Manager/actions/workflows/ci.yml)
[![E2E](https://github.com/SecretArrow/Arc-Download-Manager/actions/workflows/e2e.yml/badge.svg)](https://github.com/SecretArrow/Arc-Download-Manager/actions/workflows/e2e.yml)
[![Auto fix](https://github.com/SecretArrow/Arc-Download-Manager/actions/workflows/autofix.yml/badge.svg)](https://github.com/SecretArrow/Arc-Download-Manager/actions/workflows/autofix.yml)

## Features

### Download engine
- Persistent state machine: Queued → Connecting → Downloading → Merging → Verifying → Completed
  (plus Paused, Failed, Canceled, Waiting-for-network, Waiting-for-storage, Auth-required).
- **Multi-segment downloads**: large files are split into up to 16 parallel byte-range connections
  and merged into one verified file. Auto mode picks the best count based on server support.
- **Reliable pause/resume** backed by Room — survives process death, network loss and app restarts.
- Automatic retry with exponential backoff + jitter, `Retry-After` awareness and adaptive
  parallelism when servers rate-limit (HTTP 429/503).
- Range/ETag validation: resumed downloads verify `Content-Range` and use `If-Range`, so a changed
  file is detected and restarted instead of corrupted.
- SHA-256 checksum verification (user-supplied or computed after completion).
- Real bytes-based progress, live speed and ETA, throttled DB writes to save battery.

### Background execution
- Foreground service (`dataSync`) with aggregate progress notification and Pause all / Resume all /
  Cancel all actions.
- Boot receiver + WorkManager recovery worker reconcile temp segment files with the database.
- Handles Doze, battery restrictions and Android 13+ notification permission.

### Integrated browser
- Full WebView browser: address bar, tabs, bookmarks, history, desktop-site toggle, private mode,
  share/copy link, long-press link menu, clear browsing data.
- **Session-aware downloads**: cookies are captured from `CookieManager` per-origin and forwarded
  only to the same host — never across cross-origin redirects or scheme downgrades — so login-
  protected files download exactly as they would in the browser.
- 401/403 responses surface a "Sign-in required" state instead of silently corrupting files.

### Files & UI
- Material 3 UI with dynamic color, dark/light themes, compact/detailed list modes, and an
  adaptive layout (NavigationBar on phones, NavigationRail on tablets).
- File library with categories (images, video, audio, documents, archives, apps), search,
  open/share/rename/delete.
- Files land in `Downloads/Arc` (MediaStore on Android 10+, legacy storage before that) or any
  SAF folder the user picks.
- Duplicate detection with Skip / New copy / Replace choices.

## Tech stack

| Layer | Choice |
|---|---|
| Language | Kotlin 2.0, coroutines, Flow |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + clean data/domain/engine separation |
| DI | Hilt |
| Persistence | Room + DataStore |
| Networking | OkHttp (manual redirect handling, range requests) |
| Background | Foreground Service + WorkManager |
| Testing | JUnit, MockWebServer, Compose UI tests, Hilt instrumentation |

## CI/CD

| Workflow | Purpose |
|---|---|
| `ci.yml` | Secret scan → assembleDebug → unit tests → Android lint → ktlint → **auto-release on main** |
| `e2e.yml` | Instrumented tests on an API 30 emulator (KVM accelerated) |
| `autofix.yml` | Runs `ktlintFormat` and commits fixes back to `main` automatically |
| `release.yml` | Builds a **signed release APK** and publishes a GitHub Release when the version bumps |

**Auto-release flow:** bump `versionName` in `app/build.gradle.kts`, push to `main`; when CI is
green, a signed APK is attached to a `v<version>` GitHub release automatically. Manual runs via
`workflow_dispatch` or by pushing a `v*` tag.

Signing: the release keystore is stored as the `ARC_KEYSTORE_B64` Actions secret and decoded at
build time — it is never committed.

## Building locally

```bash
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:testDebugUnitTest  # JVM unit tests
./gradlew :app:ktlintFormat       # auto-format
```

Requirements: JDK 17, Android SDK 35.

## Known platform limitations

- Starting a `dataSync` foreground service directly from `BOOT_COMPLETED` is restricted by
  Android; interrupted downloads are requeued on boot and resume when the app is opened.
- WebView `blob:` URLs and POST-based one-time-token flows cannot be replayed by a plain HTTP
  client; use the long-press "Download linked file" action after the page resolves the real URL.
- Private browsing mode isolates history recording and clears session cookies on exit; full
  per-tab cookie isolation would require a separate WebView data directory (Android 9+ API
  constraints apply).

## License

All rights reserved by the repository owner.
