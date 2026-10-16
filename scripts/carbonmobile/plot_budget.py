#!/usr/bin/env python3
"""
F7 ablation plot: budget-sweep behaviour of CarbonMobile-Lyap
under increasingly tight long-term carbon budgets.
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
    "legend.fontsize": 9,
    "figure.dpi":    150,
    "savefig.dpi":   150,
    "savefig.bbox":  "tight",
    "axes.spines.top":   False,
    "axes.spines.right": False,
})


def main():
    out_dir = "figures"
    Path(out_dir).mkdir(parents=True, exist_ok=True)
    csv_path = "sim_results/carbonmobile_ablation/budget_sweep.csv"

    bs, totals, peaks, viol = [], [], [], []
    with open(csv_path) as f:
        for r in csv.DictReader(f):
            bs.append(float(r["budget"]))
            totals.append(float(r["total_cf"]))
            peaks.append(float(r["peak_q"]))
            viol.append(float(r["budget_violation_ratio"]))
    bs = np.array(bs); totals = np.array(totals); peaks = np.array(peaks); viol = np.array(viol)

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(8.0, 3.0))
    # Left: cumulative CF vs budget, with violation region shaded
    ax1.plot(bs, totals, marker="o", linewidth=2, color="#1f77b4",
             label="Cumulative CF emitted")
    ax1.plot(bs, bs, linewidth=1, linestyle="--", color="black",
             alpha=0.6, label="Budget $B$ (target)")
    ax1.fill_between(bs, bs, totals, where=(totals > bs),
                      color="#d62728", alpha=0.18,
                      label="Budget violation")
    ax1.set_xlabel("Budget $B$ (gCO$_2$eq)")
    ax1.set_ylabel("Cumulative CF (gCO$_2$eq)")
    ax1.set_title("F7: Cumulative CF vs.\\ budget (4 h)")
    ax1.legend(loc="upper left", frameon=False)
    ax1.grid(True, linestyle=":", alpha=0.4)

    # Right: peak Q + violation ratio (twin axes)
    ax2.plot(bs, peaks, marker="s", linewidth=2, color="#1f77b4",
             label="Peak $Q$")
    ax2.set_xlabel("Budget $B$ (gCO$_2$eq)")
    ax2.set_ylabel("Peak Lyapunov queue $Q$ (gCO$_2$eq)", color="#1f77b4")
    ax2.tick_params(axis="y", labelcolor="#1f77b4")
    ax2.grid(True, linestyle=":", alpha=0.4)
    ax2b = ax2.twinx()
    ax2b.spines["top"].set_visible(False)
    ax2b.plot(bs, viol * 100, marker="^", linewidth=2, color="#d62728",
              linestyle="--", label="Budget overshoot \\%")
    ax2b.set_ylabel("Cumulative budget overshoot (\\%)", color="#d62728")
    ax2b.tick_params(axis="y", labelcolor="#d62728")
    ax2.set_title("F7: Queue and overshoot vs.\\ budget")

    fig.tight_layout()
    fig.savefig(f"{out_dir}/fig10_budget_sweep.pdf")
    fig.savefig(f"{out_dir}/fig10_budget_sweep.png")
    plt.close(fig)
    print(f"wrote {out_dir}/fig10_budget_sweep.pdf")


if __name__ == "__main__":
    main()
