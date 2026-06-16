# Recording Storage Quota Plan - 2026-06-16

## Problem

Recording currently uses the selected recording folder without a configurable storage budget. This can fill the recording target indefinitely, especially when loop recording is expected in car use.

The settings UI needs two new recording controls under recording duration:

- A storage quota slider from `10%` to `90%`
- Default quota: `60%`
- A `Loop recording` toggle, checked by default
- Toggle label detail: `Loop recording (Stop recording if off)`

When loop recording is enabled, the app should delete the oldest app recordings once the configured quota is exceeded so that new segments can continue.

When loop recording is disabled, recording should not start if there is not enough allocated space. If recording cannot start or cannot continue safely, recording must stop, the recording button must be toggled off, and the user must see a warning toast.

## Current Code Paths

Relevant files:

- `app/src/main/res/layout/dialog_settings_section_settings.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-de/strings.xml`
- `app/src/main/java/com/drivehub/kamera/MainActivity.java`
- `app/src/main/java/com/drivehub/kamera/UiPrefs.java`
- `app/src/main/java/com/drivehub/kamera/RecordingService.java`
- `app/src/main/java/com/drivehub/kamera/RecordingStorageManager.java`

Current behavior:

1. Recording duration is stored through `UiPrefs.KEY_RECORDING_DURATION_MIN`.
2. The recording folder is either the default Downloads subfolder or a persisted SAF tree URI.
3. `RecordingService` writes complete segment files for camera indexes `15`, `14`, `16`, and `17`.
4. The service does not calculate available quota before starting or before continuing to the next segment.
5. The service does not delete old recordings.
6. Toasts are currently shown from activity/UI code, while `RecordingService` publishes state through `ACTION_STATE_CHANGED`.

## Target Behavior

Settings behavior:

- Under recording duration, show a quota row with a percentage label and slider.
- Slider min: `10`
- Slider max: `90`
- Slider step: `1`
- Default: `60`
- Persist the value immediately in `UiPrefs`.
- Under the quota slider, show a toggle named `Loop recording (Stop recording if off)`.
- The toggle is checked by default and persists immediately.

Recording behavior:

- At recording start, calculate the maximum bytes allowed for app recordings based on the configured percentage of free/available storage for the selected target.
- Before starting a new segment, reserve enough room for the next segment.
- If loop recording is enabled and the app recording folder exceeds the quota, delete oldest app-owned segment groups until the target is under quota.
- If loop recording is disabled and available quota is insufficient, do not start recording, publish recording state as off, and show a warning toast.
- If recording is already active and the next segment cannot fit, stop recording cleanly, publish recording state as off, and show a warning toast.
- Never delete files outside the app recording naming pattern.
- Prefer deleting complete segment groups across all camera files so playback sets stay consistent.

## Storage Quota Definition

Use this formula for filesystem targets:

```text
quotaBytes = floor((freeBytes + currentAppRecordingBytes) * quotaPercent / 100)
```

Rationale:

- `freeBytes` alone shrinks as recordings accumulate, which would make the quota unstable.
- Adding `currentAppRecordingBytes` gives the app a stable budget relative to the space it can reclaim.
- The quota applies only to files the app owns and recognizes in the recording folder.

For SAF tree URI targets:

- Use `DocumentFile` listing for app recording files.
- Use `StorageManager`/`StorageStatsManager` only if a reliable volume can be resolved.
- If reliable free-space data cannot be resolved for a SAF tree, fallback to a conservative preflight:
  - Allow loop deletion based on current app bytes.
  - Stop recording if an actual write/copy fails.
  - Publish an explicit warning.

## Recording File Ownership

Treat only these files as app-managed recordings:

```text
yyyyMMdd_HHmmss_NN_14.mp4
yyyyMMdd_HHmmss_NN_15.mp4
yyyyMMdd_HHmmss_NN_16.mp4
yyyyMMdd_HHmmss_NN_17.mp4
```

Segment group key:

```text
yyyyMMdd_HHmmss_NN
```

Deletion rules:

1. List only files matching the app recording pattern.
2. Group files by segment group key.
3. Sort groups by oldest timestamp, then segment index.
4. Delete whole groups oldest first.
5. If a group is incomplete, still delete it as a group because it cannot represent a complete four-camera segment.
6. Never delete the segment currently being written.

## Proposed Design

Add storage policy helpers inside the recording area without broad architecture changes:

```java
final class RecordingStoragePolicy {
    boolean ensureSpaceForNextSegment(Context context, File targetDir, long estimatedNextSegmentBytes);
    boolean pruneIfNeeded(Context context, File targetDir, long estimatedNextSegmentBytes);
}
```

Keep the first implementation simple and package-private. The helper should:

- Read `UiPrefs.KEY_RECORDING_STORAGE_QUOTA_PERCENT`
- Read `UiPrefs.KEY_LOOP_RECORDING`
- Calculate current app recording bytes
- Calculate available quota bytes
- Delete oldest segment groups when loop recording is enabled
- Return a failure reason when recording should stop

Avoid deleting during active native writes. Only prune before a segment starts and after a segment is fully finalized/copied.

## Segment Size Estimation

Use a conservative estimate before each segment starts:

```text
estimatedBytes = cameras * bitrateBytesPerSecond * segmentDurationSeconds * safetyFactor
```

Initial values:

- Cameras: `4`
- Bitrate: `2_500_000 bits/s`
- Safety factor: `1.20`

Formula:

```text
estimatedBytes = 4 * (2_500_000 / 8) * segmentDurationSeconds * 1.20
```

This estimate avoids starting a segment that cannot fit. After the segment completes, enforce quota using actual file sizes.

## Implementation Plan

1. Add preferences.

   In `UiPrefs.java`:

   - `KEY_RECORDING_STORAGE_QUOTA_PERCENT`
   - `KEY_LOOP_RECORDING`
   - `getRecordingStorageQuotaPercent(...)`
   - `isLoopRecordingEnabled(...)`
   - Clamp quota to `10..90`
   - Default quota: `60`
   - Default loop recording: `true`

2. Add settings UI.

   In `dialog_settings_section_settings.xml`, under `rgRecordingDuration`:

   - Add a label row showing `Recording storage limit` and current percentage.
   - Add a `SeekBar` with max representing `10..90`.
   - Add a hint string explaining the quota applies to app recordings.
   - Add `Switch` for `Loop recording (Stop recording if off)`.

   In `MainActivity.showSettingsDialog(...)`:

   - Bind the slider to the persisted quota.
   - Update the percentage label live.
   - Bind the loop toggle to the persisted boolean.

3. Add strings.

   Add English and German strings for:

   - Recording storage limit
   - Recording storage limit value format
   - Recording storage limit hint
   - Loop recording label
   - Not enough recording storage warning
   - Recording stopped because storage is full warning

4. Add a storage policy helper.

   Implement app recording discovery for `File` targets first:

   - Match `.mp4` app recording names.
   - Group by segment key.
   - Sum group sizes.
   - Sort groups by timestamp/index.
   - Delete oldest groups until enough space exists.

   Keep SAF support explicit:

   - Use `DocumentFile` listing when recording to a tree URI.
   - If free-space cannot be computed, rely on write failure and post-segment cleanup.
   - Do not silently delete non-matching files.

5. Integrate with `RecordingService`.

   Before first segment and before each subsequent segment:

   - Estimate next segment bytes.
   - Ask policy if recording can proceed.
   - If loop recording is enabled, prune first.
   - If loop recording is disabled and space is insufficient, stop before writing.

   After each segment completes:

   - Enforce quota using actual segment sizes.
   - If loop recording is disabled and actual files pushed usage beyond quota, stop before the next segment.

6. User warning delivery.

   Add an explicit recording warning broadcast:

   ```text
   ACTION_RECORDING_WARNING
   EXTRA_WARNING_CODE
   ```

   `MainActivity` receives it and shows the toast. This keeps UI toast ownership in the activity instead of showing toast directly from a service.

   Warning codes:

   - `not_enough_space_to_start`
   - `recording_stopped_storage_full`
   - `recording_prune_failed`

7. Button state.

   When storage prevents recording:

   - Call the same state path used by `finishRecording()`
   - Publish `ACTION_STATE_CHANGED` with `EXTRA_IS_RECORDING=false`
   - Ensure main and overlay recording buttons sync to white/off
   - Clear the recording timer start value

8. Testing.

   Add targeted manual and emulator tests:

   - Default settings show quota `60%` and loop recording checked.
   - Slider persists after reopening settings.
   - Loop toggle persists after reopening settings.
   - With loop enabled and artificial old recordings present, oldest app recordings are deleted first.
   - With loop disabled and quota too low, recording does not start and warning toast is shown.
   - Active recording stops before the next segment if quota becomes insufficient.
   - Non-app files in the recording folder are never deleted.
   - Debug recording from demo MP4 still produces four valid camera MP4 files.
   - Release build does not include debug demo assets.

## Production Safety Notes

- Do not delete files based only on extension.
- Do not delete while native recorders are actively writing a segment.
- Do not block the main thread while scanning or pruning recordings.
- Use synchronous preference commits only when the next immediate action depends on the value.
- Keep quota enforcement inside the recording service/helper so main and overlay controls behave consistently.
- Log every prune decision with segment key, bytes deleted, and remaining usage.
- Treat delete failures as a stop condition when loop recording cannot free enough space.

## Acceptance Criteria

- Settings shows a `10%..90%` recording storage limit slider, defaulting to `60%`.
- Settings shows `Loop recording (Stop recording if off)`, checked by default.
- Recording respects the configured quota for app-owned recording files.
- Loop mode deletes oldest app recording segment groups when needed.
- Non-loop mode stops or refuses recording when quota is insufficient.
- Main and overlay recording buttons turn off when recording is stopped by storage policy.
- The user sees a warning toast when recording cannot start or is stopped due to storage limits.
- Existing preview, overlay, recording timer, and demo-video debug recording behavior remain intact.
