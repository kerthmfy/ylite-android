# Y-Lite

An unofficial alternative for the official Y-Connect app. It records rides, logs fuel, and keeps a scooter's history
on your own phone.


## What it does today

**Rides.** Start/stop a ride from the Ride tab. GPS fixes are recorded once a second,
distance is accumulated, and the map follows you. The map gets a light location
subscription while the tab is open even before a ride starts, so it is not blind while
you are parked. Every ride is kept in History with duration, distance, max speed and
the track drawn on OpenStreetMap tiles.

**Scooter link.** Scan for the CCU, bond with it, and every notification it sends is
recorded. A live dashboard shows trip, odometer, fuel, temperature, battery and elapsed
time — blank (`—`) until the decoder knows where the bytes live, never a fake zero.
Debug → **CAPTURE FRAMES** stores notifications without starting a ride, and
**EXPORT CAPTURE** writes them to a CSV, which is how a parked capture is collected.

**Appearance.** System, light or dark, chosen in Scooter → Settings.

The CCU is remembered after the first connection, so Y-Lite reconnects by itself when
the scooter is switched on — including after a relaunch. It retries indefinitely,
backing off to one attempt every 30 s, because "no answer" is simply a parked scooter
with the ignition off. Debug → **RECONNECT** starts over on demand, and the **LINK LOG**
timestamps every attempt, drop and status code in plain words.

**Fuel.** Log fill-ups with a station, full/partial tank, and any two of litres,
price per litre and total (the third is computed, the same way Fuelio does it).
Consumption is calculated across full tanks only, so a top-up does not invent a tank.

The odometer can come from the dash, from the **trip meter** (added to the last reading
for you), or from neither: Scooter → Settings → **Odometer optional** puts a NO READING
switch on the form, so a receipt with litres and money is enough. Rows without a
reading stay out of the km/L maths instead of quietly skewing it.

**Maintenance.** Oil, CVT belt and rollers, air filter, spark plug and brake fluid to
start with — but the list is yours: add a job, change how many kilometres it comes
round, rename it, delete it. All of it is measured from your odometer, with overdue
warnings and a DONE button that logs the service at the current reading. Tap a job to
see its whole history — every service with its date and odometer — and to remove a
wrongly logged one.

**Where you parked.** The last fix of your last ride, with a button into your maps app.

**Import and export.** Fuelio's backup CSV imports directly, and the station in its
"City" column becomes a station here: names you have not used before are created and
starred as favourites, so past stations and their visit counts appear without retyping.
Trailing separators are cleaned off (`Petron -` becomes `Petron`), and any station can be
renamed from the picker — renaming to a name that already exists merges the two and adds
their visits together.
Everything exports as one zip: GPX per ride, raw CCU frames per ride, and CSV logs for
rides, fill-ups, services and stations.

## What it sends to the scooter

Y-Lite sends only the handshake and heartbeat frames seen from the official app:

- the two 60-byte handshake frames, once per connection, right after subscribing to
  Nordic UART `6e400003` and raising the MTU. The CCU hangs up ~2 s after connect without them.
- heartbeat A and B once per second while connected (they carry the phone's clock).

Nothing else is ever written, and no other opcodes are probed. The handshake frames contain
the CCU serial and a per-pairing token, so they are not in this repo: paste them under
**Scooter › CCU handshake** from your own capture of the official app. They are stored only in
the app's settings on the phone.

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

Grab `ylite-debug.apk` from the latest release, or:

```bash
adb install -r ylite-debug.apk
```

Releases are debug-signed, so they upgrade in place over each other but not over a
release-signed build.

## First run

1. Grant location, Bluetooth and notification permissions.
2. Scooter tab → **CHANGE MODEL** → pick your scooter.
3. Scooter switched on and parked → **MANAGE** → **SCAN** → tap the `YSCCU_…` entry.
   Android will bond with it; the PIN is usually `000000`. If a pairing attempt went
   wrong, forget the device in Bluetooth settings first.
4. Ride tab → **START RIDE**.

The CCU only powers up with the ignition on, and only one client can hold the link, so
keep the official app closed while Y-Lite is connected.

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
- **No write support** to the CCU. That is a design decision, not a missing feature.
- **No ride auto-start.** Start and stop are manual; a killed process leaves a ride
  with no end time, which History shows as `NO END`.
- **Detecting scooter model might be impossible as of the moment** No workaround as of the moment as this is something the YamahaMotorOn app has and it is closed source.



Not affiliated with Yamaha. Nothing here claims to satisfy Yamaha's terms; it is a
personal tool that talks to a device you own using the standard Bluetooth protocol
the CCU already exposes.
