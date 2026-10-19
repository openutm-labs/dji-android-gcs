# Third-party code and data

This repository's own code is under the Apache License 2.0 ([LICENSE](LICENSE)). This file lists what it contains or puts into the APKs that isn't ours, and under which terms.

## In the source tree

| What | Where | Licence | Notes |
|---|---|---|---|
| DJI UI Library for MSDK v5 (sample code) | `uxsdk-v5/` | MIT, © 2022 DJI (`uxsdk-v5/LICENSE-DJI.txt`) | Changes from DJI's copy are listed in `uxsdk-v5/README.md`. Keep the licence file with it |
| Map label glyphs, built from Roboto | `app-common/src/main/assets/glyphs/` | Apache 2.0 (Roboto, © 2011 Google) | Signed-distance-field glyphs made with `tools/glyphs/BuildGlyphs.java` from Android's `RobotoStatic-Regular.ttf` |
| EGM96 geoid grid (15′) | `core/src/main/resources/egm96-15.bin` | Public domain (US NGA) | Repacked from `us_nga_egm96_15.tif` by `tools/build_egm96_grid.py` |
| Replay tracks | `adapter-fake/src/main/resources/tracks/` | Ours | A synthetic flight, and a survey flown on ArduCopter SITL |

## Fetched at build time and packed into the APKs

Nothing below is committed. Gradle downloads it from Maven Central and Google's Maven.

| What | Licence | Notes |
|---|---|---|
| DJI Mobile SDK 4.18, UX SDK 4.16.2 (`com.dji:dji-sdk`, `dji-uxsdk`) | DJI's EULA (developer.dji.com/policies/eula) | v4 `dji` build only. Each package needs its own DJI app key, never committed |
| DJI Mobile SDK 5.18, wpmzsdk (`com.dji:dji-sdk-v5-aircraft`, `wpmzsdk`) | DJI's EULA | v5 only |
| MapLibre Native Android | BSD 2-Clause | |
| OkHttp / Okio | Apache 2.0 | Public map HTTP client and cache |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | Apache 2.0 | |
| AndroidX (core, appcompat, recyclerview, constraintlayout, cardview, lifecycle, multidex, legacy-support) | Apache 2.0 | appcompat, recyclerview and constraintlayout because DJI's UI libraries need them |
| Material Components, Lottie, RxJava 3 / RxAndroid / RxKotlin, Iconics | Apache 2.0 | v5 only: DJI's UI Library needs them |
| ZXing core, zxing-android-embedded | Apache 2.0 | QR provisioning |
| JUnit 5 | EPL 2.0 | Tests only |

## Online street maps

Default raster tiles are served by the OpenStreetMap Foundation at `tile.openstreetmap.org`. Map data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), under ODbL. Visible attribution is shown on the map. Use of the public tile service follows its [tile usage policy](https://operations.osmfoundation.org/policies/tiles/): identifiable requests, cache-header support, no bulk/offline downloads or tile prefetch. Service is best effort; administrators can configure a different public raster provider through device setup.

## Data the labs download (never in the repository or the APK)

| What | Licence and attribution |
|---|---|
| Basemap: Protomaps daily build | OpenStreetMap data, ODbL: show "© OpenStreetMap contributors" |
| Terrain: Mapterhorn | Per source, mostly Copernicus GLO-30 (© DLR e.V. 2010–2014 and © Airbus Defence and Space GmbH 2014–2018, provided under COPERNICUS by the European Union and ESA): see mapterhorn.com/attribution |
| Imagery: the lab's own orthomosaics | The lab's |

## Why Apache 2.0
- **DJI's SDKs are proprietary.** The apps link to them, so a strong copyleft licence (GPL) for the app code would conflict with distributing the APKs.
- **Matching the rest of OpenUTM.** Flight Blender, Flight Passport and the InterUSS DSS are Apache 2.0.
- `uxsdk-v5/` stays under DJI's MIT licence (`uxsdk-v5/LICENSE-DJI.txt`).
