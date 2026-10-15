# What GlassesGate actually proves

Read this before deploying it anywhere that matters.

## The claim

When the gate shows green, this is what has been established:

> A phone that holds a credential this gate issued is within Bluetooth range, **and** that phone
> currently has a live Meta Wearables session open with the specific pair of glasses the
> credential was bound to, **and** the SDK reports that pair as linked and not taken off.

That is a genuine, useful claim. It is not the same as "these glasses are cryptographically who
they say they are", and the difference matters.

## Why it isn't stronger

Meta's Wearables Device Access Toolkit does not expose a hardware attestation primitive. There is
no API that lets a pair of glasses sign a challenge, and no way for a third-party device to
observe glasses it is not itself paired with. The strongest available signal is *the SDK reports
an active session*, which is what this app uses.

Concretely, the trust chain is:

```
glasses ←──DAT session──→ wearer's phone ──BLE advertisement──→ gate phone
```

The gate trusts the wearer's phone to be honest about the session. A modified build of this app,
running on a rooted phone, could advertise without one.

## What the design does buy you

- **Presence is required, not just possession.** The beacon stops within seconds when the glasses
  are folded, taken off, powered off, or out of range. Handing your phone to a friend does not hand
  over your credential unless you hand over the glasses too. The rule is a pure function in
  `core/BeaconPolicy.kt`, and `BeaconPolicyTest` checks every combination of its inputs.
- **"Taken off" means reported as taken off.** The SDK can also answer *unknown*, which it does
  while its control channel warms up. Unknown is allowed through, because requiring a positive
  "worn" would leave the beacon dead on any pair whose wear sensor never reports. This is the one
  place the policy chooses availability over strictness; tightening it is a one-line change in
  `BeaconPolicy.decideBound` if your deployment wants it.
- **Replay is bounded.** Tokens rotate every 15 seconds and are accepted one window either side,
  so a captured advertisement is useless after at most 45 seconds. `BeaconProtocolTest` asserts
  this ceiling; if the constants change, that test fails.
- **The wire carries nothing identifying.** The advertisement is 12 bytes: a 4-byte hash-derived
  tag and an 8-byte HMAC. No name, no serial, no device id. Someone recording the radio learns
  that *a* GlassesGate credential passed by, not whose.
- **Nothing leaves the device.** No backend, no analytics, no crash reporting, no sync. Both roles
  work with the phone in airplane mode once enrolled.
- **Secrets are encrypted at rest** via `EncryptedSharedPreferences` with a keystore-backed master
  key, and backup and device-transfer are both disabled so a credential cannot be restored onto a
  second phone.
- **One credential per pair.** Enrolling a serial again revokes that serial's previous credential
  in the same write, so recovering from a lost phone also locks the lost phone out.
- **Revocation sticks.** The gate checks against a snapshot of the allowlist, so a sighting can
  land just after a revoke. Recording that sighting is refused for an entry that no longer
  exists, rather than writing the revoked credential back.

## Known weaknesses, stated plainly

**The enrollment QR is a bearer credential.** For the ten minutes it is valid, whoever scans it
first gets the credential — including someone photographing it over the admin's shoulder. The
screen showing it blocks screenshots and screen recording, counts down, and clears itself at
expiry, but none of that stops a second phone's camera. While it is on screen it is a key on a
screen.

**A relay attack works.** Two attackers, one near the wearer and one near the gate, forwarding
advertisements between them, will open the gate. The protocol has no distance bounding, and BLE
signal strength is not a reliable substitute. If this matters for your deployment, GlassesGate is
not sufficient on its own.

**A compromised wearer phone is a compromised credential.** The secret lives on the phone. Root
access, or a modified build of this app, defeats the session requirement.

**Revocation is per-gate and takes effect only on that device.** There is no broadcast revocation
because there is no server. Revoking at the front door does not revoke at the loading dock.

**The serial number is not verified.** The admin types it in. Meta's SDK gives no way to confirm a
serial belongs to the glasses in question, so a wrong serial is simply a wrong label — and,
because re-enrolling a serial revokes the old credential, a mistyped serial means the replacement
does not replace anything. The gate screen shows the serial on admission so staff can check it
against the glasses on the person's face, which is the one check the radio cannot make.

**No rate limiting on the gate.** An attacker in radio range can replay a captured token as often
as they like within its window.

## Where this is appropriate

Ticketing, event entry, member check-in, soft office access, anywhere a staffed door or a camera
is also part of the picture. It raises the bar meaningfully above showing a screenshot of a
barcode.

## Where it is not

Anywhere a hardware security key, a smart card, or a properly attested credential is the right
answer. Do not use this alone for anything whose compromise is expensive.

## Reporting a problem

Open an issue at https://github.com/fvtale/GlassesGate/issues. This is a community project with
no security team and no response-time commitment — please do not report anything here that you
would not be comfortable seeing in public.
