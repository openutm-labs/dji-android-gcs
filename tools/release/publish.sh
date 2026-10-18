#!/usr/bin/env bash
# Builds signed release APKs and the update channels, checks them, and prints how to publish
# (docs/12, Distribution and updates). Publishing itself is left to the person releasing.
#
#   tools/release/publish.sh [out dir]          default: build/publish
#
# Needs the OPENUTM_RELEASE_* signing properties (tools/release/make-release-key.sh) and the DJI app
# keys (DJI_API_KEY, DJI_V5_API_KEY) in ~/.gradle/gradle.properties or the environment.
set -euo pipefail
cd "$(dirname "$0")/../.."
out=${1:-build/publish}
sdk=$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null || true); sdk=${ANDROID_HOME:-${sdk:-}}
apksigner=$(ls -d "$sdk"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)
[[ -x "$apksigner" ]] || { echo "needs apksigner from the Android SDK build-tools" >&2; exit 2; }
./gradlew -q :core:test :adapter-fake:test
./gradlew -q :app-v4:assembleRelease :app-v4:releaseChannel :app-v5:assembleRelease :app-v5:releaseChannel
rm -rf "$out"; mkdir -p "$out/v4" "$out/v5"
cp app-v4/build/release-channel/* "$out/v4/"
cp app-v5/build/release-channel/* "$out/v5/"
for apk in "$out"/v4/*.apk "$out"/v5/*.apk; do
  # Refuse unsigned or debug-signed APKs: devices would reject them as updates.
  certs=$("$apksigner" verify --print-certs "$apk" 2>&1) || { echo "NOT SIGNED: $apk" >&2; exit 1; }
  grep -q "CN=Android Debug" <<<"$certs" && { echo "DEBUG-SIGNED: $apk" >&2; exit 1; }
  echo "$(basename "$apk"): signed by certificate SHA-256 $(grep -m1 'SHA-256 digest' <<<"$certs" | sed 's/.*: //')"
done
echo
echo "Channels: $out/v4/latest.json (v4 dji + sim), $out/v5/latest.json (v5)."
echo "Publish each folder as static files at the update_url in the devices' setup, e.g. with GitHub Releases:"
echo "  gh release create v<version> $out/v4/* --title 'OpenUTM Flight v4 <version>'   # then point update_url at the release's latest.json"
echo "Labs without internet: copy the APK to a microSD card; the Smart Controller installs from it."
