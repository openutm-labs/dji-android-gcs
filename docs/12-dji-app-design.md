# 12 · DJI flight app for Flying Labs (Android, Mobile SDK v4 and v5)

**As of:** 2026-10-18 (apps 0.4.0). Design agreed on 2026-10-16; app built in [this repository](../) on 2026-10-16/17, the v5 app on 2026-10-17, the screens redesigned on 2026-10-18 ([The app](#the-app)). Added on 2026-10-18: terrain heights and checks, terrain following, map text labels and hillshade, crosshatch and perimeter surveys, M3T/M3M/H30 survey cameras, pilot sign-in (OIDC + PKCE), the pilot's choice of take-off height, translatable strings, release tooling.
- **Tested:** the core and the replay adapter, 108 JVM tests, 7 of them against a local Flight Blender.
- **Run on an Android 14 emulator against a local Flight Blender** (`sim` build, and the `dji` build without an aircraft):
  - device setup from a file;
  - manual flight: plan → declare → fly → finish;
  - mapping mission: draw → grid preview → declare → upload → run;
  - restriction-zone warnings, GeoJSON export, the update check, DJI SDK registration, and DJI's UI Library widgets;
  - every flight's samples all reached Blender.
- **v5 app** (Mavic 3 Enterprise, Matrice 400), on the same emulator: DJI's own WPML validator accepts every mission file the app writes, the SDK starts and registers, and the screens run, with DJI's own UI Library v5 (DJI Pilot 2's status bar, camera controls, settings and status list) since 2026-10-18. See [v5 app](#v5-app-msdk-518).
- **Real map data and identity server** (2026-10-18): a Protomaps OpenStreetMap extract and a Mapterhorn terrain extract of the sim area render with labels and hillshade; terrain heights and checks come from the real terrain file; pilot sign-in runs against a local Flight Passport.
- **Not yet run on hardware:** the `dji` build with a real P4 Pro V2.0 (Smart Controller, RC + phone), and the v5 app with a Mavic 3E or M400. See [Open items](#open-items) and the [bench and field test plan](14-dji-field-test-plan.md).
This is option D of [11 · DJI integration](11-dji-integration.md): our own flight app, which replaces DJI GO 4 / DJI Pilot on the pilot's controller.

Evidence labels (risk labels such as B3 or H1 are under [Risk labels](#risk-labels)):
- **Live:** reproduced on a local OpenUTM stack (Flight Blender `54636aa`).
- **Code:** read in source.
- **Doc:** from DJI or vendor documentation, re-checked 2026-10-16.
- **Unverified:** not confirmed; each has a check in [Open items](#open-items).

## Decisions

| # | Topic | Decision | Main reason |
|---|---|---|---|
| 1 | Scope | Manual VLOS flying **and** Waypoint 1.0 missions (survey grids, waypoint lists) on the models that support them | Labs fly mapping; the test aircraft (P4 Pro V2.0) supports Waypoint 1.0 |
| 2 | Path to Blender | **Direct HTTPS from the device**, behind a narrow `UtmBackend` interface | The connector is a laptop-local service with no inbound auth, and its model drops what DJI gives us (below) |
| 3 | Offline | **Declare the window in advance**; journal everything on the device; upload whenever online; close when the backlog is in. Plus a drafted Blender feature request for importing completed flights | Blender accepts back-dated samples while a declaration's window is open (Live) |
| 4 | Maps and zones | **MapLibre Native + one local PMTiles file per lab region**; Blender geozones cached on the device; DJI's own zones as a second layer; warnings only | No paid map APIs, no online tile fetching, works with no network |
| 5 | Altitude | **RTK fix if present, else the phone/controller GNSS (WGS-84) cross-checked with DJI's take-off altitude + EGM96** | Blender wants WGS-84; DJI's datum is unverified |
| 6 | Auth | **One OAuth client credential per device, provisioned by QR code**; pilot chosen from a lab roster on the device, or (2026-10-18, optional) **signed in through the lab's identity server** (OIDC + PKCE), which only says who flies | Tokens can be fetched at any sync without a person; works after long offline periods |
| — | Distribution | Signed APK on GitHub Releases + in-app check of a static `latest.json`; microSD/USB install where offline | v4 can't go on Google Play (16 KB pages) |
| — | Builds | One DJI-free core, thin adapters, one APK per SDK generation; the screens in a shared `app-common` library | v4 and v5 can't share an APK ([11](11-dji-integration.md#d-our-own-mobile-sdk-app)) |
| — | Camera and flight controls (v5) | **DJI's UI Library v5, vendored without its map kit** (`uxsdk-v5`, 2026-10-18): DJI Pilot 2's status bar in our top bar; its FPV view with tap-to-focus, exposure bar, capture controls, lens switch and zoom, take-off/RTH; its settings (flight controller, obstacle sensing, RC, video link, battery, gimbal, camera, RTK, with DJI's compass, IMU, RC and gimbal calibration), system status list and simulator control in a panel over the Fly screen. Our Pre-flight panel stays and opens DJI's settings. The SDK's decoder into our own view (`V5CameraView`, Photo and Rec) stays as the fallback. *Replaces "no DJI UI Library in v5" (2026-10-17).* | Pilots on the RC Pro Enterprise and RC Plus 2 get DJI Pilot 2's own screens, as v4 pilots get DJI GO 4's. DJI ships it only as source, under the MIT licence of its sample code (not the SDK's EULA). Only its map kit (Google Maps and MapLibre 10 providers), `MapWidget` and sample screens use Play services or MapLibre, so they are left out; nothing else conflicts (it targets MSDK 5.18.0, as we do). Cost: +13 MB on the release APK (161.7 → 174.7 MB), mostly DJI's images |
| — | Camera and flight controls (v4) | **DJI's UI Library (UX SDK v4.16.2)** in the `dji` build: camera view, capture controls, exposure panels, status bar, take-off/RTH, pre-flight checklist. Its map widget and its map SDKs (Google Play services, AMap, Mapbox) are excluded | DJI-GO-style camera control without writing it. Last release 2022-09, built against MSDK 4.16.2: compatibility with 4.18 is checked on the emulator, still to check on aircraft |
| — | Screens (2026-10-18) | **DJI's layout, our own views:** status bar on top, flight actions on the left in three fixed slots, camera controls on the right, telemetry along the bottom, map/camera inset at bottom left; map-first Plan with a side panel; sheets instead of stock dialogs; slide to start a mission. Plain Android views and hand-drawn vector icons, no new libraries. In v4 `dji` and in v5, DJI's own status widgets sit in our top bar | Pilots come from DJI Pilot 2, Fly, GO 4 and DroneDeploy; the same screens in all three apps; the Smart Controller's 640 × 360 dp screen showed no control without scrolling ([audit](#screens-audit-and-redesign-2026-10-18)) |
| — | Terrain (2026-10-18) | **One more local PMTiles file per lab: Terrarium height tiles cut from Mapterhorn** (Copernicus GLO-30 and finer national models). Read by the core itself, no Android or DJI code | Ground heights when planning away from the site, the route's clearance over hills, height above ground (the legal reference) in flight, hillshade. Same tool and format as the basemap |
| — | Pilot sign-in (2026-10-18) | **OIDC authorization code + PKCE in an in-app web view**, optional per device setup (`pilot_login`); the device credential still does every upload | Says who flew without making syncs depend on a pilot's token (Passport's last an hour). A web view because DJI's controllers may have no browser |
| — | Survey patterns (2026-10-18) | **Grid, Crosshatch, Perimeter** from the same polygon; one mission each | Labs asked for DroneDeploy's 3D options ([13](13-dronedeploy.md)): crosshatch and perimeter are what its "Enhanced 3D" combines |
| — | Strings (2026-10-18) | **Every screen's text in Android string resources** (`app-common/src/main/res/values/strings_*.xml`); the core's warnings carry a key and arguments for the app's translation, English as fallback | The programme's labs work in Portuguese (Brazil), French (Senegal, Togo), Spanish (Panama) and English; a translation is a resource folder, not a code change |
| — | Aircraft settings and calibration | **A DJI-free `AircraftSetup` contract** (core), implemented in all three adapters: RTH height, height and distance limits, RC-loss action, battery levels, obstacle sensing, home point, compass / IMU / gimbal calibration. Written only on the pilot's tap and read back. The core checks them against the declaration (RTH above the declared ceiling) | DJI Pilot 2 and GO 4 pilots expect them one tap from the flight view; DJI's v4 panel lacks RTH height, RC-loss, battery levels, IMU and gimbal calibration, and v5 and `sim` had none. A new kind of command (settings writes), next to the mission operator and take-off/RTH |

### Why not through the connector (Code, [`utm-adapter`](https://github.com/atti92/utm-adapter) `9f45f65`)
- It is a FastAPI service on each lab's GCS laptop, with **no authentication on its own API**. A phone in the field would need that laptop on the same network, or a hosted connector per lab: a new backend service, and one that would have to grow auth.
- Its `VehicleTelemetry` has no height, ground/airborne status, vertical speed, pilot location or batching (H2).
- A `Mission` needs at least 2 waypoints; a manual flight has none.
- It drops a sample after 3 retries (H3), builds the altitude band from waypoint min/max (B3), and has the completion race (H1).

Keeping `UtmBackend` narrow (declare, set state, send samples, list zones) means a connector or UTM-adapter backend can be added if OpenUTM standardises on one.

## Test targets

| Aircraft | Controller / screen | Android | Status |
|---|---|---|---|
| **Phantom 4 Pro V2.0** | **DJI Smart Controller (RM500)** | 7.x (DJI: "still 7 or 6") → `minSdk 23` | Primary. DJI lists the P4 Pro V2.0 as the only Phantom it pairs with (Doc) |
| **Phantom 4 Pro V2.0** | Standard RC + **Android phone** | to be measured | Primary |
| Phantom 4 Pro V2.0 | Standard RC + **iPad** | — | **Not supported.** v4 iOS development stopped 2023-11-29; iPad pilots need an Android phone |
| **Mavic 2 Pro** | Mavic 2 RC + Android phone, or the Smart Controller | as above | v4 app, manual flights **and** Waypoint 1.0 missions (DJI hardware table) |
| **Air 2S** | RC-N1 + Android phone, or the Smart Controller | as above | v4 app, manual flights only: no waypoint missions in MSDK v4 (DJI firmware). DJI's class reference marks GEO zone unlocking unsupported on it |
| **Mavic Air 2** | RC-N1 + Android phone, or the Smart Controller | as above | v4 app, manual flights only: no waypoint missions in MSDK v4 (DJI firmware) |
| **Mavic 3 Enterprise** | DJI RC Pro Enterprise | Android (arm64) | **v5 app** (MSDK v5 since 5.1.0): manual flights and WPML mapping missions with the M3E wide camera, the M3T wide camera, and the M3M (spacing from its multispectral cameras; RGB and multispectral photos stored) |
| **Matrice 400** | DJI RC Plus 2 | Android (arm64) | **v5 app** (MSDK v5 since 5.15.0): manual flights; WPML mapping missions with a Zenmuse P1 (35 mm lens assumed), L2, H30 or H30T (wide lens) on the main gimbal |
| **Mini 2** | RC-N1 + **Android phone**, or the Smart Controller (DJI lists the Mini 2 as compatible) | as above | Second target: manual flights and logging only. MSDK v4 has no waypoint missions on it (DJI firmware), so the Plan screen disables Mapping mission once a Mini 2 is connected, and the Fly screen says why. Unverified on a Mini 2: whether `takeoffLocationAltitude` is reported, DJI GEO zone reads, the UX SDK widgets, and the model name the SDK reports |

Phone choice: v4.18's native libraries are 4 KB-aligned, so they **won't load on phones with 16 KB memory pages**. Before a lab buys a phone, check `getconf PAGE_SIZE` over `adb shell` returns `4096`. DJI says v4 "will not be adapted to Android 15 or 16 KB page sizes" (Mobile-SDK-Android #1352, 2026-02-11), so test any Android 15+ phone before use.

## Pilot flow

1. **Set up once (online):** import the device's setup from the lab admin, as a QR code (phones) or a file / pasted text (the Smart Controller has no camera). It holds the Blender URL, the device's credential, the lab and operator, the pilot and aircraft lists, and the map-file and update URLs. The app then downloads the map files; EGM96 is already in the APK, and zones are fetched as soon as there is a position.
2. **Plan (online if possible):**
   - Choose the pilot (or sign in, where the lab has an identity server), then the operation:
     - **manual:** a circle of radius *r* (default 500 m) around the take-off point, or a site polygon from KML/KMZ;
     - **mission:** a survey polygon turned into a grid, a crosshatch or a perimeter, or a waypoint list.
   - Set the window (start, planned end) and max height.
   - The app warns if the area touches a cached zone, and, with the lab's terrain file, if the route comes close to rising ground or goes higher above the ground than the legal limit.
3. **Declare** while online, up to 48 h ahead. The flight then shows *Declared*. If there is no network, it shows *Not declared*, and the pilot sees exactly what that means for the record (below).
4. **Pre-flight:** aircraft serial read and matched to the declaration; altitude reference resolved (and cross-checked); GPS level; home point; battery; mission uploaded (missions only).
5. **Fly:**
   - live video and status;
   - mission start/pause/resume/stop;
   - samples recorded at 1 Hz, plus one at each event (take-off, landing, mode change, waypoint reached);
   - live upload when online.
6. **Land:** the flight shows *Waiting to sync* until the backlog is accepted. Then the app sets the declaration Ended and shows *Closed*.
7. **Export:** per flight, GeoJSON + CSV of the track, plus the location of DJI's own flight record (every SDK app writes one, [11](11-dji-integration.md#a-post-flight-log-import-the-only-route-that-covers-every-case)).

## Architecture

```
apps/dji-flight/
  core/            Kotlin/JVM. No Android, no DJI. Domain, session state machine, journal, sync,
                   Blender client, F3411 mapping, altitude, declaration volumes, mission planning, zones.
  adapter-fake/    Kotlin/JVM. Replays recorded tracks through the DroneAdapter contract, with fault injection.
  adapter-dji-v4/  Android library. DroneAdapter over DJI MSDK v4.18 (compileOnly dji-sdk-provided), Waypoint 1.0
                   operator, DJI GEO zones, and a plain video decoder kept as the fallback for the UX SDK.
  adapter-dji-v5/  Android library. DroneAdapter over DJI MSDK v5.18 (key-value API), WPML mission operator,
                   DJI GEO zones, RTK, and a camera view on the SDK's own decoder.
  app-common/      Android library: the screens below, background services, maps, provisioning. No SDK code;
                   each app supplies a `Platform` (adapter, video, build facts).
  app-v4/          Android app. Flavours: `dji` (DJI SDK, UX SDK, adapter-dji-v4) and `sim` (adapter-fake only,
                   no DJI code).
  uxsdk-v5/        Android library: DJI's UI Library for MSDK v5, from DJI's sample code (MIT) without its map
                   kit and sample screens; app-v5 only. Changes from DJI's copy in its README.
  app-v5/          Android app, `org.openutm.djiflight.v5`: DJI SDK v5 + adapter-dji-v5 + uxsdk-v5. arm64 only.
  provisioning-tool/  JVM CLI for lab admins: a device's setup JSON → the file to import and its QR code.
```

**Dependency rule:**
- `core` depends only on the Kotlin standard library, `kotlinx-coroutines-core` and `kotlinx-serialization-json`.
- Adapters depend on `core`. Apps depend on `app-common` and one adapter.
- Nothing outside an adapter, `uxsdk-v5` and an app's own platform files (`DjiV4Platform.kt` with the UX SDK layout; `DjiV5Platform.kt` and `DjiPanelsActivity.kt` with uxsdk-v5's) imports `dji.*`. `app-common` reaches the vendor's screens only through `Platform` (`video`, `statusWidgets`, `vendorSettings`).

This is what made v5 "a new adapter and a new app module": `core` gained only the WPML writer, camera profiles and WPML limits, all DJI-free.

**HTTP:** `core` talks to Blender through a two-method `HttpTransport` interface, implemented with `java.net.HttpURLConnection`. That class is on both the JVM and Android (Android has no `java.net.http`), so there's no OkHttp version clash with the DJI SDK's bundled libraries.

### The app
Redesigned on 2026-10-18 after an audit on the emulator ([below](#screens-audit-and-redesign-2026-10-18)). Every screen is landscape, full screen (immersive, as DJI's apps run), dark with solid surfaces behind all text, and built from plain Android views (`app-common/.../ui/`: `Sheet`, `SlideToConfirm`, `Stepper`, `ActionButton`, `Pill`); no new libraries.

| Screen | What it does |
|---|---|
| **Fly** (the launcher) | As DJI's apps lay it out. **Top bar:** menu; aircraft status pill (*Ready to fly*, *In flight*, *Motors on*, *No data from aircraft*); UTM pill in plain words (*Declared*, *Declaring…*, *Not declared yet: offline*, *Live to UTM*, *Offline: 23 stored*, *Landed: all uploaded*, *Closed in UTM*); flight mode, GNSS and battery chips. In v4 `dji` the aircraft pill and chips are DJI's own widgets (pre-flight status, mode, GPS, vision, RC, video link, battery); in v5, DJI Pilot 2's (system status, GNSS, flight mode, simulator, AirSense, obstacle sensing, RC and video link, battery, settings; DJI's device-health card only from 1000 dp wide, AirSense and obstacle sensing from 820 dp). Tap the UTM pill for the declaration, window, height band and upload state. **Left rail, three fixed slots:** the next step (*Set up*, *Plan flight*, *Checklist 0/5*, *Upload*, *Go*, *Pause* / *Resume*, *Upload part 2*, *Resume…*, *Finish*, *Photos*, or a status: *Take off when ready*, *Flying*), then Stop / Cancel / Finish / Plan flight, then Pre-flight (amber when a check needs the pilot). Slots never appear or disappear. **Stage:** camera or map full size, with the other in a bottom-left inset (tap to swap; no inset without a camera); warnings over it (the worst one, “+N” lists all); a mission bar (waypoint n of m, progress) above a telemetry strip (H, D, H.S, V.S, 22 sp tabular). Recenter appears after the pilot pans. Menu: Flights, Plan, Pre-flight, Device setup, Upload now, Map key |
| **Pre-flight** (sheet) | The lab's checklist (large rows, *3 of 5*, Done only when all are ticked; ticks kept across Close); the aircraft's checks (connection, GNSS, battery, flight mode, home point with Update, compass / IMU / gimbal with Calibrate; calibrations run in their own large sheet that follows the aircraft's stages); settings read from the aircraft (RTH height checked against the declared ceiling and, over terrain the route follows, against its top; height limit against the legal limit and the mission's top, distance limit, RC-loss action, low / critical battery, obstacle sensing), each written after the pilot stops tapping and read back. The UTM checks sit at the top. In v5, *DJI settings and calibration* (under Aircraft settings) opens DJI's own settings panel. When the controller and the aircraft disagree on the take-off height by more than 15 m, a box at the top shows both (and the terrain model's, with a hint when the controller's looks like height above sea level) and the pilot picks one; later readings keep that source |
| **Go / Stop / Resume… / Finish / Cancel** (sheets) | Go: route, altitude, RTH height, end and RC-loss behaviour, lab checklist, then **slide to start** (blocked until the checklist is done). Stop asks once; Pause is one tap. Resume…: from waypoint n, or start over. Finish and Cancel say what happens in UTM |
| **Plan** | Map on the left, panel on the right (45 % of the width, 290–380 dp). Top bar: Back, Manual / Mapping, Import. **Map:** tap to add corners; drag a corner, or a “+” on an edge to add one; Undo, Clear, Recenter; the first waypoint marked; double-tap zoom off while drawing. **Panel:** a stats row that stays put (mapping: flight time, area, photos, batteries; manual: area, radius, max height, window); Altitude with GSD first, then Advanced (overlaps, speed, direction, gimbal, travel altitude, and with a terrain file *Follow terrain*), Flight details (pilot, aircraft, site, start, duration, ground height, site elevation), UTM (window, ceiling, height band, zones); steppers instead of a keyboard; Save and declare pinned at the bottom with the reason it can't be used yet. Batteries: a planning estimate of 65 % of DJI's rated flight time per aircraft. Under Altitude, the pattern: **Grid / Crosshatch / Perimeter**, each setting its usual gimbal angle. With a terrain file, the UTM section adds the ground's range under the route or area, the lowest clearance, and warnings (a route into rising ground blocks Save and declare). With `pilot_login`, Flight details has Sign in / Sign out; a signed-in pilot replaces the roster pick. The top bar measures its labels and drops the title, then the long labels, when they don't fit |
| **Flights** | The list (site or kind, date, photos, a status pill) and the selected flight's details: what UTM has, time in the air, samples, photos, export state, then Photos → DroneDeploy, Export GeoJSON / CSV, and Delete (confirmed; says when the flight wasn't exported) |
| **Setup** | Sections on the left: This device (three tiles: import file, scan QR, paste code; the debug stack as a small link; after a setup, *Start flying*), Offline maps, Restriction zones, App updates (a sheet explains Android's “Install unknown apps” page before it opens). This device also shows whether pilot sign-in is on. Offline maps imports a basemap, imagery and a terrain file (or downloads all three from the device setup's URLs). The paste field takes a code, not words: no suggestions and no full-screen keyboard |

- **Camera (`dji` build):** UX SDK widgets laid out as in DJI's own sample, with the same panel IDs so the widgets find them. Changes from the sample: no `DashboardWidget` (our telemetry strip shows those values); the status bar is in our top bar (`dji_status_leading.xml`, `dji_status_trailing.xml`; DJI's checklist panel listens on DJI's event bus, so it still opens from there); take-off/RTH sit above the inset. In the inset, only the camera picture is shown.
- **Camera (v5):** DJI's UI Library v5 (`uxsdk-v5`) laid out as in DJI's default layout (`dji_camera.xml`), less its map widget, HSI and primary flight display (the telemetry strip and inset sit there). The status bar is in our top bar (`dji_status_leading.xml`, `dji_status_trailing.xml`). DJI's settings panel, system status list and simulator control open over the Fly screen in `DjiPanelsActivity`, an AppCompat activity, because the settings pages are AndroidX fragments and the Fly screen is a plain activity; they open from DJI's status (left), its settings button (right end of the bar), its simulator icon and Pre-flight. The widgets in the Fly screen inflate through an AppCompat theme and AppCompat's view inflater (without it DJI's `app:srcCompat` icons stay blank), and only once the SDK has finished starting, as in DJI's sample. The camera name and tap-to-focus appear once a camera is picked (main camera first, as DJI's sample). In the inset, only the camera picture is shown. [Fly](img/12-dji-app/v5-uxsdk-fly.jpg), [settings](img/12-dji-app/v5-uxsdk-settings.jpg), [status list](img/12-dji-app/v5-uxsdk-status.jpg).
- **Lab checklist and photo hand-off** (docs/13, Phase A):
  - the lab's pre-flight checklist from the device setup, in the Pre-flight panel, ticked once per flight and saved in the journal. Go for a mission waits for it, and the rail's first slot asks for it before take-off;
  - after a finished flight that took photos, a sheet opens once with the photo count and times, the map name in large type with Copy, and the SD-card steps: Uploaded / Not uploading / Later. While it's pending the rail says *Photos*; Flights offers it for earlier flights.
- **Background:** a foreground service (type location, or dataSync without location permission) keeps recording and uploading while a flight is planned, in progress or landed. When it ends with samples still pending, a JobScheduler job uploads them later, without starting the DJI SDK, whenever the system sees a network.
- **Logs:** phase, link and sync changes go to logcat under tag `OpenUTMFlight`, for field debugging over `adb`.

#### Screens: audit and redesign (2026-10-18)
Walked on the emulator before and after (`sim` build, local Blender), at 914 × 411 dp and at 640 × 360 dp (a 5.5″ 1080p controller at 480 dpi, the worst case; the real density of the Smart Controller and RC Pro is still to read with `adb shell wm density`).

| Field impact | Before | After |
|---|---|---|
| Critical | At 640 × 360 dp no control was visible without scrolling the side panel, Finish included ([before](img/12-dji-app/before-fly-640dp.jpg)) | No scrolling on Fly; the rail's three slots and the telemetry fit ([after](img/12-dji-app/after-fly-640dp.jpg)) |
| Critical | Buttons moved and wrapped (“Pau / se”; the photo card and the checklist row pushed rows) ([before](img/12-dji-app/before-mission-controls.jpg)) | Fixed slots, one-line labels, cards became sheets ([after](img/12-dji-app/after-mission-controls.jpg)) |
| Critical | Flight state was words in 12 sp monospace among serials and UUIDs ([before](img/12-dji-app/before-fly-in-flight.jpg)) | Status pills and chips in a top bar; telemetry strip ([after](img/12-dji-app/after-fly-in-flight.jpg)) |
| Critical | Go was a Yes/No dialog behind a checklist dialog; Stop had no confirmation ([before](img/12-dji-app/before-go.jpg)) | One Go sheet with slide to start; Stop asks once ([after](img/12-dji-app/after-go.jpg)) |
| High | Plan was a four-screen form under a small map; keyboard entry; estimates at the bottom; corners append-only ([before](img/12-dji-app/before-plan.jpg)) | Map + panel, steppers, stats row, draggable corners ([after](img/12-dji-app/after-plan.jpg), [640 dp](img/12-dji-app/after-plan-640dp.jpg)) |
| High | Calibration and settings only in DJI's v4 panel | Pre-flight in all three apps, with the RTH-above-ceiling check ([after](img/12-dji-app/after-preflight-rth-check.jpg), [calibration](img/12-dji-app/after-compass-calibration.jpg)) |
| High | A resume after an app restart offered a fresh upload instead (see [Missions](#missions-waypoint-10-in-v4-wpml-in-v5)) | Resume from the last waypoint survives a real restart ([after](img/12-dji-app/after-resume-after-restart.jpg)) |
| Medium | Checklist dialog accepted Done, then refused it ([before](img/12-dji-app/before-checklist.jpg)) | Large rows, *n of m*, Done only when complete ([after](img/12-dji-app/after-checklist.jpg)) |
| Medium | Photo card squeezed into the panel, then a second dialog ([before](img/12-dji-app/before-photos.jpg)) | One sheet with Later ([after](img/12-dji-app/after-photos.jpg)) |
| Medium | Flights in one dialog; Delete without confirmation ([before](img/12-dji-app/before-flights.jpg)) | List + details; Delete confirmed ([after](img/12-dji-app/after-flights.jpg)) |
| Medium | Setup: equal buttons, no way forward ([before](img/12-dji-app/before-setup.jpg)); the install page opened without warning ([before](img/12-dji-app/before-update.jpg)) | Sections and tiles, *Start flying* ([after](img/12-dji-app/after-setup.jpg)); a sheet explains the Android step ([after](img/12-dji-app/after-update.jpg)) |

The v4 `dji` and v5 Fly screens: [dji](img/12-dji-app/after-v4-dji-fly.jpg), [v5](img/12-dji-app/after-v5-fly.jpg); v5 with DJI's UI Library (2026-10-18): [Fly](img/12-dji-app/v5-uxsdk-fly.jpg), [DJI settings](img/12-dji-app/v5-uxsdk-settings.jpg), [DJI status list](img/12-dji-app/v5-uxsdk-status.jpg).

### The adapter contract
Each adapter implements `DroneAdapter` (in `core/.../adapter/DroneAdapter.kt`):

| Member | Meaning |
|---|---|
| `capabilities` | Model name, `MissionSupport` (`NONE`, `WAYPOINT_V1`, `WAYPOINT_V2`, `WPML`), mission limits, RTK present, survey camera (e.g. the payload on an M400) |
| `link: StateFlow<LinkState>` | `DISCONNECTED`, `CONNECTING`, `CONNECTED(identity)` |
| `states: SharedFlow<DroneState>` | Raw aircraft state at the SDK's rate (v4: 10 Hz; v5: one per position-key update). The core downsamples |
| `missions: MissionOperator?` | Upload, start, pause, resume, stop, plus progress events. `null` when not supported |
| `aircraftSetup()` | The aircraft's settings, sensor states and calibrations (`AircraftSetup`); `null` when not connected or not supported. Rows the aircraft lacks are left out of `supports` |
| `start()`, `stop()` | Lifecycle |

`DroneState` carries:
- position and barometric height above take-off;
- NED velocities, yaw;
- `isFlying`, `motorsOn`, flight mode;
- GPS level, satellites, home point;
- DJI's take-off altitude (nullable), the RTK state (nullable), battery, RC link quality;
- a running count of photos (JPEGs) the camera reported. It is stored in each sample; a flight's photo count is the sum of its increases.

**B4 lesson** (the connector stopped receiving positions after an autopilot reboot):
- An adapter must re-register its callbacks on every product (re)connect, e.g. after a battery swap.
- The core runs a **stale-telemetry watchdog**: if `CONNECTED` but no state for 3 s, the pilot sees "Telemetry stale" and the adapter is told to resubscribe.

### Flight session: one writer, events on disk (avoids H1)
- A `FlightSession` is an actor: one coroutine owns the flight's state.
- Every change is an **event appended to the flight's journal**: `Planned`, `Declared(id)`, `DeclareFailed`, `TookOff`, `Landed`, `Activated`, `Ended`, `WindowExpired`, `Cancelled`.
- The current state is a fold over those events, so a restart replays to exactly the same state.
- The sync engine never writes flight state. It reports outcomes back as messages, and the actor appends the event.
- The connector's H1 came from two writers saving whole snapshots. Here there is only one writer.

| State | Entered on | Leaves to |
|---|---|---|
| `PLANNED` | plan saved | `DECLARED`, `CANCELLED` |
| `DECLARED` | Blender returned an id | `ACTIVE` (take-off), `CANCELLED` |
| `ACTIVE` | `isFlying` false→true | `LANDED` |
| `LANDED` | `isFlying` true→false | `ACTIVE` (another take-off within the window), `CLOSED` |
| `CLOSED` | backlog accepted and Ended accepted | — |
| `EXPIRED_UNSYNCED` | Blender says the window is closed while samples remain | — (export; wait for an import route) |
| `UNDECLARED` | take-off with no declaration | `EXPIRED_UNSYNCED` (until Blender can import) |
| `CANCELLED` | pilot cancels before take-off | — |

Several take-offs inside one declared window (battery swaps) stay **one declaration**, with Ground/Airborne samples between them.

### On-device journal
- **Files:** one directory per flight:
  - `events.log` and `samples.log`, each in JSON Lines with a CRC32 prefix per line (`<crc32-hex> <json>`);
  - `cursor`: the index of the last sample Blender accepted, written atomically (temp file + rename).
- **Crash safety:** a torn last line fails its CRC and is dropped on read. Everything before it is intact.
- **Size:** about 250 bytes per sample, so ~0.9 MB per flight hour at 1 Hz. Old closed flights are pruned after an export and a configurable age (default 90 days).
- **Why JSON Lines:** at 1 sample per second the CPU cost is negligible, and a field engineer can read the files with `adb pull`.

### Sync engine (store-and-forward)
What Blender does, tested on 2026-10-16 (Live; rerun steps below):

| Behaviour | Result |
|---|---|
| One `set_telemetry` request carrying 20 states with timestamps 1–20 s old, inside the declared window | 201; all stored with their **own** timestamps |
| States stamped **before** the declared start, sent inside the window | 201; stored |
| `position.height` present | stored (#216 fixed in `4f1a871`) |
| Telemetry after the declared end | 400 `does not match any current operation` |
| `flight_declaration_state` → Ended after the declared end | 200 (the endpoint checks neither transitions nor times, Code) |
| Declaration with start or end in the past, or more than 48 h ahead | 400 (Code, `_validate_dates`) |

How the sync engine runs:
- **Order per flight:** declare (if not yet declared), then Activated before the first sample, then samples in time order, then Ended once `LANDED` and the cursor has reached the end.
- **Triggers:** a network becoming available, every 2 s while `ACTIVE` and online (live view), app start, and "Sync now". Nothing runs when there's nothing pending.
- **Batching:** up to 300 states per request, in **one observation with many `current_states`**. That also avoids Blender issue 10 (several observations per request dispatch repeatedly).
- **Errors:**
  - I/O or 5xx: back off 2 s, doubling up to 60 s.
  - 401: refresh the token once.
  - 400 "does not match any current operation": record `WindowExpired` and stop retrying that flight.
- **Duplicate-safe declare:** Blender's declare isn't idempotent. Each declaration carries the app's flight UUID in its GeoJSON feature `properties.client_ref`. After an ambiguous failure (e.g. a timeout after sending), the engine lists declarations for the window and matches `client_ref` before posting again. Blender's list returns `flight_declaration_raw_geojson` with properties (Live).
- **Duplicate samples:** a batch re-sent after an ambiguous failure can be stored twice; Blender doesn't dedupe. Every sample has an exact timestamp, so readers can dedupe on (declaration, timestamp).
- **Window not open yet:** Blender answers telemetry sent *before* the declared start with the same message as after the end (Live). The engine waits for the start instead of declaring the window expired. Otherwise a pilot who takes off early would lose the flight, as the first version of the engine did in tests.
- **Wake-ups:** the sync loop sleeps when nothing is pending, and is woken by take-off, landing, finish, a network change or "Sync now". The end-to-end test caught a version that slept through a whole flight.
- **Blender's write rate:** its Celery worker writes one task per state, about 60 states/s on a laptop (Live). A one-hour backlog (3600 samples) takes about a minute to land after the 201s, so a backfilled flight appears in Spotlight gradually.
- **Clock:** declaration windows and sample times both use the GNSS-corrected clock (`CorrectedClock`). A controller that has been offline for days may have drifted. Anything measuring *intervals* (replay pacing, the stale-telemetry watchdog) uses a monotonic clock instead, so a wall-clock correction can't skip states or fake staleness.
- **Android HTTP:** request bodies are buffered, not streamed (`setFixedLengthStreamingMode`). On Android, a streamed body can't be replayed, so reusing a keep-alive connection Blender had already closed failed with `unexpected end of stream` and a backoff. That happened at the first upload after the quiet pre-take-off period in both emulator runs. The JVM tests can't show it, because the JVM's HTTP client behaves differently.

**What a flight with no network ends up as:**

| Network available… | Record in Blender |
|---|---|
| before the flight and before the window ends | complete: declaration, every sample, Ended |
| before the flight, but not again before the window ends | declaration only, state Activated or Accepted; samples stay on the device (`EXPIRED_UNSYNCED`), exportable |
| never before the flight | nothing; the flight stays `UNDECLARED` on the device until Blender has an import route |

The last two rows are why the window should be generous. The default end is planned end + 6 h, capped at 48 h from declaring. They are also why a Flight Blender feature request for importing completed flights is drafted (not filed yet).

**Rejected:** declaring after landing and attaching the earlier samples. It works on today's API, but the record would show a declaration made after the flight, with samples before its start, and it depends on Blender never checking sample times.

### Declaration volume (fixes B3)
The band needs the ground height at the take-off point, often before the pilot is there. The plan carries a `GroundEstimate` from the best source available when declaring:
- device GNSS, if declaring at the site;
- a saved site's last measured take-off reference;
- otherwise an elevation the pilot enters, converted with EGM96, with a 15–30 m sigma so the band is wider;
- otherwise (2026-10-18) the lab's **terrain model** at the take-off point, converted with EGM96, σ 8 m. A terrain file also cross-checks the other sources: a ground height more than max(20 m, 3σ) from the model's is flagged on the Plan screen.

At motors-on the real take-off reference is resolved (below). The app warns if it falls outside the declared band.

- **Horizontal:**
  - manual: a 32-vertex circle of radius *r* around the planned take-off point, or the imported polygon;
  - mission: the mission's polygon (or the route's hull) buffered by 30 m.
  - Sent as a GeoJSON `Polygon`. Blender uses the outline as given (Code, `_build_partial_operational_intent`).
- **Vertical:** never the min/max of waypoint altitudes.
  - Lower = take-off ellipsoid height − (10 m + 2σ).
  - Upper = take-off ellipsoid height + max height + (10 m + 2σ).
  - Max height = the smallest of: the planned max, the legal limit (default 120 m, configurable per country), and the highest mission waypoint + 10 m.
  - σ is the take-off reference's uncertainty.
- **Time:** start = max(planned start, now + 30 s); end = planned end + sync grace, capped at now + 48 h − 5 min.
- **Fields:**
  - `type_of_operation` 1 (VLOS);
  - `aircraft_id` = the DJI flight controller serial, which also goes in every sample's `uas_id.serial_number`;
  - `originating_party` = lab name;
  - `submitted_by` = device ID + pilot ID.

### Telemetry mapping (fixes H2)
Each recorded sample becomes one F3411 `RIDAircraftState`:

| F3411 field | Source |
|---|---|
| `timestamp` | Sample time in UTC. The phone clock is corrected by the offset to GNSS time (`Location.getTime()`), which matters on controllers that have been offline for days |
| `operational_status` | `Airborne` if `isFlying`, else `Ground` |
| `position.lat/lng` | Aircraft GNSS |
| `position.alt` | Take-off ellipsoid height + barometric height above take-off (W84) |
| `position.height` | Barometric height, `reference: TakeoffLocation` |
| `accuracy_h` / `accuracy_v` | From GPS level / satellites, and from the take-off reference's σ |
| `track` | Course over ground from velocity when > 0.5 m/s, else yaw |
| `speed` | Horizontal speed from NED velocity |
| `vertical_speed` | −`velocityZ` (DJI's NED: z is positive down) |
| `operator_location` | The phone's or controller's own GNSS: the pilot's position |
| `uas_id` | DJI serial; registration from the lab's aircraft list |

### Altitude reference
Candidates, all recorded with their uncertainty:
1. **RTK** (aircraft with RTK and a fixed solution): ellipsoid height directly. Not on the P4 Pro V2.0.
2. **Device GNSS:** Android's `Location.getAltitude()` is height above the WGS-84 ellipsoid. Averaged over 10–20 s at take-off, weighted by `getVerticalAccuracyMeters()` (API 26+).
3. **DJI `takeoffLocationAltitude`** "relative to sea level" (Doc; exact datum **unverified**; set before take-off). Converted to ellipsoid height with the on-device EGM96 grid.

How the reference is chosen:
- The app uses 1, else 2, else 3.
- If 2 and 3 differ by more than 15 m, the pilot sees both in Pre-flight and picks one (built 2026-10-18; until then the app only warned). The pick is a `TakeoffReferenceResolved` with `pilotChosen`; the resolution at take-off keeps the pilot's source with its newer value.
- **A controller that reports sea level as ellipsoid height** (a vendor bug) is told apart where the geoid sits far from the ellipsoid (Europe about +45 m, India −60 to −100 m): if its reading matches the terrain model's sea-level height rather than that height plus the geoid, the Plan screen and the Pre-flight choice say so (`GnssDatumCheck`). Where the geoid is within the readings' error (East Africa, about −15 m) it can't, and says nothing.
- The reference is resolved at motors-on, and again at take-off if the device GNSS average wasn't ready at motors-on.
- The choice and all candidates are stored in a `TakeoffReferenceResolved` event; the latest one wins.
- Field test: compare 2 and 3 against a surveyed point, to settle 3's datum.

**EGM96 on the device:**
- **Grid:** the same 15′ grid the connector uses (`us_nga_egm96_15.tif`), repacked by `tools/build_egm96_grid.py` into `egm96-15.bin`: 721 × 1441 signed 16-bit centimetres, about 2.1 MB.
- **Interpolation:** bilinear.
- **Test:** compared in a unit test against values PROJ produces from the same grid.

### Missions (Waypoint 1.0 in v4, WPML in v5)
- **Core model:** a DJI-free `MissionPlan`:
  - waypoints with lat/lon, height above take-off, speed, heading and actions (photo, gimbal pitch);
  - a finish action;
  - an RC-signal-lost behaviour.
  - The v4 adapter maps it to `WaypointMission`; the v5 adapter writes it as a WPML KMZ (below).
- **Survey planner:**
  - Input: polygon, camera model, GSD or height, front/side overlap, heading, pattern.
  - Output: a lawnmower grid of line endpoints with photo spacing.
  - **Patterns** (2026-10-18, `SurveyPattern`): **Grid** (camera −90°); **Crosshatch**: the grid, then the same area at right angles, joined at whichever of the second pass's four possible starts is nearest (−70°); **Perimeter**: once round the polygon's edge and back to the first corner, every waypoint facing the polygon's centroid, edges cut so the heading turns at most 15° and waypoints are at most 50 m apart (−45°). The Plan screen sets each pattern's gimbal angle when chosen; the pilot can change it. Spacing is computed for a nadir camera, so a tilted one gets at least the overlap asked for. Travel legs keep the heading of the waypoint they lead to.
  - P4 Pro V2.0 camera: 13.2 × 8.8 mm sensor, 8.8 mm focal length, 5472 × 3648 px. GSD at 100 m ≈ 2.74 cm/px.
- **Terrain following** (2026-10-18, `core/.../terrain/TerrainFollow.kt`; DroneDeploy's "terrain awareness"): with a terrain file, Advanced → *Follow terrain* keeps a survey at its altitude above the terrain model instead of above take-off, so GSD and clearance stay the same over a hillside. Any pattern.
  - The ground is sampled at every waypoint and every 10 m between. A waypoint is added wherever flying straight would be more than 5 m off the wanted height (the model's own σ is 8 m); along an even slope none is. Over rolling ground (±25 m), a 17 km survey went from 46 to 125 waypoints: two uploads on Waypoint 1.0 (`MissionValidator.split`), one on WPML.
  - Climbs are capped at 3 m/s and descents at 2 m/s at the leg's speed, by raising the route only: it starts climbing before a cliff (160 m early for 60 m at 8 m/s) and comes down late after a drop, never lower than wanted. Off a drop that leaves it higher above the ground than the legal limit, the Plan screen warns (`TERRAIN_ABOVE_LEGAL`), as for a flat route off a ridge.
  - Heights stay relative to take-off (Waypoint 1.0; WPML `relativeToStartPoint`), because DJI's `realTimeFollowSurface` is only on some aircraft. They count from the planned take-off point's ground, so the pilot takes off there.
  - **The legal limit counts from the ground.** Each waypoint keeps its ground (`MissionWaypoint.groundM`); the validator checks a following waypoint by its height above the terrain model. Above take-off, the declared ceiling may reach the legal limit plus the highest ground under the route (`MissionPlan.groundRiseM`, `DeclarationInput.groundRiseM`, `FlightPlan.legalCeilingM`); the in-flight height warnings and Pre-flight's hints use the same ceiling.
  - **Return to home:** the route's top is the RTH height of every upload part (`MissionPlan.returnHomeHeightM`, written as WPML `globalRTHHeight`), so a part low on the slope doesn't fly home into the hill. On v4 the RTH height is the aircraft's setting: Pre-flight warns when it is below the route's top (`RTH_BELOW_TERRAIN_ROUTE`), and, for any mission, when the aircraft's height limit is (`HEIGHT_LIMIT_BELOW_MISSION`).
  - Added waypoints take the leg's speed and a heading between its ends (a perimeter's 15° turn limit holds), and no photo of their own: survey photos stay distance-triggered (v4: `shootPhotoDistanceInterval` on every survey leg; WPML: one `multipleDistance` group over the route). A travel altitude no longer above the route's top is dropped, and the Plan screen says so.
  - Plan screen: the toggle shows only with a terrain file. The terrain lines say how many waypoints were added and the waypoints' range above take-off, and the clearance and height above ground are those of the followed route. Without terrain heights at the take-off point or under part of the route, Save and declare is blocked until the toggle is off.
- **Limits checked before upload:** from MSDK 4.18's own constants, read with `javap` (Code):
  - 2–99 waypoints;
  - max/auto speed up to 15 m/s;
  - altitude from −200 m;
  - gimbal pitch −135 to 45°;
  - at most 15 actions per waypoint.
  - 4.18 declares no maximum altitude (`Float.MAX_VALUE`), so the core caps at 500 m, and the legal limit applies first anyway.
  - `Waypoint.shootPhotoDistanceInterval` exists, so survey photos are triggered by distance, not by one action per photo.
- **Too many waypoints:** if a grid needs more than 99, the plan is split into consecutive missions, each one declared inside the same window.
- **Resume after a battery swap** (`core/.../mission/MissionRunner.kt`): an interruption leaves the mission INTERRUPTED at the last waypoint reached. Interruptions are RTH (low battery or the pilot), the pilot's Stop, an SDK error, the aircraft disconnecting, or a v4 finish without error that stops short of the last waypoint.
  - **Resume** uploads the rest of the route, starting at that waypoint, as a new mission. The aircraft flies back to it and re-flies the cut segment, so a survey line never has a gap, only a few duplicate photos. The rest is re-split into parts if it is still too long.
  - **Start over** flies the whole route again. The Fly screen's Upload button becomes "Resume…" and offers both.
  - The same on v4 and v5. DJI's v5 breakpoint query (5.6+) only works after an explicit pause, not after RTH, so it isn't used.
  - Progress counts a waypoint once the operator says it was reached (v4). v5 reports only the waypoint being executed, so the one before it counts as reached, and its clean finish counts as complete.
  - The last waypoint reached is saved per flight (`mission-progress.json`), so a resume also survives an app restart. The saved plan identity is a hash of the plan's JSON (`MissionPlan.stableHash()`). Until 2026-10-18 it was `hashCode()`, which includes an enum's identity hash, so it never matched after a real restart and Resume… silently became a fresh upload (found on the emulator; the unit test ran in one process).
- **Safety defaults:**
  - exit the mission on RC signal loss, so the aircraft's own failsafe (RTH) applies;
  - finish action RTH;
  - the route must stay inside the declared area;
  - warn when any waypoint is beyond the VLOS radius from the pilot.
- **Commanding:** the app never commands the aircraft except through DJI's mission operator and its standard take-off/RTH calls.
- **WPML (v5):** `core/.../mission/Wpml.kt` writes `wpmz/template.kml` + `wpmz/waylines.wpml` from DJI's published spec (Cloud API docs, Doc), so it is unit-tested on any JVM. The v5 adapter runs DJI's own validator (`WPMZManager.checkValidation`) on each file, then `pushKMZFileToAircraft` and `startMission`.
  - Same behaviour as the v4 mapping: heights relative to take-off; straight segments, stopping at each waypoint; gimbal to the plan's pitch at the first waypoint; survey photos as one `takePhoto` group with a `multipleDistance` trigger over the route; on signal loss, leave the mission and fly home (`executeLostAction` + `goBack`).
  - Safe take-off height and RTH height never exceed the mission's top, so both stay inside the declared volume. Over terrain the route follows, every part's RTH height is the whole route's top.
  - Aircraft and payload enums: Mavic 3E 77/0 with camera 66 (DJI spec); Matrice 400 103, P1 50, L2 84 (DJI's wpmzsdk 1.0.5.2 enums: the spec predates the M400).
  - DJI's validator wants `executeRCLostAction` even with `goContinue`, which the spec doesn't say (found on the emulator); the writer always includes it.
  - Limits: the spec allows waypoint indices up to 65535 per wayline and transitional speeds up to 15 m/s, so a survey is one upload. It gives no height range or minimum spacing; those stay as for Waypoint 1.0.
  - Survey cameras: Mavic 3E wide (17.3 × 13.0 mm, 12.29 mm, 5280 × 3956; DJI: GSD = H/37.5 cm), Zenmuse P1 with the 35 mm lens (H/80 cm), Zenmuse L2 RGB.
  - Added 2026-10-18, from DJI's spec pages, sensor sizes derived from DJI's fields of view (unit-tested against them):
    - **M3T wide** 6.4 × 4.8 mm, 4.44 mm, 8000 × 6000 (48 MP; the 12 MP setting doubles the GSD, the overlap stays);
    - **M3M**: the multispectral cameras set the spacing (61.2° × 48.1°, 2592 × 1944), being narrower than its RGB camera, and each shot stores both (`imageFormat` `visible,narrow_band`);
    - **Zenmuse H30 / H30T wide** 9.36 × 7.02 mm, 6.72 mm, 8064 × 6048 (48 MP), `imageFormat` `wide`.
  - WPML enums from DJI's wpmzsdk 1.0.5.0 (`javap`): M3E series drone 77 with sub-enums 0/1/2 for M3E/M3T/M3M, payloads 66/67/68 (`WM265E/T/M`); H30 82 and H30T 83 (`EP300_E/T`); L2 84 (`EP810`); M400 103 (`PM440`). v4 gained the Mavic 2 Pro camera (13.2 × 8.8 mm, 10.26 mm, 5472 × 3648).

### Zones and maps
- **Blender zones:** `GET /geo_fence_ops/geo_fence?start_date&end_date`. It returns each zone's `raw_geo_fence` GeoJSON, ED-269 `geozone`, limits, `bounds` and validity.
  - The app pages through the list (200 at a time, at most 5000) and keeps the zones whose `bounds` touch the area, filtering on the device.
  - It doesn't use the `view` parameter. On `54636aa` that filter only matches a lat,lng view, while `bounds` is lng,lat (Live; an issue is drafted for Flight Blender). Depending on it would silently return no zones if it changes.
  - The cache is kept with its fetch time, whose age is shown. Refreshed on start, at planning, and when older than 6 h. A zone deleted in Blender stays on the device until the next refresh.
  - Countries' zones are loaded into Blender by admins (`set_geozone`), so adding a country is data, not an app release.
- **DJI zones:** `FlyZoneManager.getFlyZonesInSurroundingArea` (within 20 km of the aircraft; offline behaviour **unverified**), read every minute while connected. Polygon and cylinder sub-zones become rings. Shown read-only, and checked by the same warnings.
- **Warnings only:**
  - v4 has no custom-geofence API (Doc), so DJI's own GEO system is the only enforcement.
  - The app warns when the declared area intersects a zone, and in flight when inside or within 100 m of one. Point-in-polygon with a bounding-box prefilter, at 1 Hz.
- **Base map:** MapLibre Native Android (BSD) ≥ 11.7.0, which reads a local `pmtiles://file://` archive, vector or raster (Doc). The region pack is one file per lab:
  - vector: an OSM extract cut from Protomaps' free daily builds with `pmtiles extract`;
  - and/or raster: the lab's own orthomosaics as PMTiles.
  - Hosted as a static file next to the lab's other downloads, or copied from microSD.
  - The app never fetches tiles online: OSM's public tile servers forbid offline prefetching (Doc).
  - Files are checked for the PMTiles v3 magic before they replace the current ones.
  - With no file, the map is a plain background with the overlays, which is what the emulator runs used.
  - The style (2026-10-18) follows the Protomaps basemap schema v4: earth, landuse (by kind), water, roads (casing, highway / major / minor / path), buildings, boundaries, and labels for places, roads, water and peaks. Checked on a Protomaps daily build of Budapest (2026-10-17) on Android 14 and 7.1. [Fly](img/12-dji-app/2026-10-18-fly-labels.jpg), [Plan](img/12-dji-app/2026-10-18-plan-terrain-manual.jpg). Map data © OpenStreetMap contributors.
  - **Text labels:** MapLibre needs signed-distance-field glyph files. `tools/glyphs/BuildGlyphs.java` (plain JDK, no libraries) builds them from Android's own Roboto (Apache 2.0) for Latin, Latin Extended, Vietnamese, Greek and Cyrillic: 712 KB in the APK's assets, loaded as `asset://glyphs/…`. A name in a script MapLibre can't shape (Devanagari) falls back to its English name.
  - **Terrain:** a third file, `terrain.pmtiles`: Terrarium tiles (WebP or PNG) cut from Mapterhorn's planet file (Copernicus GLO-30 and finer national models, zoom 12, about 19 m pixels). The core reads it itself (`core/.../terrain/`: a PMTiles v3 reader written from the spec, Terrarium decoding, bilinear interpolation; Android decodes tiles with BitmapFactory). It gives ground heights for planning away from the site, the route's clearance and the most height above ground (`TerrainCheck`: every waypoint and every 20 m between; warning below 30 m clearance or above the legal height above ground, a route into the ground blocks declaring), in-flight warnings (`ABOVE_LEGAL_AGL`, `TERRAIN_CLOSE`), and hillshade on the map. GLO-30 is a surface model, so roofs and trees count as ground, which errs towards less clearance.
  - **Credits:** the Map key lists each file's own attribution from its metadata (e.g. "© OpenStreetMap · © Mapterhorn"), since the map hides MapLibre's attribution button.
  - **Recipe:** `tools/maps/region-pack.sh area <bbox> <dir>` cuts the basemap (latest Protomaps build, zoom 0–15) and terrain (Mapterhorn, zoom 0–12) with `pmtiles extract`, fetching only the tiles inside the box; a 16 × 14 km area around Nairobi is 9.5 + 2.2 MB. `region-pack.sh imagery <orthomosaic.tif> <dir>` turns a lab orthomosaic (e.g. a DroneDeploy GeoTIFF export) into PNG-tile PMTiles with GDAL (not run here: no GDAL on this machine).

### Auth and provisioning
- **Setup payload** (`DeviceProvisioning`, versioned JSON):
  - `device_name`, `blender_url`, `auth`, `lab`, `operator_id`, `country`, `legal_max_height_m`;
  - `pilots` (id and display name only, until the data sharing agreement covers more) and `aircraft` (serial, registration, model);
  - `basemap_url`, `imagery_url`, `update_url`.
  - `auth` is `client_credentials` (`token_url`, `client_id`, `client_secret`, `scope`, `audience`), or `dev`, which debug builds only accept.
- **Delivery:** as a QR code, `openutm-device:1:` + base64url(gzip(JSON)), about 400 characters for two pilots and one aircraft. Or as the plain JSON in a file or pasted text, for the Smart Controller, which has no camera.
  - `./gradlew :provisioning-tool:run --args="device.json out/"` writes both.
  - The file holds the device's secret, so the app reminds the pilot to delete it from the card after import.
- **Storage:** encrypted with an Android Keystore AES-GCM key. Re-validated on every start, so a release build ignores a dev setup a debug build stored (checked on the emulator).
- **Tokens:** OAuth2 client credentials, fetched at sync time and cached until shortly before expiry. Revoke a device by deleting its Passport application.
- **Passport:** the connector already uses client credentials against Passport's token URL. Passport's access and refresh tokens last 3600 s (Code, `flight_passport/settings.py`), which is why the device credential, not a pilot's token, does every upload.
- **Pilot sign-in (2026-10-18, optional):** `pilot_login` in the device setup (`issuer`, `client_id` of a public client, `scope` default `openid profile`, `redirect_uri` default `http://127.0.0.1/openutm-signin`, `valid_hours` default 72, `required`).
  - Authorization code with PKCE (S256) in an in-app web view (`SignInActivity`): DJI's controllers may have no browser, and the redirect is caught before it loads, so nothing needs to answer at the redirect URI. Cookies are cleared first and `prompt=login` asks every time, so a shared controller never signs in as the previous pilot.
  - The ID token is verified on the device: RS256 against the server's JWKS, issuer, audience, expiry and issue time with 5 min of clock skew, nonce, state (`OidcClient`, 6 unit tests with a fake server and a real RSA key). Passport's ID token has no name or email, so the app reads userinfo for the same subject and matches the roster by id or email.
  - A sign-in counts for `valid_hours`, offline included. The flight records issuer, subject and the roster name (`FlightPlan.pilotSignIn`), and its pilot id is the roster's id, else the subject. No email is kept in the flight or sent to Blender. Use opaque roster ids: a roster id that is an email goes to Blender in `submitted_by`.
  - **Checked against a local Flight Passport** (`4823eaa`, run from source) on Android 14 and Android 7.1 (WebView 55): sign in, then a declared flight with `pilotSignIn`. [Page](img/12-dji-app/2026-10-18-signin-passport.jpg), [Plan](img/12-dji-app/2026-10-18-plan-signed-in.jpg).
  - **Passport findings:** it offers only the `openid` and `profile` scopes (asking for `email` is refused); stock settings allow only http/https redirect URIs (hence the loopback default); and a deployment should check that its Passport refuses token requests without PKCE (the app always sends it).
  - Still needs per-lab accounts and the programme's decision on instances ([H5](#risk-labels)).
- **Development:** `DevTokenProvider` mints the same unsigned dummy JWT as `tools/sitl-bridge/blender_api.py`. It is accepted only when Blender runs with `BYPASS_AUTH_TOKEN_VERIFICATION=1`.

### Distribution and updates
- **App IDs:** `org.openutm.djiflight.v4` (`dji` flavour), `org.openutm.djiflight.v4.sim` (`sim` flavour) and `org.openutm.djiflight.v5`. Each DJI package needs its own DJI app key, registered under one organisation's DJI developer account. The v5 key goes in `DJI_V5_API_KEY`; it was created on 2026-10-17 and registers on the emulator.
- **App key in the build:** set `DJI_API_KEY` in `~/.gradle/gradle.properties` or an environment variable; it is never committed. The key for `org.openutm.djiflight.v4` was created on 2026-10-17. Every APK carries it in its manifest, so it isn't a strong secret, but it stays out of git.
- **DJI registration:** needs internet on first launch only, then is cached (DJI FAQ, Doc). How long the cache stays valid is **unverified**.
- **Releases:**
  - `./gradlew :app-v5:assembleRelease :app-v5:releaseChannel` does the same for v5 in `app-v5/build/release-channel/`. v5 controllers get their own channel (`update_url` in their device setup).
  - `./gradlew :app-v4:assembleRelease :app-v4:releaseChannel` collects the release APKs into `build/release-channel/` and writes `latest.json` (application ID, version, file name, SHA-256, min SDK). Publish the folder as static files, e.g. GitHub Releases or the lab's web host.
  - The app checks the channel (Setup, or the `update_url` in the device's setup), downloads the APK, refuses it unless its SHA-256 matches, and hands it to the system installer.
  - On Android 8+, the pilot first allows "Install unknown apps" for the app once; the app opens that page. Verified on the emulator up to that page.
  - Offline: copy the APK to microSD/USB from a lab laptop. The Smart Controller installs APKs from microSD (Doc).
- **Signing key:** one key, held by OpenUTM, given to the build as `OPENUTM_RELEASE_STORE_FILE`, `…_STORE_PASSWORD`, `…_KEY_ALIAS` and `…_KEY_PASSWORD` (gradle properties or environment; never committed). Without them, release APKs are unsigned. `tools/release/make-release-key.sh` creates it once (RSA 4096, PKCS12, outside the repo) and writes the four lines to `~/.gradle/gradle.properties`; it refuses to overwrite a key.
- **Publishing:** `tools/release/publish.sh` runs the JVM tests, builds both apps' signed releases and channels, refuses any unsigned or debug-signed APK, and prints how to publish. `.github/workflows/dji-flight.yml` runs the tests and a `sim` build on every push and pull request, and on a `v*` tag builds signed releases from repository secrets and attaches the APKs plus `latest-v4.json` / `latest-v5.json` to a GitHub release; a device's `update_url` then points at `…/releases/latest/download/latest-v4.json`.
- **Release builds:** R8 shrinks both flavours, using DJI's own 4.18 keep rules (verbatim from its sample) plus ours.
  - A test-signed `sim` release ran on the emulator and read journals written by a debug build.
  - **A signed `dji` release** (2026-10-18, first run): it crashed at start because resource shrinking removed strings DJI's SDK looks up by name (`FPVTipLogic`, `Resources$NotFoundException`). `app-v4/src/dji/res/raw/keep.xml` keeps DJI's resources; code shrinking stays on. It then started, opened Setup (a release refuses the dev setup) and showed DJI's widgets on the Fly screen ([screenshot](img/12-dji-app/2026-10-18-dji-release-fly.jpg)).
  - Sizes (0.4.0): `dji` 94.4 MB release (91.1 MB before: +2.5 MB kept DJI resources, +0.7 MB glyphs), `sim` 21.9 MB (21.3 MB), v5 175.3 MB (174.7 MB).
  - `tools/release/publish.sh` was run end to end with a throwaway key (2026-10-18). It found two bugs, both fixed: `releaseChannel` could run before `assembleRelease` in one Gradle call, and the channel folder kept APKs from older builds (an unsigned 0.2.0), which would have been published with it.
- **What the build showed** (Live, `app-v4-dji-debug.apk`):
  - 18 of the 34 arm64 libraries are 4 KB-aligned (ELF `PT_LOAD` alignment 4096), so the APK can't load on 16 KB-page phones and can't go on Google Play;
  - DJI's native libraries take 54 MB (arm64) + 43 MB (armeabi-v7a);
  - the SDK's own layouts need AppCompat and ConstraintLayout, which its POM doesn't declare (the `dji` flavour adds them);
  - the UX SDK also needs RecyclerView, likewise undeclared, which showed up as a crash on the emulator. A scan of its bytecode shows no other external library besides the HERE maps SDK used by the excluded map widget;
  - the `sim` APK has no native libraries and no DJI code.

### Device load budget
The status screen redraws at most twice a second, although states arrive at 10 Hz. The first emulator run redrew at the full rate and the screen never went idle.

Targets to measure in the field test (not measurements):

| Resource | Target | How measured |
|---|---|---|
| CPU (app's own, beyond DJI video decode) | < 5 % of one core while flying; ~0 idle | `top -H` / `dumpsys cpuinfo`, `sim` vs `dji` build |
| Mobile data | ≤ 3 MB per flight hour (1 Hz, batched every 2 s; backlog 300 per request) | `dumpsys netstats` per UID |
| Storage | ≤ 1 MB per flight hour, plus DJI's own flight record | journal size |
| Wake-ups | none when no flight is active or pending | `dumpsys alarm` / `jobscheduler` |
| Battery | a foreground service only during `ACTIVE`/`LANDED` | `dumpsys batterystats` |

### Testing without a drone

| Layer | What | Where |
|---|---|---|
| Unit | Altitude, EGM96, volumes, F3411 mapping, journal (incl. torn writes), session folding, sync backoff, survey grid | `core/src/test` |
| Integration | Real Blender: declare → activate → live + back-dated batch → Ended; window expiry; duplicate-safe declare. Skipped when `:8000` is down | `core/src/test/.../BlenderIntegrationTest.kt` |
| Pipeline | Fake adapter replays a track → session → journal → sync → Blender, with an 8 s outage mid-flight. Result: all 29 samples stored, each with height and Ground/Airborne status, largest gap 1.07 s; the connector's was 29 s | `adapter-fake/src/test/.../PipelineBlenderTest.kt` |
| Device without DJI | `sim` APK on an emulator or any phone, replaying bundled tracks (60 s on the ground first, so the pilot can plan); also for pilot training. **Run 2026-10-16** on an Android 14 emulator (arm64, 4 KB pages):<br>• the app restored an unfinished flight from its journal after a restart; Cancel closed it in Blender (state 7);<br>• a new flight went plan → declare → take-off → live upload every 2 s → landing → finish → Ended;<br>• Blender holds 112 of 112 recorded samples, each with height and pilot location (100 Airborne, 12 Ground), largest gap 1.26 s, no duplicates | `app-v4` `sim` flavour; status in logcat, tag `OpenUTMFlight` |
| App screens, no aircraft | **Run 2026-10-17** on the emulator, `sim` build:<br>• setup by file import;<br>• a manual flight declared with a zone conflict confirmed;<br>• a second take-off within the same declaration (battery-swap case): 156 of 156 samples stored, Ended;<br>• the near-zone warning ("91 m from …") from a zone created in Blender, and the track drawn on the map;<br>• a mapping mission: four taps → 18-waypoint grid → declared → uploaded → run (fake operator) → 224 of 224 samples, Ended;<br>• GeoJSON export through the file picker (156 points, W84 altitudes, declared band and window);<br>• the update check, download and SHA-256 match.<br>`dji` build: DJI registration succeeds; the UX SDK widgets inflate and show their disconnected states; camera and map swap | `app-v4`; positions over `adb`, results in Blender's DB |
| DroneDeploy migration, Phase A | 9 unit tests (`MigrationFeaturesTest`): photo counts across sessions and restarts; checklist and hand-off replayed from the journal and in the GeoJSON; device-setup fields; the checklist warning; travel legs (geometry, spacing, no photos on them, validator, WPML climb/RTH heights and photo range); the runner adding travel legs on upload and on resume with the pilot's numbering; the map name and steps. **On the emulator** (`sim`, setup imported from a file with a 5-item checklist and DroneDeploy):<br>• Done refused until every item was ticked; ticks kept across Later; Go blocked until the checklist was done;<br>• the warning turned orange when the replay took off without it;<br>• a survey at 80 m with gimbal −80° and travel 100 m declared 164.9–306.9 m W84 (20 m higher than without travel); 39 points uploaded, progress shown as 36/36;<br>• 108 of 108 samples in Blender, largest airborne gap 1.27 s, Ended;<br>• after Finish, the card showed 19 photos with their times, Copy name worked, Done → Uploaded recorded, and the Flights screen showed "photos: in DroneDeploy" | `core/src/test/.../MigrationFeaturesTest.kt` |
| Mission resume | 8 unit tests (`MissionRunnerTest`): RTH mid-segment and battery swap, then resume; a v4 clean finish short of the end; v5-style progress; an interruption in part 2 of a 150-waypoint Waypoint 1.0 survey; resume after an app restart; Stop, then start over; disconnect before Go. On the emulator (`sim`), a 36-waypoint mapping mission still runs upload → Go → done | `core/src/test/.../MissionRunnerTest.kt` |
| v5 app, no aircraft | **Run 2026-10-17** on the emulator:<br>• DJI's WPML validator and parser (wpmzsdk, inside the app) on three files from our writer (a Mavic 3E survey, an M400 + P1 survey, a waypoint list with headings and photos): no errors, aircraft and payload enums read back, take-off and RTH heights as written;<br>• the SDK starts; without a key, registration fails with `EMPTY_APP_KEY`; with the app's own key it registers ("DJI registration succeeded (MSDK 5.18.0)" in logcat) and every key and RTK listener subscribes without error;<br>• device setup and the Fly screen with the v5 camera view.<br>Also re-run after moving the screens to `app-common`: the `sim` build's manual flight, 136 of 136 samples in Blender, each with height, largest airborne gap 1.27 s, Ended.<br>**Run 2026-10-18 with DJI's UI Library v5** (914 × 411 dp): the SDK registers; DJI's status bar and camera widgets show their disconnected states; DJI's settings panel opens from its settings button and from Pre-flight (flight controller, obstacle sensing and gimbal pages checked), the system status list from DJI's status, and both close with Back or a tap outside; no crash. Two crashes found and fixed on the way: DJI's FPV view handed its surface to the SDK's decoder before any camera existed (EGL failure, then the process died; `FPVWidget` patched), and the widgets attached before the SDK had started (now deferred). 83 JVM tests pass | `app-v5` debug: `WpmlSelfTestActivity` (debug only; `adb shell am start -n org.openutm.djiflight.v5/org.openutm.djiflight.app.v5.WpmlSelfTestActivity`), logcat tag `OpenUTMFlight` |
| Screens and Pre-flight | **Run 2026-10-18** on the emulator (`sim`, setup from a file with a 5-item checklist and DroneDeploy), at 914 × 411 and 640 × 360 dp: first-run setup → *Start flying*; manual flight: plan → declare → checklist → take-off → land → Finish → photos sheet → Flights; mapping: corners tapped and dragged → declare → Upload → Go (slide) → Pause → Resume → Stop → **app force-stopped and restarted** → Resume from 9 → done; Pre-flight: RTH set to 130 m above the declared 120 m → warning on the panel and over the Fly screen; compass calibration in the simulated aircraft; update check up to the explanation sheet (Android's page not opened). The sim's mission operator flies one waypoint a second (`missionStepMs`), so every mission state can be walked. v4 `dji` and v5 Fly screens checked without an aircraft. 9 unit tests: aircraft checks against the declaration (`AircraftChecksTest`), the paced operator and the simulated settings (`FakeSetupAndPacingTest`), the plan identity across runs (`MissionRunnerTest`) | `core/src/test/.../AircraftChecksTest.kt`, `adapter-fake/src/test/.../FakeSetupAndPacingTest.kt` |
| Aircraft, no flight | DJI's built-in simulator (props off, aircraft on USB). Per-model support **unverified** | `dji` flavour, `app-v5` |
| Terrain, maps, patterns, cameras, sign-in, strings (2026-10-18) | 17 unit tests: `SurveyPatternsTest` (crosshatch join, perimeter edge, headings and turn limit, travel-leg headings, the new cameras' fields of view and GSD, their WPML enums), `TerrainTest` (PMTiles tile ids against the spec, Terrarium heights from a synthetic archive, a survey up a hillside: clearance, the legal height above ground, a route into the ground, in-flight height above ground), `PilotSignInTest` (RFC 7636's PKCE example, a full code + PKCE sign-in against a fake server with a real RSA key, Passport's style with userinfo, forged, foreign, expired and replayed tokens refused, wrapped setup codes), `AltitudeChoiceTest` (the pilot's pick and later resolutions; the sea-level check in Budapest, Nairobi and Bengaluru). The core read the real Mapterhorn and Protomaps files byte for byte as the `pmtiles` CLI does. **On the emulator:** labels and hillshade on a real extract; terrain lines on Plan (−14 to +64 m around the Chain Bridge, a survey over Castle Hill with 29 m clearance); Grid, Crosshatch and Perimeter drawn; DJI's WPML validator accepts M3T, M3M, H30, H30T, crosshatch and perimeter files and reads back their enums; pilot sign-in against a local Passport; every screen in the en-XA pseudo-locale (labels shrink to fit; Plan's top bar falls back to short labels) | `core/src/test`, `app-v5` `WpmlSelfTestActivity` |
| Terrain following (2026-10-18) | 8 unit tests: `TerrainFollowTest` over synthetic ground (an even slope: heights follow, nothing added, 80 m clearance instead of 20; a ridge: waypoints where the slope changes, climbs ≤ 3 m/s and descents ≤ 2 m/s, a perimeter keeps its turn limit; a cliff: the climb starts 160 m early; a drop: the route stays high and the Plan screen warns above the legal height above ground; the validator by height above ground; rolling ground: 125 waypoints in two Waypoint 1.0 parts, each valid and returning home at the route's top, WPML heights relative to take-off; the declared ceiling, in-flight and Pre-flight checks; a travel altitude dropped; no plan without terrain heights), `TerrainTest` (a survey followed over the Terrarium hillside). **On the emulator:** DJI's WPML validator accepts a followed survey over a ridge (80–153 m above take-off) and an upload part of one (103–126 m, RTH 153.5 m, which DJI reads back) | `core/src/test`, `app-v5` `WpmlSelfTestActivity` |
| Android 7.1 (2026-10-18) | `system-images;android-25;google_apis;arm64-v8a`, 1080 × 1920 at 480 dpi: 640 × 360 dp, the Smart Controller's worst case. `sim` build: setup by pasted code, map with labels, Plan (manual, crosshatch), declare, replayed flight with the screen off for a minute (foreground service kept recording and uploading), Wi-Fi off, Finish with 10 samples pending, the process killed (`kill -9`): Android restarted the service and the background job uploaded the rest when Wi-Fi came back. Blender holds 124 of 124 samples, Ended. Pilot sign-in in the system WebView 55 | [Fly](img/12-dji-app/2026-10-18-android7-fly-640dp.jpg), [Plan](img/12-dji-app/2026-10-18-android7-plan-640dp.jpg) |
| Field | P4 Pro V2.0 on the Smart Controller and on RC + phone; altitude vs a surveyed point; load budget | [14 · bench and field test plan](14-dji-field-test-plan.md) |

**Fake adapter:**
- **Track files:** JSON Lines. A header line gives model, serial, take-off point and reference altitudes; then one line per state.
- **Faults:** link drop, reconnect with a new serial (battery swap keeps the serial; a different aircraft doesn't), GPS loss, stale telemetry, clock skew.
- **Bundled tracks:**
  1. a deterministic synthetic manual flight;
  2. a survey flown on ArduCopter SITL, converted from the bridge's MAVLink positions;
  3. later, real P4 flight records converted with `dji-log-parser`.

## Open items

| Item | How to close it |
|---|---|
| v4.18 on current build tools | **Builds** with AGP 8.7.3, Gradle 8.11.1, Kotlin 2.1.0, JDK 17 (2026-10-16). **SDK registration verified** on the arm64 Android 14 emulator with the app's DJI key (2026-10-17): the SDK loads and `onRegister` returns success. Not yet run: the Smart Controller, a phone, and connecting the P4 Pro V2.0 |
| `Model.getDisplayName()` strings used to pick Waypoint 1.0 aircraft | Log them from a real P4 Pro V2.0 (`DjiV4Adapter.WAYPOINT_V1_MODELS`); a Mini 2 must come out as no missions |
| `takeoffLocationAltitude` datum **unverified** | Field test against a surveyed point |
| Waypoint 1.0 behaviour (limits read from 4.18 constants; 0.5 m spacing and distance-triggered photos untested) | DJI simulator, props off |
| What each SDK reports when RTH interrupts a mission (v4 `onExecutionFinish` with or without an error; v5 state sequence) | DJI simulator: start a survey, trigger RTH, land, power-cycle, then Resume. The runner treats both "error" and "clean finish short of the end" as interrupted, so either works; confirm the resume point |
| RTK `takeOffAltitude` datum **unverified** | An RTK aircraft (none in the test fleet) |
| `FlyZoneManager` offline behaviour **unverified** | Smart Controller with Wi-Fi off |
| DJI registration cache lifetime **unverified** | Leave a registered controller offline; re-check monthly |
| UX SDK 4.16.2 with MSDK 4.18 on aircraft | Built and checked on the emulator only. On the P4 Pro V2.0: live video, capture, exposure panels, pre-flight checklist, take-off/RTH. If it fails, switch `DjiV4Platform.video` to `DjiV4Video` |
| UX SDK's shaded list helper (`com.chad.library`) ships without its R classes | R8 told to accept it (debug has the same gap and the panels inflate). Watch for `NoClassDefFoundError` when a UX SDK list opens |
| Protomaps style layer names, and a real PMTiles file | **Checked** on a Protomaps daily build (2026-10-18): the layer names match, labels and hillshade render. Still to see: a lab region, and the style's contrast in sunlight on a controller |
| Foreground service and background job on Android 7 (Smart Controller) and 14 | **Checked on an Android 7.1 emulator** (2026-10-18): screen off mid-flight, then a process kill with samples pending and the network off; all samples reached Blender. Still to check on the Smart Controller itself (DJI's ROM may kill background apps more) |
| v5 on aircraft | Mavic 3E on the RC Pro Enterprise, M400 on the RC Plus 2: registration, key push rates (`nativeRateHz` assumes 10), the `ProductType`/`CameraType` values, live video on the SDK's decoder (the emulator's renderer can't show it), Photo/Rec, a WPML survey upload and run (DJI simulator first), photos per distance interval, RC-loss behaviour |
| Photo counts on aircraft | v4 counts JPEGs through `Camera.setMediaFileCallback`, a single-listener setter that DJI's UI Library might also set. v5 uses `KeyNewlyGeneratedMediaFile`. Compare the card's count with the SD card after a survey on each aircraft |
| Travel legs on aircraft | Waypoint 1.0 (v4) flies them as ordinary waypoints with no photos on their legs. On v5 the WPML safe take-off height is the travel height. Check in DJI's simulator that the aircraft climbs before the transit and that no photos are taken on it |
| `LocationCoordinate3D.altitude` and `KeyTakeoffLocationAltitude` in v5 | Assumed to match v4 (height above take-off; take-off MSL). Field test against a surveyed point |
| M400 payload enums (P1 50, L2 84) and `imageFormat` `visible` | From DJI's wpmzsdk enums and spec, validator-accepted; confirm with a real upload. The P1 profile assumes the 35 mm lens |
| v5 APK size (release 175.3 MB at 0.4.0, of which DJI's UI Library 13 MB) | `-Popenutm.slimNative=true` leaves out the live-streaming libraries (Agora, ONVIF, RTSP, GB/T 28181, WebRTC, RTMP, NDI): about 45 MB less. On the emulator the slim build starts and registers with DJI. Still to check on hardware: video, missions, then make it the default |
| Aircraft settings and calibration on aircraft | `DjiV4AircraftSetup` and `DjiV5AircraftSetup` are read from the 4.18 and 5.18 jars and compile; none of it has run on an aircraft. In DJI's simulator, props off: read every setting, change RTH height and RC-loss action and read them back, compass and IMU calibration stages, gimbal calibration, obstacle sensing on the M3E. v4 reads sensor states from the SDK's key cache, not the single-listener setters DJI's UI Library may use |
| DJI's pre-flight panel from our top bar (v4) | The panel stops listening while no aircraft is connected (read in the 4.16.2 jar), so on the emulator it can't open from anywhere. Check on the P4 Pro V2.0 that tapping DJI's status opens it |
| Controller screen density | 640 × 360 dp is the worst case assumed, and every screen was checked there on Android 7.1 and 14. Read `adb shell wm size` and `wm density` on a Smart Controller, an RC Pro Enterprise and an RC Plus 2 ([14](14-dji-field-test-plan.md), 0.1) |
| DJI's UI Library v5 on aircraft | Vendored and checked on the emulator without an aircraft (2026-10-18). On the Mavic 3E (RC Pro Enterprise) and the M400 (RC Plus 2): live video in DJI's FPV view, capture, the exposure bar, lens switch and zoom (M3T, H30), tap-to-focus; each settings page with a product connected; compass, IMU and RC calibration and gimbal adjust; RTK pages; take-off/RTH; the simulator control (DJI's simulator, props off); whether DJI's panels and `DjiV5AircraftSetup` ever disagree on a value; the top bar on the controllers' own screen widths. If the FPV view fails, switch `DjiV5Platform.video` to `V5CameraView` |
| MSDK 5.18 exits at start-up after an empty `files/sdkconfig.json` | Seen on the emulator 2026-10-18: a 0-byte `sdkconfig.json` and `test.json`, left by a killed run, made every launch exit. **Guarded** since 2026-10-18: `DjiV5Adapter.start()` removes an empty one before `SDKManager.init` (the SDK rewrites it) |
| Map basemap and imagery | A real Protomaps extract and a Mapterhorn terrain extract are checked; a lab's own orthomosaic as imagery is not (needs GDAL and an orthomosaic; `tools/maps/region-pack.sh imagery`) |
| Not built yet | Corridor, facade and orbit missions, combined crosshatch + perimeter in one mission (one gimbal angle per mission today), translations themselves (every string is a resource; no language added: they need translators from the labs) |
| Device GNSS ellipsoid vs MSL vendor bugs | `GnssDatumCheck` flags a controller whose reading matches sea level where the geoid is far enough from the ellipsoid (with a terrain file). Still to see on real controllers ([14](14-dji-field-test-plan.md), 2.1) |
| Importing completed flights | Flight Blender feature request drafted; not filed |
| Pilot data | Data sharing agreement before rosters hold licence numbers or contacts. Pilot sign-in keeps no email in flight records |
| Pilot sign-in on a lab's Passport | Per-lab accounts and the programme's decision on instances (H5); a public application per app with the loopback redirect URI; a check that the deployment's Passport requires PKCE |
| Terrain following on aircraft | Built and unit-tested (2026-10-18); DJI's WPML validator accepts a followed survey and an upload part whose RTH height is above its own waypoints (emulator). Not flown. In DJI's simulator, props off: that the aircraft holds each waypoint's height on Waypoint 1.0 and WPML; what an added waypoint costs, since every waypoint is a stop (Waypoint 1.0's normal path, WPML `toPointAndStopWithDiscontinuityCurvature`), and if it is a lot, fly through the added ones (WPML `toPointAndPassWithContinuityCurvature`, v4's curved path); whether v4 restarts its photo distance at every waypoint (an extra photo per added one); the RTH height on v5. In the field, compare the height above ground with the aircraft's own ([14](14-dji-field-test-plan.md), 3.2) |
| What terrain following doesn't cover | Heights count from the **planned** take-off point's ground: taking off elsewhere shifts the whole route by the difference (re-referencing the heights at upload from the aircraft's home point is not built). The legs from take-off to the first waypoint and RTH's straight line home are neither followed nor checked; a travel altitude must be above the route's top and within the legal limit at take-off, which a route up a hill often can't be. A drop steeper than the 2 m/s descent leaves the route higher above the ground than wanted between waypoints: the Plan screen warns, but doesn't block, above the legal height above ground |
| Terrain model accuracy | GLO-30 is a surface model with about 4 m vertical error in open ground; under forest and in steep valleys it is worse. Compare the Plan screen's clearance with the aircraft's own height above ground in the field ([14](14-dji-field-test-plan.md), 3.2) |
| WebView start time on controllers | 11.7 s for the first WebView on the Android 14 emulator (the screen now opens first and WebView is warmed up when Plan opens). Time it on the controllers ([14](14-dji-field-test-plan.md), 0.3) |

## v5 app (MSDK 5.18)
For the Mavic 3 Enterprise (DJI RC Pro Enterprise) and Matrice 400 (DJI RC Plus 2). The app replaces DJI Pilot 2 on the controller while it flies.
- **SDK:** `com.dji:dji-sdk-v5-aircraft:5.18.0` (2026-05-22), `minSdk 24`, arm64 only, as DJI's sample. All 56 arm64 native libraries are 16 KB-aligned (Live), so the APK can load on 16 KB-page phones. Google Play becomes possible.
- **Telemetry:** v5 has no single state callback: each value is a key (position, NED velocity, attitude, flying, motors, GNSS, home, take-off altitude, battery, camera type) with its own push rate. The adapter keeps the latest of each and publishes one `DroneState` per position update. Listeners are dropped and re-added on every product (re)connect and on `resubscribe` (B4). RTK comes from `RTKCenter`: fixed solution, σ, and the RTK take-off altitude (datum unverified, as in v4).
- **Missions:** WPML (above), for the Mavic 3E, 3T and 3M, and for the M400 with a P1, L2, H30 or H30T. Other v5 aircraft (M30, M300, M350, Matrice 4, Mini 3/4 Pro) fly manually and are still declared and tracked.
- **Slim build** (`-Popenutm.slimNative=true`): without the SDK's live-streaming libraries, about 45 MB smaller; starts and registers on the emulator, not yet the default ([Open items](#open-items)).
- **Camera:** see Decisions. A payload swap on the M400 updates what the Plan screen offers.
- **Build facts** (Live):
  - the SDK's own R8 rules (in its aar) switch off shrinking for the whole app, so release ≈ debug size;
  - `app-v5-debug.apk` is 173 MB, the release 161.7 MB (a test-signed release starts the SDK on the emulator); with DJI's UI Library (2026-10-18) the release is 174.7 MB, and a test-signed one shows DJI's widgets and settings panel on the emulator. Debug APK sizes drift by tens of MB between incremental builds (AGP leaves gaps in the zip), so compare releases or clean builds: the SDK's arm64 native libraries alone are about 280 MB uncompressed, much of it live-streaming code (Agora, RTMP/RTSP/ONVIF, NDI) the app doesn't use;
  - a listener method that the API jar declares as a Java default method is abstract in the runtime SDK (`AvailableCameraUpdatedListener.onCameraStreamEnableUpdate`: `AbstractMethodError` with a lambda). The adapter implements every listener method explicitly.

## Re-run the Blender behaviour checks
With a local Flight Blender on `:8000` (started with `BYPASS_AUTH_TOKEN_VERIFICATION=1`), from the repository root, with JDK 17 as `JAVA_HOME`:
```bash
./gradlew test
```
- `BlenderIntegrationTest` covers the table under Sync engine.
- `PipelineBlenderTest` covers the end-to-end outage.
- Both skip themselves when Blender is down.

## Risk labels
From the OpenUTM Flying Labs launch review (2026-10), which checked the existing GCS connector ([`utm-adapter`](https://github.com/atti92/utm-adapter)) and Flight Blender on a local stack. The ones this app's design answers:
- **B3:** the declared altitude band collapses for constant-altitude missions (the connector builds it from the waypoints' min and max).
- **B4:** telemetry stops silently after an autopilot reboot.
- **H1:** completed flights come back after a connector restart (two writers saving whole snapshots).
- **H2:** the connector's telemetry record lacks height, ground/airborne status and pilot location.
- **H3:** no store-and-forward, and no way to upload a flight afterwards (Blender refuses declarations in the past).
- **H5:** a shared Blender instance has no isolation between labs.
