"""Repack the EGM96 15' geoid grid for the app, and write a PROJ-computed test fixture.

The connector converts AMSL <-> WGS-84 with PROJ and NGA's `us_nga_egm96_15.tif`. The app can't
ship PROJ, so this samples PROJ on the same 15' lattice and stores the geoid undulation N as
big-endian int16 centimetres:

  header  b"EGM96G1\\0" | int32 rows | int32 cols | float64 lat0 | float64 lon0 | float64 step  (40 bytes)
  body    rows x cols int16, row 0 = lat0 (north), col 0 = lon0 (west); value = round(N * 100)

N is defined so that ellipsoid height = orthometric (AMSL) height + N.

Run with the connector's venv (it has pyproj; github.com/atti92/utm-adapter), from this repository's root:
  <utm-adapter>/.venv/bin/python -I tools/build_egm96_grid.py <utm-adapter>/.local/us_nga_egm96_15.tif .
"""
from __future__ import annotations

import random
import struct
import sys
from pathlib import Path

import numpy as np
from pyproj import Transformer

ROWS, COLS, LAT0, LON0, STEP = 721, 1441, 90.0, -180.0, 0.25


def transformer(grid: Path) -> Transformer:
    g = str(grid).replace(" ", r"\ ")
    return Transformer.from_pipeline(
        "+proj=pipeline +step +proj=unitconvert +xy_in=deg +xy_out=rad "
        f"+step +proj=vgridshift +grids={g} +multiplier=1 "
        "+step +proj=unitconvert +xy_in=rad +xy_out=deg"
    )


def undulation(t: Transformer, lat: np.ndarray, lon: np.ndarray) -> np.ndarray:
    """N = (ellipsoid height of a point at 0 m AMSL)."""
    _, _, h = t.transform(lon, lat, np.zeros_like(lat))
    return np.asarray(h)


def main(grid: Path, app_root: Path) -> None:
    t = transformer(grid)
    lats = LAT0 - STEP * np.arange(ROWS)
    lons = LON0 + STEP * np.arange(COLS)
    lon_g, lat_g = np.meshgrid(lons, lats)
    n = undulation(t, lat_g.ravel(), lon_g.ravel()).reshape(ROWS, COLS)
    if not np.all(np.isfinite(n)):
        raise SystemExit("PROJ returned non-finite values; is the grid path right?")
    cm = np.round(n * 100).astype(">i2")

    out = app_root / "core/src/main/resources/egm96-15.bin"
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("wb") as f:
        f.write(b"EGM96G1\0" + struct.pack(">iiddd", ROWS, COLS, LAT0, LON0, STEP))
        f.write(cm.tobytes())
    print(f"wrote {out} ({out.stat().st_size} bytes); N range {n.min():.2f} .. {n.max():.2f} m")

    # Off-lattice points evaluated by PROJ itself (its own interpolation), for the Kotlin test.
    rng = random.Random(96)
    pts = [(-1.2921, 36.8219), (-15.7939, -47.8828), (14.7167, -17.4677), (6.1375, 1.2123),
           (8.9824, -79.5199), (-17.8292, 31.0522), (-22.5609, 17.0658), (46.98, 7.472),
           (89.9, 10.0), (-89.9, -170.0), (0.0, 179.9), (0.0, -179.9)]
    pts += [(rng.uniform(-80, 80), rng.uniform(-180, 180)) for _ in range(40)]
    plat = np.array([p[0] for p in pts]); plon = np.array([p[1] for p in pts])
    pn = undulation(t, plat, plon)
    fx = app_root / "core/src/test/resources/egm96-fixture.csv"
    fx.parent.mkdir(parents=True, exist_ok=True)
    with fx.open("w") as f:
        f.write("# lat,lon,N_m  (PROJ vgridshift on us_nga_egm96_15.tif)\n")
        for (la, lo), v in zip(pts, pn):
            f.write(f"{la:.6f},{lo:.6f},{v:.4f}\n")
    print(f"wrote {fx} ({len(pts)} points)")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    main(Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve())
