#!/usr/bin/env python3
"""
CarbonMobile - Paper-ready figure generator.

Reads:
  sim_results/carbonmobile_train/training_curve.csv    (PPO learning curve)
  sim_results/carbonmobile/ite1/*_TASKS.csv            (per-policy per-task logs)

Writes 5 PDF + PNG figures into figures/:
  fig1_training_curve.pdf
  fig2_baseline_comparison.pdf
  fig3_cf_composition.pdf
  fig4_routing_distribution.pdf
  fig5_latency_boxplot.pdf
"""

import csv
import os
import re
import sys
from pathlib import Path
from typing import Dict, List

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np


# ---------- styling ----------

plt.rcParams.update({
    "font.family":   "DejaVu Sans",
    "font.size":     10,
    "axes.titlesize": 11,
    "axes.labelsize": 10,
    "xtick.labelsize": 9,
    "ytick.labelsize": 9,
    "legend.fontsize": 9,
    "figure.dpi":    150,
    "savefig.dpi":   150,
    "savefig.bbox":  "tight",
    "axes.spines.top":   False,
    "axes.spines.right": False,
})

# Friendly display names + ordering
DISPLAY = {
    "DRL":                  "CarbonMobile (DRL)",
    "LYAPUNOV":             "CarbonMobile (Lyap)",
    "CARBON_GREEDY":        "CarbonMobile (Greedy)",
    "AUTOSCALE_ENERGY":     "AutoScale (Energy)",
    "CLOUD_ONLY":           "Cloud-Only (DC-Best)",
    "GREENSCALE_MULTIEDGE": "GreenScale-MultiEdge",
    "GREENSCALE_QTABLE":    "GreenScale [IoT-J'25]",
    "RANDOM":               "Random",
    "EDGE_GREEDY":          "Edge-Greedy",
}
ORDER = list(DISPLAY.keys())

# Colors: ours = strong blue tones, baselines = muted
COLORS = {
    "DRL":                  "#1f77b4",
    "LYAPUNOV":             "#5ba3d8",
    "CARBON_GREEDY":        "#a6cee3",
    "AUTOSCALE_ENERGY":     "#ff7f0e",
    "CLOUD_ONLY":           "#2ca02c",
    "GREENSCALE_MULTIEDGE": "#e08080",   # ablation: lighter red than full
    "GREENSCALE_QTABLE":    "#d62728",   # primary competitor, red
    "RANDOM":               "#7f7f7f",
    "EDGE_GREEDY":          "#9467bd",
}


# ---------- data loading ----------

def load_tasks(csv_path: str) -> List[dict]:
    rows = []
    with open(csv_path) as f:
        for r in csv.DictReader(f):
            rows.append({
                "tier":         int(r["target_tier"]),
                "op_comp_cf":   float(r["op_comp_cf"]),
                "op_comm_cf":   float(r["op_comm_cf"]),
                "embodied_cf":  float(r["embodied_cf"]),
                "total_cf":     float(r["total_cf"]),
                "latency_s":    float(r["latency_s"]),
            })
    return rows


def policy_from_filename(fn: str) -> str:
    # CARBON_<scenario>_<policy>_<n>DEV_TASKS.csv
    m = re.match(r"CARBON_TWO_TIER_WITH_EO_(.+)_\d+DEV_TASKS\.csv$", fn)
    return m.group(1) if m else fn


def load_all_policies(results_dir: str) -> Dict[str, List[dict]]:
    out = {}
    for fn in sorted(os.listdir(results_dir)):
        if not fn.endswith("_TASKS.csv"):
            continue
        out[policy_from_filename(fn)] = load_tasks(os.path.join(results_dir, fn))
    return out


# ---------- figures ----------

def fig_training_curve(curve_csv: str, out_path: str):
    eps, cfs = [], []
    with open(curve_csv) as f:
        for r in csv.DictReader(f):
            eps.append(int(r["episode"]))
            cfs.append(float(r["avg_cf"]))
    fig, ax = plt.subplots(figsize=(5.5, 3.2))
    ax.plot(eps, cfs, marker="o", linewidth=2, color="#1f77b4", label="DRL training")
    # Reference lines for the strongest baselines (latest 5-app benchmark)
    refs = {"GreenScale [IoT-J'25]": 0.00294,
            "AutoScale (Energy)":    0.00178,
            "CarbonMobile (Greedy)": 0.00126}
    for name, val in refs.items():
        ax.axhline(val, linestyle="--", linewidth=1, alpha=0.6,
                   color=COLORS["GREENSCALE_QTABLE"] if "GreenScale" in name
                         else COLORS["AUTOSCALE_ENERGY"] if "AutoScale" in name
                         else COLORS["CARBON_GREEDY"])
        ax.text(eps[-1] + 0.1, val, f" {name}", va="center", fontsize=8,
                color="#555555")
    ax.set_xlabel("Training episode")
    ax.set_ylabel("Average CF per task (gCO$_2$eq)")
    ax.set_title("PPO training convergence")
    ax.set_xticks(eps)
    ax.set_ylim(0, max(cfs) * 1.15)
    ax.legend(loc="upper right", frameon=False)
    fig.tight_layout()
    save_both(fig, out_path)


def fig_baseline_comparison(by_policy: Dict[str, List[dict]], out_path: str):
    avg_cf = {}
    for pol, rows in by_policy.items():
        if not rows:
            continue
        avg_cf[pol] = sum(r["total_cf"] for r in rows) / len(rows)
    sorted_pols = sorted(avg_cf, key=lambda p: avg_cf[p])
    labels = [DISPLAY.get(p, p) for p in sorted_pols]
    values = [avg_cf[p] for p in sorted_pols]
    colors = [COLORS.get(p, "#aaaaaa") for p in sorted_pols]

    fig, ax = plt.subplots(figsize=(7, 3.5))
    bars = ax.bar(range(len(labels)), values, color=colors, edgecolor="white")
    ax.set_xticks(range(len(labels)))
    ax.set_xticklabels(labels, rotation=20, ha="right")
    ax.set_ylabel("Average CF per task (gCO$_2$eq)")
    ax.set_title("Carbon footprint comparison (100 GeoLife users, 30-min sim)")
    # Annotate values + reduction vs GreenScale
    if "GREENSCALE_QTABLE" in avg_cf:
        gs = avg_cf["GREENSCALE_QTABLE"]
        for i, (p, v) in enumerate(zip(sorted_pols, values)):
            delta = (v - gs) / gs * 100
            tag = f"{v:.4f}\n({delta:+.0f}%)"
            ax.text(i, v + max(values) * 0.01, tag,
                    ha="center", va="bottom", fontsize=8)
    ax.set_ylim(0, max(values) * 1.20)
    fig.tight_layout()
    save_both(fig, out_path)


def fig_cf_composition(by_policy: Dict[str, List[dict]], out_path: str):
    pols = [p for p in ORDER if p in by_policy]
    labels = [DISPLAY[p] for p in pols]
    op_comp, op_comm, emb = [], [], []
    for p in pols:
        rows = by_policy[p]
        n = max(len(rows), 1)
        op_comp.append(sum(r["op_comp_cf"] for r in rows) / n)
        op_comm.append(sum(r["op_comm_cf"] for r in rows) / n)
        emb.append(sum(r["embodied_cf"] for r in rows) / n)

    x = np.arange(len(labels))
    fig, ax = plt.subplots(figsize=(7, 3.5))
    ax.bar(x, op_comp, label="Op. (compute)", color="#1f77b4")
    ax.bar(x, op_comm, bottom=op_comp, label="Op. (communicate)", color="#ff7f0e")
    ax.bar(x, emb,
           bottom=[a + b for a, b in zip(op_comp, op_comm)],
           label="Embodied", color="#2ca02c")
    ax.set_xticks(x)
    ax.set_xticklabels(labels, rotation=20, ha="right")
    ax.set_ylabel("Average CF per task (gCO$_2$eq)")
    ax.set_title("Carbon footprint composition by policy")
    ax.legend(loc="upper right", frameon=False)
    fig.tight_layout()
    save_both(fig, out_path)


def fig_routing_distribution(by_policy: Dict[str, List[dict]], out_path: str):
    pols = [p for p in ORDER if p in by_policy]
    labels = [DISPLAY[p] for p in pols]
    edge_pct, cloud_pct = [], []
    for p in pols:
        rows = by_policy[p]
        n = max(len(rows), 1)
        edge_pct.append(100 * sum(1 for r in rows if r["tier"] == 1) / n)
        cloud_pct.append(100 * sum(1 for r in rows if r["tier"] == 2) / n)

    x = np.arange(len(labels))
    fig, ax = plt.subplots(figsize=(7, 3.5))
    ax.bar(x, edge_pct, label="Edge", color="#1f77b4")
    ax.bar(x, cloud_pct, bottom=edge_pct, label="Cloud", color="#ff7f0e")
    ax.set_xticks(x)
    ax.set_xticklabels(labels, rotation=20, ha="right")
    ax.set_ylabel("Routing share (%)")
    ax.set_ylim(0, 105)
    ax.set_title("Where each policy sends tasks")
    ax.legend(loc="upper right", frameon=False)
    fig.tight_layout()
    save_both(fig, out_path)


def fig_latency_box(by_policy: Dict[str, List[dict]], out_path: str):
    # Sort policies by median latency for readability
    pols = [p for p in ORDER if p in by_policy]
    data = [[r["latency_s"] for r in by_policy[p]] for p in pols]
    labels = [DISPLAY[p] for p in pols]
    colors = [COLORS.get(p, "#aaaaaa") for p in pols]

    fig, ax = plt.subplots(figsize=(7, 3.5))
    bp = ax.boxplot(data, patch_artist=True, showfliers=False, widths=0.6,
                    medianprops={"color": "black"})
    for patch, c in zip(bp["boxes"], colors):
        patch.set_facecolor(c); patch.set_alpha(0.85)
    ax.set_xticks(range(1, len(labels) + 1))
    ax.set_xticklabels(labels, rotation=20, ha="right")
    ax.set_ylabel("Per-task latency (s)")
    ax.set_title("Latency distribution by policy")
    fig.tight_layout()
    save_both(fig, out_path)


def save_both(fig, out_path_pdf):
    fig.savefig(out_path_pdf)
    fig.savefig(out_path_pdf.replace(".pdf", ".png"))
    plt.close(fig)


# ---------- main ----------

def main():
    # Prefer the most recent training-curve file
    candidates = [
        "sim_results/carbonmobile_train/training_curve_v2.csv",
        "sim_results/carbonmobile_train/training_curve.csv",
    ]
    train_csv = next((p for p in candidates if os.path.exists(p)), candidates[0])
    bench_dir = sys.argv[1] if len(sys.argv) > 1 else "sim_results/carbonmobile/ite1"
    out_dir = "figures"
    Path(out_dir).mkdir(parents=True, exist_ok=True)

    print("Loading benchmark results from", bench_dir)
    by_policy = load_all_policies(bench_dir)
    print("Found policies:", list(by_policy.keys()))

    print("Generating figures into", out_dir)
    fig_training_curve(train_csv, f"{out_dir}/fig1_training_curve.pdf")
    fig_baseline_comparison(by_policy, f"{out_dir}/fig2_baseline_comparison.pdf")
    fig_cf_composition(by_policy, f"{out_dir}/fig3_cf_composition.pdf")
    fig_routing_distribution(by_policy, f"{out_dir}/fig4_routing_distribution.pdf")
    fig_latency_box(by_policy, f"{out_dir}/fig5_latency_boxplot.pdf")
    print("Done. Files:")
    for f in sorted(os.listdir(out_dir)):
        print(" ", f)


if __name__ == "__main__":
    main()
