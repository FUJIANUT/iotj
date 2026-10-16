#!/usr/bin/env bash
# F1 ablation: sweep the Lyapunov V parameter for the LYAPUNOV policy.
#
# Each V value is run REPS times and the metrics are averaged. This
# absorbs the EdgeCloudSim per-run randomness (task arrivals, network).
set -e
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
export PATH=$JAVA_HOME/bin:$PATH

V_VALUES="${1:-1 10 50 100 500 1000 5000}"
REPS=${REPS:-3}
PROJECT_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$PROJECT_ROOT"

CONFIG_TMP=scripts/carbonmobile/config/_sweep_v_config.properties
cp scripts/carbonmobile/config/default_config.properties "$CONFIG_TMP"
sed -i.bak 's/^orchestrator_policies=.*/orchestrator_policies=LYAPUNOV/' "$CONFIG_TMP"
rm -f "${CONFIG_TMP}.bak"

# Tight carbon budget so the Lyapunov queue actively binds.
TIGHT_BUDGET=15
PROPS_TMP=scripts/carbonmobile/config/_sweep_v_props.properties
sed "s/^total_carbon_budget_g.*/total_carbon_budget_g = ${TIGHT_BUDGET}/" \
    scripts/carbonmobile/config/carbonmobile.properties > "$PROPS_TMP"

OUT_CSV=sim_results/carbonmobile_ablation/v_sweep.csv
mkdir -p sim_results/carbonmobile_ablation
echo "v,reps,avg_cf,avg_peak_q,avg_final_q,sd_cf" > "$OUT_CSV"

for V in $V_VALUES; do
    echo "=== V = $V (averaging over $REPS runs) ==="
    SUM_CF=0; SUM_PEAK=0; SUM_FINAL=0
    AVGS=""
    for r in $(seq 1 $REPS); do
        DIR=sim_results/carbonmobile_ablation/v_${V}_r${r}
        rm -rf "$DIR" && mkdir -p "$DIR"
        java -cp "bin:lib/*" -Xmx2g \
             -Dcarbonmobile.lyapunov_v="$V" \
             edu.boun.edgecloudsim.applications.carbonmobile.MainApp \
             "$CONFIG_TMP" \
             scripts/carbonmobile/config/edge_devices.xml \
             scripts/carbonmobile/config/applications.xml \
             "$PROPS_TMP" \
             "$DIR" "$r" > /dev/null 2>&1
        TASKS_CSV=$(ls "$DIR"/CARBON_*_TASKS.csv | head -1)
        AVG=$(awk -F',' 'NR>1 {s+=$11; n++} END {printf "%.6f", s/n}' "$TASKS_CSV")
        PEAK=$(awk -F',' 'NR>1 && $14+0>m {m=$14+0} END {print m}' "$TASKS_CSV")
        FINAL=$(awk -F',' 'NR>1 {q=$14} END {print q}' "$TASKS_CSV")
        SUM_CF=$(echo "$SUM_CF + $AVG"   | bc -l)
        SUM_PEAK=$(echo "$SUM_PEAK + $PEAK" | bc -l)
        SUM_FINAL=$(echo "$SUM_FINAL + $FINAL" | bc -l)
        AVGS="$AVGS $AVG"
        printf "    rep %d: avgCF=%s peakQ=%s\n" "$r" "$AVG" "$PEAK"
    done
    AVG_CF=$(echo "scale=6; $SUM_CF / $REPS" | bc -l)
    AVG_PEAK=$(echo "scale=4; $SUM_PEAK / $REPS" | bc -l)
    AVG_FINAL=$(echo "scale=4; $SUM_FINAL / $REPS" | bc -l)
    SD_CF=$(python3 -c "import statistics, sys; v=[float(x) for x in '$AVGS'.split()]; print(f'{statistics.stdev(v) if len(v)>1 else 0:.6f}')")
    echo "$V,$REPS,$AVG_CF,$AVG_PEAK,$AVG_FINAL,$SD_CF" >> "$OUT_CSV"
done

rm -f "$CONFIG_TMP" "$PROPS_TMP"
echo "V sweep done (tight budget=${TIGHT_BUDGET} gCO2eq, $REPS reps avg)."
cat "$OUT_CSV"
