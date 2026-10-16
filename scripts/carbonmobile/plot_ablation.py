#!/usr/bin/env python3
"""
Generate the three ablation figures for paper Section 7:
  Fig 6: V-value sweep (Lyapunov tradeoff)
  Fig 7: CI forecast noise sweep (DRL robustness)
  Fig 8: Device-count scaling
"""

import csv
import os
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np


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


def save_both(fig, path):
    fig.savefig(path)
    fig.savefig(path.replace(".pdf", ".png"))
    plt.close(fig)


def fig_v_sweep(csv_path, out):
    vs, cfs, qs, sds = [], [], [], []
    with open(csv_path) as f:
        for r in csv.DictReader(f):
            vs.append(float(r["v"]))
            cfs.append(float(r["avg_cf"]))
            qs.append(float(r["avg_peak_q"]))
            sds.append(float(r["sd_cf"]))
    vs = np.array(vs); cfs = np.array(cfs); qs = np.array(qs); sds = np.array(sds)
    fig, ax = plt.subplots(figsize=(5.5, 3.2))
    ax.errorbar(vs, cfs, yerr=sds, marker="o", color="#1f77b4",
                linewidth=2, label="Avg CF / task (left)")
    ax.set_xscale("log")
    ax.set_xlabel("Lyapunov weight $V$ (log scale)")
    ax.set_ylabel("Avg CF / task (gCO$_2$eq)", color="#1f77b4")
    ax.tick_params(axis="y", labelcolor="#1f77b4")
    ax.grid(True, which="both", linestyle=":", alpha=0.4)
    # right axis: peak Q
    ax2 = ax.twinx()
    ax2.spines["top"].set_visible(False)
    ax2.plot(vs, qs, marker="s", color="#d62728", linewidth=2,
             linestyle="--", label="Peak queue $Q$ (right)")
    ax2.set_ylabel("Peak Lyapunov queue $Q$ (gCO$_2$eq)", color="#d62728")
    ax2.tick_params(axis="y", labelcolor="#d62728")
    ax.set_title("F1: Lyapunov $V$-vs-$Q$ trade-off (tight 15\\,gCO$_2$eq budget)")
    fig.tight_layout()
    save_both(fig, out)


def fig_noise_sweep(csv_path, out):
    sigmas, cfs, sds = [], [], []
    with open(csv_path) as f:
        for r in csv.DictReader(f):
            sigmas.append(float(r["sigma"]))
            cfs.append(float(r["avg_cf"]))
            sds.append(float(r["sd_cf"]))
    sigmas = np.array(sigmas); cfs = np.array(cfs); sds = np.array(sds)
    fig, ax = plt.subplots(figsize=(5.5, 3.2))
    ax.errorbar(sigmas, cfs, yerr=sds, marker="o", color="#1f77b4",
                linewidth=2, capsize=3, label="CarbonMobile (DRL)")
    # Reference lines for non-noise baselines (latest 5-app benchmark)
    refs = {"GreenScale [IoT-J'25]": 0.00294,
            "AutoScale (Energy)":    0.00178,
            "CarbonMobile (Greedy)": 0.00126}
    colors = {"GreenScale [IoT-J'25]": "#d62728",
              "AutoScale (Energy)":    "#ff7f0e",
              "CarbonMobile (Greedy)": "#a6cee3"}
    for name, val in refs.items():
        ax.axhline(val, linestyle="--", linewidth=1, color=colors[name], alpha=0.7)
        ax.text(sigmas[-1] * 1.02, val, f" {name}", va="center",
                fontsize=8, color=colors[name])
    ax.set_xlabel("CI forecast noise $\\sigma$ (gCO$_2$/kWh)")
    ax.set_ylabel("Avg CF / task (gCO$_2$eq)")
    ax.set_title("F2: DRL robustness to CI forecast noise")
    ax.set_ylim(0, max(refs.values()) * 1.25)
    ax.legend(loc="lower left", frameon=False)
    ax.grid(True, linestyle=":", alpha=0.4)
    fig.tight_layout()
    save_both(fig, out)


def fig_scaling_sweep(csv_path, out):
    by_policy = {}
    with open(csv_path) as f:
        for r in csv.DictReader(f):
            p = r["policy"]
            by_policy.setdefault(p, []).append(
                (int(r["n_devices"]), float(r["avg_cf"])))
    order = ["DRL", "CARBON_GREEDY", "GREENSCALE_QTABLE", "EDGE_GREEDY"]
    display = {"DRL": "CarbonMobile (DRL)",
               "CARBON_GREEDY": "CarbonMobile (Greedy)",
               "GREENSCALE_QTABLE": "GreenScale [IoT-J'25]",
               "EDGE_GREEDY": "Edge-Greedy"}
    colors = {"DRL": "#1f77b4",
              "CARBON_GREEDY": "#a6cee3",
              "GREENSCALE_QTABLE": "#d62728",
              "EDGE_GREEDY": "#9467bd"}
    fig, ax = plt.subplots(figsize=(5.5, 3.2))
    for p in order:
        if p not in by_policy: continue
        pts = sorted(by_policy[p])
        xs = [n for n, _ in pts]
        ys = [c for _, c in pts]
        ax.plot(xs, ys, marker="o", linewidth=2,
                color=colors[p], label=display[p])
    ax.set_xscale("log")
    ax.set_xlabel("Number of mobile users (log scale)")
    ax.set_ylabel("Avg CF / task (gCO$_2$eq)")
    ax.set_title("F3: Scaling: per-task CF stays flat as users grow")
    ax.set_xticks([50, 100, 200, 500])
    ax.get_xaxis().set_major_formatter(matplotlib.ticker.ScalarFormatter())
    ax.legend(loc="center right", frameon=False)
    ax.grid(True, which="both", linestyle=":", alpha=0.4)
    fig.tight_layout()
    save_both(fig, out)


def main():
    out_dir = "figures"
    Path(out_dir).mkdir(parents=True, exist_ok=True)
    abl_dir = "sim_results/carbonmobile_ablation"
    fig_v_sweep    (f"{abl_dir}/v_sweep.csv",       f"{out_dir}/fig6_v_sweep.pdf")
    fig_noise_sweep(f"{abl_dir}/noise_sweep.csv",   f"{out_dir}/fig7_noise_sweep.pdf")
    fig_scaling_sweep(f"{abl_dir}/scaling_sweep.csv", f"{out_dir}/fig8_scaling.pdf")
    print("Wrote ablation figures:")
    for f in sorted(os.listdir(out_dir)):
        if f.startswith(("fig6", "fig7", "fig8")):
            print(" ", f)


if __name__ == "__main__":
    main()
