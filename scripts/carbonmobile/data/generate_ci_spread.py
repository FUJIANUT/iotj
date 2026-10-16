#!/usr/bin/env python3
"""
Generate 8 CI traces whose pair-wise spread is bounded by a
configurable maximum (in gCO2/kWh). Used for the CI-spread
sensitivity sweep (C3).

Each region's mean is sampled symmetrically around 400 gCO2/kWh
within +/- spread/2; diurnal and weekly patterns are kept identical
to the default generator so that only the spread changes between
sensitivity points.
"""

import argparse
import csv
import math
import os
import random


REGIONS = ["R1", "R2", "R3", "R4", "R5", "R6", "R7", "R8"]
EPOCH_START = 1735689600
DAYS = 30


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--spread", type=float, required=True,
                   help="Pair-wise CI spread in gCO2/kWh "
                        "(max(mean) - min(mean) across regions).")
    p.add_argument("--out_dir", required=True)
    args = p.parse_args()

    os.makedirs(args.out_dir, exist_ok=True)
    rng = random.Random(7)
    # Distribute means uniformly across [400 - spread/2, 400 + spread/2]
    means = [400 - args.spread / 2 + i * args.spread / max(1, len(REGIONS) - 1)
             for i in range(len(REGIONS))]
    rng.shuffle(means)

    diurnal_amp  = 80   # constant across regions for clean comparison
    weekly_amp   = 15
    noise_sd     = 25

    # global weather events (same for all regions, scaled)
    n_global = 4
    global_events = []
    for _ in range(n_global):
        start_hour = rng.randrange(0, DAYS * 24 - 4 * 24)
        duration   = rng.randint(2 * 24, 4 * 24)
        sign       = +1 if rng.random() < 0.66 else -1
        magnitude  = rng.uniform(20, 60)
        global_events.append((start_hour, start_hour + duration, sign * magnitude))

    for region, mean in zip(REGIONS, means):
        path = os.path.join(args.out_dir, f"{region}.csv")
        with open(path, "w", newline="") as f:
            f.write(f"# Synthetic CI trace for spread-sensitivity sweep (spread={args.spread:.0f} gCO2/kWh)\n")
            f.write(f"# Region {region}, mean = {mean:.1f}\n")
            w = csv.writer(f)
            w.writerow(["timestamp", "gCO2eq_per_kWh"])
            for h in range(DAYS * 24):
                ts = EPOCH_START + h * 3600
                phase = 2 * math.pi * (h % 24 - 12) / 24
                diurnal = diurnal_amp * math.sin(phase)
                weekly = weekly_amp if (h // 24) % 7 < 5 else -weekly_amp
                event = 0
                for s, e, m in global_events:
                    if s <= h < e:
                        event += m * min(1, (h - s) / 12, (e - h) / 12)
                noise = rng.gauss(0, noise_sd)
                ci = max(20, mean + diurnal + weekly + event + noise)
                w.writerow([ts, f"{ci:.1f}"])
    print(f"  wrote 8 region traces to {args.out_dir} (spread={args.spread:.0f})")


if __name__ == "__main__":
    main()
