# GlassesGate

GlassesGate turns a pair of Meta AI glasses (Ray-Ban Meta / Ray-Ban Display) into a proximity-based access credential, built on Meta's official **Wearables Device Access Toolkit (DAT)** — currently in developer preview — instead of raw BLE name/MAC scanning.

## Why this isn't "scan for nearby glasses"

Meta's glasses don't broadcast an open BLE signal that a third-party phone can scan and identify. Glasses pair to **one** phone through the Meta AI app, and only that phone's app can talk to them through the DAT SDK. There is no public API for an arbitrary device to detect someone else's glasses walking past.

So GlassesGate's actual trust chain is:

```
Glasses  <-- Meta AI app / DAT SDK -->  User's phone  --BLE beacon-->  Admin's phone
```

The **User** app confirms it has a live DAT session with a pair of glasses, then broadcasts a short-lived, rotating, HMAC-signed proof over BLE. The **Admin** app scans for that proof and checks it against a locally stored list of approved glasses. The admin device never talks to the glasses directly.

## Roles (one app, chosen at launch)

- **User** — registers with the Meta AI app, connects to their glasses via DAT, and once connected can broadcast the approval beacon. Also generates a one-time enrollment QR code for an admin to scan.
- **Admin** — maintains a local, encrypted, offline list of approved glasses, added by scanning a user's enrollment QR. No cloud, no backend. "Start beacon session" opens a full-screen scanner: **green** while an approved beacon is in range, **red** otherwise.

## How the beacon works

- Each enrolled pair of glasses gets a random 32-byte secret, generated on the user's phone and shared with the admin's phone only via the one-time QR code.
- The user's phone advertises `HMAC-SHA256(secret, deviceId + timeWindow)` truncated to 8 bytes, rotating every 30 seconds, alongside a 4-byte non-identifying tag so the admin knows which stored secret to check it against. Total payload is 12 bytes, which fits comfortably in a standard BLE advertisement's ~20 usable bytes.
- The admin's phone recomputes the same HMAC locally for each approved device and compares — no network call, no shared backend. Both the current and previous window are accepted, to tolerate clock drift and scan timing.
- The scanner runs a watchdog that reverts to red once a full rotation window passes with no fresh match, since `ScanCallback` only fires on a hit and would otherwise leave the gate stuck green after someone walks away.

See `core/BeaconProtocol.kt` for the exact scheme and `app/src/test/…/BeaconProtocolTest.kt` for the properties it's expected to hold.

### Security limitations, stated plainly

- This proves **an approved phone with a live glasses session is nearby** — not that the glasses hardware is cryptographically who it claims to be. Meta's public SDK doesn't expose a hardware attestation primitive that would allow the stronger claim.
- The enrollment QR contains a live credential. Anyone who photographs it can spoof that user's beacon until the secret is rotated (`EnrollmentSecretStore.rotateSecret`).
- A token is replayable within its ~30–60 second validity window. Shorten `ROTATION_SECONDS` if that matters more than tolerance for clock drift.
- BLE advertisements are unauthenticated broadcasts; an attacker within radio range can record them. The HMAC is what makes them useless without the secret.

Treat this as proximity + session verification, appropriate for ticketing and soft access control — not as a replacement for a hardware security key.

## Setup

1. **Wearables Developer Center** — register at [wearables.developer.meta.com](https://wearables.developer.meta.com) to get an `APPLICATION_ID` / `CLIENT_TOKEN` for release builds. For local development, **Developer Mode** works with the placeholder value `0` for both, which is already the default in `gradle.properties`.
2. **GitHub Packages token** — the DAT SDK is distributed through GitHub Packages, which requires auth even for public read access. Create a classic PAT with the `read:packages` scope, then either `export GITHUB_TOKEN=ghp_...` or add `github_token=ghp_...` to your (gitignored) `local.properties`.
3. **Gradle wrapper jar** — the binary `gradle/wrapper/gradle-wrapper.jar` isn't checked in. Open the project in Android Studio and let it regenerate the wrapper, or run `gradle wrapper --gradle-version 8.9` once if you have Gradle installed. The properties file pointing at 8.9 is already committed.
4. Build and run. On first launch as **User**, tap "Register with Meta AI app" — this hands off to the Meta AI app to complete pairing and permission grants.
5. **Testing without physical glasses** — `mwdat-mockdevice` is wired up as a debug dependency. `MockDeviceKit.pairRaybanMeta()` simulates a connected device, which lets you exercise the whole User → beacon → Admin flow before you have hardware in hand. You'll need two Android devices (or one device plus an emulator with BLE) to test the beacon handoff itself.

## Project structure

```
app/src/main/java/com/glassesgate/app/
  core/         BeaconProtocol — the rotating-token scheme shared by both roles
  user/         GlassesSessionManager (DAT wrapper), BeaconAdvertiser, EnrollmentSecretStore
  admin/        ApprovedDeviceStore (encrypted, local-only), BeaconScanner
  enrollment/   EnrollmentPayload (QR contents), QR generation + scanning views
  MainActivity.kt   Role selection and all screens (simple state-based navigation)
app/src/test/     BeaconProtocol unit tests
```

## Known gaps / next steps

- `GlassesSessionManager` targets the API shape documented for DAT 0.9 (`Wearables.initialize`, `createSession`, `AutoDeviceSelector`). The SDK is still moving in preview — if a type has been renamed in the version you resolve, that's a rename here, not a redesign.
- **Verify the device identifier is stable.** The enrollment QR reuses whatever identifier the DAT SDK reports for the connected device. Confirm against the current API reference that it persists across reconnects and reboots before relying on it as a long-term "serial number" — if it rotates, enrollment would need to be redone each session.
- The beacon only runs while the User screen is in the foreground. A foreground service would be needed for it to keep advertising with the screen off, which is likely what a real ticketing deployment wants.
- Admin lists are per-device by design (offline). Multi-gate venues would need an export/import step or the cloud sync we deliberately skipped.
- `local.properties` is currently committed to the repo. It only contains a local SDK path, so it's harmless, but it's in `.gitignore` for a reason — worth a `git rm --cached local.properties`.

---
*Community project, not affiliated with Meta Platforms, Inc.*
