#!/usr/bin/env bash
# F6 ablation: sweep the long-term carbon budget B for the LYAPUNOV
# policy. Reports cumulative emission, peak Q, and budget violation
# ratio for each B value.
set -e
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
export PATH=$JAVA_HOME/bin:$PATH

BUDGETS="${1:-100 125 150 175 200 250}"
PROJECT_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$PROJECT_ROOT"

CONFIG_TMP=scripts/carbonmobile/config/_sweep_budget_config.properties
cp scripts/carbonmobile/config/longrun_config.properties "$CONFIG_TMP"
sed -i.bak 's/^orchestrator_policies=.*/orchestrator_policies=LYAPUNOV/' "$CONFIG_TMP"
rm -f "${CONFIG_TMP}.bak"

OUT_CSV=sim_results/carbonmobile_ablation/budget_sweep.csv
mkdir -p sim_results/carbonmobile_ablation
echo "budget,tasks,total_cf,avg_cf,peak_q,final_q,budget_violation_ratio" > "$OUT_CSV"

for B in $BUDGETS; do
    PROPS_TMP=scripts/carbonmobile/config/_sweep_budget_props.properties
    sed "s/^total_carbon_budget_g.*/total_carbon_budget_g = ${B}/" \
        scripts/carbonmobile/config/longrun_props.properties > "$PROPS_TMP"

    DIR=sim_results/carbonmobile_ablation/budget_${B}
    rm -rf "$DIR" && mkdir -p "$DIR"
    echo "=== B = $B gCO2eq ==="
    java -cp "bin:lib/*" -Xmx4g \
         edu.boun.edgecloudsim.applications.carbonmobile.MainApp \
         "$CONFIG_TMP" \
         scripts/carbonmobile/config/edge_devices.xml \
         scripts/carbonmobile/config/applications.xml \
         "$PROPS_TMP" \
         "$DIR" 1 2>&1 | grep "→" | head -1

    TASKS_CSV=$(ls "$DIR"/CARBON_*_TASKS.csv | head -1)
    TOTAL=$(awk -F',' 'NR>1 {s+=$11} END {printf "%.4f", s}' "$TASKS_CSV")
    AVG=$(awk -F',' 'NR>1 {s+=$11; n++} END {printf "%.6f", s/n}' "$TASKS_CSV")
    N=$(awk -F',' 'NR>1 {n++} END {print n}' "$TASKS_CSV")
    PEAK=$(awk -F',' 'NR>1 && $14+0>m {m=$14+0} END {print m}' "$TASKS_CSV")
    FINAL=$(awk -F',' 'NR>1 {q=$14} END {print q}' "$TASKS_CSV")
    VIOL=$(echo "$TOTAL > $B" | bc -l)
    if [ "$VIOL" = "1" ]; then
        VRATIO=$(echo "scale=4; ($TOTAL - $B) / $B" | bc -l)
    else
        VRATIO=0
    fi
    echo "$B,$N,$TOTAL,$AVG,$PEAK,$FINAL,$VRATIO" >> "$OUT_CSV"
    rm -f "$PROPS_TMP"
done

rm -f "$CONFIG_TMP"
echo "Budget sweep done."
column -s, -t < "$OUT_CSV"
