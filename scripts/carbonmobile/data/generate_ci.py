#!/usr/bin/env python3
"""
Generate deterministic candidate 30-day hourly carbon-intensity (CI)
profiles for eight power-grid regions. The parameters are design values
anchored to public grid summaries; the output is not an empirical replay
or a fitted time-series model.

The paper's archived inputs (data/carbon, not part of this repository)
are protected by SHA256SUMS; this script produces a statistically
equivalent but different realisation. It writes to data/carbon_generated
by default so a new profile cannot silently replace archived inputs.

Each region template captures four components observed in real CI traces:
  - regional baseline mean (gCO2eq/kWh)
  - diurnal cycle (renewable solar dip / evening demand peak)
  - weekly cycle (weekday/weekend differences)
  - episodic weather-front excursions (3-5 day high or low CI)
  - white observation noise

Output CSV: timestamp,gCO2eq_per_kWh
The file headers record the public provenance note for each region.
"""

import argparse
import csv
import math
import os
import random


REGIONS = {
    # (mean, daily_amp, peak_h, weekly_amp, noise_sd, source_note)
    "CAISO":        (260, 130, 12, 20, 25,
        "EIA Hourly Electric Grid Monitor / CAISO Today's Outlook (2024)"),
    "NYISO":        (320,  80, 18, 15, 30,
        "EIA Hourly Grid Monitor / NYISO public dashboard (2024)"),
    "ERCOT":        (400, 150,  3, 30, 50,
        "EIA Hourly Grid Monitor / ERCOT 2024 fuel mix"),
    "PJM":          (380,  60, 17, 10, 25,
        "EIA Hourly Grid Monitor / PJM 2024 mix"),
    "MISO":         (520,  70,  6, 20, 40,
        "EIA Hourly Grid Monitor / MISO 2024 mix"),
    "WYOMING":      (700,  30, 12, 10, 20,
        "EIA Hourly Grid Monitor / WAPA Wyoming subregion"),
    "HYDRO_QUEBEC": ( 35,  10, 12,  5,  5,
        "Hydro-Quebec sustainability reports (2023-24)"),
    "NORDIC":       (110,  25, 18, 10, 12,
        "ENTSO-E Nordic reference (Sweden+Norway, 2024)"),
}

REGION_SEEDS = {
    "CAISO": 1101,
    "NYISO": 1102,
    "ERCOT": 1103,
    "PJM": 1104,
    "MISO": 1105,
    "WYOMING": 1106,
    "HYDRO_QUEBEC": 1107,
    "NORDIC": 1108,
}

EPOCH_START = 1735689600   # 2025-01-01 00:00 UTC
DAYS = 30


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--out-dir",
        default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                             "carbon_generated"),
    )
    args = parser.parse_args()
    out_dir = args.out_dir
    os.makedirs(out_dir, exist_ok=True)

    # Generate weather-front events that hit ALL regions (correlated)
    # plus per-region events. Each event lasts 3-5 days with
    # sign chosen by realistic frequency: high-CI events (cold
    # snaps, solar drought) ~ 2x more common than low-CI events
    # (windy weeks, mild weather).
    rng_global = random.Random(42)
    n_global_events = 6
    global_events = []
    for _ in range(n_global_events):
        start_hour = rng_global.randrange(0, DAYS * 24 - 5*24)
        duration   = rng_global.randint(3*24, 5*24)
        sign       = +1 if rng_global.random() < 0.66 else -1
        magnitude  = rng_global.uniform(40, 100)  # gCO2/kWh shift
        global_events.append((start_hour, start_hour + duration, sign * magnitude))

    for region, (mean, amp, peak_h, w_amp, noise_sd, source) in REGIONS.items():
        path = os.path.join(out_dir, f"{region}.csv")
        # Per-region weather events
        rng = random.Random(REGION_SEEDS[region])
        local_events = []
        for _ in range(4):
            start_hour = rng.randrange(0, DAYS * 24 - 4*24)
            duration   = rng.randint(2*24, 4*24)
            sign       = +1 if rng.random() < 0.6 else -1
            mag        = rng.uniform(20, 60)
            local_events.append((start_hour, start_hour + duration, sign * mag))

        with open(path, "w", newline="") as f:
            f.write(f"# Source: {source}\n")
            f.write("# Synthetic 30-day hourly CI profile; not an empirical replay.\n")
            f.write(f"# Stable region seed {REGION_SEEDS[region]}.\n")
            f.write(f"# Diurnal amplitude {amp}, peak hour {peak_h}, "
                    f"weekly amplitude {w_amp}, observation noise sd {noise_sd}.\n")
            w = csv.writer(f)
            w.writerow(["timestamp", "gCO2eq_per_kWh"])
            for h in range(DAYS * 24):
                ts = EPOCH_START + h * 3600
                # Diurnal pattern (region-specific phase)
                phase_diurnal = 2 * math.pi * (h % 24 - peak_h) / 24
                if region == "CAISO":
                    diurnal = -amp * math.cos(phase_diurnal)  # solar valley
                elif region == "MISO":
                    diurnal = amp * math.sin(phase_diurnal)
                else:
                    diurnal = amp * math.sin(phase_diurnal)
                # Weekly cycle: weekday vs weekend
                day_of_week = (h // 24) % 7
                weekly = w_amp if day_of_week < 5 else -w_amp
                # Sunday extra dip (industry off, residential renewable share up)
                if day_of_week == 6:
                    weekly -= 0.5 * w_amp
                # Episodic weather-front excursions
                event = 0.0
                for s, e, m in global_events:
                    if s <= h < e:
                        # ramp up/down at edges to avoid step function
                        ramp = min(1.0, (h - s) / 12.0, (e - h) / 12.0)
                        event += m * ramp
                for s, e, m in local_events:
                    if s <= h < e:
                        ramp = min(1.0, (h - s) / 8.0, (e - h) / 8.0)
                        event += m * ramp
                # Heteroscedastic noise: more noise during transitions
                noise_scale = noise_sd * (1.0 + 0.3 * abs(math.cos(phase_diurnal)))
                noise = rng.gauss(0, noise_scale)
                ci = max(20.0, mean + diurnal + weekly + event + noise)
                w.writerow([ts, f"{ci:.1f}"])
        print(f"  wrote {path}  ({DAYS*24} samples; weather events {len(local_events)+n_global_events})")


if __name__ == "__main__":
    main()
