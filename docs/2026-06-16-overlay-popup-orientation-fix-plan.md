# Overlay Popup Orientation Fix Plan - 2026-06-16

## Problem

On the MG4, these settings can be enabled at the same time:

- `Show Signal Camera on turn signal`
- `Rotate side cameras to driving direction`

The manual camera popup opened from the app displays the processed MG4 camera image correctly. After switching to another OEM app, using the turn signal opens the floating overlay, but the processed image is rotated `90` degrees to the right. The overlay window also changes shape from the expected landscape popup size to a taller portrait-style shape.

The user-visible failure is that the overlay no longer respects the initial popup size and orientation.

## Findings

Relevant files:

- `app/src/main/java/com/drivehub/kamera/OverlayService.java`
- `app/src/main/java/com/drivehub/kamera/SignalService.java`
- `app/src/main/java/com/drivehub/kamera/MainActivity.java`
- `app/src/main/java/com/drivehub/kamera/UiPrefs.java`

The manual popup path and the signal overlay path use the same `OverlayService`, but with different mode flags:

```java
OverlayService.showPopup(context, cameraIndex)   // popupMode = true
OverlayService.showOverlay(context, cameraIndex) // popupMode = false
```

Manual app popup:

- Called from `MainActivity.startPopupOverlay()`
- Uses `popupMode=true`
- `OverlayService.shouldRotatePreviewToDrivingDirection()` returns false because it explicitly checks `!popupMode`
- The popup remains landscape and displays the processed MG4 camera image correctly

Signal-triggered overlay:

- Called from `SignalService.evaluateMode(...)`
- Uses `OverlayService.showOverlay(this, 16)` for left signal
- Uses `OverlayService.showOverlay(this, 14)` for right signal
- Uses `popupMode=false`
- If `Rotate side cameras to driving direction` is enabled, `shouldRotatePreviewToDrivingDirection()` returns true for camera indexes `14` and `16`

Current rotation logic in `OverlayService`:

```java
private boolean shouldRotatePreviewToDrivingDirection() {
    return !popupMode
            && uiPrefs != null
            && UiPrefs.isOverlayRotationToDrivingDirectionEnabled(uiPrefs)
            && (cameraIndex == 14 || cameraIndex == 16);
}
```

When rotation is enabled, the service does three coupled things:

1. Rotates the `TextureView` by `+90` or `-90`.
2. Swaps the `TextureView` layout width/height.
3. Changes the active overlay aspect ratio from landscape to portrait.

The size mutation happens here:

```java
private void normalizeOverlaySizeForCurrentMode() {
    boolean shouldRotate = shouldRotatePreviewToDrivingDirection();
    boolean isLandscape = overlayWidthPx >= overlayHeightPx;
    if (shouldRotate == isLandscape) {
        int swappedWidth = overlayHeightPx;
        overlayHeightPx = overlayWidthPx;
        overlayWidthPx = swappedWidth;
    }
    int[] clamped = clampOverlaySize(overlayWidthPx);
    overlayWidthPx = clamped[0];
    overlayHeightPx = clamped[1];
}
```

The resized geometry is then persisted through `saveOverlayLayoutPrefs(...)`, using shared keys:

- `overlay_w`
- `overlay_h`
- `last_x`
- `last_y`

This means a signal overlay can persist a portrait-shaped size into the same geometry used by the manual popup. The next overlay/popup startup can reload that rotated geometry and appear taller than wide.

## Root Cause

The app currently treats manual popup geometry and signal overlay geometry as one shared state, while also applying signal-only rotation transforms to the shared overlay window dimensions.

`Rotate side cameras to driving direction` is intended for the signal camera behavior, but the current implementation couples camera rotation with window aspect-ratio mutation and persists the rotated size globally.

The processed MG4 camera image path is already correct. The bug is in presentation mode handling:

- Signal overlay mode rotates the already processed image.
- Signal overlay mode mutates the window size to portrait.
- The mutated window geometry is saved into the same preference keys used by the manual popup.

## Target Behavior

Manual popup behavior:

- Always preserve its saved landscape size and position.
- Never inherit portrait geometry from signal-triggered overlays.
- Display the processed MG4 camera image upright.
- Keep resize handles, close button, popup button, recording button, and timer unchanged.

Signal overlay behavior:

- Still opens when turn signals are used and `Show Signal Camera on turn signal` is enabled.
- Should not corrupt manual popup geometry.
- Should not rotate an already processed MG4 camera image a second time.
- Should preserve the overlay window aspect ratio unless a separate, explicit signal-overlay sizing mode is added later.

Driving-direction rotation setting:

- Keep the setting in place for compatibility.
- Make the implementation aware of processed MG4 camera output.
- Do not apply `TextureView` rotation to the processed camera output used by this app unless a future raw side-camera mode needs it.

## Proposed Fix

### 0. Add Emulator Signal Simulation

The emulator cannot reliably provide MG/SAIC turn-signal and gear values through the car APIs or system properties used in the vehicle.

Add a debug-only simulation entry point through `DebugSignalSimulationReceiver`, declared only in `app/src/debug/AndroidManifest.xml`:

```text
com.drivehub.kamera.debug.SET_SIGNAL_STATE
```

Intent extras:

- `lamp=0`: signal off
- `lamp=1`: left signal
- `lamp=2`: right signal
- `gear=0`: normal/no special gear
- `gear=1`: drive
- `gear=2`: reverse

Implementation rules:

- Accept the debug action only when `BuildConfig.DEBUG` is true.
- Keep `SignalService` non-exported.
- Make only the debug receiver exported, and only in debug builds.
- The debug receiver forwards the simulated state to `SignalService` inside the app process.
- Ignore it in release builds.
- Route the simulated state through the same `updateOverlayDecision()` path used by real car signals.
- Reset cached lamp/gear values before applying the simulated state so repeated emulator tests are deterministic.
- Add `scripts/control-script.sh` menu item `8. Simulate actions`.
- Add submenu actions for left signal, right signal, signal off, reverse gear, drive gear, left signal while driving, and right signal while driving.

This gives emulator tests a repeatable way to exercise `SignalService -> OverlayService.showOverlay(...)`, which is the exact path involved in the car orientation bug.

### 1. Separate Geometry by Overlay Mode

Replace shared geometry keys with mode-specific keys, or wrap existing helpers so they select the correct key namespace:

Manual popup keys:

- `popup_last_x`
- `popup_last_y`
- `popup_w`
- `popup_h`

Signal overlay keys:

- `signal_overlay_last_x`
- `signal_overlay_last_y`
- `signal_overlay_w`
- `signal_overlay_h`

Migration:

- On first run after upgrade, use the existing `overlay_*` keys as fallback.
- Once mode-specific values are saved, prefer the mode-specific keys.
- Do not delete old keys immediately; keep fallback compatibility.

### 2. Decouple Preview Rotation from Window Size

Do not change `overlayWidthPx`/`overlayHeightPx` only because the camera preview is rotated.

If rotation remains supported:

- Rotate only the internal `TextureView`.
- Keep the outer overlay window dimensions stable.
- Center and scale the rotated preview inside the existing window.
- Do not save rotated dimensions back as popup dimensions.

### 3. Disable Rotation for Processed MG4 Camera Output

The current app displays a processed MG4 360 image, not raw unprocessed side-camera frames. Rotating the processed image causes the visible `90` degree sideways bug.

Introduce an explicit decision method:

```java
private boolean shouldApplySignalPreviewRotation() {
    return false; // processed MG4 output is already oriented for display
}
```

Or, if future raw side-camera support is expected:

```java
private boolean shouldApplySignalPreviewRotation() {
    return isRawSideCameraSource()
            && !popupMode
            && UiPrefs.isOverlayRotationToDrivingDirectionEnabled(uiPrefs)
            && (cameraIndex == 14 || cameraIndex == 16);
}
```

For the current production fix, prefer the conservative behavior:

- Keep the setting persisted and visible.
- Do not apply preview rotation to this processed source path.
- Add logging that explains rotation was skipped because the source is already processed.

### 4. Preserve Manual Popup State

When `popupMode=true`:

- Load only popup geometry.
- Save only popup geometry.
- Keep `getActiveOverlayAspect()` landscape.
- Keep `normalizeOverlaySizeForCurrentMode()` from swapping dimensions.

When `popupMode=false`:

- Load only signal overlay geometry.
- Save only signal overlay geometry.
- Do not write signal overlay geometry into popup keys.

### 5. Keep Mode Explicit in Logs

Add targeted logs:

- `popupMode`
- `cameraIndex`
- `rotationEnabledSetting`
- `rotationApplied`
- loaded geometry key namespace
- saved geometry key namespace
- final `overlayParams.width/height`

This is important because the issue only reproduces reliably on the car with OEM app switching and turn-signal triggers.

## Implementation Plan

1. Add mode-specific geometry keys in `OverlayService`.

   Keep existing keys as migration fallback.

2. Update `loadSavedOverlayGeometry()`.

   It should choose popup keys when `popupMode=true` and signal overlay keys when `popupMode=false`.

3. Update `saveOverlayLayoutPrefs(...)`.

   It should save into the same key namespace selected by current mode.

4. Replace `shouldRotatePreviewToDrivingDirection()`.

   Split it into:

   - `isSignalSideCameraRotationSettingEnabled()`
   - `shouldApplyPreviewRotation()`

   For the current processed MG4 camera source, `shouldApplyPreviewRotation()` should return false.

5. Simplify `normalizeOverlaySizeForCurrentMode()`.

   It should clamp dimensions but not swap width/height because of rotation state.

6. Keep `applyPreviewTransform()` stable.

   It should use `MATCH_PARENT` for processed output and zero rotation.

7. Add guardrails for resize persistence.

   Resize gestures in popup mode must persist popup geometry only.

   Signal overlays must not overwrite popup geometry.

8. Validate with emulator.

   Emulator checks:

   - Menu `8. Simulate actions` starts the emulator if needed.
   - Simulated left signal opens the signal overlay path with camera `16`.
   - Simulated right signal opens the signal overlay path with camera `14`.
   - Simulated signal off hides the overlay after the configured delay.
   - Simulated reverse gear follows the current reverse behavior.
   - Manual popup opens landscape.
   - Resize popup, close, reopen: size/position restored.
   - Trigger simulated signal overlay: manual popup geometry is not changed.
   - Build debug and release.

9. Validate in car.

   Car checks:

   - Enable `Show Signal Camera on turn signal`.
   - Enable `Rotate side cameras to driving direction`.
   - Open manual popup from app: processed image is upright.
   - Switch to OEM app.
   - Use left/right turn signal: overlay appears with upright processed image.
   - Overlay remains landscape and does not become taller than wide.
   - Reopen manual popup: previous manual popup size is preserved.

## Production Safety Notes

- Do not remove the settings toggle in this fix; users already have it persisted.
- Do not change native camera source selection.
- Do not change preview ownership or recording behavior.
- Avoid destructive migration of existing geometry preferences.
- Avoid applying global orientation changes to the service or activity.
- Keep the fix local to overlay presentation and geometry persistence.

## Acceptance Criteria

- Signal-triggered overlays no longer rotate the processed MG4 camera image `90` degrees.
- Signal-triggered overlays no longer mutate the manual popup saved size.
- Manual popup preserves its own size and position independently from signal overlays.
- The overlay remains landscape when the processed MG4 camera image is shown.
- Existing popup controls and resize handles still work.
- Debug and release builds pass.
