#!/usr/bin/env bash
# Train PPO across multiple episodes and save per-episode CF to CSV.
#
# Assumes the Python DRL server is already running (otherwise start it first):
#   PYTHONUNBUFFERED=1 python3 scripts/carbonmobile/drl/carbon_drl_server.py
#
# Usage: bash run_drl_training.sh [num_episodes] [output_csv]
set -e
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
export PATH=$JAVA_HOME/bin:$PATH

EPISODES=${1:-10}
OUT_CSV=${2:-sim_results/carbonmobile_train/training_curve.csv}

PROJECT_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$PROJECT_ROOT"

mkdir -p "$(dirname "$OUT_CSV")"
echo "episode,tasks,total_cf,avg_cf,qos_violation_rate" > "$OUT_CSV"

for i in $(seq 1 "$EPISODES"); do
    DIR="sim_results/carbonmobile_train/ep${i}"
    rm -rf "$DIR" && mkdir -p "$DIR"
    echo "=== Episode $i ==="
    OUT=$(java -cp "bin:lib/*" -Xmx2g \
         edu.boun.edgecloudsim.applications.carbonmobile.MainApp \
         scripts/carbonmobile/config/drl_only_config.properties \
         scripts/carbonmobile/config/edge_devices.xml \
         scripts/carbonmobile/config/applications.xml \
         scripts/carbonmobile/config/carbonmobile.properties \
         "$DIR" "$i" 2>&1 | grep "→")
    echo "  $OUT"
    # Parse the "  → tasks=N, totalCF=X gCO2eq, avgCF=Y, qosViol=Z" line
    TASKS=$(echo "$OUT"   | sed -E 's/.*tasks=([0-9]+).*/\1/')
    TOTAL=$(echo "$OUT"   | sed -E 's/.*totalCF=([0-9.]+).*/\1/')
    AVG=$(echo "$OUT"     | sed -E 's/.*avgCF=([0-9.]+).*/\1/')
    QOS=$(echo "$OUT"     | sed -E 's/.*qosViol=([0-9.]+).*/\1/')
    echo "$i,$TASKS,$TOTAL,$AVG,$QOS" >> "$OUT_CSV"
done

echo "Training done. Curve saved to $OUT_CSV"
