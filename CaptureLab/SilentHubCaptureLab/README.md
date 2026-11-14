# SilentHub External

## Versioning policy
The public version starts at `1.0.0`. Versions are assigned explicitly by the maintainer; no automatic increment or rounding is performed. Use the requested release number exactly:
- `1.0.0`: initial public release.
- `1.1.0`, `1.2.0`, etc.: incremental fixes and small patches (the final component remains `0` under this project convention).
- `1.5.0`: a larger feature, interface redesign or meaningful compatibility update. Jump directly to `1.5.0` from `1.2.0`; do not turn it into `1.6.0` automatically.
- `2.0.0`: major compatibility expansion or major release.

The Android `versionCode` is derived from the exact release tag using `major × 1,000,000 + minor × 1,000 + patch`. This is an ordering value only; it does not change the displayed semantic version.

## Branding and animation
The launcher label is permanently `SilentHub External`; it never contains a version number. The installed version is shown inside the app. The supplied launcher artwork is stored under `res/mipmap-xhdpi`. The in-app animated wordmark is played from the original looping GIF resource `res/raw/silenthub_logo.gif` using Android's native GIF decoder, preserving its frame timing.

## Engineering changes
- Android Keystore AES-GCM protects the pairing token. The 0.4.x plaintext preference is migrated once and deleted.
- Supabase join acknowledgement is shown as Connected. Paired requires the first valid Delta frame.
- Connection state is event-driven; the Activity has no one-second polling loop.
- The foreground service uses START_STICKY for system recovery and respects an explicit user stop.
- The secure Canvas invalidates on new frames and once on frame expiry. No idle redraw timer runs.
- WorkManager checks every six hours with network and battery-not-low constraints. Checks and downloads are deferred during an active receiver session.
- Updates validate HTTPS origin, package ID, newer versionCode, exact size, SHA-256 and the installed signing certificate both after download and immediately before installation.
- `PackageInstaller.MODE_FULL_INSTALL` performs an in-place package replacement: app data and the encrypted pairing token remain when the application ID and persistent signing certificate match. Android still displays its own install confirmation; ordinary apps cannot silently install or guarantee rollback.

## Pairing
Paste the pairing code into Delta once. The app detects the session automatically on the first valid frame. Regenerating the code revokes the old channel.

## Initial signing migration
The earlier 0.5.0 APK shared from CI is a debug build (versionCode 5) signed with a temporary CI debug certificate. Android will not replace it with the production certificate as an in-place update. For this one-time migration: copy the pairing code from the current app, uninstall the debug build, install the first signed release, then use **Restaurar código guardado** to import the copied code. This preserves the same Delta pairing token even though Android removes the old Keystore key during uninstall. From that signed installation onward, every release built with the same persistent keystore is installed in place and retains app data and the encrypted pairing token. The signed-release workflow calculates the Android versionCode from the validated SemVer tag (major × 1,000,000 + minor × 1,000 + patch) and writes that exact same value into both the APK and release metadata.

## Release signing
Add repository Actions secrets before pushing a version tag:
- ANDROID_KEYSTORE_BASE64
- ANDROID_KEYSTORE_PASSWORD
- ANDROID_KEY_ALIAS
- ANDROID_KEY_PASSWORD

The signed-release workflow generates the APK, SHA-256 sidecar and release JSON expected by Vercel. Publication remains unavailable until the persistent keystore secrets are configured.

## Build and tests
gradle -p CaptureLab/SilentHubCaptureLab testDebugUnitTest assembleDebug

## Platform limits
Minimum Android 8.0 (API 26); target API 35. Overlay, notifications and unknown-source installation use Android system prompts. OEM battery and background restrictions remain controlled by Android/Samsung.