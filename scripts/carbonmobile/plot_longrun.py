#!/usr/bin/env python3
"""
F5 ablation plot: Lyapunov queue Q(t) tracking over a long-horizon
(4-hour) simulation.  Demonstrates that the queue stabilises near
zero (DRL) or modestly above the budget rate (LYAPUNOV) without
diverging, even as multiple CI diurnal cycles pass through the
operator's pinned carbon budget.
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


def load_qt(csv_path):
    """Return (start_s_array, Q_array, cumulative_cf_array)."""
    ts, qs, cfs = [], [], []
    with open(csv_path) as f:
        for r in csv.DictReader(f):
            ts.append(float(r["start_s"]))
            qs.append(float(r["Q"]))
            cfs.append(float(r["total_cf"]))
    ts = np.array(ts); qs = np.array(qs)
    cfs = np.cumsum(cfs)
    # Subsample to keep the plot crisp
    if len(ts) > 5000:
        idx = np.linspace(0, len(ts) - 1, 5000).astype(int)
        ts = ts[idx]; qs = qs[idx]; cfs = cfs[idx]
    return ts, qs, cfs


def main():
    out_dir = "figures"
    Path(out_dir).mkdir(parents=True, exist_ok=True)
    base = "sim_results/carbonmobile_longrun"
    files = {
        "LYAPUNOV": f"{base}/CARBON_TWO_TIER_WITH_EO_LYAPUNOV_100DEV_TASKS.csv",
        "DRL":      f"{base}/CARBON_TWO_TIER_WITH_EO_DRL_100DEV_TASKS.csv",
    }
    budget = 250  # gCO2eq, per longrun_props.properties

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(8.0, 3.0))
    colours = {"LYAPUNOV": "#1f77b4", "DRL": "#d62728"}
    labels  = {"LYAPUNOV": "CarbonMobile-Lyap", "DRL": "CarbonMobile-DRL"}
    for pol, path in files.items():
        if not os.path.exists(path):
            print(f"missing {path}")
            continue
        ts, qs, cumcf = load_qt(path)
        hours = ts / 3600.0
        ax1.plot(hours, qs, color=colours[pol], linewidth=1.5, label=labels[pol])
        ax2.plot(hours, cumcf, color=colours[pol], linewidth=1.5, label=labels[pol])
    # Budget reference
    ax2.axhline(budget, linestyle="--", color="black", linewidth=1, alpha=0.6)
    ax2.text(0.05, budget * 1.02, f" budget = {budget}\\,gCO$_2$eq",
             fontsize=8, color="#555555")

    ax1.set_xlabel("Simulation time (hours)")
    ax1.set_ylabel("Lyapunov queue $Q(t)$ (gCO$_2$eq)")
    ax1.set_title("F5: Queue dynamics over 4 h")
    ax1.legend(loc="upper left", frameon=False)
    ax1.grid(True, linestyle=":", alpha=0.4)

    ax2.set_xlabel("Simulation time (hours)")
    ax2.set_ylabel("Cumulative CF (gCO$_2$eq)")
    ax2.set_title("F5: Cumulative carbon vs.\\ budget")
    ax2.legend(loc="upper left", frameon=False)
    ax2.grid(True, linestyle=":", alpha=0.4)

    fig.tight_layout()
    fig.savefig(f"{out_dir}/fig9_longrun.pdf")
    fig.savefig(f"{out_dir}/fig9_longrun.png")
    plt.close(fig)
    print(f"wrote {out_dir}/fig9_longrun.pdf")


if __name__ == "__main__":
    main()
