#!/usr/bin/env bash
# F3 ablation: scaling — run the headline policies (DRL, CARBON_GREEDY,
# GREENSCALE_QTABLE, EDGE_GREEDY) at several device counts and measure
# how the average per-task CF holds up. Demonstrates that CarbonMobile's
# advantage scales.
#
# Requires the Python DRL server already running in --eval mode.
set -e
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
export PATH=$JAVA_HOME/bin:$PATH

DEVICE_COUNTS="${1:-50 100 200 500}"
PROJECT_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$PROJECT_ROOT"

CONFIG_TMP=scripts/carbonmobile/config/_sweep_scale_config.properties

OUT_CSV=sim_results/carbonmobile_ablation/scaling_sweep.csv
mkdir -p sim_results/carbonmobile_ablation
echo "n_devices,policy,tasks,total_cf,avg_cf,p50_lat" > "$OUT_CSV"

for N in $DEVICE_COUNTS; do
    cp scripts/carbonmobile/config/default_config.properties "$CONFIG_TMP"
    sed -i.bak "s/^min_number_of_mobile_devices=.*/min_number_of_mobile_devices=$N/" "$CONFIG_TMP"
    sed -i.bak "s/^max_number_of_mobile_devices=.*/max_number_of_mobile_devices=$N/" "$CONFIG_TMP"
    sed -i.bak "s/^orchestrator_policies=.*/orchestrator_policies=DRL,CARBON_GREEDY,GREENSCALE_QTABLE,EDGE_GREEDY/" "$CONFIG_TMP"
    rm -f "${CONFIG_TMP}.bak"

    DIR=sim_results/carbonmobile_ablation/scale_${N}
    rm -rf "$DIR" && mkdir -p "$DIR"
    echo "=== n_devices = $N ==="
    java -cp "bin:lib/*" -Xmx2g \
         edu.boun.edgecloudsim.applications.carbonmobile.MainApp \
         "$CONFIG_TMP" \
         scripts/carbonmobile/config/edge_devices.xml \
         scripts/carbonmobile/config/applications.xml \
         scripts/carbonmobile/config/carbonmobile.properties \
         "$DIR" 1 2>&1 | grep "→"

    for f in "$DIR"/CARBON_*_TASKS.csv; do
        POLICY=$(basename "$f" | sed -E 's/CARBON_TWO_TIER_WITH_EO_(.+)_[0-9]+DEV_TASKS\.csv/\1/')
        TASKS=$(awk -F',' 'NR>1' "$f" | wc -l | tr -d ' ')
        TOTAL=$(awk -F',' 'NR>1 {s+=$11} END {printf "%.4f", s}' "$f")
        AVG=$(awk -F',' 'NR>1 {s+=$11; n++} END {printf "%.6f", s/n}' "$f")
        P50=$(awk -F',' 'NR>1 {print $12}' "$f" | sort -n |
              awk '{a[NR]=$1} END {print a[int(NR/2)]}')
        echo "$N,$POLICY,$TASKS,$TOTAL,$AVG,$P50" >> "$OUT_CSV"
    done
done
rm -f "$CONFIG_TMP"
echo "Scaling sweep done."
column -s, -t < "$OUT_CSV" | head -25
