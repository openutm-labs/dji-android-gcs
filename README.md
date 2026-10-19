# OpenUTM DJI Ground Control Station

An Android ground control station for DJI drones, built for Flying Labs and drone operators. It connects flight planning, on-device recording and telemetry uploads to [Flight Blender](https://github.com/openutm/flight-blender). The repository contains separate apps for DJI Mobile SDK v4 and v5, with shared screens and a DJI-free core.

## Project status

This is preview software with hardware and flight qualification still in progress. Mini 2 automatic mission execution is disabled in release builds. See the [qualification gates and remaining gaps](docs/15-survey-expansion-and-mini2.md#qualification-gates) for the current validation status.

The reporting scope is Flight Blender network integration. Broadcast Remote ID is not implemented.

## Capabilities

- Flight declarations, pre-flight checks, flight history and export, with telemetry recorded locally and uploaded when a network is available.
- Grid, crosshatch, perimeter, five-direction, corridor and layered-surround survey planning; saved plans and JSON import/export. Execution depends on the aircraft and SDK support.
- Offline basemaps, imagery and terrain from local PMTiles packs, with restriction-zone overlays and planning warnings.
- DJI camera views and settings, flight status and aircraft controls through the appropriate SDK adapter.
- Administrator-provided device setup, pilot rosters and optional Passport pilot sign-in.

## Choose an app

| App | SDK | Aircraft integration targets |
|---|---|---|
| `app-v4` | DJI Mobile SDK v4 | Phantom 4 Pro V2.0, Mavic 2 Pro, Air 2S, Mavic Air 2, Mini 2 |
| `app-v5` | DJI Mobile SDK v5 | Mavic 3 Enterprise, 3T and 3M; Matrice 400 with supported payloads |
| `app-v4` — `sim` flavour | No DJI SDK | Recorded-track replay for development and training |

Aircraft/controller compatibility and mission execution vary by model. Check the [aircraft and mission limits](docs/15-survey-expansion-and-mini2.md) and confirm your equipment with the coordinator.

## Get started

For operators, start with the [APK installation and device setup guide (English / Español)](docs/17-panama-apk-distribution.md). Fresh DJI installs default to Panama; administrators supply each device's private setup and approve Passport access. Offline map packs are installed separately.

For developers, follow [Build and test](#build-and-test) below. The simulator can be used without a DJI aircraft.

## Documentation

- [Architecture and design decisions](docs/12-dji-app-design.md)
- [DJI integration options](docs/11-dji-integration.md)
- [DroneDeploy integration and pilot migration](docs/13-dronedeploy.md)
- [Survey planning, Mini 2 qualification and feature gaps](docs/15-survey-expansion-and-mini2.md)
- [Connect a phone to Panama](docs/16-connect-panama.md)
- [Install, configure and distribute the APKs](docs/17-panama-apk-distribution.md)
- [Bench and field test plan](docs/14-dji-field-test-plan.md)
- [Changelog](CHANGELOG.md)

## Repository layout

| Module | What | Builds on |
|---|---|---|
| `core/` | Domain, flight state as events on disk (single writer), journal, store-and-forward sync, Blender client, F3411 mapping, EGM96, declaration volumes, survey planner, zones. No Android, no DJI | any JVM 17 |
| `adapter-fake/` | `ReplayDroneAdapter`: plays recorded tracks through the adapter contract, with link drops, stale telemetry and GPS loss. Bundled: `tracks/sitl-auto-mission.jsonl` (ArduCopter SITL, recorded by the UTM connector) | any JVM 17 |
| `adapter-dji-v4/` | `DjiV4Adapter` + Waypoint 1.0 `DjiV4MissionOperator` over DJI MSDK 4.18 | Android SDK |
| `adapter-dji-v5/` | `DjiV5Adapter` (key-value telemetry, RTK, DJI GEO zones) + WPML `DjiV5MissionOperator` + `DjiV5Camera` over DJI MSDK 5.18 | Android SDK |
| `app-common/` | The screens, background services, maps and provisioning; each app supplies a `Platform` | Android SDK |
| `app-v4/` | Flavours `dji` (DJI SDK + UI Library, `org.openutm.djiflight.v4`) and `sim` (no DJI code, `org.openutm.djiflight.v4.sim`) | Android SDK |
| `app-v5/` | `org.openutm.djiflight.v5`, arm64 only, for the DJI RC Pro Enterprise and RC Plus 2 | Android SDK |
| `provisioning-tool/` | For lab admins: a device's setup JSON → the file to import and its QR code | any JVM 17 |

## Build and test

You need JDK 17 (on macOS, `brew install openjdk@17`); the wrapper downloads Gradle 8.11.1.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
```

Unit tests, plus Blender integration tests that skip themselves if `:8000` is down:

```bash
./gradlew test
```

For the integration tests, run a local [Flight Blender](https://github.com/openutm/flight-blender) on `:8000` with `BYPASS_AUTH_TOKEN_VERIFICATION=1` first.

The Android modules are included only when an SDK is configured, via `ANDROID_HOME` or `sdk.dir` in `local.properties`:

```bash
./gradlew :app-v4:assembleSimDebug
```

```bash
./gradlew :app-v4:assembleDjiDebug -PDJI_API_KEY=<key>
```

```bash
./gradlew :app-v5:assembleDebug -PDJI_V5_API_KEY=<key>
```

Notes:
- **Default deployment:** fresh DJI installs show Panama (`https://blender.panama.openutm.net`) and require private device credentials or a setup code. The simulator retains local-development onboarding.
- **Development Blender URL:** `-PBLENDER_URL=http://<laptop-ip>:8000` sets the debug-only local-stack button; it does not change the Panama default. Its default `10.0.2.2` is the host as seen from an emulator.
- **DJI app keys:** put `DJI_API_KEY` (for `org.openutm.djiflight.v4`) and `DJI_V5_API_KEY` (for `org.openutm.djiflight.v5`) in `~/.gradle/gradle.properties`, never in this repo. A DJI key is tied to one package name.
- **Debug auth:** debug builds accept a dev token only when a local-development setup is explicitly selected. Blender must use `BYPASS_AUTH_TOKEN_VERIFICATION=1` for it. Panama and release builds use device client credentials (below).

## Set up a device (lab admins)
Write the device's setup as JSON. Two optional fields help pilots coming from DroneDeploy (docs/13):
- `checklist`: the lab's pre-flight checklist, ticked once per flight;
- `imagery`: where the lab processes photos. After each flight with photos, the pilot gets the map name and the upload steps.

`auth` is either `{"type": "client_credentials", "token_url": …, "client_id": …, "client_secret": …}`, using one Passport application per device, or `{"type": "dev"}`, which only debug builds accept.

Map files (`basemap_url`, `terrain_url`, `imagery_url`) are PMTiles made with `tools/maps/region-pack.sh` and hosted next to the lab's other downloads; pilots can also import them from a card (Setup → Offline maps).

`pilot_login` (optional) turns on pilot sign-in through the lab's identity server: `{"issuer": "https://passport.example.org", "client_id": "<public client>", "valid_hours": 72, "required": false}`. Register a public client (authorization code, RS256, PKCE) with the redirect URI `http://127.0.0.1/openutm-signin` (or set `redirect_uri`). Give roster pilots opaque ids, e.g. Passport's user id: a signed-in pilot is matched to the roster by id, then by email, and the roster id goes to Blender. Fields:

```json
{"device_name": "KE-SC-01", "blender_url": "https://utm.example.org", "lab": "Kenya Flying Labs", "operator_id": "OPR-KE-001",
 "auth": {"type": "client_credentials", "token_url": "https://passport.example.org/oauth/token/", "client_id": "…", "client_secret": "…"},
 "pilots": [{"id": "p1", "name": "Amina"}], "aircraft": [{"serial": "…", "registration": "KE-…", "model": "Phantom 4 Pro V2.0"}],
 "basemap_url": "https://…/kenya-basemap.pmtiles", "terrain_url": "https://…/kenya-terrain.pmtiles", "update_url": "https://…/latest.json",
 "checklist": ["Propellers and arms secure", "Batteries charged and seated", "SD card in, enough space"],
 "imagery": {"service": "DroneDeploy", "project": "Kenya Flying Labs maps"}}
```

Then turn it into `out/<device_name>.json` (to import from a microSD card or paste) and `out/<device_name>.png` (a QR code for phones):

```bash
./gradlew :provisioning-tool:run --args="device.json out/"
```

Both outputs contain the device's secret: hand them over directly.

## Release
Once, whoever owns releases creates the key (outside the repo; keep the keystore and password in the organisation's password manager):

```bash
tools/release/make-release-key.sh
```

It writes `OPENUTM_RELEASE_STORE_FILE`, `OPENUTM_RELEASE_STORE_PASSWORD`, `OPENUTM_RELEASE_KEY_ALIAS` and `OPENUTM_RELEASE_KEY_PASSWORD` to `~/.gradle/gradle.properties`. Then build, check and collect both apps' channels:

```bash
tools/release/publish.sh
```

Or build one app at a time:

```bash
./gradlew :app-v4:assembleRelease :app-v4:releaseChannel
```

`app-v4/build/release-channel/` then holds the APKs and `latest.json`; publish them as static files. Without the key, release APKs are unsigned. For v5 (its own channel: point v5 controllers' `update_url` at it):

```bash
./gradlew :app-v5:assembleRelease :app-v5:releaseChannel
```

`-Popenutm.slimNative=true` leaves the SDK's live-streaming libraries out of the v5 APK (about 45 MB); not the default until checked on an aircraft. `.github/workflows/dji-flight.yml` runs the tests on every push and builds signed releases on a `v*` tag (secrets listed in the file).

## Translations
Every screen's text is in `app-common/src/main/res/values/strings_*.xml`. A language is a copy of those files in `values-<code>/` (`values-fr/`, `values-es/`, `values-pt-rBR/`), translated; strings left out stay English. The warnings from the core (`strings_advisories.xml`) are generated from the core's own English by `python3 -I tools/i18n/advisory_strings.py`; translate the generated file's copy, not the core. Debug builds offer Android's pseudo-languages (Settings → System → Languages, "English (XA)") to check every screen with longer text.

## Run the sim app on an emulator
The `sim` app reaches the computer's Blender at `10.0.2.2:8000`: start a local Flight Blender first (as for the integration tests). Then start an emulator (Android 7.0 or later; `openutm-sim-34` is an API 34 image here):

```bash
emulator -avd openutm-sim-34 -no-snapshot -no-audio
```

```bash
./gradlew :app-v4:installSimDebug
```

```bash
adb shell am start -n org.openutm.djiflight.v4.sim/org.openutm.djiflight.app.FlyActivity
```

```bash
adb logcat -s OpenUTMFlight
```

How the run goes:
- The first launch opens Setup: import a setup file made with the tool above, or use the debug link to the local dev stack, then **Start flying**.
- The replay starts at launch and spends 60 s on the ground before the recorded take-off. Tap **Plan flight** during that time, choose Manual or Mapping (tap the corners on the map, drag them to adjust), then **Save and declare**.
- The rail's first slot always shows the next step: the lab checklist, Upload, Go (slide to start), Pause, Resume…. The sim's mission operator flies one waypoint a second, so Pause, Stop and Resume can be tried; the simulated aircraft also has settings and calibrations in **Pre-flight**.
- After landing, tap **Finish**. The app sets the declaration to Ended once Blender has the last batch.
- The last command shows every phase change and sync result.

The v5 debug build has a self-test that runs DJI's own WPML validator on mission files from our writer, no aircraft needed:

```bash
adb shell am start -n org.openutm.djiflight.v5/org.openutm.djiflight.app.v5.WpmlSelfTestActivity
```

## What the tests cover
- **Live Blender** (`BlenderIntegrationTest`):
  - declare (Polygon, `client_ref`) and find it again;
  - activate, a 120-sample back-dated batch, a live batch, Ended; stored count and `height` checked in Postgres;
  - a 300-state batch;
  - telemetry before and after the window → `WindowClosed`, and Ended still accepted;
  - a declaration in the past → rejected;
  - the volume's altitudes stored as sent.
- **End to end** (`PipelineBlenderTest`):
  - the SITL mission replayed at 4× → recorder → journal → sync → Blender, with an 8 s outage mid-flight;
  - every recorded sample is stored, each with height and Ground/Airborne status, and the largest gap is ~1 s.
- **Unit:**
  - EGM96 against PROJ (≤ 4 mm), RFC 3339 against java.time;
  - B3 altitude band, H2 F3411 fields;
  - torn/corrupt journal lines;
  - restart replays to the same state (H1);
  - offline backfill (H3), duplicate-safe declare, window-not-yet-open, window expiry, cancel, backoff;
  - P4 Pro V2.0 survey grid, 99-waypoint split;
  - mission resume after RTH/battery swap, Stop, disconnect and app restart, including mid-way through a split survey;
  - travel altitude legs (no photos on them, declared, WPML climb/RTH heights), the lab checklist, photo counts and the DroneDeploy hand-off;
  - WPML: a Mavic 3E survey → one wayline with the same waypoints, heights and distance-triggered photos; template headings and per-waypoint photos; locale-independent numbers; KMZ layout; camera GSDs against DJI's published figures;
  - zone parsing and proximity;
  - the aircraft's settings against the declaration (RTH above the declared ceiling, height limit, RC-loss action on missions, compass and IMU errors);
  - the sim's paced mission operator (Pause holds, Stop stops) and simulated settings and calibrations;
  - a mission plan's saved identity is the same in every run, so Resume… survives an app restart;
  - crosshatch and perimeter surveys, the M3T, M3M and H30 cameras and their WPML enums;
  - terrain: PMTiles tile ids, Terrarium heights, clearance and height above ground on a synthetic hillside, the in-flight warnings;
  - pilot sign-in: PKCE, the ID token's signature and claims, Passport's userinfo, refused tokens;
  - the pilot's choice of take-off height, and the sea-level check;
  - the map name's local date; setup codes wrapped over lines.

## Tools
- `tools/maps/region-pack.sh`: a lab's basemap and terrain files from Protomaps' and Mapterhorn's free builds, and imagery from an orthomosaic (needs the `pmtiles` CLI; imagery also GDAL).
- `tools/glyphs/BuildGlyphs.java`: the map's label glyphs from a TrueType font (`java -Djava.awt.headless=true tools/glyphs/BuildGlyphs.java <font.ttf> "Roboto Regular" app-common/src/main/assets/glyphs 0 255 …`).
- `tools/i18n/advisory_strings.py`: the warnings' string resources from the core.
- `tools/release/`: the release key and the publishing script (above).

These two need Python with pyproj and the [UTM connector](https://github.com/atti92/utm-adapter)'s files (its EGM96 grid and MAVLink journal):

- `tools/build_egm96_grid.py` regenerates `core/src/main/resources/egm96-15.bin` and the PROJ test fixture from the connector's EGM96 grid.
- `tools/mavlink_journal_to_track.py` turns any armed segment of the connector's MAVLink journal into a replay track:
  - `--list` shows the segments;
  - `--segment N --out …` writes one.

## License

The project's own code is licensed under [Apache 2.0](LICENSE). DJI SDK binaries and other dependencies have separate terms; see [third-party notices](THIRD_PARTY_NOTICES.md).
