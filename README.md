# GlassesGate: Ray-Ban Meta Ticket Validator

GlassesGate is a proof-of-concept Android application that turns Ray-Ban Meta glasses into digital "tickets" for access control. Using Bluetooth Low Energy (BLE) scanning, the app validates nearby wearables to grant or deny access.

## Features

- **BLE Scanning:** Automatically scans for nearby Ray-Ban Meta glasses using standard Android Bluetooth APIs.
- **Validation Modes:**
    - **Any Glasses:** Grants access if any device with the "Ray-Ban Meta" name is detected.
    - **Specific IDs:** Grants access only to specific Bluetooth MAC addresses pre-configured in the settings.
- **Visual Feedback:** 
    - **GREEN (Access Granted):** Valid glasses detected.
    - **RED (Access Denied):** No valid glasses found within the scan window.
    - **GRAY (Bluetooth Off):** Bluetooth is disabled on the host device.
- **Settings Management:** Persistent configuration using `SharedPreferences`.

## How It Works

The app performs a 5-second BLE scan. During this window:
1. It listens for advertisement packets from nearby devices.
2. It checks the device name or MAC address against the active `ValidationMode`.
3. If a match is found, the UI immediately updates to "ACCESS GRANTED" and the scan stops.
4. If the timer expires without a match, it displays "ACCESS DENIED".

## Permissions

The app requires the following permissions (handled dynamically for API 31+):
- `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT`
- `ACCESS_FINE_LOCATION` (Required for BLE on older Android versions)

## Getting Started

1. Clone this repository.
2. Open in Android Studio.
3. Build and run on an Android device with BLE support.
4. Grant the necessary permissions when prompted.
5. (Optional) Go to **Settings** to switch to "Specific Device IDs" mode and add your glasses' MAC address.

## Future Integration

This app is designed as a template. You can easily swap the raw BLE scanning logic with the official **Meta Wearables SDK** once you have access to their client libraries for more robust device verification and secure handshake protocols.

---
*Disclaimer: This is a community-driven project and is not affiliated with Meta Platforms, Inc.*
