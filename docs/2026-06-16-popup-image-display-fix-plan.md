# Popup Image Display Fix Plan

Date: 2026-06-16

## Problem

On the MG4, the main activity can display the camera image correctly, but the popup overlay can stay black when it is opened while the main activity preview is active. If the app is then closed with the popup open, the popup remains black.

When `Show Signal Camera` and `Rotate side cameras to driving direction` are enabled and the app is closed, the overlay can display the camera image, but the image is rotated sideways.

## Findings

The native preview pipeline is singleton-based. In `cameraprobe.cpp`, preview state is held in global variables such as `g_fd`, `g_window`, `g_thread`, `g_running`, and `g_videoIndex`.

`CameraProbe.startPreview(...)` returns false when `g_running` is already true. This means the main activity and overlay cannot independently render the native MG4 camera stream at the same time. If the main activity owns the native preview surface, the overlay can fail to acquire the stream and render black.

The rotation issue comes from `OverlayService.shouldRotatePreviewToDrivingDirection()`. It applies side-camera rotation for camera indexes `14` and `16` whenever the rotation setting is enabled. That behavior is valid for signal-triggered side-camera overlays, but it should not automatically apply to the manual popup preview.

## Fix Plan

1. Make preview ownership explicit.

   Add a single app-level preview ownership rule so the main activity and overlay do not race the native `CameraProbe` singleton.

2. Transfer preview cleanly when opening popup.

   In `MainActivity.startPopupOverlay()`, stop the main activity preview before calling `OverlayService.showPopup(...)`. This releases the native camera surface before the popup tries to acquire it.

3. Prevent main preview restart while popup is active.

   Guard `surfaceCreated()` and `startPreviewIfReady()` so the main activity does not immediately restart preview while popup mode is active.

4. Restore main preview only when returning from overlay.

   When the overlay show-app button launches the main activity, release the overlay preview first or stop the overlay service before the main activity resumes preview.

5. Split rotation behavior by overlay mode.

   Keep `Rotate side cameras to driving direction` for signal-triggered overlay mode, but do not apply it to manual popup mode. `shouldRotatePreviewToDrivingDirection()` should return false when `popupMode == true`.

6. Make overlay mode state explicit.

   Persist whether the visible overlay was opened as manual popup or signal overlay separately from the last UI state. This prevents restoring a manual popup as a signal overlay and avoids applying signal-only transforms to popup mode.

7. Add targeted logging.

   Add logs around preview handoff and failure points:

   - Main activity stopping preview before popup launch
   - Overlay starting preview with `cameraIndex` and `popupMode`
   - Native preview start failures caused by an already running preview
   - Overlay rotation decisions

8. Verify in emulator and car.

   Emulator verification should cover lifecycle, overlay restore, and fallback preview behavior.

   Car verification should cover the real `/dev/video*` camera path, preview handoff from main to popup, popup behavior after main activity closes, and side-camera rotation with signal overlay.

## Expected Result

Opening the popup from the main activity should stop the main preview, start the popup preview on the same MG4 camera source, and display the image upright.

Closing or hiding the main activity should not break the popup feed.

Signal-triggered side-camera overlays should keep the driving-direction rotation behavior, while manual popup previews should stay upright unless a separate popup rotation setting is added later.
