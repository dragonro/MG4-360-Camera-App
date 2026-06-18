# MG4 Android 9 Recording Fix Plan - 2026-06-17

## Problem

When the app runs in the MG4 car on Android 9, recording appears to start from the UI, but no recording files are produced.

The current code has several paths where recording can fail without a clear user-visible error:

- Runtime storage permission may not be requested from the current in-app settings flow.
- The default recording folder is `Downloads/mg4_cam_records`, which requires `WRITE_EXTERNAL_STORAGE` on Android 9.
- The native recorder reports success as soon as the background recording thread is created, before `/dev/video*`, the output file, the encoder, and the muxer are actually opened.
- If the native thread fails after startup, `RecordingService` does not receive a failure callback and can keep the UI in a misleading recording state.
- If one native camera slot fails, partial files may be deleted, but the user only sees generic storage/recording state behavior.

Permissions are likely part of the issue, but they are not the only risk. The fix must make recording startup verifiable end to end.

## Current Code Paths

Relevant files:

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/drivehub/kamera/MainActivity.java`
- `app/src/main/java/com/drivehub/kamera/SettingsActivity.java`
- `app/src/main/java/com/drivehub/kamera/RecordingService.java`
- `app/src/main/java/com/drivehub/kamera/RecordingStorageManager.java`
- `app/src/main/java/com/drivehub/kamera/RecordingStoragePolicy.java`
- `app/src/main/java/com/drivehub/kamera/CameraProbe.java`
- `app/src/main/cpp/cameraprobe_record.cpp`

Current behavior:

1. The manifest declares `WRITE_EXTERNAL_STORAGE` and `READ_EXTERNAL_STORAGE`.
2. `SettingsActivity` has a `WRITE_EXTERNAL_STORAGE` permission check, but the current app settings dialog in `MainActivity` does not request storage permission when enabling recording or when pressing the record button.
3. `RecordingStorageManager.getWritableBaseDir(...)` writes to public Downloads.
4. `RecordingService.startRecording(...)` starts a foreground service and sets recording state on before native capture has proven that files are being written.
5. `CameraProbe.startMp4Record(...)` returns `true` after `pthread_create(...)`, not after the native thread opens `/dev/videoX` and output MP4 successfully.
6. Native failures are logged with tag `CameraMp4Record`, but there is no callback to Java and no user-visible failure reason.

## Likely Failure Modes On Android 9

### 1. Missing Runtime Storage Permission

Android 9 still requires runtime approval for `WRITE_EXTERNAL_STORAGE` when writing to public external storage such as Downloads.

The app declares the permission, but the current `MainActivity` settings flow only stores:

```text
UiPrefs.KEY_ENABLE_RECORDING_BUTTON
```

It does not request or verify `WRITE_EXTERNAL_STORAGE`.

Expected symptom:

- Record button turns red briefly or stays red.
- No files appear in `Downloads/mg4_cam_records`.
- Native recorder may fail opening the output file.

### 2. Native Recorder Startup Is Asynchronous

The native JNI method starts a thread and immediately returns success:

```text
CameraProbe.startMp4Record(...) -> pthread_create(...) -> true
```

The actual work happens later in `recordThread(...)`:

- open `/dev/videoX`
- query V4L2 format
- allocate buffers
- start stream
- create encoder
- open output file
- create muxer
- write frames

Any failure after `pthread_create(...)` is currently invisible to `RecordingService`.

Expected symptom:

- UI shows recording active.
- Native logs contain the real failure.
- Java service does not know recording failed.
- No final MP4 files are produced.

### 3. Output Directory Writability Is Not Strongly Verified

`RecordingService.canStartInitialRecording(...)` creates the folder and checks quota, but it does not perform a real write/delete probe in the target directory before starting native recording.

Expected symptom:

- Directory exists or `mkdirs()` succeeds, but file creation by native muxer fails.
- The user sees no precise message.

### 4. Camera Device Access May Differ From Preview Access

Preview and recording use different native code paths. Preview may work while recording fails if:

- recording opens the wrong camera indices,
- the device allows preview-style reads but rejects concurrent opens,
- `/dev/video*` permissions differ for the app process,
- a camera device is already held by preview or another service,
- the native recorder assumes a format that the MG4 camera node does not expose.

Expected symptom:

- Main camera image is visible.
- Recording produces no files.
- Logs show `open /dev/videoX failed`, `VIDIOC_* failed`, encoder configuration errors, or muxer failures.

## Target Behavior

Recording on Android 9 should be explicit and observable:

- Before recording starts, verify that required runtime permissions are granted.
- If permission is missing, request it from the active UI path and do not start recording until granted.
- Before native recording starts, verify the target folder is writable with a small create/delete probe.
- Native start should only be considered successful after each slot confirms it opened the device, opened the output file, configured encoder/muxer, and wrote at least one frame or reached a clear ready state.
- If any of the four cameras fails, stop all slots, delete partial files, publish recording state off, and show a specific warning.
- The recording timer and red button must only stay active when recording is truly running.
- Logs should include enough detail to diagnose MG4-specific failures from `adb logcat`.

## Proposed Fix

### 1. Centralize Recording Permission Checks

Add a small helper, for example `RecordingPermissions`, with:

```java
static boolean needsLegacyStoragePermission()
static boolean hasRequiredStoragePermission(Context context)
static String[] requiredPermissions()
```

Rules:

- On Android 9 and below, require `WRITE_EXTERNAL_STORAGE` for the default public Downloads folder.
- If a SAF tree URI is selected, rely on persisted URI permission instead of legacy storage permission.
- On Android 10+, do not request legacy storage permission for the default app behavior unless a later target path requires it.

### 2. Request Permission From `MainActivity`

Update the current settings dialog and record button flow in `MainActivity`:

- When enabling recording, check storage permission.
- If missing, request `WRITE_EXTERNAL_STORAGE`.
- If denied, leave recording disabled and show a warning.
- When pressing the record button, re-check permission before starting `RecordingService`.
- If permission is missing, request it and start only after grant.

Keep `SettingsActivity` behavior aligned or remove duplicated logic later. The current active path is `MainActivity`, so it must be fixed first.

### 3. Add Storage Write Probe

Before starting native recording, `RecordingService.canStartInitialRecording(...)` should verify the output target:

- create a temporary file in the selected segment directory,
- write a few bytes,
- flush/close,
- delete it,
- fail with a specific warning if any step fails.

Add warning codes and strings for:

- missing storage permission,
- recording folder not writable,
- native camera recording failed.

### 4. Make Native Start Synchronous Enough To Trust

Change the native recorder contract so `CameraProbe.startMp4Record(...)` does not return success before startup is proven.

Recommended implementation:

- Add startup state to each native slot:
  - `STARTING`
  - `READY`
  - `FAILED`
  - `RUNNING`
  - `STOPPED`
- Use a `pthread_mutex_t` and `pthread_cond_t` per slot.
- `startMp4Record(...)` starts the thread, then waits with a short timeout for `READY` or `FAILED`.
- In `recordThread(...)`, set `READY` only after:
  - `/dev/videoX` opened,
  - V4L2 stream started,
  - encoder started,
  - output file opened,
  - muxer initialized enough to accept samples.
- On any startup failure, set `FAILED` with an error code and wake the waiting Java call.
- Return `false` to Java if startup fails.

This keeps the Java all-or-nothing four-camera logic meaningful.

### 5. Add Native Failure Diagnostics To Java

Extend `CameraProbe` with a minimal diagnostic API:

```java
public static native String getLastRecordError(int slot);
```

Native code should store short error strings such as:

- `open /dev/video15 failed: EACCES`
- `open output file failed: EACCES`
- `VIDIOC_STREAMON failed`
- `AMediaCodec_configure failed`
- `AMediaMuxer_new failed`

`RecordingService` should log those messages and publish a user warning when startup fails.

### 6. Improve Recording State Accuracy

Move `setRecordingState(true)` until after all four native slots have confirmed startup for the first segment.

Current state is set before `recordOnce()` proves the segment started. The UI should only show recording active after `startSegment(...)` succeeds.

Implementation detail:

- Start the worker.
- Worker calls `startSegment(...)`.
- When first segment successfully starts, publish recording state true.
- If first segment fails, publish warning and keep state false.
- On stop, publish state false as today.

### 7. Preserve Emulator Behavior

Keep debug demo recording behavior separate:

- Debug builds with test video sources should continue using the MP4 demo path.
- The Android 9 permission fix should not block debug demo recording into app-private cache or selected SAF destinations.
- Release builds in the car should use native recording only.

## Diagnostic Commands For The Car

Run these on the MG4 after attempting to record:

```sh
adb logcat -d | grep -E "RecordingService|CameraMp4Record|RecordingStoragePolicy|Permission"
adb shell dumpsys package com.drivehub.dualbytes.kamera | grep -E "WRITE_EXTERNAL_STORAGE|READ_EXTERNAL_STORAGE|granted="
adb shell ls -la /sdcard/Download/mg4_cam_records
adb shell ls -la /dev/video14 /dev/video15 /dev/video16 /dev/video17
```

Expected useful failures:

- `open output file failed: 13 (Permission denied)`
- `open /dev/video15 failed: 13 (Permission denied)`
- `VIDIOC_STREAMON failed`
- `AMediaCodec_configure failed`
- missing or denied `WRITE_EXTERNAL_STORAGE`

## Implementation Plan

1. Add `RecordingPermissions`.

   Include Android 9 legacy storage rules and SAF-tree exemption.

2. Wire permission handling into `MainActivity`.

   Add request code, `onRequestPermissionsResult(...)`, and pending-start behavior for the record button.

3. Add storage write probe.

   Add a helper in `RecordingStorageManager` or `RecordingService` and fail before native recording starts if the output folder is not writable.

4. Add user-facing warnings.

   Add new warning codes in `RecordingService`, strings in English and German, and display them in `MainActivity` and `OverlayService`.

5. Fix native startup contract.

   Update `cameraprobe_record.cpp` so JNI start waits for native ready/failure with a bounded timeout.

6. Add native diagnostic API.

   Store and expose last per-slot recording error.

7. Update recording state timing.

   Publish `recording=true` only once the first segment has actually started.

8. Validate on emulator.

   Ensure demo recording still works with debug sample MP4s.

9. Validate on MG4 Android 9.

   Test with permission denied, permission granted, default Downloads folder, and selected SAF folder.

10. Document operational checks.

   Add a short README troubleshooting section after the fix is implemented.

## Testing Plan

Emulator:

1. Build and install debug.
2. Enable recording.
3. Start recording from main activity and overlay.
4. Verify demo MP4 recording files are produced.
5. Confirm no legacy storage permission prompt is required for debug demo paths unless the selected folder requires it.
6. Confirm recording warnings are visible when the folder is made unwritable.

MG4 Android 9:

1. Fresh install the app.
2. Open Settings and enable recording.
3. Confirm Android asks for storage permission.
4. Deny permission and verify recording cannot start and shows a clear warning.
5. Grant permission and start recording.
6. Verify files are created in `Downloads/mg4_cam_records`.
7. Stop recording and verify all four MP4 files are playable.
8. Start recording from the overlay popup.
9. Verify the overlay timer and main timer stay synchronized.
10. Reboot the head unit and verify recording resumes only when enabled and permission is still granted.

Regression checks:

- `./gradlew assembleDebug`
- `./gradlew assembleRelease`
- Manual `adb logcat` review on MG4 for native startup success/failure logs.

## Acceptance Criteria

- On Android 9, recording cannot silently fail because storage permission is missing.
- The UI only shows active recording when at least the first segment has successfully started.
- If native camera recording fails, the app stops all slots, deletes partial files, and shows a clear warning.
- The logs identify the exact failing camera, output path, or native operation.
- Default Downloads recording and selected SAF folder recording both behave predictably.
