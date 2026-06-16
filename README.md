# MG4-Camera-Mod
## DualBytes version - Experimental

Experimental fork from the original work by jamakr4.

Community mod for the MG4 EV (AAOS 9, pre-2026 facelift) that improves the 360° turn signal camera behavior — removing the launcher overlay in favor of a Tesla-style tile view and raising the auto-close speed threshold.

# Features for v0.7
- **Manual camera popup** — open the live camera view as a floating popup from the main app
- **Split preview support** — main activity and popup can display the same MG4 camera feed at the same time
- **Signal camera overlay** — automatic camera popup when the indicator is activated
- **Recording controls** — start and stop recording from the app and overlay, with synchronized state
- **Overlay restore** — reopen the app with the last visible state, position, and size preserved
- **Resizable popup** — move and resize the popup with corner handles and a small corner radius
- **Debug sample video mode** — emulator testing can use the bundled MP4 sample instead of a real vehicle camera
- **OTA release channel** — download and install promoted releases directly from GitHub
- **Full access to the camera system**
- **Language support for English and German with auto select based on the vehicle language**

## Build Setup

OpenCV is referenced from a local path, so Android builds can fail if `OpenCV_DIR` is not set correctly.

Before building:
- Download the OpenCV Android SDK from [opencv/opencv releases](https://github.com/opencv/opencv/releases)
- Use `opencv-android-sdk.zip` and not the Windows or macOS packages
- Update `OpenCV_DIR` in [app/src/main/cpp/CMakeLists.txt](/Users/jan/Projekts/MG4-360-Camera-App/app/src/main/cpp/CMakeLists.txt:1) so it matches your local OpenCV Android SDK path

## ⚠️ Disclaimer

**Use at your own risk.**

This project involves modifying system APKs on a production vehicle. The author(s) take **no responsibility** for any damage, malfunction, data loss, voided warranty, or any other consequences resulting from the use of these modifications. Modifying vehicle software may affect safety systems — always test in a safe environment.

This is an independent community project and is **not affiliated with SAIC, MG Motor, or any of their subsidiaries**.

## Credits
- AdrianBega/DualBytes/dragonro - current experimental version of the app
- Analysis based on community research from [XDA Forums — MG4 Electric AAOS 9](https://xdaforums.com/t/mg4-electric-aaos-9-playing-and-possibly-other-mg-models.4697712/)
- Tile View based on: [merth4n](https://xdaforums.com/m/merth4n.13350648/)
- OpenCV 4.9.0 — Apache License 2.0
- AndroidX AppCompat 1.7.1 — Apache License 2.0
- AndroidX Activity 1.12.4 — Apache License 2.0
- AndroidX ConstraintLayout 2.2.1 — Apache License 2.0
- Material Components for Android 1.13.0 — Apache License 2.0
- Icons based on [Google Material Symbols](https://developers.google.com/fonts/docs/material_symbols) — Apache License 2.0

## Support the Project

If you want to show your appreciation, the best ways to help are:
- **Star the repository** on GitHub
- **Contribute ideas**, feedback, or feature requests via Issues or in discussions
- **Share the project** with others who might find it useful

That means a lot and keeps the project going!

## License

GPL-3.0 — see [LICENSE](LICENSE) for details.
