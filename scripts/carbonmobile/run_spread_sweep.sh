#!/usr/bin/env bash
# C3 ablation: sweep the CI spatial spread to test whether the
# headline advantage holds when the eight power-grid regions are
# more similar to each other than the original 35-700 spread.
#
# Requires the Python DRL server to be running in --eval mode.
set -e
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
export PATH=$JAVA_HOME/bin:$PATH

SPREADS="${1:-50 100 200 400 600}"
PROJECT_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$PROJECT_ROOT"

CONFIG_TMP=scripts/carbonmobile/config/_sweep_spread_config.properties
cp scripts/carbonmobile/config/default_config.properties "$CONFIG_TMP"
sed -i.bak 's/^orchestrator_policies=.*/orchestrator_policies=DRL,CARBON_GREEDY,GREENSCALE_QTABLE,EDGE_GREEDY/' "$CONFIG_TMP"
rm -f "${CONFIG_TMP}.bak"

OUT_CSV=sim_results/carbonmobile_ablation/spread_sweep.csv
mkdir -p sim_results/carbonmobile_ablation
echo "spread,policy,tasks,total_cf,avg_cf,p95_lat" > "$OUT_CSV"

# Stash the original CI traces; restore at end.
TRACE_DIR=scripts/carbonmobile/data/carbon
BACKUP_DIR=scripts/carbonmobile/data/carbon_backup
mv "$TRACE_DIR" "$BACKUP_DIR"

for SP in $SPREADS; do
    mkdir -p "$TRACE_DIR" && rm -f "$TRACE_DIR"/*.csv
    python3 scripts/carbonmobile/data/generate_ci_spread.py \
        --spread "$SP" --out_dir "$TRACE_DIR" > /dev/null

    DIR=sim_results/carbonmobile_ablation/spread_${SP}
    rm -rf "$DIR" && mkdir -p "$DIR"
    echo "=== spread = $SP gCO2/kWh ==="
    java -cp "bin:lib/*" -Xmx2g \
         edu.boun.edgecloudsim.applications.carbonmobile.MainApp \
         "$CONFIG_TMP" \
         scripts/carbonmobile/config/edge_devices.xml \
         scripts/carbonmobile/config/applications.xml \
         scripts/carbonmobile/config/carbonmobile.properties \
         "$DIR" 1 2>&1 | grep "→"

    for f in "$DIR"/CARBON_*_TASKS.csv; do
        POL=$(basename "$f" | sed -E 's/CARBON_TWO_TIER_WITH_EO_(.+)_[0-9]+DEV_TASKS\.csv/\1/')
        TASKS=$(awk -F',' 'NR>1' "$f" | wc -l | tr -d ' ')
        TOTAL=$(awk -F',' 'NR>1 {s+=$11} END {printf "%.4f", s}' "$f")
        AVG=$(awk -F',' 'NR>1 {s+=$11; n++} END {printf "%.6f", s/n}' "$f")
        P95=$(awk -F',' 'NR>1 {print $12}' "$f" | sort -n |
              awk '{a[NR]=$1} END {print a[int(NR*0.95)]}')
        echo "$SP,$POL,$TASKS,$TOTAL,$AVG,$P95" >> "$OUT_CSV"
    done
done

# Restore originals
rm -rf "$TRACE_DIR" && mv "$BACKUP_DIR" "$TRACE_DIR"
rm -f "$CONFIG_TMP"
echo "Spread sweep done."
column -s, -t < "$OUT_CSV" | head -30
