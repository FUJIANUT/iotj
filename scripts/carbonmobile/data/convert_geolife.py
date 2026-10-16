#!/usr/bin/env python3
"""
Convert Microsoft GeoLife trajectories (~18,670 .plt files, 182 users)
into the CarbonMobile per-device CSV format expected by
TraceBasedMobilityModel.loadCsvTraces().

Output: device_<i>.csv with columns timestamp_sec_relative,x_m,y_m
where (x, y) are projected meters within a 50 km × 50 km city box
centered on Beijing ((39.9042, 116.4074)).

For each requested device, we:
  1) Pick a GeoLife user at random
  2) Concatenate their trajectories in chronological order
  3) Resample to one fix every 5 seconds (using nearest-time interpolation)
  4) Map (lat, lng) → city-grid (x, y), clamped to [0, 50000]
  5) Make timestamps relative to simulation start (0.0)

Usage:
    python3 convert_geolife.py <geolife_root> <output_dir> <num_devices> <sim_seconds>
"""

import csv
import math
import os
import random
import sys


# Beijing reference point (downtown)
LAT_REF = 39.9042
LNG_REF = 116.4074

# City extent (m)
CITY_M = 50_000

# Approximate meters per degree at Beijing's latitude
# (1 deg lat ≈ 111 km; 1 deg lng at 40°N ≈ 85 km)
M_PER_LAT = 111_000.0
M_PER_LNG = 111_000.0 * math.cos(math.radians(LAT_REF))


def latlng_to_xy(lat, lng):
    dy = (lat - LAT_REF) * M_PER_LAT
    dx = (lng - LNG_REF) * M_PER_LNG
    # center city at (CITY_M/2, CITY_M/2)
    x = max(0.0, min(CITY_M, CITY_M / 2 + dx))
    y = max(0.0, min(CITY_M, CITY_M / 2 + dy))
    return x, y


def parse_plt(path):
    """Yield (epoch_sec, lat, lng) tuples from one .plt file."""
    with open(path) as f:
        for _ in range(6):
            f.readline()  # skip header
        for line in f:
            parts = line.strip().split(",")
            if len(parts) < 7:
                continue
            try:
                lat = float(parts[0]); lng = float(parts[1])
                # date,time → epoch seconds
                from datetime import datetime, timezone
                dt = datetime.strptime(parts[5] + " " + parts[6], "%Y-%m-%d %H:%M:%S")
                yield dt.replace(tzinfo=timezone.utc).timestamp(), lat, lng
            except Exception:
                continue


def load_user(user_dir):
    """Load all of one user's trajectories sorted by time."""
    plt_files = []
    traj_dir = os.path.join(user_dir, "Trajectory")
    if not os.path.isdir(traj_dir):
        return []
    for fn in sorted(os.listdir(traj_dir)):
        if fn.endswith(".plt"):
            plt_files.append(os.path.join(traj_dir, fn))
    points = []
    for f in plt_files:
        for ts, lat, lng in parse_plt(f):
            points.append((ts, lat, lng))
    points.sort(key=lambda p: p[0])
    return points


def resample(points, sim_seconds, sample_period_s=5.0):
    """
    Make timestamps relative to the user's first fix and emit one sample
    every sample_period_s seconds, up to sim_seconds. Linear interpolation.
    """
    if not points:
        return []
    t0 = points[0][0]
    rel = [(t - t0, lat, lng) for t, lat, lng in points]
    out = []
    cur = 0.0
    i = 0
    while cur <= sim_seconds and i < len(rel) - 1:
        # advance i so rel[i].t <= cur < rel[i+1].t
        while i + 1 < len(rel) and rel[i + 1][0] < cur:
            i += 1
        if i + 1 >= len(rel):
            break
        t1, lat1, lng1 = rel[i]
        t2, lat2, lng2 = rel[i + 1]
        if t2 == t1:
            lat, lng = lat1, lng1
        else:
            f = (cur - t1) / (t2 - t1)
            f = max(0, min(1, f))
            lat = lat1 + f * (lat2 - lat1)
            lng = lng1 + f * (lng2 - lng1)
        x, y = latlng_to_xy(lat, lng)
        out.append((cur, x, y))
        cur += sample_period_s
    return out


def main():
    if len(sys.argv) < 5:
        print(__doc__)
        sys.exit(1)
    geolife_root = sys.argv[1]
    out_dir = sys.argv[2]
    num_devices = int(sys.argv[3])
    sim_seconds = float(sys.argv[4])

    os.makedirs(out_dir, exist_ok=True)
    data_root = os.path.join(geolife_root, "Data")
    users = sorted([d for d in os.listdir(data_root)
                    if os.path.isdir(os.path.join(data_root, d))])
    print(f"Found {len(users)} GeoLife users; sampling for {num_devices} devices.")
    rng = random.Random(123)
    rng.shuffle(users)

    written = 0
    # First pass: write one file per user that has enough data
    user_pts = []
    for user in users:
        pts = load_user(os.path.join(data_root, user))
        if len(pts) >= 60:
            user_pts.append(pts)
    n_real = len(user_pts)
    print(f"  {n_real} users have enough data.")
    for i in range(min(num_devices, n_real)):
        samples = resample(user_pts[i], sim_seconds)
        write_csv(os.path.join(out_dir, f"device_{written}.csv"), samples)
        written += 1
    # Second pass: if more devices needed, recycle users with random temporal
    # offsets so they look like distinct individuals.
    while written < num_devices:
        src = user_pts[written % n_real]
        # Shift the trajectory by a random offset so it looks like a different
        # but plausible commuter pattern.
        offset = rng.uniform(0, max(1, sim_seconds * 0.5))
        shifted = [(p[0] + offset, p[1], p[2]) for p in src]
        samples = resample(shifted, sim_seconds)
        write_csv(os.path.join(out_dir, f"device_{written}.csv"), samples)
        written += 1
        if written % 50 == 0:
            print(f"    …{written} devices written")
    print(f"Done — wrote {written} device CSVs to {out_dir}")


def write_csv(path, samples):
    with open(path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["timestamp", "x", "y"])
        for t, x, y in samples:
            w.writerow([f"{t:.1f}", f"{x:.1f}", f"{y:.1f}"])


if __name__ == "__main__":
    main()
