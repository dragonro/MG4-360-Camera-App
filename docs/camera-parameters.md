# Camera Parameters

This application does not contain hardcoded vehicle geometry values for these measurements.
It accesses the car cameras directly through native V4L2 `/dev/video*` devices, but the codebase does not expose the following physical installation parameters:

- Front camera height from ground
- Rear camera height from ground
- Left mirror camera height
- Right mirror camera height
- Distance from front camera to front bumper edge
- Distance from rear camera to rear bumper edge
- Vehicle width
- Vehicle length

## What the app does contain

- Direct camera device usage through `/dev/video14`, `/dev/video15`, `/dev/video16`, and `/dev/video17`
- Native camera probe and preview logic in `app/src/main/cpp/cameraprobe.cpp`
- Native MP4 recording logic in `app/src/main/cpp/cameraprobe_record.cpp`

## Notes

- The app uses direct camera device access, not the standard Android `CameraManager` API.
- Any real-world mounting heights or vehicle dimensions must be measured from the vehicle or obtained from OEM documentation.
- If these values are needed for calibration or overlay math, they should be added as explicit configuration fields rather than inferred from the current app.
