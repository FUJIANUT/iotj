#!/usr/bin/env python3
"""
CarbonMobile - Result analysis script.

Reads all *_TASKS.csv files in a results directory, joins them by
policy, and prints a comparison table. Computes:
  - total CF (gCO2eq)
  - avg CF / task
  - p50/p95 latency
  - operational vs embodied CF breakdown
  - target tier distribution (mobile/edge/cloud %)

Usage:
    python3 scripts/carbonmobile/analyze_results.py sim_results/carbonmobile/ite1
"""

import csv
import os
import re
import statistics
import sys


def load_tasks(csv_path):
    rows = []
    with open(csv_path) as f:
        for row in csv.DictReader(f):
            rows.append({
                "task_id": int(row["task_id"]),
                "device_id": int(row["device_id"]),
                "tier": int(row["target_tier"]),
                "node": int(row["target_node"]),
                "op_comp_cf": float(row["op_comp_cf"]),
                "op_comm_cf": float(row["op_comm_cf"]),
                "embodied_cf": float(row["embodied_cf"]),
                "migration_cf": float(row["migration_cf"]),
                "total_cf": float(row["total_cf"]),
                "latency_s": float(row["latency_s"]),
                "Q": float(row["Q"]),
            })
    return rows


def policy_from_filename(name):
    m = re.match(r"CARBON_[A-Z_]+?_([A-Z_]+)_\d+DEV_TASKS\.csv$", name)
    if not m:
        return None
    # Strip the simScenario prefix if needed
    pol = m.group(1)
    return pol


def summarize(policy, rows):
    if not rows:
        return None
    n = len(rows)
    total_cf = sum(r["total_cf"] for r in rows)
    avg_cf = total_cf / n
    op_comp = sum(r["op_comp_cf"] for r in rows)
    op_comm = sum(r["op_comm_cf"] for r in rows)
    emb = sum(r["embodied_cf"] for r in rows)
    mig = sum(r["migration_cf"] for r in rows)
    lat_sorted = sorted(r["latency_s"] for r in rows)
    p50 = lat_sorted[n // 2]
    p95 = lat_sorted[min(n - 1, int(0.95 * n))]
    tiers = [r["tier"] for r in rows]
    pct_mobile = 100 * sum(1 for t in tiers if t == 0) / n
    pct_edge = 100 * sum(1 for t in tiers if t == 1) / n
    pct_cloud = 100 * sum(1 for t in tiers if t == 2) / n
    return {
        "policy": policy,
        "n": n,
        "total_cf": total_cf,
        "avg_cf": avg_cf,
        "op_comp": op_comp,
        "op_comm": op_comm,
        "embodied": emb,
        "migration": mig,
        "p50_lat": p50,
        "p95_lat": p95,
        "pct_mobile": pct_mobile,
        "pct_edge": pct_edge,
        "pct_cloud": pct_cloud,
    }


def main(results_dir):
    summaries = {}
    for fn in sorted(os.listdir(results_dir)):
        if not fn.endswith("_TASKS.csv"):
            continue
        rows = load_tasks(os.path.join(results_dir, fn))
        # Extract policy: filename is CARBON_<scenario>_<policy>_<n>DEV_TASKS.csv
        # We split off the trailing _<n>DEV_TASKS.csv and take everything after the
        # first "CARBON_" + scenario.
        stem = fn.replace("_TASKS.csv", "")
        # known scenarios:
        scen = "TWO_TIER_WITH_EO"
        if stem.startswith(f"CARBON_{scen}_"):
            tail = stem[len(f"CARBON_{scen}_"):]
        else:
            tail = stem
        # Strip the trailing _<n>DEV
        m = re.match(r"(.+)_(\d+)DEV$", tail)
        policy = m.group(1) if m else tail
        s = summarize(policy, rows)
        if s:
            summaries[policy] = s

    if not summaries:
        print(f"No *_TASKS.csv files found under {results_dir}")
        return

    # Sort by avg_cf ascending (best at top)
    ordered = sorted(summaries.values(), key=lambda d: d["avg_cf"])
    cols = [
        ("Policy", 22, "policy", "{:<22s}"),
        ("Tasks", 7, "n", "{:>7d}"),
        ("TotalCF", 9, "total_cf", "{:>9.4f}"),
        ("AvgCF", 9, "avg_cf", "{:>9.5f}"),
        ("Comp%", 7, None, "{:>7.1f}"),
        ("Comm%", 7, None, "{:>7.1f}"),
        ("Emb%", 7, None, "{:>7.1f}"),
        ("p50Lat", 8, "p50_lat", "{:>8.3f}"),
        ("p95Lat", 8, "p95_lat", "{:>8.3f}"),
        ("Edge%", 7, "pct_edge", "{:>7.1f}"),
        ("Cloud%", 8, "pct_cloud", "{:>8.1f}"),
    ]
    header = " | ".join(("{:<%ds}" % w).format(name) for name, w, _, _ in cols)
    print(header)
    print("-" * len(header))
    for s in ordered:
        if s["total_cf"] > 0:
            comp_pct = 100 * s["op_comp"] / s["total_cf"]
            comm_pct = 100 * s["op_comm"] / s["total_cf"]
            emb_pct = 100 * s["embodied"] / s["total_cf"]
        else:
            comp_pct = comm_pct = emb_pct = 0
        row = []
        for name, w, key, fmt in cols:
            if key is None:
                if name == "Comp%": v = comp_pct
                elif name == "Comm%": v = comm_pct
                elif name == "Emb%": v = emb_pct
                else: v = 0
            else:
                v = s[key]
            row.append(fmt.format(v))
        print(" | ".join(row))

    # Compare against ours
    if "CARBON_GREEDY" in summaries:
        ours = summaries["CARBON_GREEDY"]
        print("\n--- Reductions vs CARBON_GREEDY (ours, greedy variant) ---")
        for s in ordered:
            if s["policy"] == "CARBON_GREEDY":
                continue
            delta = (s["avg_cf"] - ours["avg_cf"]) / ours["avg_cf"] * 100
            sign = "+" if delta > 0 else ""
            print(f"  {s['policy']:<22s}  avgCF {sign}{delta:6.1f}%")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "sim_results/carbonmobile/ite1")
