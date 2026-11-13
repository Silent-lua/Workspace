# SilentHub External ESP 0.5.0

## Engineering changes
- Android Keystore AES-GCM protects the pairing token. The 0.4.x plaintext preference is migrated once and deleted.
- Supabase join acknowledgement is shown as Connected. Paired requires the first valid Delta frame.
- Connection state is event-driven; the Activity has no one-second polling loop.
- The foreground service uses START_STICKY for system recovery and respects an explicit user stop.
- The secure Canvas invalidates on new frames and once on frame expiry. No idle redraw timer runs.
- WorkManager checks every six hours with network and battery-not-low constraints. Checks and downloads are deferred during an active receiver session.
- Updates validate HTTPS origin, package ID, versionCode, exact size, SHA-256 and the installed signing certificate before PackageInstaller is called.
- Android displays its own install confirmation. Ordinary apps cannot silently install or guarantee rollback.

## Pairing
Paste the pairing code into Delta once. The app detects the session automatically on the first valid frame. Regenerating the code revokes the old channel.

## Initial signing migration
The 0.4.x package was a GitHub Actions debug build. A production release must use one persistent release keystore. Android will not update a package signed with a different certificate. For the first migration only, export the pairing code, uninstall the debug build and install the first signed release. Subsequent releases must reuse the same keystore.

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