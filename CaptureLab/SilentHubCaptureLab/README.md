# SilentHub External Preview 0.2.0

## Purpose
This is a capture-compatibility preview for the Galaxy A55. It creates a full-screen transparent Android application overlay protected with `WindowManager.LayoutParams.FLAG_SECURE`. The overlay draws three mock ESP examples: player boxes, tracers, names, and distance labels.

## How to run
1. Install and open **SilentHub External Preview**.
2. Grant **Display over other apps** permission.
3. Press **Show transparent ESP preview**.
4. Confirm that the sample boxes, tracers, and labels are visible over the current app.
5. Record Roblox using Medal and review the saved video.
6. Repeat using Samsung Screen Recorder.
7. Verify both conditions:
   - No demo box, tracer, text, or overlay artwork appears in the saved video.
   - The Roblox scene below the overlay remains fully visible (no black rectangle or black screen).
8. Return to the app and press **Stop overlay**, or use the notification action.

## Critical technical notes
- The overlay is a single full-screen secure window. `FLAG_SECURE` protects the entire window, not individual Canvas primitives.
- The window background is transparent; only mock ESP strokes and text are drawn.
- The window alpha is 0.75 to stay below Android's default maximum obscuring opacity for a single application overlay on Android 12+ and preserve touch pass-through when allowed by the system.
- The app does not connect to Delta, read Roblox state, or contain a live ESP.
- A successful preview proves only that this rendering layer is hidden from that capture path on the tested device/app version. It does not guarantee every recorder behaves identically.

## Build
Requirements: JDK 17+, Android SDK Platform 35 and Android Build Tools 35.

```sh
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`
