# 11 · Getting DJI flights into OpenUTM

**As of:** 2026-10-03. Every claim below comes from DJI's own documentation or from source repositories, with the link given. Anything that couldn't be confirmed from a primary source is marked **unverified**.

**Why this matters:**
- 7 of 7 Flying Labs survey responses use DJI Pilot / FlightHub (Flying Labs programme survey).
- The kickoff lists DJI as "Phased rollout (DJI support TBD)" and asks labs to declare flights from Mission Planner and QGC, so DJI isn't a Nov 15 deliverable. It is still where most flights will happen.

## The constraint that shapes everything

A DJI drone talks to exactly one app at a time. DJI SDK support, 2025-12-18: "the MSDK app and DJI Pilot 2 app cannot run simultaneously. The aircraft can only connect to one of the two apps at a time" ([dji-sdk/Mobile-SDK-Android-V5#682](https://github.com/dji-sdk/Mobile-SDK-Android-V5/issues/682)).

DroneDeploy, Litchi, Dronelink and DJI GS Pro are themselves built on DJI's Mobile SDK. So there is no way to add live tracking next to whatever app the pilot uses. Every option below gets its data from one of four places:

1. **Our own flight app**, built on DJI's Mobile SDK. It has to replace the pilot's app.
2. **DJI's cloud:** Pilot 2 connected to our server (Cloud API), or FlightHub 2 sending data out.
3. **The flight log**, imported after landing. Every DJI app and every SDK-based app writes one.
4. **Separate hardware on the drone** (an add-on tracker).

**Broadcast Remote ID is not an option in these countries:**
- DJI broadcasts Remote ID only where a law requires it. Asked whether drones broadcast outside the US, DJI answers "No" ([DJI Ag](https://ag.dji.com/newsroom/ag-news-faa-remote-id-compliance)).
- None of the 7 lab countries mandates it as of 2026-10:
  - Brazil: [RBAC 100](https://pergamum.anac.gov.br/pergamum/vinculos/RBAC100EMD00.pdf), in force 2026-06-16.
  - Kenya: [UAS Regulations 2020](https://www.kcaa.or.ke/sites/default/files/regulation/Civil%20Aviation%20(Unmanned%20Aircraft%20Systems)%20Regulations%202020.pdf).
  - Senegal: [RAS 06 Annex 5](https://www.anacim.sn/IMG/pdf/annexe_5_au_ras_06_-_systemes_d_aeronefs_telepilotes_rpas_.pdf).
  - Zimbabwe: [SI 271/2018](https://www.veritaszim.net/sites/veritas_d/files/S.%20I.%20271%20of%202018%20Civil%20Aviation%20Remotely%20Piloted.pdf).
  - Panama, Togo and Namibia: primary texts not obtained (**unverified**).
- India hasn't notified one either; Drone Rules 2021 Rule 11 only reserves the power to do so.
- So a ground receiver would hear nothing.
- Decoding DJI's proprietary DroneID with a software radio ([NDSS 2023](https://www.ndss-symposium.org/wp-content/uploads/2023-217-paper.pdf)) carries legal risk, and the signal is encrypted on newer O4 drones. Not recommended.

## The options

| Route | Live? | Covers | Doesn't cover | What changes for the pilot | Build effort |
|---|---|---|---|---|---|
| **A. Post-flight log import** | No, after landing | **Every DJI drone and every app:** DJI Fly, Pilot 2, DroneDeploy, Litchi, Dronelink, GS Pro, Map Pilot | Live tracking | Upload the log after the flight, or it syncs automatically via Airdata | Medium, and **needs a Flight Blender change** (below) |
| **B. DJI Cloud API** (Pilot 2 → our server) | Yes, **0.5 Hz** | Enterprise only: M30/M30T, M300 RTK, M350 RTK, Mavic 3E/3T/3TA, Matrice 4E/4T, Matrice 400, Docks | Consumer drones, DJI Fly, third-party apps | Log Pilot 2 into our server once per controller. **Can't use FlightHub 2 at the same time** | Medium–high |
| **C. FlightHub 2 → FlightHub Sync / airspace webhook** | Yes (rate **unverified**) | Enterprise drones already on FlightHub 2 | Free Standard tier; consumer drones (video only) | None, if the lab already uses FlightHub 2 Business/Enterprise | Low–medium; **coverage of manual Pilot 2 flights unverified** |
| **D. Our own Mobile SDK v5 app** | Yes, up to 10 Hz | Mini 3, Mini 3 Pro, Mini 4 Pro; Mavic 3E family; M30, M300, M350; Matrice 4, Matrice 400. **Android only** | Air 3/3S, Mavic 3 Classic/Pro, Mini 4K/5 Pro, Avata, anything flown on a DJI RC or RC 2 | **Our app becomes the flight app**, replacing DJI Fly, Pilot 2 or DroneDeploy | High: a full flight app |
| **E. Add-on tracker** (e.g. [Dronetag Mini](https://www.dronetag.com/products/mini), €299, 32 g, own GNSS, LTE-M, REST/WebSocket API) | Yes | Any drone, any app | Sub-250 g weight class; LTE-M coverage in these countries **unverified** | Mount hardware on each drone | Low software, per-drone hardware cost |

### A. Post-flight log import (the only route that covers every case)
- **Every DJI app writes the same log.** DJI Fly, Pilot 2 and every app built on the Mobile SDK write `DJIFlightRecord_*.txt` ([DJI parser README](https://github.com/dji-sdk/FlightRecordParsingLib)). Litchi additionally writes its own unencrypted CSV ([Litchi help](https://flylitchi.com/help)).
- **Encryption:** logs from version 13 on are AES-encrypted. The key comes from DJI's API, using a free DJI developer app key, once per log. [`dji-log-parser`](https://github.com/lvauvillier/dji-log-parser) (MIT) reads every log version, stores the fetched keys with the log for later offline use, and exports JSON, CSV, KML and GeoJSON. The unencrypted header gives the aircraft serial and start time with no network. DJI's own parser needs to be online for every parse and hasn't been updated since 2024-04.
- **What the log contains:**
  - a 10 Hz track: latitude/longitude, barometric height above take-off, velocity, `isFlying`, motors on/off;
  - the take-off point's altitude above sea level, plus serial, start time and duration ([proto](https://github.com/dji-sdk/FlightRecordParsingLib/tree/master/dji-flightrecord-kit/protoc/standard/protoc)).
  
  So DJI flights would carry the height above take-off that our MAVLink records lack ([H2](12-dji-app-design.md#risk-labels)). Ellipsoid altitude = take-off altitude above sea level + height + geoid correction, the same conversion the connector already does.
- **Where the logs live:**

| App | Location |
|---|---|
| DJI Fly | phone or controller: `Android/data/dji.go.v5/files/FlightRecord`; iOS Files app ([DJI](https://support.dji.com/help/content?customId=en-us03400006894&spaceId=34&re=NZ&lang=en)) |
| DJI RC / RC 2 | USB copy only, since they can't install other apps |
| DJI Pilot 2 | `DJI/com.dji.industry.pilot/FlightRecord` |
| DroneDeploy | its own cloud |
| Pix4Dcapture | the app is **retired**: Pro on 2025-07-31 ([Pix4D](https://support.pix4d.com/hc/en-us/articles/360047701131)) |

- **Logs that already arrive automatically:**
  - Airdata auto-syncs from its own app, DJI cloud sync, Litchi, DroneDeploy, Dronelink and UgCS. Its API (`GET /flights`, CSV/KML links) needs an Enterprise plan ([Airdata API](https://app.airdata.com/docs/api/)).
  - DJI's own cloud sync is opt-in. It was turned off in the US in 2024 but **still works in the Flying Labs countries** ([DJI](https://viewpoints.dji.com/blog/dji-to-disable-flight-record-sync-in-the-us)). It has no public API.
- **Blocker on our side:** Flight Blender rejects any declaration whose start or end is in the past, and accepts telemetry only for current operations ([H3](12-dji-app-design.md#risk-labels)). Importing past flights needs an upstream design change: either an "import completed flight" path or declare-before / attach-log-after.

### B. DJI Cloud API
- **How it connects:** in Pilot 2, Cloud Service › Open Platforms › our URL. Our server serves a login page, checks the developer licence, and hands MQTT credentials to Pilot 2 ([docs](https://developer.dji.com/doc/cloud-api-tutorial/en/feature-set/pilot-feature-set/pilot-access-to-cloud.html)). Current release v1.16.1 (2025-12-17).
- **Supported drones and controllers** ([product support](https://developer.dji.com/doc/cloud-api-tutorial/en/overview/product-support.html)):
  - M300 with RC Plus or Smart Controller Enterprise
  - M350 and M30 with RC Plus
  - Mavic 3E/3T/3TA with RC Pro Enterprise
  - Matrice 4E/4T and Matrice 400 with RC Plus 2
  - Docks 1/2/3
- **Telemetry** on topic `thing/product/{sn}/osd`, sent at **0.5 Hz** ([thing model](https://developer.dji.com/doc/cloud-api-tutorial/en/overview/basic-concept/thing-model.html)):
  - `latitude`, `longitude`;
  - `height` (WGS-84 ellipsoid, which is the datum Flight Blender wants) and `elevation` (relative to take-off);
  - `horizontal_speed`, `attitude_head`, home point.
  
  Faster rates need "Live Flight Controls", which asks the pilot to grant cloud control and disables the sticks on Matrice 4. Not suitable.
- **Planned route:** Pilot 2 syncs a KMZ/WPML route library with our server, but doesn't report *which* route is being flown.
- **One or the other:** Pilot 2 logs into FlightHub 2 *or* a third-party server, not both. This comes from a DJI SDK forum article, now behind a login, read via search index (**treat as likely**).
- **Reference code:** DJI's open-source demo server reached [end of maintenance 2025-04-10](https://github.com/dji-sdk/DJI-Cloud-API-Demo) and is "not production-ready".

### C. FlightHub 2 sending data out
- **FlightHub Sync** pushes telemetry in real time to our MQTT broker, but the docs scope it to "task operation". Media and flight records go to S3/MinIO, plus HMAC-signed webhooks. It needs the Business or Enterprise tier ([Sync intro](https://fh.dji.com/user-manual/en/custom-development/flighthub-sync-release/sync-introduction.html), [billing](https://fh.dji.com/user-manual/en/billing-instructions/billing-package-instructions.html)).
- **"Airspace Management Service Provider Flight Data Reporting"** (added 2025-11-04) pushes signed JSON to a webhook ([docs](https://fh.dji.com/user-manual/en/organization-management/airspace-management.html)):
  - fields: position, height, altitude, speeds, course, take-off/in-flight/landed status;
  - the format looks designed for China's CAAC;
  - send rate and coverage of controller-flown aircraft are **unverified**.
- **Test with one lab** before relying on either.

### D. Our own Mobile SDK app
- **Two SDK generations, and gaps in both:**

| SDK | Platform | Status | Supports |
|---|---|---|---|
| **v5** | Android only | 5.18.0, 2026-05-22 | Mini 3, Mini 3 Pro, Mini 4 Pro, Mavic 3 Enterprise series, Mavic 3TA, M30, M300, M350, Matrice 4 / 4D, Matrice 400, H30 ([README](https://github.com/dji-sdk/Mobile-SDK-Android-V5)) |
| **v4** | Android | Legacy, last release 4.18 on 2024-10-24 | Older drones: Phantom 4 family, Mavic 2, Mavic Air 2, Air 2S, Mini 2, Mini SE, M200/M210 |
| **v4** | iOS | Development stopped 2023-11-29 | — |

- **No SDK at all** for Air 3/3S, Mavic 3 (original, Classic, Pro), Mavic 4 Pro, Mini 4K, Mini 5 Pro, Avata/Avata 2, Neo, Flip or Inspire 3. Nor for Mini 3 / 3 Pro on the DJI RC, or Mini 4 Pro on the DJI RC 2 ([DJI compatibility table, 2026-06-29](https://support.dji.com/help/content?customId=01700000763&documentType=&lang=en&paperDocType=ARTICLE&re=US&spaceId=17)).
- **Coverage will keep shrinking:** DJI says Mobile SDK "will only supports some of the new DJI products in the future" ([version differences](https://developer.dji.com/doc/mobile-sdk-tutorial/en/quick-start/version-differences.html)).
- **Screen controllers:** the DJI RC and RC 2 can't install third-party apps; the RC Pro, RC Plus and RC Plus 2 can.
- **Telemetry the app could read:** position at up to 10 Hz. `Location3D.altitude` is relative to take-off; DJI recommends RTK take-off altitude plus that relative altitude for an absolute value ([#727](https://github.com/dji-sdk/Mobile-SDK-Android-V5/issues/727)).
- **Registration:** free developer account (asks for a card "for verification"), one app key per app. Online registration is needed once; offline licences also exist.
- **v4 and v5 can't share one app.** Checked 2026-10-03 against DJI's official sample apps ([v4 sample](https://github.com/dji-sdk/Mobile-SDK-Android/tree/master/Sample%20Code), [v5 sample](https://github.com/dji-sdk/Mobile-SDK-Android-V5/tree/master/SampleCode-V5)):

| Conflict | Evidence |
|---|---|
| **Same start-up class** | Both SDKs initialise through `com.cySdkyc.clx.Helper.install(this)`, a class that ships inside each SDK, not in the samples. Two different copies under one name means a duplicate-class build error; even if forced, only one SDK could load |
| **Same native library file names** | `libDJICSDKCommon.so`, `libDJIUpgradeCore.so` and `libDJIUpgradeJNI.so` appear in both SDKs' packaging lists. An APK holds one file per path, so one SDK would run against the other's native code |
| **Same manifest key** | Both read `com.dji.sdk.API_KEY` and both claim the controller's USB connection |
| **Different targets** | v4.18 targets API 34 with 32- and 64-bit libraries; v5.18 targets API 35, 64-bit only |

  - DJI's docs describe v5 as a new SDK that is not compatible with v4.
  - No DJI answer on combining them exists in either repo. #634, an unrelated attempt to load v5 from an Android dynamic feature module, crashes in `Helper.install` and has no reply.
  - **Not done:** an actual combined build.
- **v4 is also blocked from Google Play.**
  - Google Play has required 16 KB page-size support for new apps and updates targeting Android 15+ since 2025-11-01 ([Android blog](https://android-developers.googleblog.com/2025/07/transition-to-16-kb-page-sizes-android-apps-games-android-studio.html)).
  - v4.18's native libraries are 4 KB-aligned. The request to fix it ([Mobile-SDK-Android#1343](https://github.com/dji-sdk/Mobile-SDK-Android/issues/1343), 2025-08-26) is open with no DJI reply. v5.18 added 16 KB support.
  - So anything built with v4 has to be installed as an APK file, outside Google Play.
- **The workable shape: one codebase, two apps.**
  - A shared core holds the user interface, the declare/start/complete flow, the offline buffer and the push to the connector's `PUT /v1/vehicles/{id}`.
  - Thin adapters for v4 and v5 each read position, height, speed, heading, serial number and home point.
  - Built as two APKs with different package names, each with its own DJI app key; pilots install the one that matches their drone.
- **Even both apps together don't reach most current drones:**
  - Still uncovered: Air 3/3S, Mavic 3 Classic/Pro, Mavic 4 Pro, Mini 4K/5 Pro, Avata, Neo, Flip, Inspire 3, and any Mini flown on the DJI RC or RC 2.
  - The v4 app is frozen: no release since 2024-10 and no Google Play route.
- **Bottom line:** our app would have to be a complete flight app that labs choose over DJI Fly, Pilot 2 and their mapping apps. Two SDK generations, Android only, and many current DJI models unsupported.

## Restriction maps on DJI
- **Consumer drones and DJI Fly:** our zones can't be pushed at all; DJI's own geofence database applies.
- **Pilot 2 via Cloud API:** our map elements can be *displayed*, but not enforced ([map elements](https://developer.dji.com/doc/cloud-api-tutorial/en/feature-set/pilot-feature-set/pilot-map-elements.html)).
- **FlightHub 2 custom flight areas:** sync to dock aircraft automatically; controller-flown aircraft get an update prompt. Confirmed for M30 and Matrice 4. Third parties can manage these zones through the OpenAPI ([custom flight area](https://fh.dji.com/user-manual/en/task-area-management/custom-flight-area.html)).

## Plugging into our stack (verified 2026-10-03)
The connector accepts telemetry pushed over HTTP, not only from MAVLink: `PUT /v1/vehicles/{vehicle_id}` with `{vehicle_id, observed_at, latitude, longitude, altitude:{meters, datum: WGS84|AMSL}, heading_deg, groundspeed_mps, quality}`.

**Test:** an invented vehicle `dji-push-test`, not configured as a MAVLink link, ran mission → submit → start → 6 pushes at 1 Hz → complete. Result: 6 observations stored in Flight Blender, `CONFORMING`, declaration Ended.

So a Cloud API bridge, an SDK app or a tracker gateway can reuse the connector's operations, conformance check and Blender backend unchanged. They still inherit the connector issues in the [launch review](12-dji-app-design.md#risk-labels): B3 (altitude band) and H1 (restart race). Log import after landing additionally needs the Blender change described under A.

## Recommendation
1. **Week 1: inventory each lab.**
   - Drone models and controllers (RC-N1/N2/N3, DJI RC, RC 2, RC Pro, RC Plus).
   - Flight app per job type: manual flying vs mapping (DroneDeploy, Litchi, Dronelink, GS Pro).
   - FlightHub 2 tier; whether Airdata or DJI cloud sync is already on.
   - Phone OS; field connectivity.
   
   "DJI Pilot / FlightHub" in the survey suggests enterprise drones, and those are the only Cloud API candidates; confirm it.
2. **Baseline for every lab: declare before the flight + import the log after.**
   - It covers every drone and every app, including the mapping apps, with no change in how pilots fly.
   - Two things to build: the Blender change for past flights, and a small importer (`dji-log-parser` → 1 Hz track → push to Blender).
   - The importer can take files uploaded from a laptop, or pull from Airdata for labs that already use it.
3. **Live tracking where it matters:**
   - Enterprise drones flown with Pilot 2: Cloud API bridge (0.5 Hz) → connector `PUT /v1/vehicles/{id}`. Use FlightHub Sync instead if the lab won't leave FlightHub 2 *and* a test shows it covers controller-flown flights.
   - Any other drone or app that truly needs live tracking: an add-on tracker.
4. **Our own Mobile SDK app only if** a specific lab flies manually with a supported model, needs live tracking, and is willing to change apps. It's the most expensive route and covers the fewest drones.
