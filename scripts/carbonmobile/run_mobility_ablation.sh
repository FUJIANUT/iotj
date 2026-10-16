#!/usr/bin/env bash
# Mobility ablation: run DRL (eval mode) under three settings:
#   FULL:          mobility features and trajectory prediction enabled
#   NO_PREDICTION: trajectory prediction features zeroed
#   NO_MOBILITY:   all mobility features (location, velocity, prediction) zeroed
# Reports avg_cf, p95 latency, migration rate for each.
#
# Requires the Python DRL server already running in --eval mode.
set -e
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
export PATH=$JAVA_HOME/bin:$PATH

PROJECT_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$PROJECT_ROOT"

OUT_CSV=sim_results/carbonmobile_ablation/mobility_ablation.csv
mkdir -p sim_results/carbonmobile_ablation
echo "variant,tasks,total_cf,avg_cf,p95_lat,mig_rate" > "$OUT_CSV"

run_variant() {
    local NAME=$1
    local PROPS=$2
    DIR=sim_results/carbonmobile_ablation/mobility_${NAME}
    rm -rf "$DIR" && mkdir -p "$DIR"
    echo "=== $NAME ==="
    java $PROPS -cp "bin:lib/*" -Xmx2g \
         edu.boun.edgecloudsim.applications.carbonmobile.MainApp \
         scripts/carbonmobile/config/mobility_ablation_config.properties \
         scripts/carbonmobile/config/edge_devices.xml \
         scripts/carbonmobile/config/applications.xml \
         scripts/carbonmobile/config/carbonmobile.properties \
         "$DIR" 1 2>&1 | grep "→" | head -1

    TASKS_CSV=$(ls "$DIR"/CARBON_*_TASKS.csv | head -1)
    TASKS=$(awk -F',' 'NR>1' "$TASKS_CSV" | wc -l | tr -d ' ')
    TOTAL=$(awk -F',' 'NR>1 {s+=$11} END {printf "%.4f", s}' "$TASKS_CSV")
    AVG=$(awk -F',' 'NR>1 {s+=$11; n++} END {printf "%.6f", s/n}' "$TASKS_CSV")
    # p95 latency
    P95=$(awk -F',' 'NR>1 {print $12}' "$TASKS_CSV" | sort -n |
          awk '{a[NR]=$1} END {print a[int(NR*0.95)]}')
    # migration rate
    MIG=$(awk -F',' 'NR>1 && $10+0>0 {nmig++} NR>1 {n++} END {printf "%.4f", nmig/n}' "$TASKS_CSV")
    echo "$NAME,$TASKS,$TOTAL,$AVG,$P95,$MIG" >> "$OUT_CSV"
}

run_variant FULL          ""
run_variant NO_PREDICTION "-Dcarbonmobile.ablate_prediction=true"
run_variant NO_MOBILITY   "-Dcarbonmobile.ablate_mobility=true"

echo "Mobility ablation done."
column -s, -t < "$OUT_CSV"
