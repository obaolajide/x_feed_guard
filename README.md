# X Feed Guard

A native Android prototype that interrupts a matching next-video swipe in X while preserving ordinary video playback. `docs.docx` remains the unchanged requirements reference.

## Current prototype: 0.7.0

The home screen follows the supplied `interface outline.png`: a centred circular power toggle, an **X** launcher, and **Settings & diagnostics** on white. All application colors use black, white, and neutral gray, including the launcher icon, switches, dialog accents, and settings buttons. The phone's chosen system font is preserved.

The circle shows a power icon and On/Off state; dark means ON and light gray means OFF. The whole circle is a native checkable, keyboard-focusable control exposed to accessibility as a switch. It atomically changes observation and blocking together. OFF pauses both and cancels an unfinished capture. X opens the installed official X app. Settings opens a separate scrollable page with connection status, notification settings, background controls, recordings, export, and deletion. The page's Back control and Android Back return home. Returning from external system settings keeps the settings page open; a new launcher/shortcut intent returns home.

**Notification settings** is always available inside Settings & diagnostics and opens Android's controls; opening it never grants permission or enables notifications. The earlier 0.6.2 live test passed ordinary-playback preservation and swipe exit. This release changes the interface, retaining that version's detection policy. The installed 0.7.0 build passed phone tests for circle ON/OFF, settings and notification navigation, returning to home, and launching X. Protection was left ON, with notifications and the floating shortcut OFF.

## Background support and notification

Earlier versions were terminated twice by Xiaomi with the system exit description `SwipeUpClean`; these were not app fatal exceptions. A killed accessibility service left Android's crashed-service flag set, making its shortcut and detection unavailable. Retaining preferences alone did not solve this failure.

Version 0.6 adds a separate, non-exported foreground service with an ongoing low-importance notification. It remains available while protection is paused, so the controls can still be reached. The notification opens the controls and has a Pause/Resume action. It explicitly shows when accessibility needs attention or when the phone/X version is unsupported. Foreground status was verified through Android's service dump, not inferred from a preference. Notifications are disabled on the test phone at the user's request. The foreground helper can remain running with its drawer notification hidden by Android.

The service uses Android's `specialUse` foreground type and START_STICKY. A non-exported receiver handles completed boot and this package's replacement, respecting the persisted background-support choice. These recovery paths are implemented but boot/reboot and process-kill recovery have not been validated for this version. Android/Xiaomi may still stop it after Force stop, cleanup, memory pressure, or policy changes. Do not describe it as guaranteed always-on or as automatically repairing a crashed accessibility grant.

Use **Settings & diagnostics → Keep running** for:

- **Background autostart:** Xiaomi's autostart page. Enable X Feed Guard if shown.
- **App battery settings:** the app's system App info page; choose No restrictions under Battery/Battery saver if available.
- **Battery optimization list:** Android's generic list. Its reported exemption does not verify Xiaomi's separate choices.
- **Stop background support:** explicitly removes the foreground helper/notification. Turning Protection ON again re-enables background support.

If Android says the accessibility service is not working, use **Reconnect accessibility**, turn the main Use X Feed Guard service OFF, confirm it, then ON again. The shortcut toggle is separate. Android requires the user to enable accessibility; the app never writes secure settings to grant it. The ongoing notification/control screen remains available if the accessibility connection is missing. The user's blocking choice survives interruption, reconnect, and app updates.

## Detection scope and limits

The experimental rule is intentionally restricted to the tested Redmi 13C model 23106RN0DA, Android 15/API 35, X 12.35.0-prod.01, and portrait 720 × 1600 accessibility viewport. It requires a matching video-viewer baseline preceding the swipe, complete bounded structural inspection, a bottom SeekBar/full-screen scroll container, matching active X window/source identity, and a full-screen scroll reporting a positive whole-page Y offset, zero horizontal scroll, and a maximum of at least three whole screen heights. Missing or ambiguous evidence produces no Back action. A matching scroll is retained in memory for at most three seconds; checks at roughly 250 ms intervals can wait for the next viewer to finish loading. Timeline return, a different active window/package, unsupported layout, pause, capture, or lifecycle invalidation cancels recovery. Quiet playback does not expire a known viewer baseline. A static viewer without prior matching scroll evidence never triggers recovery. Entirely missing scroll evidence cannot be reconstructed from the otherwise indistinguishable static viewer.

Two positive recordings exposed this page-scroll pattern and a no-swipe control did not. Normal video and feed structures are otherwise very similar and expose no resource IDs. This rule is not a general video-identity detector. It reacts after the swipe rather than intercepting touch. It attempts Back once, latches until a recognized timeline, and retains a five-second cooldown. A Back request accepted by Android does not prove the final X navigation.

Guard inspection runs on a single background worker, with at most four queued tasks and at most one outstanding content inspection. Copied events are recycled on completion, rejection, or shutdown. Stale queued events are dropped. Generation checks invalidate work after toggles/lifecycle/capture changes, and the active X viewer is rechecked immediately before Back. No wake lock, VPN, overlay permission, root, modification of X, or Internet access is used.

The implementation uses four application permissions: FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS, and RECEIVE_BOOT_COMPLETED. BIND_ACCESSIBILITY_SERVICE protects the system-bound service. Status notifications contain only protection/access state. Local action metadata includes a count, timestamp, and whether Android accepted Back; no post content is recorded by normal operation.

## Test the phone

1. Open X Feed Guard. Tap the power circle to turn protection ON. The optional accessibility shortcut can also open this screen.
2. Notifications are optional to display. Manage them through **Settings & diagnostics → Notification settings**.
3. Open X, watch one video without swiping for several seconds, then swipe once. Ordinary playback should remain open; the matching next-video swipe should request Back.
4. Check normal timeline scrolling and another video. Pause Protection if an unintended screen is interrupted.

Profiles, messages, replies, search, notifications, landscape, picture-in-picture, overlays, service recovery, battery use, and long-duration background behavior still require broader regression testing. Future X versions are blocked by the compatibility gate until retested.

For an authorized own-app UI test, keep the phone unlocked and untouched:

```powershell
python scripts/check_device_controls.py --test-switch --test-navigation --screenshot dist/qa/prototype-0.7.0-controls.png
```

The helper checks foreground before taps/screenshots, tests both states ending at its initial choice, and verifies settings/notification navigation and the X launcher. It only checks X foreground identity, collecting no X hierarchy or screenshot, and never changes accessibility or notification permissions. Concurrent phone use invalidates the test.

## Structural samples

Under Settings & diagnostics, **Record a video transition** starts a guided 40-second explicit capture. Blocking pauses throughout captures.

| Time | Action |
| --- | --- |
| 0–8 seconds | Normal timeline |
| 8–18 seconds | Open one video and let it play |
| 18–30 seconds | Swipe to the next video once, then wait |
| 30–40 seconds | Return to the timeline |

For a no-swipe control, ignore the swipe prompt and remain on the first video until the return prompt. Guided phase labels are clock instructions, not predictions. Up to three completed traces are retained. Separate single-screen captures wait 12 seconds and retain up to five per tester-supplied label. Cancelling or pausing observation discards unfinished capture.

Explicit recordings save sanitized class/resource identifiers, bounds, structural flags, timestamps, device/X metadata, and bounded scroll-event metadata. They exclude post text, descriptions, messages, screenshots, video, and keystrokes. Content records are limited to one per 250 ms and at most 160; scroll/window records have a separate cap of 400. At most 20 structural snapshots are collected approximately two seconds apart.

**Export samples** uses Android's file picker. **Delete samples** removes stored samples/transitions. Nothing leaves the device unless explicitly exported or retrieved over authorized ADB. UI sample counts are refreshed only when a successful save/delete changes a revision, rather than rereading the file every second. Raw captures and QA artifacts are ignored and excluded from source archives.

## Offline build and installation

Existing downloaded tools are sufficient; no new downloads are needed:

```powershell
python scripts/check_source.py
python scripts/build_minimal.py
.tools/sdk/platform-tools/adb.exe install --no-incremental -r dist/x-feed-guard-0.7.0-debug.apk
```

The offline builder uses Java 17, the Kotlin 2.0.20 compiler/runtime bundled with Gradle 8.11.1, Android platform 35, and Build Tools 35.0.0. It compiles resources/source, runs host tests, creates DEX, aligns/signs the APK, and verifies signature/alignment without network requests. Installation minimum is API 26, target API 35; blocking has the narrower compatibility gate described above. Keep `.tools/android-user/debug.keystore` to update this debug installation; never include it in a source archive.

Conventional AGP 8.9.2/Kotlin 2.1.20 configuration remains, but that route and Android lint have not completed because of earlier dependency DNS/read-timeout failures. `scripts/build.ps1` is offline unless explicitly passed `-AllowDependencyDownloads`. Setup requires an explicit `scripts/setup_tools.py --download` opt-in.

The official tool archives were approximately 460 MB. Retained archives, dependency cache, and partial transfers totaled approximately 616 MB; actual historic data usage was higher due to retries/metadata. New prototype builds have been offline. The SDK/JDK/Gradle/cache can be reused by compatible projects on this Windows laptop. The APK runs on Android, not directly on Windows.

Host tests cover both recorded positive scroll ranges, no-swipe controls, stale/missing baselines, window identity, geometry, duplicate suppression/latching/cooldown, recording timing/budgets, and truthful foreground notification states. Static checks constrain permissions, exported components and APIs. They do not replace Android lint, real shortcut/event delivery, phone navigation tests, or battery measurements. See `docs/PROTOTYPE_STATUS.md` for the current verification record.

## References

This is original minimal Kotlin source under MIT. The inspected ShortsBlocker reference supports YouTube/Instagram identifiers, not a verified X detector. X's public recommendation repositories expose server-side ranking/filtering, not the Android player navigation implementation.

Primary Android references: [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService), [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [foreground background-start limits](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission).
