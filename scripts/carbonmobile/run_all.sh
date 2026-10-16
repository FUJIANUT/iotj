#!/usr/bin/env bash
# Master reproducibility script for CarbonMobile (IEEE IoT-J).
#
# Re-runs every experiment of the paper and regenerates the figures,
# from the project root, in one bash invocation (~30-60 min on a laptop).
#
# Pre-requisites:
#   - Java 17+ (tested with OpenJDK 21)
#   - Python 3.10+ with numpy, torch, matplotlib
#   - The public GeoLife Trajectories 1.3 release unpacked under
#       scripts/carbonmobile/data/geolife_raw/Geolife Trajectories 1.3/
#     (see README.md, section "Mobility traces")
#
# Usage (from the project root):
#   bash scripts/carbonmobile/run_all.sh            # reuse an existing PPO checkpoint, if any
#   RETRAIN=1 bash scripts/carbonmobile/run_all.sh  # retrain PPO from scratch first
set -e

if [ -z "${JAVA_HOME:-}" ]; then
    for c in /opt/homebrew/opt/openjdk@21 /usr/local/opt/openjdk@21 /usr/lib/jvm/java-21-openjdk-amd64; do
        if [ -d "$c" ]; then export JAVA_HOME="$c"; break; fi
    done
fi
if [ -n "${JAVA_HOME:-}" ]; then export PATH="${JAVA_HOME}/bin:${PATH}"; fi

PROJECT_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$PROJECT_ROOT"
LOG_DIR=sim_results/logs
mkdir -p "$LOG_DIR" figures

step() { printf '\n========== %s ==========\n' "$1"; }

step "1/10 Compile"
mkdir -p bin
javac -nowarn -d bin -cp "lib/*" $(find src -name "*.java")

step "2/10 Carbon-intensity inputs"
if ! ls scripts/carbonmobile/data/carbon/*.csv >/dev/null 2>&1; then
    echo "No CI traces in scripts/carbonmobile/data/carbon/ (they are not part of"
    echo "this repository). Either place the archived traces there, or generate a"
    echo "statistically equivalent set with:"
    echo "  python3 scripts/carbonmobile/data/generate_ci.py --out-dir scripts/carbonmobile/data/carbon"
    exit 1
fi
if [ -f scripts/carbonmobile/data/carbon/SHA256SUMS ]; then
    shasum -a 256 -c scripts/carbonmobile/data/carbon/SHA256SUMS
fi

step "3/10 Convert GeoLife to per-device CSVs (30-min and 4-h)"
GEOLIFE="scripts/carbonmobile/data/geolife_raw/Geolife Trajectories 1.3"
if [ ! -d "$GEOLIFE/Data" ]; then
    echo "GeoLife not found at '$GEOLIFE'. Download it from"
    echo "  https://www.microsoft.com/en-us/download/details.aspx?id=52367"
    echo "and unpack it there (see README.md)."
    exit 1
fi
python3 scripts/carbonmobile/data/convert_geolife.py "$GEOLIFE" \
    scripts/carbonmobile/data/mobility 500 1800
python3 scripts/carbonmobile/data/convert_geolife.py "$GEOLIFE" \
    scripts/carbonmobile/data/mobility_4h 100 14400
if [ -f scripts/carbonmobile/data/MOBILITY_SHA256SUMS ]; then
    shasum -a 256 -c scripts/carbonmobile/data/MOBILITY_SHA256SUMS --quiet \
        && echo "All 600 mobility traces match the traces used in the paper."
fi

step "4/10 PPO policy"
if [ ! -f scripts/carbonmobile/drl/checkpoints/ppo.pt ] && [ "${RETRAIN:-0}" != "1" ]; then
    echo "No trained checkpoint found; training one from scratch."
    RETRAIN=1
fi
if [ "${RETRAIN:-0}" = "1" ]; then
    mkdir -p scripts/carbonmobile/drl/checkpoints
    CKPT=scripts/carbonmobile/drl/checkpoints/ppo.pt
    if [ -f "$CKPT" ]; then mv "$CKPT" "${CKPT%.pt}_previous.pt"; fi
    PYTHONUNBUFFERED=1 python3 -u \
        scripts/carbonmobile/drl/carbon_drl_server.py > "$LOG_DIR/drl_train.log" 2>&1 &
    TRAIN_PID=$!
    sleep 2
    bash scripts/carbonmobile/run_drl_training.sh 12 \
         sim_results/carbonmobile_train/training_curve_v2.csv
    kill $TRAIN_PID 2>/dev/null || true
else
    echo "Using the existing checkpoint scripts/carbonmobile/drl/checkpoints/ppo.pt"
    echo "(set RETRAIN=1 to retrain from scratch)."
fi

step "5/10 Start the DRL server in evaluation mode"
sleep 1
PYTHONUNBUFFERED=1 nohup python3 -u \
    scripts/carbonmobile/drl/carbon_drl_server.py --eval > "$LOG_DIR/drl_eval.log" 2>&1 &
EVAL_PID=$!
trap 'kill $EVAL_PID 2>/dev/null || true' EXIT
sleep 2

step "6/10 Main 30-min benchmark (9 policies)"
rm -rf sim_results/carbonmobile/ite1
mkdir -p sim_results/carbonmobile/ite1
java -cp "bin:lib/*" -Xmx2g \
    edu.boun.edgecloudsim.applications.carbonmobile.MainApp
python3 scripts/carbonmobile/analyze_results.py sim_results/carbonmobile/ite1

step "7/10 Ablation sweeps (F1, F2, F3, F6, F8)"
bash scripts/carbonmobile/run_v_sweep.sh
bash scripts/carbonmobile/run_noise_sweep.sh
bash scripts/carbonmobile/run_scaling_sweep.sh
bash scripts/carbonmobile/run_mobility_ablation.sh
bash scripts/carbonmobile/run_spread_sweep.sh

step "8/10 Strict-QoS / high-load stress test (F9)"
rm -rf sim_results/carbonmobile_strict
mkdir -p sim_results/carbonmobile_strict
java -cp "bin:lib/*" -Xmx3g \
    edu.boun.edgecloudsim.applications.carbonmobile.MainApp \
    scripts/carbonmobile/config/strict_qos_config.properties \
    scripts/carbonmobile/config/edge_devices.xml \
    scripts/carbonmobile/config/applications_strict.xml \
    scripts/carbonmobile/config/carbonmobile.properties \
    sim_results/carbonmobile_strict 1

step "9/10 Long-horizon F5 + budget sweep F7"
rm -rf sim_results/carbonmobile_longrun
mkdir -p sim_results/carbonmobile_longrun
java -cp "bin:lib/*" -Xmx4g \
    edu.boun.edgecloudsim.applications.carbonmobile.MainApp \
    scripts/carbonmobile/config/longrun_config.properties \
    scripts/carbonmobile/config/edge_devices.xml \
    scripts/carbonmobile/config/applications.xml \
    scripts/carbonmobile/config/longrun_props.properties \
    sim_results/carbonmobile_longrun 1
bash scripts/carbonmobile/run_budget_sweep.sh

step "10/10 Generate all 10 figures into figures/"
python3 scripts/carbonmobile/plot_results.py
python3 scripts/carbonmobile/plot_ablation.py
python3 scripts/carbonmobile/plot_longrun.py
python3 scripts/carbonmobile/plot_budget.py

echo
echo "ALL DONE. Results are in sim_results/, figures in figures/."
