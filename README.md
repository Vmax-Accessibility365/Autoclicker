# Autoclicker

## 1. Project Overview
Standalone Android app that:
1. Takes user-entered target text (single or comma-separated sequence).
2. Captures the screen via `MediaProjection`.
3. Runs on-device OCR (ML Kit) on each captured frame.
4. Finds the target text and taps its bounding-box center via an
   `AccessibilityService` gesture.
5. Retries on a configurable interval if the text isn't found, and
   advances through a multi-text sequence when it is.

Package: `com.example.autoclicker`

## 2. Folder Structure
```
Autoclicker/
├── .github/
│   └── workflows/
│       └── build.yml
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/autoclicker/
│       │   ├── MainActivity.kt
│       │   ├── ClickAccessibilityService.kt
│       │   ├── ScreenCaptureManager.kt
│       │   ├── OcrHelper.kt
│       │   ├── AutoClickService.kt
│       │   └── TextMatcher.kt
│       └── res/
│           ├── drawable/ic_launcher.xml
│           ├── layout/activity_main.xml
│           ├── values/{colors,strings,themes}.xml
│           └── xml/accessibility_service_config.xml
├── .gitignore
├── README.md
├── build.gradle
├── settings.gradle
└── gradle.properties
```

## 3. Build Instructions (local)
Requires Android SDK + JDK 17 installed.
```
./gradlew assembleDebug
```
Output APK: `app/build/outputs/apk/debug/app-debug.apk`

## 4. GitHub Actions Instructions
1. Push this repo's contents to a new GitHub repository (or use the
   existing one).
2. `.github/workflows/build.yml` runs automatically on push to `main`,
   and can also be triggered manually from the **Actions** tab via
   `workflow_dispatch`.
3. The workflow: checks out the code, sets up JDK 17, sets up Gradle,
   generates the Gradle wrapper (`gradle wrapper --gradle-version 8.7`),
   then runs `./gradlew assembleDebug`.

## 5. APK Artifact Location
- Build output path: `app/build/outputs/apk/debug/app-debug.apk`
- GitHub Actions artifact name: **`app-debug-apk`** (download from the
  finished workflow run's summary page).

## 6. Required Android Permissions
- `FOREGROUND_SERVICE` — to run `AutoClickService` in the foreground.
- `FOREGROUND_SERVICE_MEDIA_PROJECTION` — required alongside the
  `mediaProjection` foreground service type (Android 10+).
- `POST_NOTIFICATIONS` — requested at runtime on Android 13+ so the
  ongoing service notification can be shown.
- **Accessibility Service** (not a manifest `<uses-permission>`, but a
  system-level grant): the user must manually enable
  *Settings → Accessibility → Autoclicker → ON*. Apps cannot enable
  this for themselves — this is an Android security restriction.
- **Screen-capture consent**: a one-time system dialog triggered by
  `MediaProjectionManager.createScreenCaptureIntent()`, accepted per
  app-session.

## 7. Android Version Compatibility
- `minSdk 24`, `targetSdk 34`, `compileSdk 34`.
- Build tooling: AGP `8.5.0`, Gradle `8.7`, Kotlin `1.9.24`, JDK `17`
  (all mutually compatible versions).
- Android 10+ (API 29+): foreground service type must be passed at the
  `startForeground()` call site, not only declared in the manifest —
  handled via `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)`.
- Android 13 (API 33): notification permission is requested at runtime.
- Android 14 (API 34): `MediaProjection` requires a registered
  `Callback` before `createVirtualDisplay()` is called, or the system
  may tear the projection down — handled in `ScreenCaptureManager`.

## 8. MediaProjection / OCR Lifecycle Notes
- **Permission flow**: `MainActivity` requests screen-capture consent →
  passes `resultCode` + result `Intent` to `AutoClickService` via
  intent extras → the service calls `startForeground()` **first**,
  then calls `MediaProjectionManager.getMediaProjection(...)` and
  constructs `ScreenCaptureManager`, which registers its
  `MediaProjection.Callback` **before** creating the `VirtualDisplay`.
- **Capture threading**: `ImageReader` and `VirtualDisplay` run on a
  dedicated `HandlerThread` (not the main thread), so continuous
  capture cannot cause an ANR.
- **Sequential OCR (no overlap)**: the main loop `suspend`-awaits
  `OcrHelper.recognizeTextSuspend()` before it will capture the next
  frame — a new screenshot is never taken while a previous OCR pass is
  still running.
- **Start/Stop safety**: `AutoClickService.stopSelfCleanly()` is
  guarded by an `AtomicBoolean` so it can only run once per session,
  even if both an explicit `ACTION_STOP` and the loop's own normal-exit
  path try to call it around the same time (no double-stop race).
- **Text matching**: `TextMatcher` first tries an exact case-insensitive
  substring match, then falls back to a normalized Levenshtein-distance
  fuzzy match (threshold 0.82) to tolerate minor OCR misreads; multiple
  targets (comma-separated) are tracked and advanced by `TextSequence`.

## 9. Notes on Corrections vs. the Original Design Doc
These are the Android-version-compatibility fixes applied on top of the
original design, with no unrelated refactors:
1. Removed the unused `SYSTEM_ALERT_WINDOW` permission (no overlay UI
   in this build; left commented in the manifest for future use).
2. Explicit `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` at the
   `startForeground()` call site (Android 10+ requirement).
3. `MediaProjection.Callback` registered before `createVirtualDisplay()`
   (Android 14 requirement).
4. OCR loop rewritten with coroutines so capture/OCR is strictly
   sequential instead of a raw `Thread` racing an async ML Kit callback.
5. `ImageReader`/`VirtualDisplay` moved off the main thread onto a
   `HandlerThread`.
6. Runtime `POST_NOTIFICATIONS` request added for Android 13+.
7. Multi-text sequence support added via `TextSequence`.
8. Fuzzy matching added to `TextMatcher` as an OCR-misread fallback.
9. `stopSelfCleanly()` made idempotent with an `AtomicBoolean` guard to
   remove a double-stop race.
