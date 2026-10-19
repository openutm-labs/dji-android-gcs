# 14 · DJI flight apps: bench and field test plan

**As of:** 2026-10-18. For apps 0.4.0 (v4 `dji`, v5). Everything here needs hardware that the emulator tests don't have. Each check closes one or more items in [12 · Open items](12-dji-app-design.md#open-items). Fill in the record sheet at the end as you go.

Order: controller facts first, then the bench with props off in DJI's simulator, then first flights, then a full survey. Don't move to the next stage until the one before has passed.

For the 0.5.0 development expansion, run the separate [Mini 2 qualification gates](15-survey-expansion-and-mini2.md#qualification-gates). The 0.4.0 native-mission checks below do not establish Virtual Stick qualification.

## Before you start

**People:** a pilot and a second person who watches the aircraft and fills in the sheet.

**Safety:**
- Props off for every bench test.
- In the field, follow the country's rules and the lab's own procedures: VLOS, an open area, observers briefed.
- The app commands the aircraft only through DJI's mission operator, take-off and RTH. The pilot's sticks and DJI's RTH always override it.

**Equipment:**

| Stage | v4 (`dji` build) | v5 |
|---|---|---|
| 0–1 | P4 Pro V2.0 + DJI Smart Controller; also the standard RC + an Android phone (`getconf PAGE_SIZE` = 4096) | Mavic 3E + RC Pro Enterprise; M400 + P1 or L2 (and H30 if the lab has one) + RC Plus 2 |
| 2–3 | Same, charged batteries (at least 3), an SD card with space, a surveyed point (a benchmark with a published height) | Same |

**Software:**
- Signed release APKs, from `tools/release/publish.sh`.
- A device setup per controller from the lab admin, made with `provisioning-tool`. It should include the lab's basemap, terrain and imagery files, made with `tools/maps/region-pack.sh`.
- Optionally, `pilot_login` pointing at the lab's Passport.
- A Blender the controller can reach: the lab's instance, or a local one through a tunnel.

**What to keep from every session:**
- `adb logcat -s OpenUTMFlight DjiV4Adapter DjiV5Adapter` saved to a file, with the date and controller in the name.
- The app's GeoJSON and CSV export of each flight (Flights → Export).
- DJI's own flight record. Every SDK app writes one.
- Screenshots of anything unexpected. Note the time from the controller's clock.

## Stage 0: controller facts (10 minutes per controller)

| # | Check | How | Pass / record |
|---|---|---|---|
| 0.1 | Screen size and density | `adb shell wm size`, `adb shell wm density` | Record both. The screens were judged at 640 × 360 dp, the worst case assumed |
| 0.2 | Android version and page size | `adb shell getprop ro.build.version.release`, `adb shell getconf PAGE_SIZE` | Record. A v4 phone needs 4096 |
| 0.3 | WebView present (pilot sign-in) | Setup with `pilot_login` → Plan → Flight details → Sign in | The identity server's page opens. Record how long the first open takes |
| 0.4 | Install and update path | Install 0.3.0, then update to 0.4.0 from the release channel (Setup → App updates) and from microSD | Both work. Flights from 0.3.0 still listed |

## Stage 1: bench, props off, DJI's simulator

Aircraft on USB or connected, DJI simulator on only where the exact model supports it: DJI Assistant 2 or the Mobile SDK simulator for v4, DJI's simulator control in the v5 app. Mini 2 needs the separate SDK simulator check in [the experimental procedure](15-survey-expansion-and-mini2.md#developer-sdk-simulator-bench); Assistant installation is not evidence of simulator support.

| # | Check | How | Pass |
|---|---|---|---|
| 1.1 | DJI registration, online and then offline | First launch online. Then Wi-Fi and mobile data off, relaunch, connect the aircraft | Registers online. Offline it still connects; note any registration message. Re-check monthly (cache lifetime) |
| 1.2 | Identity strings | Connect; logcat shows model, serial, `ProductType`/`CameraType` (v5) | v4: `Model.getDisplayName()` for the P4 Pro V2.0 is in `WAYPOINT_V1_MODELS`; a Mini 2 gives no missions. v5: M3E/M3T/M3M, M400 payloads are recognised (Plan offers Mapping with the right camera) |
| 1.3 | Live video | Fly screen, camera full size, swap to map, swap back | Video in both apps (v4 UX SDK view; v5 DJI's FPV view). If not: v4 → `DjiV4Video`, v5 → `V5CameraView` |
| 1.4 | DJI's widgets and panels | v4: tap DJI's status (top left), the checklist panel opens. v5: DJI's status list, settings pages, simulator control | Panels open and close. No `NoClassDefFoundError` in logcat when a UX SDK list opens (v4) |
| 1.5 | Aircraft settings read and write | Pre-flight: read every setting. Change RTH height (e.g. 40 → 60 → 40) and RC-loss action. Close, reopen | Values read back as written. Compare with DJI's own settings page (v5) |
| 1.6 | IMU calibration | Pre-flight → IMU → Calibrate, aircraft level | The stages show in the calibration sheet; it ends Succeeded |
| 1.7 | Gimbal calibration | Pre-flight → Gimbal → Calibrate | Same |
| 1.8 | Obstacle sensing (M3E, M400) | Toggle in Pre-flight, compare with DJI's settings | Matches |
| 1.9 | Survey mission in the simulator | Plan a 200 × 200 m grid at 80 m, travel altitude 100 m → declare → Upload → Go (slide) | Climbs to the travel altitude over the aircraft, flies to the first line, flies the grid, climbs and returns home. Progress n/m on the Fly screen |
| 1.10 | Crosshatch and perimeter | Same area: Crosshatch (gimbal −70°), then Perimeter (−45°) | Crosshatch flies both passes; perimeter faces the middle all the way round (watch the heading in DJI's view) |
| 1.11 | Pause, Resume, Stop | During 1.9 | Each does what it says; Stop then Resume… offers "from waypoint n" |
| 1.12 | RTH mid-mission, battery swap, resume | During 1.9 trigger RTH, land, power-cycle, reconnect, Resume… | Resumes from the last waypoint reached; no gap in the lines. Record what the SDK reported (logcat) |
| 1.13 | RC signal loss | During 1.9 switch the RC off for 10 s | The aircraft leaves the mission and returns home (its own failsafe). The app shows the link loss and keeps recording |
| 1.14 | Photo counting | Survey with photos in the simulator (or on the ground with props off) | The card's photo count matches the SD card |
| 1.15 | v5 slim build | Build with `-Popenutm.slimNative=true`, repeat 1.1, 1.3, 1.9 | Same results; the APK is about 45 MB smaller |
| 1.16 | Background on Android 7 (Smart Controller) | Plan and take off in the simulator, lock the screen for 5 min; then close the app with samples pending and turn Wi-Fi on later | Recording continues with the screen locked; samples upload later without opening the app |

## Stage 2: first flights (manual)

| # | Check | How | Pass |
|---|---|---|---|
| 2.1 | Take-off height against a surveyed point | Set the aircraft on the benchmark. Before take-off, record from Pre-flight: the controller GNSS, the aircraft's (DJI) and the terrain model's heights, and the benchmark's published height. Repeat on 3 days | Write down all four. Settles DJI's `takeoffLocationAltitude` datum and the controller's GNSS datum. If the controller is off by about the geoid height, it reports sea level (Pre-flight says so when the terrain model agrees) |
| 2.2 | Compass calibration outdoors | Pre-flight → Compass → Calibrate, away from metal | Stages shown, Succeeded, and the compass error clears |
| 2.3 | Manual flight end to end, online | Plan manual → declare → checklist → fly 10 min within VLOS → land → Finish | Declared, Live to UTM, all samples in Blender (compare the count with the export), Closed |
| 2.4 | Same, offline | Mobile data and Wi-Fi off before planning; declare later | "Not declared yet: offline", flight recorded; on reconnect it declares (if the window allows), uploads and closes |
| 2.5 | Height above ground on a slope | Fly from a hilltop out over lower ground, staying within the legal height above take-off | "About N m above the ground here (terrain model)" appears where the ground drops more than the margin |
| 2.6 | Load budget | `adb shell top -H -n 1`, `dumpsys cpuinfo`, `dumpsys netstats detail`, `dumpsys batterystats` during and after a 20 min flight | CPU < 5 % of a core beyond video; ≤ 3 MB data per flight hour; no wake-ups after the flight |
| 2.7 | Zone warnings | Plan near a zone loaded in Blender, and near a DJI GEO zone | The plan shows "Touches …"; in flight "… m from …" appears within 100 m |

## Stage 3: a full survey

| # | Check | How | Pass |
|---|---|---|---|
| 3.1 | Survey with a battery swap | A survey that needs 2 batteries (Plan shows the estimate); swap at RTH, Resume… | One declaration, both parts flown, no gap in the photos (check the orthomosaic) |
| 3.2 | Terrain clearance | Plan over sloping ground with the lab's terrain file | Plan shows the ground range and the lowest clearance; it matches what the aircraft's height above ground showed in DJI's view |
| 3.3 | Photos to DroneDeploy | After 3.1: the photo sheet → map name → upload from the SD card | The map name finds the flight; the photo count matches |
| 3.4 | M400 payloads | Repeat 3.1 with the P1 (35 mm lens) and with the L2; with the H30 if available | Missions upload and run; photo spacing as planned; the P1 profile's lens is right |

## Stage 4: pilot sign-in (if the lab's Passport is set up)

| # | Check | How | Pass |
|---|---|---|---|
| 4.1 | Sign in online | Plan → Flight details → Sign in, with the pilot's Passport account | "Signed in: <name> · until …" and the flight's export shows `pilot_signed_in: true` |
| 4.2 | Offline after sign-in | Sign in, then fly offline within `valid_hours` | The flight still records the signed-in pilot |
| 4.3 | Shared controller | Pilot A signs in, signs out; pilot B signs in | The page asks B for a password (no leftover session) |

## Record sheet

Copy this table per controller and aircraft.

| # | Date | Controller / aircraft / app version | Result (pass / fail / numbers) | Logs and notes |
|---|---|---|---|---|
| 0.1 | | | | |
| … | | | | |

Send the sheet and the logs back to the OpenUTM team. Each result goes into [12 · Open items](12-dji-app-design.md#open-items) and closes or changes an item there.

## What we still need from the labs
- Which aircraft and controllers each lab flies, with the Android versions (Stage 0 answers most of this).
- Which mission types they use besides mapping: crosshatch and perimeter are now built; corridor, facade and orbit are not.
- Whether they process in DroneDeploy, and on which plan (Phase B needs an Enterprise key and DroneDeploy's written OK: [13](13-dronedeploy.md)).
- A surveyed point near each lab for 2.1.
- Who in each lab will hold Passport accounts for pilots, once the programme decides on per-lab instances ([H5](12-dji-app-design.md#risk-labels)).
