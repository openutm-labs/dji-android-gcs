# uxsdk-v5: DJI's UI Library for MSDK v5 (vendored)

DJI's widgets and panels for Mobile SDK v5, used by `app-v5` only, so pilots on the DJI RC Pro
Enterprise and RC Plus 2 get DJI Pilot 2's own status bar, settings, calibration and camera controls
(docs/12, Decisions: "Camera and flight controls (v5)").

- **Source:** [dji-sdk/Mobile-SDK-Android-V5](https://github.com/dji-sdk/Mobile-SDK-Android-V5),
  branch `dev-sdk-main`, commit `a48aa4e` (2026-06-03), `SampleCode-V5/android-sdk-v5-uxsdk/src`.
- **Licence:** the sample code, this module included, is MIT, © 2022 DJI ([LICENSE-DJI.txt](LICENSE-DJI.txt)).
  The MSDK it compiles against is under DJI's EULA, as for the rest of app-v5.
- **Built against:** MSDK 5.18.0 (the same as DJI's sample at that commit).

## Changes from DJI's module

Removed, so the module needs neither Google Play services nor MapLibre 10 (we use MapLibre 11):

- `mapkit/gmap` and `mapkit/maplibre` (the Google Maps and MapLibre providers), `map/MapWidget.java`,
  and the layouts only they or the sample use (`uxsdk_widget_map`, `menu/map_select_menu`).
  `mapkit/core` stays: plain Java, used by the HSI and the fly-zone helper; it loads providers by
  class name and finds none.
- `sample/**` (DJI's demo activities) and their layouts (`uxsdk_activity_default_layout`,
  `uxsdk_activity_widgets`, `uxsdk_fragment_widget_list`, `uxsdk_widget_list_item`).
- `res/font/noto_sans_cjk_sc_medium.otf` (16.5 MB, two top-bar popover rows): those rows use the
  system `sans-serif-medium` instead.

Build: Gradle Kotlin DSL instead of DJI's Groovy file; Java/Kotlin 17 (DJI: 1.8); compileSdk 34
(DJI: 35); no `kotlin-kapt` and no `wpmzsdk` 1.0.5.1 (neither is used by the code; MSDK brings its
own `wpmzsdk` 1.0.5.0); no `package` attribute in the manifest (AGP 8).

Source changes, each marked `OpenUTM:` in the code:

- `FPVWidget.updateCameraStream`: no stream surface while the camera is unknown (no aircraft). DJI's
  version hands its surface to the SDK's decoder on every `surfaceChanged`, even for no camera; on
  the emulator the decoder's EGL setup fails and the process dies (native, "pthread_mutex_lock called
  on a destroyed mutex"). Our own v5 camera view already waited for a camera.

## Updating

Copy `src/` from a newer DJI commit over this one, then repeat the removals above and rebuild
`:app-v5:assembleDebug`. Check DJI's `dependencies.gradle` for new libraries and their versions
(`gradle/libs.versions.toml`, the "DJI's UI Library v5" block).
