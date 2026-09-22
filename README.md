# Automation Utility

Personal, user-controlled sequential UI automation tool built on Android's Accessibility
APIs. See `TESTING_CHECKLIST.md` for a full manual test pass.

## Important build note

This project was generated in a sandbox with **no internet access and no Android SDK/Gradle
installed**, so it could not be compiled or built into an APK here, and the Gradle wrapper jar
(`gradle/wrapper/gradle-wrapper.jar`) is intentionally not included — that binary can only be
fetched from `services.gradle.org`, which this sandbox cannot reach.

To build it yourself:
1. Open the `AutomationUtility` folder in Android Studio (Koala/Ladybug 2024.x or newer).
2. Android Studio will detect the missing wrapper jar and offer to regenerate it (or just use
   its own bundled Gradle) — accept that prompt, or run `gradle wrapper --gradle-version 8.7`
   from a terminal if you have a local Gradle install.
3. Let Gradle sync (this downloads AGP 8.5.2, Kotlin 1.9.24, and the AndroidX/Kotlin
   dependencies listed in `app/build.gradle.kts`).
4. Build → Build Bundle(s)/APK(s) → Build APK(s).
5. Output: `app/build/outputs/apk/debug/app-debug.apk`.

I fixed all compile-time issues I could check for statically in this sandbox (package/import
consistency, brace balance, API usage matching AndroidX/AccessibilityService signatures for
API 26–34), but only Android Studio's real compiler can give a final, authoritative
"0 errors" result — please run a build and tell me the exact error text if anything surfaces
that I couldn't catch here, and I'll fix it.

## Architecture

```
Profile (saved) -> Steps[] (Target -> Action -> Verify) -> Retry policy -> Completion behaviour
```

- `data/` — Profile, Step, InterruptionRule models + JSON persistence (no credential fields).
- `engine/` — WorkflowEngine (sequential Target→Action→Verify→Next orchestrator), NodeFinder
  (targeted search + bounded hierarchy fallback + stale-node revalidation), SafetyGate (hard
  block on password/OTP/PIN/CAPTCHA-looking fields), InterruptionGuard (allow-list-only popup
  handling), VariableResolver ({{DATE}} {{TIME}} {{COUNTER}} {{USER_TEXT}}),
  AutomationController (app-wide singleton wiring UI ↔ Accessibility Service ↔ engine).
- `service/` — AutomationAccessibilityService (the only class touching Accessibility APIs),
  AutomationForegroundService (required foreground notification + STOP action), StopReceiver.
- `feedback/` — LogRepository (sanitized live execution log), SnapshotManager (default-off
  diagnostic capture), CompletionNotifier (vibration/notification, no auto-restart),
  BatteryHelper (opens Android's own battery settings — no optimization bypass).
- `ui/` — MainActivity, MainViewModel, StepAdapter/LogAdapter/StepEditDialog.

## Safety boundary (enforced in code, not just documented)

- `SafetyGate.checkNode()` stops the entire workflow before any CLICK/INPUT on a node whose
  `isPassword` flag is set, or whose text/hint/contentDescription/viewId contains an OTP,
  password, PIN, CVV, CAPTCHA, or card-number keyword.
- `InterruptionGuard` only ever clicks a button that matches a rule the user explicitly added
  to the active profile; any other dialog-looking window stops the workflow instead.
- `LogRepository.sanitize()` redacts any 4+ digit run before it's written to the visible log.
- Snapshots default to off per profile and are skipped per-step when a step is marked
  `sensitive`, regardless of the profile setting.
- `BatteryHelper` only opens `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`; it never
  calls `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` or otherwise tries to self-grant the exemption.
