#!/usr/bin/env bash
# A lab's offline map files (docs/12, Zones and maps), cut from free sources. Needs the `pmtiles` CLI
# (protomaps/go-pmtiles: `brew install pmtiles`, or a release binary); imagery also needs GDAL.
#
#   region-pack.sh area <minlon,minlat,maxlon,maxlat> <out dir> [--dry-run]
#       basemap.pmtiles  OpenStreetMap from Protomaps' latest daily build, zoom 0-15 (ODbL: credit OpenStreetMap)
#       terrain.pmtiles  Mapterhorn terrain (Copernicus GLO-30 and finer national models), zoom 0-12
#   region-pack.sh imagery <orthomosaic.tif> <out dir>
#       imagery.pmtiles  the lab's own orthomosaic (e.g. a DroneDeploy GeoTIFF export) as PNG tiles
#
# Only the tiles inside the box are fetched (HTTP range requests), so a 10 x 10 km area is tens of MB,
# not the planet. Put the files on the lab's web host and list them in each device's setup as
# basemap_url / terrain_url / imagery_url, or copy them to a card and use Setup → Offline maps → Import.
set -euo pipefail

need() { command -v "$1" >/dev/null || { echo "needs $1 ($2)" >&2; exit 2; }; }

case "${1:-}" in
  area)
    [[ $# -ge 3 ]] || { echo "usage: $0 area <minlon,minlat,maxlon,maxlat> <out dir> [--dry-run]" >&2; exit 2; }
    need pmtiles "brew install pmtiles"
    bbox=$2; out=$3; dry=${4:-}
    IFS=, read -r x0 y0 x1 y1 <<<"$bbox"
    awk -v a="$x0" -v b="$y0" -v c="$x1" -v d="$y1" 'BEGIN { exit !(a < c && b < d && a >= -180 && c <= 180 && b >= -85 && d <= 85) }' \
      || { echo "bbox must be minlon,minlat,maxlon,maxlat" >&2; exit 2; }
    mkdir -p "$out"
    # The newest Protomaps build; its list is public JSON.
    build=$(curl -fsS --max-time 30 https://build-metadata.protomaps.dev/builds.json | python3 -I -c 'import json,sys; print(json.load(sys.stdin)[-1]["key"])')
    echo "basemap: Protomaps build $build"
    pmtiles extract "https://build.protomaps.com/$build" "$out/basemap.pmtiles" --bbox="$bbox" --maxzoom=15 $dry
    echo "terrain: Mapterhorn planet (zoom 12)"
    pmtiles extract https://download.mapterhorn.com/planet.pmtiles "$out/terrain.pmtiles" --bbox="$bbox" --maxzoom=12 $dry
    [[ -n "$dry" ]] || ls -la "$out"/basemap.pmtiles "$out"/terrain.pmtiles
    ;;
  imagery)
    [[ $# -eq 3 ]] || { echo "usage: $0 imagery <orthomosaic.tif> <out dir>" >&2; exit 2; }
    need pmtiles "brew install pmtiles"; need gdalwarp "brew install gdal"; need gdal_translate "brew install gdal"; need gdaladdo "brew install gdal"
    src=$2; out=$3; mkdir -p "$out"
    tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
    # Web Mercator, transparent outside the survey, then 256 px PNG tiles with overviews down to the region.
    gdalwarp -t_srs EPSG:3857 -r bilinear -dstalpha -co COMPRESS=DEFLATE "$src" "$tmp/ortho.tif"
    gdal_translate -of MBTILES -co TILE_FORMAT=PNG "$tmp/ortho.tif" "$tmp/imagery.mbtiles"
    gdaladdo -r average "$tmp/imagery.mbtiles" 2 4 8 16 32 64 128 256
    pmtiles convert "$tmp/imagery.mbtiles" "$out/imagery.pmtiles"
    ls -la "$out/imagery.pmtiles"
    ;;
  *)
    sed -n '2,15p' "$0"; exit 2 ;;
esac
