# 13 · DroneDeploy: integration and pilot migration

**As of:** 2026-10-17. Read from DroneDeploy's help center (all articles, through its public Zendesk API), its developer docs, archived copies of its GraphQL reference (2024-11 to 2026-03), its Master Services Agreement (2026-02-20) and its pricing page. Nothing was signed up for or logged into. Key claims were re-read directly: supported drones, the upload API, mobile upload, multi-battery resume and the MSA.

Labels:
- **Doc:** DroneDeploy's own documentation.
- **Archived:** its GraphQL reference, now removed (developer.dronedeploy.com redirects to the help center), read from the Wayback Machine.
- **Inferred:** our reading; says from what.

Article links: `H/<id>` = `https://help.dronedeploy.com/hc/en-us/articles/<id>`.

## Answer
- **Pushing imagery into DroneDeploy for processing: possible, with conditions.**
  - How: create a map plan, then an "image transfer" that points DroneDeploy at a zip of the photos on a public or signed URL. Processing then starts by itself (Doc, H/1500004963742).
  - DroneDeploy pulls from that URL; there is no direct or resumable upload from a device. So an in-app upload needs somewhere to stage the photos (a storage bucket).
  - It needs an **Enterprise** API key, tied to one DroneDeploy user, with no OAuth (Doc, H/27659888106391).
- **Mirroring DroneDeploy plans in our app: polygons only.**
  - The API lists and creates plans with their boundary.
  - It exposes none of a plan's flight settings: altitude, overlap, speed, gimbal, terrain (Archived).
  - It has no plan export (KML/KMZ/WPML).
- **No live link.** No telemetry API and no flight webhooks. Flight records and DroneDeploy's own `.log` files appear only after the pilot's device syncs (Archived). Pilots who keep flying DroneDeploy can't send live positions to Blender.
- **Most of the friction is hardware, not software.** Several of the labs' aircraft and controllers can't fly with DroneDeploy on Android at all (below). DroneDeploy pilots in the labs are therefore likely on iPhones/iPads, or on Android 14-or-older phones. Our app is Android-only.

## What DroneDeploy's API allows

| Need | Possible? | How (Doc unless marked) | Needs |
|---|---|---|---|
| Send our photos to DroneDeploy for processing | **Yes** | REST `POST /v2/plans`, then `POST /v2/image_transfer {plan_id, inputs[{location: <signed URL>, type: map_source_image}]}`. Zip recommended, over 2 GB fine. Processing starts automatically; poll for status (H/1500004963742) | Enterprise API key; a place to host the zip at a URL |
| Upload straight from the device (chunked/resumable) | **No** (not documented) | DroneDeploy's own app uses an internal path. A "Data Upload API" is mentioned in passing, without docs | Ask DroneDeploy |
| List projects and plans, read their boundary | **Yes** | GraphQL `viewer.organization.plans`, `mapPlan(id)`: id, name, `geometry[{lat,lng}]`, project (Archived) | Enterprise API key |
| Read a plan's altitude, overlap, speed, gimbal, terrain setting | **No** | Not in the schema. `FlightRecord.flightSettings` has altitude, overlap, speed, but only for flights already flown (Archived) | — |
| Create or rename a plan | **Yes** | GraphQL `createMapPlan {name, geometry, projectId, workflow}`, `updateMapPlan`; REST `POST /v2/plans` (Archived; H/1500004963702) | Enterprise API key |
| Export a plan (KML, KMZ, WPML) | **No** | Import only: a KML/GeoJSON/SHP boundary in the web app (H/1500004861201); a DJI KMZ "recorded flight" (closed beta, H/33102686051095) | — |
| Get processed outputs | **Yes** | GraphQL `createExport` (orthomosaic, elevation, point cloud, model…; GeoTIFF, LAS, OBJ…), with an optional webhook when ready; links last 30 days (H/1500004963722) | Enterprise API key |
| Flight tracks while pilots fly DroneDeploy | **After the flight only** | GraphQL `flightRecords(...)`: location, duration, aircraft serial, and a download of DroneDeploy's own (undocumented) `.log` (Archived) | Enterprise API key; device synced |
| Write our flights into DroneDeploy's flight logs | **No** (not found) | — | — |

API terms (Doc, MSA 2026-02-20):
- Machine accounts need DroneDeploy's prior written consent.
- Customers may not access the service to build a competitive product, or one using similar ideas, features, functions or graphics.
- **Inferred:** a replacement flight app that feeds DroneDeploy's processing may or may not count as "competitive". So:
  - Ask DroneDeploy before building the API connector.
  - Don't copy its screens or graphics. Plan parameters such as altitude, overlap, speed and direction are industry-standard terms and fine to use.

## Who can fly with DroneDeploy today (the labs' fleet)
From DroneDeploy's supported-drones table (Doc, H/1500004964842, updated 2026-08-04) and its Smart Controller guide (Doc, H/1500004964222):

| Aircraft / controller | DroneDeploy flight app | Our apps |
|---|---|---|
| Phantom 4 Pro V2.0 | iOS, or Android 14 and older; standard RC only | v4: phone, or the **Smart Controller** |
| Mavic 2 Pro | iOS, or Android 14 and older | v4: phone or Smart Controller |
| Air 2S | **iOS only** | v4: phone or Smart Controller (manual) |
| Mavic Air 2 | **iOS only** | v4: phone or Smart Controller (manual) |
| Mini 2 | **Processing only, no flight** | v4: phone or Smart Controller (manual) |
| Mavic 3 Enterprise | RC Pro Enterprise | v5 on the RC Pro Enterprise |
| Matrice 400 | **Processing only, no flight** | v5 on the RC Plus 2 |
| DJI Smart Controller (RM500) | **Not compatible** | Supported (v4) |

What this means for migration:
- **iPhone/iPad pilots** (Air 2S, Mavic Air 2, and likely many P4/Mavic 2 users) need an Android phone or tablet, or a Smart Controller. That is the one real cost of switching. A Smart Controller also gives them a bright screen and no phone to manage.
- **Pilots who couldn't fly with DroneDeploy at all** gain something by switching: a Smart Controller, the Mini 2, the Matrice 400. Start the migration with them.
- **DroneDeploy on Android stops at Android 14** for the v4-era aircraft. New phones won't run it either way. Our v4 app on Android 15+ is still to test (docs/12).

## Pilot workflow: DroneDeploy vs our app
DroneDeploy's steps, from its own guides (Doc: H/1500004861101, H/1500004964262, H/1500004862021, H/1500004965162, H/22589689799703):

| Step | In DroneDeploy | In our app today | Gap → plan |
|---|---|---|---|
| Plan the area | Web or app: draw or import a boundary; altitude, front/side overlap (default 75/65), direction, speed, gimbal angle, travel altitude, terrain awareness | Draw or import KML/KMZ; height, front/side overlap (default 75/65), heading, speed, gimbal angle, travel altitude. GSD, lines, time, photos and batteries shown. With a terrain file (2026-10-18): the route's clearance over the ground and the height above ground, as warnings, and *Follow terrain* (heights that keep the altitude above the terrain model, [12](12-dji-app-design.md#missions-waypoint-10-in-v4-wpml-in-v5)) | Terrain following is not yet flown on an aircraft. Optional: import DroneDeploy plan boundaries via the API (Phase B) |
| Other mission types | Enhanced 3D (crosshatch + perimeter), facade, corridor, panorama, video, orbit (v5) | Map (lawnmower grid), **Crosshatch and Perimeter** (built 2026-10-18, one mission each; [12](12-dji-app-design.md#missions-waypoint-10-in-v4-wpml-in-v5)) | Ask the labs whether they need corridor, facade or orbit, and whether one combined 3D mission matters |
| Offline | "Make available offline", while online, for standard map plans; up to 10 base maps | Offline by design: map files, zones, declarations queued | None |
| On site | Firmware, compass/IMU in the DJI app; Enterprise custom checklists (mandatory, offline, synced later) | DJI's pre-flight checklist widget (v4); zone and altitude warnings | Add a lab-defined checklist from the device setup, recorded in the flight journal |
| Fly | Automatic checks, take off, autonomous | Same, plus UTM declaration and live telemetry | None |
| Battery swap | Return home at low battery, swap, "Continue Flight" from the exact point | **Resume…** from the last waypoint reached (built 2026-10-17) | Matches; DroneDeploy resumes mid-segment, ours re-flies the cut segment (no gaps either way) |
| Upload images | "Upload Now" over the controller link: paid plans, iOS only, at most 1,000 images, single battery. Otherwise SD card → computer → web uploader | Nothing in the app | Most lab surveys already go by SD card. Keep that. Phase B names the plan for them; Phase C only if asked |
| Process, share | Automatic; email; explore, measure, export, share | — | Unchanged: DroneDeploy web |
| Flight logs | Recorded automatically, synced, downloadable | Every flight on the device; GeoJSON/CSV export; tracks in Blender | Labs keeping logbooks in DroneDeploy or Airdata lose entries; check what they use |

## Recommendation
- **Phase A: built and run on the emulator, 2026-10-17.** Removes the differences pilots will notice, with no DroneDeploy dependency:
  - **Plan screen:** labelled Altitude, Front/Side overlap, Speed, Flight direction, plus **Gimbal angle** and **Travel altitude**. Travel altitude means: climb over the aircraft, fly level to the start, descend; and climb again after the last line. It must be above the survey altitude, as in DroneDeploy. It is declared to Blender, and is also the v5 RTH height. The declared area now includes the take-off point and the transit legs.
  - **Lab checklist** (`checklist` in the device setup): offline. It is in the Fly screen's Pre-flight panel; before take-off the rail's first slot asks for it (*Checklist 0/5*), and it blocks an automatic mission's slide to start until done. It is saved in the flight journal and the GeoJSON export. (Since the 2026-10-18 redesign, docs/12.)
  - **Photos → DroneDeploy sheet** (`imagery` in the device setup), opened once after a flight is finished; until it's answered, the rail's first slot says *Photos*. It shows:
    - the photo count and time span, from the camera's own new-file reports (v4 `MediaFile` callback, v5 `KeyNewlyGeneratedMediaFile`; JPEGs only);
    - a map name to use (site or lab, date, flight ID), with Copy name;
    - the SD-card steps;
    - Uploaded, Not uploading, or Later. The answer is recorded and shown on the Flights screen, which also offers the sheet for earlier flights.
  - SD-card upload to DroneDeploy's web uploader stays as it is. Large and multi-battery surveys already go that way even inside DroneDeploy.
- **Phase B: with an Enterprise API key and DroneDeploy's written OK.** A small DroneDeploy client in `core`; the key comes in the device setup like the Blender credential:
  - import plan boundaries from the lab's DroneDeploy projects;
  - after each mapping flight, create the matching DroneDeploy map plan with the flown boundary and a clear name. The pilot's SD-card upload then lands in it; whether the web uploader can target an API-made plan is to confirm;
  - show processing status and export links.
- **Phase C: only if labs ask.** In-app upload:
  - photos from the drone to the controller over the link (MSDK media download);
  - then to a storage bucket, with a signed URL handed to `image_transfer`.
  - This adds storage cost and a service to run, against docs/12's "no new backend services". It needs good internet in the field, which the labs mostly lack. DroneDeploy itself doesn't offer it on Android or for multi-battery flights.

Before any of this, find out per lab:
- who flies DroneDeploy, on which phone or tablet (iOS or Android, which version);
- which mission types they use;
- whether they rely on DroneDeploy checklists or logbooks.

The answers decide hardware purchases and which mission types to add.

## Questions for DroneDeploy
WeRobotics has been a DroneDeploy Technology Partner since 2018, with the partnership renewed in 2022 (werobotics.org), so this can go through that channel:
1. API access for Flying Labs below Enterprise (DroneDeploy.org nonprofit licensing, or a Developer Partner account)?
2. Is a third-party flight app that feeds DroneDeploy processing acceptable under the MSA's competitive-product clause? May we use a machine account, and where are the "API online portal" terms?
3. Is there a direct or chunked upload endpoint (the "Data Upload API")?
4. Can an image transfer, or a web upload, target a plan created through the API or a template?
5. Can a plan's flight settings be read, or plans be exported?
6. What is the `.log` format? Are there any near-real-time flight events?
7. What are the GraphQL rate limits, and is there a current schema reference?
8. What is the status of the Apps platform?
9. Is non-US data residency available, for Africa, Asia and Latin America?
10. Can our app write flight records into DroneDeploy, so labs' logbooks stay complete?

## Sources
- REST map processing / image transfer: H/1500004963742 (2026-03-25)
- API getting started: H/27659888106391 (2026-04-24); authentication: https://developer-docs.dronedeploy.com/api/authentication
- GraphQL reference (archived): https://web.archive.org/web/20251011202303/https://developer.dronedeploy.com/reference/queryroot.doc.html
- Export API: H/1500004963722
- Supported drones: H/1500004964842 (2026-08-04); Smart Controller compatibility: H/1500004964222 (2025-09-25)
- Uploading on mobile: H/1500004965162 (2026-02-19)
- Multi-battery flights: H/22589689799703 (2025-12-03)
- Flight logs: H/1500004964582, H/1500004964242
- Master Services Agreement: https://www.dronedeploy.com/legal/master-services-agreement (2026-02-20)
- Pricing and DroneDeploy.org: https://www.dronedeploy.com/pricing, https://www.dronedeploy.com/about/dot-org
