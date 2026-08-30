# GlassesGate

Turn a pair of Meta AI glasses into a proximity credential. Built on Meta's official
[Wearables Device Access Toolkit](https://wearables.developer.meta.com/) (developer preview),
not on scraping Bluetooth names.

Two roles, one app, chosen when you open it:

- **Gate** — an Android phone at a door. Enroll glasses by serial number, then start a session:
  a full-screen red that turns green while an approved wearer is standing in front of it.
- **Wearer** — your phone. Claim an enrollment, and your glasses become the thing that opens
  the gate.

---

## How it works, and why it works that way

The obvious design — the door phone scans for nearby glasses — is impossible. Meta's glasses
pair to exactly one phone through the Meta AI app, and only that phone can talk to them. There
is no discovery API for a third device, and no serial-number lookup. Anything claiming otherwise
is guessing.

So the trust chain runs through the wearer's phone:

```
glasses ──DAT session──► wearer's phone ──BLE advertisement──► gate phone
```

The wearer's phone broadcasts a rotating, HMAC-signed token — **but only while it holds a live
session with the exact pair of glasses the credential was issued against.** Fold the glasses,
take them off, or walk out of range, and the broadcast stops within seconds. That constraint is
the entire product: it is what makes the glasses the credential rather than the phone.

### Enrollment runs gate → wearer

1. The admin taps **Add glasses**, types in the serial number printed inside the temple arm, a
   name, and which gate this is for.
2. The app mints a random credential id and a 32-byte secret, adds them to the local allowlist,
   and puts them on screen as a QR code.
3. The wearer scans it with their glasses connected. The credential binds to that specific pair.
4. From then on the wearer can broadcast, and this gate — and only this gate — will admit them.

Running enrollment in this direction means the allowlist is authored by the admin. The gate never
has to trust anything a stranger's phone asserts about itself.

The serial number is a **label**, not a key. Meta's SDK gives no way to verify that a serial
belongs to a particular pair of glasses, so nothing cryptographic rests on it. It exists so a
human can tell two enrollments apart.

### The beacon

12 bytes of BLE service data:

```
[0,4)   tag   = SHA-256(credentialId) truncated       — which secret to check against
[4,12)  token = HMAC-SHA256(secret, credentialId ‖ window) truncated
```

The token rotates every 15 seconds, and the gate accepts one window either side to absorb clock
skew, so a captured advertisement is useless after at most 45 seconds. The advertiser sleeps
until the exact window boundary rather than republishing on a timer, so there is always exactly
one live token.

Nothing identifying is on the wire. Someone recording the radio learns that *a* GlassesGate
credential went past, not whose.

Both sides are entirely offline. No backend, no accounts, no sync, no analytics. Once enrolled,
both phones work in airplane mode.

### What this does and doesn't prove

Green means *an approved credential is nearby and its glasses are live*. It does not mean the
glasses hardware has cryptographically identified itself — Meta's public SDK has no attestation
primitive to make that claim with.

**[SECURITY.md](SECURITY.md) states the guarantees and the weaknesses in full.** Read it before
deploying this anywhere that matters. The short version: good for ticketing, events, member
check-in, and soft access control. Not a replacement for a hardware security key.

---

## Project layout

```
core/     Pure JVM Kotlin — beacon protocol, enrollment wire format. No Android, no SDK.
          This is where the security properties live, and where the tests that guard them live.
app/
  user/   DAT session wrapper, credential vault, BLE advertiser, foreground service
  admin/  Allowlist store, BLE scanner
  ui/     Compose screens, shared view model
  enrollment/  QR generation and scanning
```

`core/` is deliberately Android-free. It means `./gradlew :core:test` runs anywhere — no Android
SDK, no package token, no device — and it is what CI checks on every push.

## Setup

**1. Get a GitHub Packages token.** The DAT SDK is served from GitHub Packages, which needs auth
even for public reads. Create a classic PAT with the `read:packages` scope, then either:

```bash
export GITHUB_TOKEN=ghp_...
```

or add `github_token=ghp_...` to `local.properties` (gitignored).

**2. Open in Android Studio and build.** Developer Mode works with the placeholder credentials
already set in `gradle.properties`. For a release build, replace `MWDAT_APPLICATION_ID` and
`MWDAT_CLIENT_TOKEN` with the pair issued in the
[Wearables Developer Center](https://wearables.developer.meta.com) — override them in
`~/.gradle/gradle.properties` rather than committing real values.

**3. On the wearer's phone**, enable Developer Mode in the Meta AI app (Settings → App Info, tap
the version five times), then tap **Connect to the Meta AI app** in GlassesGate.

Requires Android 12 (API 31) or later, and a phone that can act as a BLE peripheral — most can,
but the app checks and says so if yours cannot.

### Testing without glasses

`mwdat-mockdevice` is wired in as a debug dependency. `MockDeviceKit.getInstance(context)` can
pair a simulated Ray-Ban Meta, which exercises the whole wearer flow. You still need two Android
devices to test the beacon handoff itself — one phone cannot usefully advertise to and scan from
itself.

### CI

`:core:test` runs on every push and pull request with no configuration. Building `:app` needs the
package token, so add your PAT as a repository secret named `MWDAT_PACKAGES_TOKEN` — until you
do, the Android job is skipped rather than failed.

## Contributing

[AGENTS.md](AGENTS.md) has the working rules, including the ones that exist because a previous
iteration got them wrong. The most important: never let the beacon advertise without a live
glasses session, and verify DAT API shapes against the pinned version rather than from memory.

---

*Community project. Not affiliated with Meta Platforms, Inc.*
