# Changelog

Changes to preview APK builds, newest first. Current setup instructions and qualification limits are in the [distribution guide](docs/17-panama-apk-distribution.md).

## 0.5.3 — 2026-10-19

### Fixed

- DJI checklist/settings panels opened while the camera is in the map inset now expand to the full camera stage, keeping their controls readable and the camera widget tree attached.
- Closing the panel and returning to map-primary mode leaves the camera inset free of miniature controls.

## 0.5.2 — 2026-10-19

### Fixed

- Opening **Scan QR code** no longer crashes because of conflicting DJI and JourneyApps layouts. Provisioning uses app-owned outer and nested scanner layouts.

## 0.5.1 — 2026-10-19

### Added

- Signed distribution builds with Panama as the default server for fresh DJI installs.
- Private device-credential entry with a protected server-read check before saving setup.
- A missing-offline-map hint in Plan that opens map import.
- English and Spanish distribution instructions.

### Changed

- Operator and pilot identities, device credentials and OAuth secrets remain outside the shared APKs.
- Existing saved server configurations are preserved during upgrades.

## 0.5.0 — 2026-10-19

### Added

- Five-direction, corridor and layered-surround survey planning alongside grid, crosshatch and perimeter.
- Reusable local saved plans with versioned JSON import/export.
- Flight Blender network diagnostics.
- An explicitly opted-in, debug-only Mini 2 Virtual Stick stop-and-shoot executor. Automatic Mini 2 execution remains disabled in ordinary debug and release builds pending qualification.

### Fixed

- Concave-area strip connectors, camera pitch interchange, corridor editing, area estimates and finish-action summaries.
- Per-flight server binding so changing device setup cannot redirect existing flight records to another Flight Blender server.
- Missing pilot/aircraft validation before declaring, and aircraft display when registration is blank.

See the [survey implementation and qualification notes](docs/15-survey-expansion-and-mini2.md) for scope, limitations and validation evidence.

## 0.4.0 — 2026-10-18

### Baseline snapshot

- Separate DJI v4 and v5 apps, with shared Fly, Plan, Pre-flight, Flights and Setup screens and optional pilot sign-in.
- DJI camera/status widgets and settings panels; grid, crosshatch and perimeter survey planning.
- Offline basemaps, terrain and imagery from local PMTiles packs.
- Emulator workflows against local Flight Blender and Flight Passport, and DJI WPML validation without an aircraft.

The baseline had not been flown on a real aircraft. See the [design notes](docs/12-dji-app-design.md) and [field test plan](docs/14-dji-field-test-plan.md).
