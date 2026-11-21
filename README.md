# Y-Lite

An unofficial alternative for the official Y-Connect app. It records rides, logs fuel, and keeps a scooter's history
on your own phone.


## Build

Requirements: JDK 17 or newer, Android SDK platform 35. Gradle comes from the wrapper.

```bash
./gradlew :app:assembleDebug        # APK at app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # JVM tests for the parsers and fuel maths
```

Versions are pinned in `gradle/libs.versions.toml` and `app/build.gradle.kts`:
AGP 8.7.3, Gradle 8.9, Kotlin 2.0.21, KSP 2.0.21-1.0.28, Compose BOM 2024.10.01,
Room 2.6.1 (the last release that works with Kotlin 2.0.x under KSP1), osmdroid 6.1.20.

## Install

Grab `ylite-debug.apk` from the latest release



## Data

The database `ylite.db` in the app's private storage, currently schema **v7** with
hand-written migrations covering every version since v1, so upgrades never drop rides
or fill-ups.

Exports land in `Android/data/app.ylite/files/exports/` and are shared through the
system sheet.

## Known gaps

- **The telemetry decoder is a stub.** It keeps the raw bytes and returns no values
  until the CCU's payload layout is known from captured frames. Everything that does
  not depend on it — GPS, fuel, maintenance — works today. The Debug tab's LINK LOG
  shows whether the notification subscription actually succeeded, because a CCCD write
  that fails looks exactly like a quiet CCU: `subscribed ok` with no frames is a silent
  CCU, `subscribe failed (status N)` is a rejected one, and a drop carries Android's
  status with a short explanation.
- **No bulk offline map download.** OpenStreetMap's tile policy forbids it and osmdroid
  enforces that, so the map relies on its normal cache of tiles you have already seen.
- **Writes are limited to the handshake and heartbeat.** No other opcodes are sent or
  probed. See *What it sends to the scooter* above.
- **No ride auto-start.** Start and stop are manual; a killed process leaves a ride
  with no end time, which History shows as `NO END`.
- **Detecting scooter model might be impossible as of the moment** No workaround as of the moment as this is something the YamahaMotorOn app has and it is closed source.



Not affiliated with Yamaha. Nothing here claims to satisfy Yamaha's terms; it is a
personal tool that talks to a device you own using the standard Bluetooth protocol
the CCU already exposes.


#AI disclosure

roughly 70-80% of this project were AI assisted with my own supervision and carefully reviewing each of its output. If you're against AI-made tweaks/apps then this is not for you. These projects were just a personal projects of mine and just love to share it with y'all :)
