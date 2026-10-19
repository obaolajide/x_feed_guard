# Prototype status

## Current version 0.7.0

Built offline and installed on 19 October 2026, version code 9. APK `dist/x-feed-guard-0.7.0-debug.apk`: 746,023 bytes, SHA256 `f4c12d16a5a191a58fb8f8512e4e013e8883856b3c739a759f9cdd5251b670ea`. No downloads or new permissions. The detection policy remains 0.6.2's tester-confirmed implementation.

The supplied interface outline is implemented as a white home screen with a centred circular power control, X launcher, and Settings & diagnostics. The circle uses a native Switch with the full-area touch handler shared by the previously tested FullRowSwitch, a drawn power icon, and On/Off label. It is keyboard-focusable and exposes the checked state to accessibility. ON uses a black circle with white icon; OFF uses a neutral gray circle with dark icon. The system font is retained. All app accents, settings actions, native dialog accent, switches, and launcher icon are monochrome.

Settings opens its own scrollable page retaining access/background controls, observation status, samples/transition recording, export, and deletion. Notification settings is always present there, opens this app's Android notification settings, and never requests or restores permission. Returning from system settings preserves the settings page; its Back control and Android Back return home. Launcher/shortcut intents return to the power screen. Export metadata now reads the installed app version instead of the stale hardcoded version.

Offline policy/status/timing tests, Kotlin compilation, source/privacy/XML checks, signature v2/v3, and alignment passed. Earlier UI checks caught a nonresponsive native circular touch area; the final control shares the full-area Switch handling. Its OFF/ON test passed with both saved preferences updated. A later navigation check exposed settings controls moving as Android reconnected accessibility; the reconnect button now reserves its space while hidden so the notification target remains stationary. Final on-device checks passed: the power circle switched ON and OFF with matching UI and observing/blocking preferences; Settings & diagnostics opened; Notification settings launched Android's app notification controls; returning from Android preserved the settings page; its Back control returned home; X launched the official com.twitter.android app. Only app screens were captured. Home ON/OFF and settings PNGs under `dist/qa/prototype-0.7.0-controls*.png` were visually inspected.

Final state was explicitly restored to observing=true/blocking=true. Android reports the accessibility service bound, an empty crashed-services list, and ProtectionService foreground=true. Notifications remain denied (POST_NOTIFICATIONS granted=false). Package replacement had restored the app's floating shortcut target; only accessibility_button_targets was cleared back to its prior OFF state after checking that it contained solely X Feed Guard. The enabled accessibility-services setting was identical before/after; the service grant and source shortcut feature remain intact. No accessibility grant was enabled through secure settings.

No new live video-swipe test was performed for this UI-only release. The existing 0.6.2 live test and unchanged detection policy remain the evidence for blocking; broader regressions, reboot/OEM-kill recovery, battery/long-term behavior, conventional Gradle, and Android lint remain unverified.

## Version 0.6.2 history

Built offline and installed on 19 October 2026. APK `dist/x-feed-guard-0.6.2-debug.apk`: 741,927 bytes, SHA256 `27370407fbee832f458741b4f3a1a47849c6646f3b01d430f97e5361161737ee`. Phone reports version code 8/name 0.6.2. No downloads, new permissions, or notification-setting changes.

The user reported that blocking worked after clearing recent apps but intermittently missed the next-video transition. That report is not a controlled cleanup-survival test. This update accepts later positive whole-page offsets using the existing viewport/source/version gates. A known viewer before the matching scroll is required. Quiet playback no longer expires that baseline merely because it produced no events. When a matching swipe arrives during loading, structural evidence is retained only in memory for up to three seconds, with bounded worker checks approximately every 250 ms. Recovery still requires the fresh active supported X viewer in the same window before one Back request. Static playback alone never arms it. Existing session latch and five-second cooldown remain; no repeated navigation loop was introduced.

Host tests pass for recorded first-page geometry, later offsets, negative/control geometry, quiet playback, delayed viewer loading, expiry, timeline/window changes, reset, opening delay, latch, and cooldown. Source/privacy/XML checks, Kotlin compilation, host timing/status tests, signature v2/v3, and alignment passed. Installation preserved observing=true, blocking=true, backgroundEnabled=true, backgroundError empty, and blockAttempts=13. This historic action count is not a new 0.6.2 success. Android briefly reported a crashed accessibility entry during replacement; the subsequent check cleared it and found the accessibility service and foreground helper in the same live process. App-scoped retained crash output was empty.

The tester subsequently confirmed the 0.6.2 live test: playback stayed open and swiping took them back. This confirms that scenario; the reply does not isolate whether the delayed-loading fallback was exercised. Recovery cannot infer a completely missing scroll event from a static viewer, and cannot guarantee loading recovery beyond its three-second evidence window. The broad X regression matrix, reboot/OEM-kill recovery, battery/long-term behavior, and conventional Gradle/lint remain unverified.

## Version 0.6.1 history

Built and installed offline on 19 October 2026. APK `dist/x-feed-guard-0.6.1-debug.apk`: 741,927 bytes, SHA256 `fe75f694ed3c581113f2bcbd2180aea4b8c54858928369b74a49caccaa10845d`. Phone reports version code 7/name 0.6.1, minimum API 26, target API 35. Signature v2/v3, alignment, host policy/timing/notification-status tests, and source/privacy/XML checks passed. No further dependency downloads were performed. Four explicit background/notification/boot permissions are now declared; no Internet permission is present.

### Root cause of the latest failure

Version 0.5 recorded a new accepted Back, then Android terminated its process at device-reported 13:58:21 with OTHER KILLS BY SYSTEM / SwipeUpClean. The accessibility service was left in Android's crashed-services set, so neither button callbacks nor guard inspection could operate. Current-process logs were from the subsequently opened Activity, not the terminated service; no app fatal stack was found in the inspected crash buffer. The foreground helper addresses process priority/visibility, not a demonstrated app exception.

### Changes and verification

- ProtectionService is a non-exported specialUse foreground service with an ongoing low-importance notification and Pause/Resume action. It stays available while protection is paused. Tap opens MainActivity independently of the accessibility button. Android reports isForeground=true, notification id 7, type 0x40000000, startRequested=true, stopIfKilled=false and startCommandResult=1 (START_STICKY). No wake lock or alarm/watchdog loop was added. Status refresh uses a non-waking Handler every ten seconds and updates only when state changes.
- Notification states explicitly distinguish paused, disconnected, unsupported, recording and active. Host tests passed for the priority of those states. The user allowed notifications; package runtime permission POST_NOTIFICATIONS is granted=true. The notification was visible as an ongoing status icon on the visually inspected control screenshot.
- A non-exported BootReceiver handles completed boot and this package's replacement, respecting the saved background preference. Boot, reboot, and post-kill restoration are implemented but NOT phone-verified. Force stop/OEM cleanup can still defeat normal recovery; no always-on guarantee is established.
- On the initial 0.6 installation, Android rebound accessibility and cleared the crashed flag. A foreground-service observation showed the same helper process alive for over seven minutes, without a new accessibility crashed flag. This is limited-duration evidence, not a long-term reliability test.
- FullRowSwitch retains native Switch accessibility/performClick behavior but recognizes a tap over the whole row and rejects movement/cancellation. Early automated checks were invalidated by changing foreground screens and one centre tap failed before the patch. A coordinated hands-off test on 0.6.1 passed launcher open, one visible Protection switch, centre-row OFF updating both UI and observing/blocking=false, then centre-row ON restoring both=true. Protection was left enabled. The ongoing foreground service remained active while paused and after resuming, with an empty crashed-services list.
- Tester explicitly confirmed that the accessibility button now opens Protection controls after foreground support was added. No overlay permission or secure accessibility-setting write was used.
- Saved samples/transition SHA256 values were unchanged across the 0.6 and 0.6.1 installs. Own-app screenshot `dist/qa/prototype-0.6.1-controls.png` was visually inspected; the compact screen fits, system typography is preserved, and the notification-permission button is hidden after grant. No screenshot of X or private content was collected.
- The tester confirmed the final 0.6.1 check: ordinary playback stayed open and a swipe to the next video exited the viewer. The subsequent read-only check found blockAttempts increased from 4 to 5 with lastBackAccepted=true, observing/blocking=true, backgroundEnabled=true, backgroundError empty, accessibility bound with no crashed services, and ProtectionService still in the foreground. This verifies the narrow live scenario alongside the tester-confirmed shortcut and automated control tests.

### Remaining verification

Wider X regressions and longer background behavior remain to be tested. Xiaomi autostart/No restrictions choices remain unverified independently; one earlier autostart-list inspection did not locate the app. Android lint and the conventional Gradle build are still unverified. The actual foreground notification/helper is verified; do not infer reboot/kill survival or guaranteed always-on behavior from it. The earlier forced-kill test on 0.4 required manual service OFF/ON; it was not repeated on this version.

## Version 0.5.0 history

Built offline on 19 October 2026. Final APK `dist/x-feed-guard-0.5.0-debug.apk`: 737,831 bytes, SHA256 `0dddcb98307900dfe69c8a308f13a2fde0cdb8c770569a6ec5a94ef5a8bfaf0c`. Signature v2/v3, alignment, host swipe-policy/trace tests, source/privacy/API/XML checks, and APK ZIP/DEX descriptor checks passed. No additional dependencies or permissions were downloaded/added.

USB authorization was restored and version 0.5.0 installed successfully. The phone reports code 5/name 0.5.0, min API 26, target API 35. Saved screen/transition SHA256 values were unchanged across the update. `scripts/check_device_controls.py --test-switch --screenshot dist/qa/prototype-0.5.0-controls.png` passed: launcher warm-opened the compact screen, exactly one Protection switch was visible, OFF updated the UI plus observing/blocking to false, and ON restored the UI and both preferences to true. The own-app screenshot was visually inspected: the control card, status, Open X and Settings & diagnostics fit above the fold. The system font is preserved.

Android reports requestA11yBtn=true for the bound service and an empty crashed-services list. Scoped current-process logs showed no AndroidRuntime fatal exception or ANR in the inspected retained output. Actual home/floating shortcut taps and a new real playback/swipe through the background worker are pending user feedback; the historical blockStatus must not be counted as a new 0.5 action.
Changes:

- Compact launcher/control screen with a single always-responsive Protection switch and Open X. The switch atomically sets observing/blocking together, and Off cancels capture. Missing access is explained rather than silently disabling the switch. Settings/diagnostics are collapsed; returning via launcher/shortcut resets to the control screen.
- Accessibility configuration requests the accessibility button, and the service registers a click callback that opens MainActivity with NEW_TASK/CLEAR_TOP/SINGLE_TOP. The launcher activity is singleTop. Actual OEM shortcut taps remain unverified.
- A single background worker performs normal guard inspections rather than blocking the main/UI thread. Queue capacity is four, content events coalesce to one outstanding task, copied events are recycled on completion/rejection/shutdown, stale events are rejected, and a generation check invalidates work after toggles/capture/lifecycle changes. Existing X/version/layout/baseline/cooldown checks and a fresh foreground check before Back remain. Sampling itself is still explicit and main-thread based.
- Sample counts are parsed only when a successful save/delete changes the persisted revision, rather than rereading samples.json every UI tick.
- A local Back-attempt counter, timestamp and accepted flag distinguish new attempts from the historical status. These metadata do not prove the resulting X navigation, and record no post text or hierarchy.

Next verification: user taps the shortcut, confirms the controls open, watches one normal video, and swipes once. Compare a new blockAttempts/lastBlockAt/lastBackAccepted record with their observed navigation. No new 0.5 action record was present at the first post-install log check. Do not repeat the earlier forced process-kill test: it required manual accessibility recovery.

## Version 0.4.0 history

Built and installed offline on 19 October 2026. APK `dist/x-feed-guard-0.4.0-debug.apk`: 733,735 bytes, SHA256 `e7baaea64d8ef7290c6316ebfd62879becc6f5735314469bbf888db24455343e`. Signature v2/v3, alignment, host policy/timing tests, and XML/privacy/API checks pass. Installation reports code 4/name 0.4.0, min API 26, target API 35. Saved samples and transition SHA256 values were unchanged across the update. No dependency downloads or additional app permissions were required.

The user reported that blocking did work, but Accessibility showed an error. Earlier diagnostics confirmed a past Xiaomi `SwipeUpClean` termination, not an app fatal exception. Version 0.4 removes preference writes from service connection/interruption/unbind/destruction callbacks and the Activity refresh, preserving the explicit blocking choice while Android access is unavailable. New installs still default off. Explicit user pause/off still disables blocking; runtime checks are unchanged. Static regression checks pin both preference-loss paths.

A new Keep running card links to Xiaomi autostart (component verified present on the phone), this app's Android App info/battery settings, and the standard battery-optimization list. The displayed battery exemption is the standard Android API result, not verification of Xiaomi's separate settings. The user reported configuring background autostart and No restrictions; those vendor choices have not been independently verified.

### Recovery verification and limits

A controlled app-owned SIGKILL test deliberately terminated the old process. Android marked the accessibility service crashed and did NOT automatically rebind it. This reproduces a failure mode and is not a successful recovery test. The blocking preference remained true throughout. Restricted-setting permission was initially default with recent rejected access; after the user's manual action it reports allow. A fully verified manual OFF step removed the service from enabled services and cleared the crashed list; a separately verified ON step rebound the service. No accessibility permission or battery setting was written over ADB.

After that manual reconnection, the service is bound with an empty crashed-services list, observing=true and blocking=true. The own-app screen was opened and then left for Home; after 15 seconds in the background the same service/process remained bound and preferences stayed enabled. This verifies normal background operation and preference retention, not continuous or automatic recovery after a kill, reboot, force stop, OEM cleanup, or long idle period. Do not claim always-on reliability or that the accessibility warning is permanently fixed. Broader blocking regressions, battery use, Android lint, revised layout, and long-duration background behavior remain unverified.

## Version 0.3.0 history

Built and installed offline on 19 October 2026. APK `dist/x-feed-guard-0.3.0-debug.apk`: 733,735 bytes, SHA256 `fa855cdcd1abb1172ba7401fb7a26e678165fbcfc86d030340df5c7447f52933`. Signature v2/v3 and ZIP alignment checks pass. The phone reports version code 3/name 0.3.0, min API 26, target API 35. The update cold-launched MainActivity successfully. Both saved-sample and transition-file SHA256 values were identical before/after update. Observation remains enabled; blocking was read back as false. The notification shade was foreground at the final focus check, so no screenshot was taken and the revised layout is not yet visually verified on the phone.

The repeated positive recording contains 19 untruncated snapshots and 108 events. At 20.645 seconds, the full-screen scroll reports Y=1600 / maxY=20800. This repeats the earlier Y=1600 / maxY=19200 candidate. The no-swipe control contains neither. The repeated test also shows a return to nested timeline structures without a SeekBar by 36.899 seconds. See ignored `captures/2026-10-19/repeat-positive-0.2/inspection.md`.

Version 0.3 adds a manual **Block video swiping** switch, off by default and after service reconnection. Its narrow experimental rule checks the tested device/Android/X version and portrait bounds, recent viewer baseline, fresh current viewer structure, X foreground/window identity, full-screen scroll source, one-height positive offset, zero horizontal scroll, and a multi-height range. It attempts Back once, latches until a recognized timeline, and retains a five-second cooldown. Sample recordings suspend blocking. Missing/ambiguous structures cause no action. No dependency downloads were required.

Host tests passed for both positive scroll ranges, every control scroll value, nonmatching geometry, unavailable metadata, baseline freshness, window identity, latching, and cooldown, plus the earlier trace timing/budget tests. XML/privacy/API/source checks passed. Actual automatic Back, preservation of ordinary playback, real-screen false positives, lifecycle recovery, and battery behavior still require phone testing. The broad regression matrix must pass before describing blocking as reliable. Android lint and the conventional Gradle build remain unverified. The initial sandboxed resource compiler crashed; running the same offline local build outside that sandbox succeeded.

## Earlier observations and builds
## Version 0.2.0 update

Built and installed offline on 19 October 2026. APK: `dist/x-feed-guard-0.2.0-debug.apk`, 725,543 bytes. SHA256: `8a498e68b782c7fe7fce44ae9a47a3d0c55d13c00e1d0fcd4c4b4f94429a5a9b`. APK signature and alignment checks pass. The installed version is code 2/name 0.2.0, minimum API 26, target API 35.

The existing screen-sample file had the same SHA256 before and after `adb install -r`. Accessibility authorization was retained. Launching with CLEAR_TOP brought the native main activity forward; its initial layout was visually inspected on the phone. Observe X was enabled and the transition button was visible. No X screenshot was captured.

The new 40-second transition recording captures bounded structural/event data and supplies timed text prompts. Host JVM tests passed for guided phase boundaries, content throttling under a flood, preserving a separate scroll/window budget, event caps, and deadline rejection. No further dependency downloads were performed. Real transition recording, prompt delivery while X is foreground, cancellation, and export still require device testing. Automatic blocking remains absent.

## Version 0.1 history

The first 0.2 transition recording has since been retrieved and inspected: exactly 40 seconds, 19 snapshots, and 105 retained events including 8 scroll events. A full-screen scroll at 31.479 seconds reports Y=1600 and maxY=19200, followed by a change in the viewer's structural snapshot. This is a candidate video-page transition signal, not a validated rule. The tester confirmed switching videos and returning to the timeline; exact correspondence between actions and recorded events is still ambiguous. See ignored `captures/2026-10-19/transition-0.2/inspection.md`. A matched no-swipe control is the next validation step.

Source and APK prepared on 19 October 2026. The initial download was cancelled after approximately 2 MiB. The user subsequently authorized the required tool downloads. All five tool archives (approximately 460 MB) were downloaded, checksum-verified, and extracted project-locally.

The physical phone is Redmi 13C model 23106RN0DA, Android 15 / API 35, with X 12.35.0-prod.01 installed. The prototype was installed over authorized ADB and cold-launched successfully. Later USB reconnection required restarting ADB with `ADB_USB_LEGACY=1`. No driver downloads or changes were made.

## Built APK

- Path: `dist/x-feed-guard-0.1.0-debug.apk`
- Size: 717,351 bytes, approximately 0.72 MB.
- SHA256: `08fc2729c0a8b929a87251b2f1452b701adab76b5a46a2aec354e606ebd21716`
- Package/version: `app.xfeedguard`, version 0.1.0, version code 1.
- Minimum Android API 26; target/compile API 35.
- APK signature schemes v2 and v3 verified; alignment and ZIP integrity verified.
- Activity and service DEX descriptors verified; no application uses-permission declarations.

The successful offline build uses `scripts/build_minimal.py`: Java 17, Kotlin 2.0.20 already bundled with Gradle 8.11.1, and SDK Build Tools 35.0.0. Resources, generated Java, Kotlin, DEX, alignment, and signing all completed successfully. It makes no network requests.

The conventional Gradle/Kotlin 2.1.20 build failed on dependency DNS/read timeouts; Android lint did not complete. Approximately 111 MB of reusable dependency cache and 45 MB of incomplete transfers were retained. Together with the tool archives, retained downloaded files total approximately 616 MB. This is not an exact network/data meter; earlier attempts, metadata, and retries may increase actual usage. Gradle daemons were stopped before the offline build.

## Ready in source

- Native Kotlin activity with neutral surfaces, system typography, blue controls, and a single observation toggle.
- Accessibility setup disclosure and permission/connection status.
- Service restricted to X events, enabled by the user's observation preference.
- A user-requested snapshot after 12 seconds, restricted to X's active window.
- Three user-supplied labels: timeline, single video, and video feed.
- Up to five structural snapshots per label, private atomic storage, deletion, and explicit file export.
- Structural comparison utility that avoids claiming that sample differences prove reliable detection.
- Project-local setup and build scripts with explicit opt-in flags for network downloads.

## Checked offline

Android XML parses. Script syntax checks pass. Manifest/service checks confirm no Internet permission, X-only event configuration, and no gesture capability request. Comparison cases cover missing labels, identical structures, a feed-only candidate, and a candidate also present on a safe screen. These checks do not compile or execute the Kotlin app.

## Not yet verified

- Android lint and the conventional Gradle build.
- Native layout, file export through the picker, or exact capture timing on the phone.
- Any distinction between individual video playback and the endless video feed.
- Automatic navigation, battery use, or service recovery.

This prototype has no automatic detector and never invokes Back. A physical-device observation experiment is required before implementing blocking. Identical accessible structures may make the precise distinction impossible with this approach.

## First saved samples inspected

Three user-labeled snapshots were retrieved from the app's private storage over ADB: one timeline (106 visible nodes), one single video (62), and one feed (69). None was truncated; all had zero resource IDs. This confirms that the service and manual saved-sample path operated on the phone. It does not verify the export picker or precise timer duration.

The two video samples expose the same full-screen scrollable structure and several identical control positions. Their SeekBar/ProgressBar difference occurs at the same bounds and may reflect playback state. No reliable feed-only signal was established. The local, ignored inspection report is `captures/2026-10-19/inspection.md`. A sequence around a swipe is the next useful experiment; automatic exit remains disabled.

## First device checklist

The APK is installed. Manually enable access in Android Settings. Verify that the observation toggle is disabled before access, works after connection, and cancels a pending capture when turned off. Capture all three labeled screen types at least three times. Verify that a capture fails safely if another app is in front. Export the samples and inspect them for unwanted content; only structural metadata should be present. Delete them and confirm that export becomes unavailable. Revoke accessibility permission and confirm that the app explains the missing access.

Installation and launch are verified. Do not claim validated capture, detection, or blocking until the respective physical-device checks have passed. No screenshot of X or other unrelated phone content was collected during verification.

## No-swipe control inspected

The 40-second control recording contains 15 untruncated snapshots (3 skipped), 44 retained events including 5 scroll events, and 12 identical video-viewer structures followed by a timeline-like snapshot at 31.576 seconds. The candidate full-screen Y=1600/maxY=19200 event is absent; the return scroll instead reports Y=0/maxY=100. This supports the candidate but does not validate a detector with only one positive and one control. A repeated positive test is next. See ignored captures/2026-10-19/control-0.2/inspection.md. No blocking, build, installation, or download was performed for this comparison.
