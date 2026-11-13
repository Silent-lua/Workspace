# SilentHub Capture Lab

Android capture-isolation proof of concept for the Samsung Galaxy A55.

## Latest test app
Open `CaptureLab/SilentHubCaptureLab/` in Android Studio or build it with the GitHub Actions workflow. Version 0.2.0 uses a full-screen transparent secure overlay and draws mock ESP primitives (boxes, tracers, labels) for capture testing.

## Build from the phone
1. Open the repository **Actions** tab.
2. Open the latest **Build SilentHub Capture Lab APK** run.
3. Download the `SilentHub-CaptureLab-v0.2.0-debug-apk` artifact.
4. Extract the artifact ZIP and install the APK. Version 0.2 has a separate application ID, so it can coexist with the 0.1 test app.

## Test requirements
The mock ESP must be visible on the phone, absent from the saved recording, and the Roblox image below it must remain intact (no black rectangle or black screen). Test Medal and Samsung Screen Recorder separately.

## Important
This preview does not connect to Delta, read Roblox state, inject code, or implement a live ESP. It validates the secure transparent full-screen rendering layer only. A separate, authenticated local data bridge and a Delta-side sender still need to be designed and tested.
