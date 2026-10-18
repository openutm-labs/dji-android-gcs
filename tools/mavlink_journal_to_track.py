"""Turn a flight recorded by the UTM connector (raw MAVLink frames) into a fake-adapter track file.

The connector's journal (`<utm-adapter>/.local/connector-events.jsonl`) holds every MAVLink
frame it received from ArduCopter SITL, base64-encoded. This decodes the few messages a track needs
directly from the MAVLink 2 wire format (no pymavlink, so 800 MB scans in about a minute):

  HEARTBEAT (0)             armed flag, ArduCopter flight mode
  GLOBAL_POSITION_INT (33)  lat/lon, AMSL and relative altitude, NED velocity, heading
  EXTENDED_SYS_STATE (245)  landed state (on ground / in air)

and maps them onto the DJI-shaped DroneState the app's core consumes (see docs/12).

  python -I mavlink_journal_to_track.py JOURNAL --list
  python -I mavlink_journal_to_track.py JOURNAL --segment N --out TRACK.jsonl [--serial S] [--geoid-n M]

`--geoid-n` is the EGM96 undulation at the take-off point, used to write the "device GNSS"
ellipsoid reference into the header (SITL has no phone; the value is ground truth + 0 noise).
"""
from __future__ import annotations

import argparse
import base64
import json
import struct
import sys
from dataclasses import dataclass, field
from datetime import datetime

COPTER_MODES = {0: "STABILIZE", 2: "ALT_HOLD", 3: "AUTO", 4: "GUIDED", 5: "LOITER", 6: "RTL", 9: "LAND",
                16: "POSHOLD", 17: "BRAKE"}


def _payload(frame: bytes) -> tuple[int, bytes] | None:
    if len(frame) < 12 or frame[0] != 0xFD:
        return None
    length = frame[1]
    msgid = frame[7] | (frame[8] << 8) | (frame[9] << 16)
    return msgid, frame[10:10 + length]


def _pad(p: bytes, n: int) -> bytes:  # MAVLink 2 trims trailing zero bytes
    return p + b"\0" * (n - len(p)) if len(p) < n else p[:n]


@dataclass
class Segment:
    start_s: float
    end_s: float = 0.0
    samples: list[dict] = field(default_factory=list)
    max_rel: float = 0.0


def scan(path: str) -> list[Segment]:
    armed, mode, landed = False, "UNKNOWN", None
    segments: list[Segment] = []
    current: Segment | None = None
    with open(path, "rb") as f:
        for raw in f:
            if b'"mavlink_frame"' not in raw:
                continue
            rec = json.loads(raw)
            frame = base64.b64decode(rec["payload"]["frame_b64"])
            pm = _payload(frame)
            if pm is None:
                continue
            msgid, p = pm
            t = datetime.fromisoformat(rec["payload"]["received_at"]).timestamp()
            if msgid == 0:
                custom_mode, _type, _ap, base_mode = struct.unpack("<IBBB", _pad(p, 9)[:7])
                if _type in (6, 18):  # skip GCS heartbeats
                    continue
                mode = COPTER_MODES.get(custom_mode, f"MODE_{custom_mode}")
                now_armed = bool(base_mode & 128)
                if now_armed and not armed:
                    current = Segment(start_s=t)
                if armed and not now_armed and current is not None:
                    current.end_s = t
                    segments.append(current)
                    current = None
                armed = now_armed
            elif msgid == 245:
                landed = _pad(p, 2)[1]
            elif msgid == 33 and current is not None:
                (tb, lat, lon, alt, rel, vx, vy, vz, hdg) = struct.unpack("<IiiiihhhH", _pad(p, 28))
                rel_m = rel / 1000.0
                flying = landed == 2 if landed in (1, 2, 3, 4) else rel_m > 0.5
                if landed in (3, 4):
                    flying = True
                current.max_rel = max(current.max_rel, rel_m)
                current.samples.append({
                    "t_s": t, "lat": lat / 1e7, "lng": lon / 1e7, "amsl": alt / 1000.0, "h": rel_m,
                    "vn": vx / 100.0, "ve": vy / 100.0, "vd": vz / 100.0,
                    "yaw": (hdg / 100.0) if hdg != 65535 else 0.0, "flying": flying, "mode": mode,
                })
    return segments


def write_track(seg: Segment, out: str, serial: str, geoid_n: float | None) -> None:
    s0 = seg.samples[0]
    takeoff_msl = s0["amsl"] - s0["h"]
    header = {
        "type": "header", "format": 1, "source": "ArduCopter SITL via UTM connector journal",
        "model": "Phantom 4 Pro V2.0 (replayed SITL track)", "serial": serial,
        "takeoff": {"lat": s0["lat"], "lng": s0["lng"]},
        "dji_takeoff_msl_m": round(takeoff_msl, 2),
        "device_gnss_ellipsoid_m": round(takeoff_msl + geoid_n, 2) if geoid_n is not None else None,
        "device_gnss_sigma_m": 3.0, "recorded_at_s": seg.start_s,
    }
    with open(out, "w") as f:
        f.write(json.dumps(header) + "\n")
        for s in seg.samples:
            f.write(json.dumps({
                "type": "state", "t": int(round((s["t_s"] - seg.start_s) * 1000)),
                "lat": round(s["lat"], 7), "lng": round(s["lng"], 7), "h": round(s["h"], 2),
                "vn": s["vn"], "ve": s["ve"], "vd": s["vd"], "yaw": round(s["yaw"], 1),
                "flying": s["flying"], "motors": True, "gps": 5, "sats": 12, "mode": s["mode"],
            }) + "\n")
    print(f"wrote {out}: {len(seg.samples)} states over {seg.end_s - seg.start_s:.0f} s")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("journal")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--segment", type=int)
    ap.add_argument("--out")
    ap.add_argument("--serial", default="SITLP4PV2-0001")
    ap.add_argument("--geoid-n", type=float)
    a = ap.parse_args()
    segs = [s for s in scan(a.journal) if s.samples]
    if a.list or a.segment is None:
        for i, s in enumerate(segs):
            dur = s.end_s - s.start_s
            modes = sorted({x["mode"] for x in s.samples})
            print(f"{i:3d}  {datetime.utcfromtimestamp(s.start_s):%Y-%m-%d %H:%M:%S}Z  {dur:6.0f} s  "
                  f"{len(s.samples):6d} pos  max {s.max_rel:5.1f} m  {','.join(modes)}")
        return
    if not a.out:
        sys.exit("--out is required with --segment")
    write_track(segs[a.segment], a.out, a.serial, a.geoid_n)


if __name__ == "__main__":
    main()
