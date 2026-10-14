# Arc Download Manager

Advanced native Android download manager with an integrated browser, a multi-segment download
engine, and session-aware (cookie-preserving) downloads. Built with Kotlin, Jetpack Compose and
Material Design 3.

[![CI](https://github.com/SecretArrow/Arc-Download-Manager/actions/workflows/ci.yml/badge.svg)](https://github.com/SecretArrow/Arc-Download-Manager/actions/workflows/ci.yml)
[![E2E](https://github.com/SecretArrow/Arc-Download-Manager/actions/workflows/e2e.yml/badge.svg)](https://github.com/SecretArrow/Arc-Download-Manager/actions/workflows/e2e.yml)
[![Auto fix](https://github.com/SecretArrow/Arc-Download-Manager/actions/workflows/autofix.yml/badge.svg)](https://github.com/SecretArrow/Arc-Download-Manager/actions/workflows/autofix.yml)
[![Release](https://github.com/SecretArrow/Arc-Download-Manager/actions/workflows/release.yml/badge.svg)](https://github.com/SecretArrow/Arc-Download-Manager/releases)
[![Latest release](https://img.shields.io/github/v/release/SecretArrow/Arc-Download-Manager?include_prereleases&label=APK)](https://github.com/SecretArrow/Arc-Download-Manager/releases/latest)

<p align="center">
  <img src="store/feature-graphic.png" alt="Arc Download Manager" width="640" />
</p>

## Features

### All internet protocols
Arc downloads over **HTTPS, HTTP, FTP, FTPS, SFTP, magnet (BitTorrent)** and `content://` URIs:

| Protocol | Transport | Highlights |
|----------|-----------|------------|
| HTTPS / HTTP | OkHttp | HTTP/2 on TLS, up to 16 parallel range segments, redirect chains, signed-CDN refresh, cookie scoping |
| FTP | Apache Commons Net | Passive mode, binary transfer, `REST`-based resume, anonymous or `ftp://user:pass@host` credentials |
| FTPS (explicit TLS) | Apache Commons Net | `AUTH TLS` + encrypted data channel (`PROT P`), self-signed home/NAS servers accepted |
| SFTP | JSch (mwiede fork) | Modern SSH key exchange (works with current OpenSSH), `user:pass@host` credentials, resume via local offset skip |
| Magnet (BitTorrent) | jlibtorrent (libtorrent 2.0) | DHT + tracker peer discovery, v1 & hybrid v1/v2 magnets, live peers/seeds in the detail screen |
| content:// | ContentResolver | Copies any local content-provider document into your library |

**BitTorrent notes:** magnet tasks run on a native libtorrent 2.0 session (arm64-v8a,
armeabi-v7a and x86_64 are bundled; that is what grows the APK to ~40 MB).
Files are assembled per-task, then promoted into `Downloads/Arc/` (single file) or
`Downloads/Arc/<torrent name>/` (multi-file). Seeding is disabled by design: the torrent is
paused the moment its payload completes. If no peers answer the magnet, the task fails after a
3-minute metadata window with an actionable message. Other niche schemes (ed2k, rtmp ...) still
fail fast with a clear "protocol not supported" message. The Add-download sheet shows a live
protocol badge for the URL you type, and private-server links (FTP/FTPS/SFTP) accept credentials
inline (`sftp://user:pass@host/path`).

### Download engine
- Persistent state machine: Queued → Connecting → Downloading → Merging → Verifying → Completed
  (plus Paused, Failed, Canceled, Waiting-for-network, Waiting-for-storage, Auth-required).
- **Multi-segment downloads**: large files are split into up to 16 parallel byte-range connections
  and merged into one verified file. Auto mode picks the best count based on server support.
- **Live progress everywhere**: multi-segment byte counts are aggregated into the task row on a
  500 ms cadence, so the list, detail screen, hero card, notification and widget all update in
  real time — live per-task speed, smoothed ETA and total throughput included.
- **Reliable pause/resume** backed by Room — survives process death, network loss and app restarts.
- Automatic retry with exponential backoff + jitter, `Retry-After` awareness and adaptive
  parallelism when servers rate-limit (HTTP 429/503).
- **Large-file safe**: the whole pipeline is streaming — bounded 128 KiB network buffers, a
  streamed multi-part merge with a 512 KiB copy buffer, and streaming SHA-256 — with 64-bit byte
  math everywhere, so files of any size (multi-GB) never load into RAM and never crash from OOM.
- **Storage safety**: pre-flight free-space check (2× file-size headroom for staging + final copy),
  live free-space monitoring that auto-pauses into a "waiting for storage" state, part files
  deleted immediately after merge, and orphaned temp cleanup on boot.
- Range/ETag validation: resumed downloads verify `Content-Range` and use `If-Range`, so a changed
  file is detected and restarted instead of corrupted.
- SHA-256 checksum verification (user-supplied or computed after completion).
- Real bytes-based progress, live speed and ETA, throttled DB writes to save battery.
- **Signed CDN links handled end-to-end** (e.g. GitHub release assets): the redirect chain is
  re-resolved automatically when the signed link expires mid-download; session cookies are never
  forwarded to the cross-host asset CDN; pasting a release *page* URL fails fast with a clear
  message instead of saving a junk HTML file; pasted URLs tolerate stray spaces/unicode.

### Background execution
- Foreground service (`dataSync`) with aggregate progress notification and Pause all / Resume all /
  Cancel all actions.
- Boot receiver + WorkManager recovery worker reconcile temp segment files with the database.
- Handles Doze, battery restrictions and Android 13+ notification permission.
- **Android 15+ 6-hour dataSync quota handled end-to-end**: the service implements
  `Service.onTimeout()` (pauses the queue, notifies, stops within the deadline), every
  foreground-service start is guarded against `ForegroundServiceStartNotAllowedException`,
  downloads paused by the timeout auto-resume when the app is next opened (the platform resets
  the timer on foreground), and Quick Settings / widget resume actions degrade gracefully
  while the budget is exhausted.

### Integrated browser
- Full WebView browser: address bar, tabs, bookmarks, history, desktop-site toggle, private mode,
  share/copy link, long-press link menu, clear browsing data.
- **Session-aware downloads**: cookies are captured from `CookieManager` per-origin and forwarded
  only to the same host — never across cross-origin redirects or scheme downgrades — so login-
  protected files download exactly as they would in the browser.
- 401/403 responses surface a "Sign-in required" state instead of silently corrupting files.

### Files & UI
- **Modern Material 3 redesign (v2.0)**: brand-gradient hero card with glass stat chips and
  decorative depth, gradient progress bars on every download row, a premium About card with
  developer credit, icon-led settings sections, smooth tab transitions, a tighter editorial
  type scale, dynamic color, dark/light themes, compact/detailed list modes, and an adaptive
  layout (NavigationBar on phones, NavigationRail on tablets).
- **Full task management**: edit a task's URL and filename (validated per protocol; URL changes
  discard partial progress safely), delete tasks with an optional "also delete the downloaded
  file" step, and **archive** finished or paused tasks out of the queue — archived tasks never
  auto-resume and stay out of widget stats until restored.
- **Bulk actions**: long-press to enter selection mode, then select-all, archive/restore and
  delete (with optional file cleanup) in one go, plus one-tap "Clear finished".
- **Search, filters and sorting**: instant search over filenames and URLs, status filter chips
  (All / Running / Done / Failed / Archive) with live counts, and sorting by date, name or size.
- **True edge-to-edge**: content is inset exactly once against the Android status bar (top) and
  the gesture-navigation bar (bottom) on every screen — nothing collides with system UI, and
  input screens (browser address bar, add-download sheet) lift above the keyboard via IME insets.
- Per-category accent colors — images, video, audio, documents, archives and apps each get a
  distinct icon + hue in the download list and file library (color always paired with an icon).
- Branded launcher experience: adaptive icon with **monochrome (themed) layer** for Android 13+
  Material You and a branded splash screen on Android 12+.
- **Home-screen widget** with live queue status and pause/resume/add quick controls.
- **Quick Settings tile** to pause or resume all downloads from the notification shade.
- File library with categories (images, video, audio, documents, archives, apps), search,
  open/share/rename/delete.
- Files land in `Downloads/Arc` (MediaStore on Android 10+, legacy storage before that) or any
  SAF folder the user picks.
- Duplicate detection with Skip / New copy / Replace choices.

## Screenshots

Captured automatically by the E2E pipeline (instrumented test on an API 30 emulator pulls them
from the device and commits them back to this repository on every green run).

| Home | Browser |
|---|---|
| ![Home](screenshots/home.png) | ![Browser](screenshots/browser.png) |

| Files | Settings |
|---|---|
| ![Files](screenshots/files.png) | ![Settings](screenshots/settings.png) |

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

## Play Store assets

`store/` contains Play-ready art generated by `tools/gen_play_assets.py` (pure Pillow):

- `icon-512.png` — 512×512 full-bleed listing icon.
- `feature-graphic.png` — 1024×500 feature banner (also used as the README hero).

Regenerate after branding changes with:

```bash
python3 tools/gen_play_assets.py
```

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
- **Android 15+ stops `dataSync` foreground services after 6 hours of runtime in any 24-hour
  window** (shared by all of an app's dataSync services; the budget only resets when the app is
  brought to the foreground). When the budget runs out Arc pauses the queue, posts a
  "Downloads paused" notification, and resumes automatically the next time you open the app —
  resuming from a widget or Quick Settings tile while the budget is exhausted asks you to open
  the app instead of failing silently. See the official
  [foreground service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout)
  documentation. To verify the behavior on a device:

  ```bash
  adb shell am compat enable FGS_INTRODUCE_TIME_LIMITS com.arc.downloadmanager
  adb shell device_config put activity_manager data_sync_fgs_timeout_duration 60000  # 1 min for testing
  ```
- WebView `blob:` URLs and POST-based one-time-token flows cannot be replayed by a plain HTTP
  client; use the long-press "Download linked file" action after the page resolves the real URL.
- Private browsing mode isolates history recording and clears session cookies on exit; full
  per-tab cookie isolation would require a separate WebView data directory (Android 9+ API
  constraints apply).

## License

All rights reserved by the repository owner.
