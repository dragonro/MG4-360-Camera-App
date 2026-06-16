# Recording Source Fix Plan - 2026-06-16

## Problem

Recording currently does not reliably match the active runtime source.

On the car, recording must capture all four physical camera feeds:

- Front: `/dev/video15`
- Right: `/dev/video14`
- Left: `/dev/video16`
- Rear: `/dev/video17`

On the emulator, recording must capture from the demo MP4 source used by preview. It should not try to record from `/dev/video*`, because those devices do not exist in the emulator.

The current `RecordingService` always attempts native `/dev/video*` recording first. If that fails in a debug build, it falls back to copying MP4 files into files named with a `.mpg` extension. That fallback is not a real recording pipeline and has several issues:

- It does not explicitly select the same debug source as preview.
- It copies existing files instead of producing a segment for the requested recording duration.
- It writes MP4 content using `.mpg` names.
- It can produce placeholders instead of playable video when the demo source is missing.
- It does not clearly separate car recording behavior from emulator/demo behavior.

## Current Code Paths

Relevant files:

- `app/src/main/java/com/drivehub/kamera/RecordingService.java`
- `app/src/main/java/com/drivehub/kamera/TestVideoSources.java`
- `app/src/main/java/com/drivehub/kamera/TestVideoPlayer.java`
- `app/src/main/java/com/drivehub/kamera/CameraProbe.java`
- `app/src/main/cpp/cameraprobe_record.cpp`
- `app/src/main/java/com/drivehub/kamera/RecordingStorageManager.java`

Current behavior:

1. `RecordingService.startSegment(...)` loops over the four camera indexes.
2. Each index calls `CameraProbe.startMp4Record(...)`.
3. Native code records from `/dev/video*` with `AMediaMuxer`.
4. If any native camera start fails, debug builds call `writeDebugFallbackRecordings(...)`.
5. The debug fallback copies `TestVideoSources.getFile(...)` to a `.mpg` target or writes a placeholder text file.

## Target Behavior

Car behavior:

- Recording uses the native recorder only.
- All four camera recorders must start successfully before the segment is considered active.
- If any camera fails to start, all already-started native recorders are stopped and the segment fails cleanly.
- Output files should use the correct extension and MIME for the native muxer output, preferably `.mp4`.
- A stop command should end the current segment quickly, not after the full segment duration.

Emulator/debug behavior:

- If debug demo video sources are active, recording bypasses native `/dev/video*`.
- Recording writes playable MP4 files derived from the same demo source used by preview.
- The same demo video can be used for all four camera indexes when no camera-specific samples exist.
- Segment duration follows the same recording duration setting as car recording.
- Stop command interrupts the segment promptly.
- The output naming matches car recording naming, but with `.mp4` files.

## Proposed Design

Introduce a small recording source abstraction inside the Java layer:

```java
interface SegmentRecorder {
    boolean startSegment(File segmentBaseDir, String segmentStamp, long durationMs);
    void stop();
}
```

Implement two concrete paths:

1. `NativeCameraSegmentRecorder`

   Uses `CameraProbe.startMp4Record(...)` for `/dev/video15`, `/dev/video14`, `/dev/video16`, and `/dev/video17`.

2. `DebugDemoVideoSegmentRecorder`

   Uses `MediaExtractor` and `MediaMuxer` to copy encoded video samples from the debug MP4 source into proper segment MP4 files. It should loop the input when the requested segment duration is longer than the source video.

Keep this abstraction package-private and local to the recording package/classes. Do not introduce a broader architecture unless later work needs it.

## Implementation Plan

1. Normalize recording output naming.

   Use `.mp4` for both native and debug recording outputs because `CameraProbe.startMp4Record(...)` uses `AMediaMuxer`.

   Update:

   - Native output path generation in `RecordingService`
   - Tree copy lookup
   - Segment cleanup
   - Debug output generation
   - DocumentFile MIME from `application/octet-stream` to `video/mp4`

2. Add an explicit recording source selector.

   Add a method similar to:

   ```java
   private boolean shouldUseDebugDemoRecording() {
       return BuildConfig.DEBUG && TestVideoSources.shouldUse(this);
   }
   ```

   In debug mode with available demo video sources, use the demo recorder directly. Do not attempt native `/dev/video*` first.

3. Replace `writeDebugFallbackRecordings(...)`.

   Remove the placeholder/copy fallback as the primary emulator behavior.

   Replace it with `DebugDemoVideoSegmentRecorder`, which:

   - Resolves each camera source through `TestVideoSources.getFile(...)`
   - Falls back to front sample for all cameras through the existing `TestVideoSources` fallback
   - Uses `MediaExtractor` to read the video track
   - Uses `MediaMuxer` to write an MP4 file
   - Loops input samples until `durationMs` or stop request
   - Preserves sample timing with monotonic presentation timestamps
   - Ignores audio for recording consistency unless audio is explicitly required later

4. Make stop behavior immediate.

   `RecordingService.requestStop()` already interrupts the worker thread. Ensure both native and debug segment recorders observe `stopRequested` frequently:

   - Native path keeps calling `stopRecordingNative()`
   - Debug muxing loop checks `stopRequested` between sample writes
   - Sleep-only waits are avoided for debug recording

5. Keep car recording all-or-nothing.

   In native mode:

   - Start each slot
   - If any slot fails, stop all started slots immediately
   - Return `false` for the segment
   - Set recording state back to false through the existing service lifecycle

   This avoids a misleading red recording button when only some cameras are captured.

6. Improve diagnostics.

   Add concise logs for:

   - Selected recorder mode: `native` or `debug_demo`
   - Source path per camera index
   - Output path per camera index
   - Native slot start failure
   - Debug muxer failure
   - Segment completion and stop reason

   These logs should be under `RecordingService` or the concrete recorder class tag.

7. Validate default and tree storage.

   Default storage writes directly to:

   - `Downloads/mg4_cam_records`

   Tree URI storage should continue staging in cache first, then copy to the selected tree. Use `video/mp4` when creating `DocumentFile` entries.

8. Update recording folder settings actions.

   In the Settings recording section, split the current row that contains `Change recording folder` into two side-by-side buttons:

   - `Change recording folder`
   - `Open recording folder`

   `Change recording folder` should keep the current folder picker behavior.

   `Open recording folder` should attempt to open the active recording folder in the default file explorer. If there is no default handler, show an Android app chooser. If no compatible file explorer exists, show a clear toast instead of failing silently.

   Folder opening rules:

   - For a persisted Storage Access Framework tree URI, open that tree URI with an `ACTION_VIEW` intent and grant read/write URI permissions.
   - For the default `Downloads/mg4_cam_records` folder, use the safest available public folder intent for the current Android version.
   - If direct folder viewing is not supported by the device build, fall back to opening `Downloads` or showing the path in a toast/dialog.
   - Do not request broad storage permissions just to open the folder.

   Required UI/resources:

   - Add `settings_open_recording_folder`
   - Add a failure string such as `settings_records_path_open_failed`
   - Update English and German string resources
   - Keep the two buttons visually balanced in the existing settings style

## Testing Plan

Emulator:

1. Build and install debug.
2. Ensure `front_camera_sample_1.mp4` is available through `app/src/debug/assets` and/or `tools/install_debug_test_videos.sh`.
3. Start preview and confirm demo video is visible.
4. Start recording from the main activity.
5. Stop recording after a short interval.
6. Verify four playable `.mp4` files are produced.
7. Repeat start/stop from the overlay popup.
8. Verify recording button state stays synchronized between popup and main activity.
9. Verify stopping recording interrupts promptly.
10. In Settings, verify `Change recording folder` still opens the picker.
11. In Settings, verify `Open recording folder` opens the active folder or shows a chooser/fallback message.

Car:

1. Install release or signed debug build on MG4.
2. Confirm native camera preview works.
3. Start recording from main activity.
4. Verify four `.mp4` files are created for `/dev/video15`, `/dev/video14`, `/dev/video16`, and `/dev/video17`.
5. Verify all four files are playable.
6. Stop recording from overlay popup and confirm all native slots stop.
7. Test failure behavior by temporarily blocking one camera path if feasible; recording should stop cleanly rather than recording a partial set.
8. Verify `Open recording folder` works on the MG4 Android 9 file manager, or falls back to a user-visible message.

Regression checks:

- `./gradlew assembleDebug`
- `./gradlew assembleRelease`
- Release APK must not include `app/src/debug/assets/front_camera_sample_1.mp4`
- Emulator debug recording must not call `/dev/video*`
- Car recording must not use debug demo sources in release builds

## Risks

- `MediaExtractor` and `MediaMuxer` timestamp handling must be precise enough to produce playable looped segments.
- Some players can be sensitive to copied codec-specific data; muxer track format should be taken directly from the extractor video track.
- The native recorder currently records each camera independently; CPU and I/O pressure on the car must be watched when all four slots run.
- Storage Access Framework writes can fail or be slow; cache staging and cleanup must remain robust.

## Acceptance Criteria

- On the car, recording captures all four physical camera feeds.
- On the emulator, recording captures the demo video source used for preview.
- Emulator recordings are playable `.mp4` files, not placeholders and not MP4 data with `.mpg` names.
- Recording start/stop state remains synchronized between the main activity and overlay popup.
- Stop recording responds promptly in both native and debug modes.
- Debug sample video remains excluded from release APKs.
