# Agent operating rules

Instructions for any AI agent working in this repository. Read this before changing code.

## The one thing to understand first

GlassesGate makes a pair of Meta glasses act as an access credential. The temptation, every
single time, is to assume the admin's phone can *see* the glasses. It cannot, and no amount of
clever code will make it. Meta's SDK pairs glasses to exactly one phone through the Meta AI app,
and only that phone can hold a session with them. There is no discovery API, no serial-number
lookup, and no way for a third device to observe someone else's glasses.

So the chain is:

```
glasses --DAT session--> wearer's phone --BLE advert--> gate phone
```

The wearer's phone broadcasts a rotating HMAC only while a live session with the *specific*
enrolled pair of glasses exists. That constraint is the product. Any change that lets the beacon
run without a live session — "just for testing", "as a fallback", "while reconnecting" — deletes
the reason this app exists. Do not make it.

## Repository layout

| Path | What it is |
|------|------------|
| `core/` | Pure JVM Kotlin. Beacon protocol and enrollment wire format. No Android, no DAT. |
| `app/` | The Android app. Both roles, chosen at launch. |
| `app/src/main/java/.../user/` | Wearer role: DAT link, credential vault, advertiser, foreground service. |
| `app/src/main/java/.../admin/` | Gate role: allowlist store and BLE scanner. |
| `app/src/main/java/.../ui/` | Compose screens and the shared view model. |

## Rules

**1. Security claims live in `core/` and are enforced by its tests.**
`BeaconProtocolTest` asserts things like "the replay window stays under a minute" and "a genuine
tag with a foreign token is rejected". If you change `ROTATION_SECONDS`, `DRIFT_WINDOWS`, or the
matching logic, you are changing a claim made in `README.md` and `SECURITY.md`. Update all three
together or do not touch it.

**2. Never widen what the beacon proves.**
The advertisement carries a tag and a token, nothing else. Do not add the serial number, the
wearer's name, the device identifier, or anything else identifying to the payload. It is an
unauthenticated broadcast that anyone in radio range can record.

**3. `GlassesLink.kt` is the only file that may import `com.meta.wearable.*`.**
The DAT SDK is a developer preview and its type shapes move between releases. Everything else
consumes plain Kotlin flows. Keeping that boundary means an SDK upgrade is one file to fix.

**4. Verify DAT API shapes against the pinned version. Do not write them from memory.**
The version is in `gradle/libs.versions.toml`. Meta publishes an agent knowledge base and a docs
MCP server for exactly this:

```bash
claude plugin marketplace add facebook/meta-wearables-dat-android
claude plugin install mwdat-android@mwdat-android-marketplace
```

The sample apps under `samples/` in that repository are the ground truth for call sequences. A
previous iteration of this codebase shipped `session.device.identifier`, which does not exist,
and `Result.fold` with the wrong arity — both from guessing. The SDK uses its own result type
whose failure callback takes `(error, throwable)`.

**5. Nothing leaves the device.**
No analytics, no crash reporting, no sync, no telemetry. The manifest opts out of both SDK data
channels. Both roles work with the radio off. Do not add a network call.

**6. Do not add a "skip verification" path.**
No debug flag that admits everyone, no hardcoded test credential, no bypass behind a build
config. `mwdat-mockdevice` is already wired in as a debug dependency for testing without
hardware; that is the sanctioned way to develop without glasses.

**7. State assumptions in comments, and say what breaks.**
Comments here explain *why* a thing is the way it is — why the watchdog exists, why the payload
is 12 bytes, why enrollment runs admin-to-user. Match that. A comment restating the code is
worse than none.

## Before you open a PR

```bash
./gradlew :core:test
```

That runs everywhere. Building `:app` additionally needs a GitHub classic PAT with
`read:packages` in `$GITHUB_TOKEN` or as `github_token` in `local.properties`, because the DAT
SDK is served from GitHub Packages.

If you could not build or run the app, say so in the PR rather than implying you did.
