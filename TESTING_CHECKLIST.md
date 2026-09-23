# Testing Checklist — Automation Utility

## Build (in Android Studio, since this sandbox has no Android SDK/network)
1. Open the project folder in Android Studio (Koala/Ladybug or newer, AGP 8.5.2, Kotlin 1.9.24).
2. Let Gradle sync (downloads Gradle 8.7 + dependencies from google()/mavenCentral()).
3. Build → Build Bundle(s)/APK(s) → Build APK(s).
4. Debug APK output path after a successful build:
   `app/build/outputs/apk/debug/app-debug.apk`
   Release APK:
   `app/build/outputs/apk/release/app-release-unsigned.apk` (needs signing before install).

## Setup on device
- [ ] Install the debug APK.
- [ ] Open the app, tap "Accessibility Service चालू करें" and enable
      "Automation Utility" in Android's Accessibility settings.
- [ ] Grant the POST_NOTIFICATIONS prompt on Android 13+.
- [ ] Optionally tap "Battery Settings खोलें" and set the app to Unrestricted.

## Profile / Step editor
- [ ] Create a new profile; confirm it appears in the profile spinner.
- [ ] Add a step targeting a known viewId, text, and contentDescription on a simple test
      screen (e.g. your own app or a calculator app) — confirm each TargetType works.
- [ ] Edit a step and confirm changes persist after Save.
- [ ] Delete a step and confirm it's removed from the list and (after Save) from storage.
- [ ] Force-close and reopen the app — confirm saved profiles/steps reload correctly.

## Sequential execution (happy path)
- [ ] Build a 3-step profile (e.g. tap button A → verify text appears → tap button B).
- [ ] Tap START; confirm "Current Step" updates 1 → 2 → 3 and the log shows
      Started / Action executed / Verification passed for each step in order.
- [ ] Confirm step 2 never starts until step 1's verification has passed.
- [ ] Confirm completion vibration/notification fires once, and the workflow does not
      auto-restart afterward.

## Verification failure / retry / timeout
- [ ] Point a step's target at something that won't appear; confirm it retries up to the
      configured retry count, then the whole workflow stops with a FAILED status and log entry.
- [ ] Confirm no later step ever runs after a step fails.
- [ ] Set a very short timeout on a step whose action is slow; confirm a TIMEOUT log line
      appears and the workflow stops rather than hanging.

## Interruption handling
- [ ] Add an allow-list rule matching a known dialog's text and its dismiss button; trigger
      that dialog during a run; confirm it's dismissed and the workflow continues.
- [ ] Trigger an unrelated/unknown popup during a run; confirm the workflow PAUSES/STOPS and
      logs "अज्ञात popup" rather than clicking anything on it.

## Safety boundary
- [ ] Point a step at a password/PIN/OTP-labelled field; confirm the workflow stops
      immediately with a SafetyStop log line and never types into it.
- [ ] Confirm the execution log never contains raw values that look like OTP/PIN/card
      numbers (4+ digit runs should show as `****`).

## Variables
- [ ] Use `{{DATE}}`, `{{TIME}}`, `{{COUNTER}}`, and `{{USER_TEXT}}` in an INPUT step; confirm
      each resolves correctly, and COUNTER wraps back to its start value once it hits the
      configured max.

## Snapshot
- [ ] With profile snapshots OFF (default), force a verification failure; confirm no snapshot
      file is created.
- [ ] Turn snapshots ON, force a failure on a non-sensitive step; confirm a PNG appears under
      the app's private `files/snapshots/` directory.
- [ ] Mark a step "sensitive" and force it to fail with snapshots ON; confirm no snapshot is
      captured for that step.

## Emergency stop
- [ ] Tap the in-app STOP button mid-run; confirm the workflow cancels immediately and the
      status becomes STOPPED.
- [ ] Tap STOP on the foreground-service notification; confirm the same behaviour.
- [ ] (Optional/best-effort) Press volume-down mid-run on a device where accessibility key
      filtering is supported; confirm it also stops the workflow — and confirm the app does
      not claim this works on every device.

## Foreground service / battery
- [ ] Confirm the "Automation Running" notification appears only while a workflow is active
      and disappears (or updates to a terminal status) once it finishes/stops/fails.
- [ ] Confirm the app never silently tries to bypass battery optimization — the button only
      opens Android's own settings screen.

## Regression pass
- [ ] Run the same profile twice in a row; confirm results and logs are consistent.
- [ ] Rotate the screen / put the app in background during a run; confirm the foreground
      service keeps the workflow alive and the UI reflects current state correctly on return.
