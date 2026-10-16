#!/usr/bin/env bash
# F2 ablation: sweep CI forecast noise (sigma in gCO2/kWh) for the DRL policy
# in eval mode. Tests robustness of the trained policy to forecast errors.
#
# Requires the Python DRL server already running in --eval mode with the
# trained checkpoint loaded.
set -e
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
export PATH=$JAVA_HOME/bin:$PATH

SIGMAS="${1:-0 10 20 30 50 100}"
REPS=${REPS:-3}
PROJECT_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$PROJECT_ROOT"

CONFIG_TMP=scripts/carbonmobile/config/_sweep_noise_config.properties
cp scripts/carbonmobile/config/default_config.properties "$CONFIG_TMP"
sed -i.bak 's/^orchestrator_policies=.*/orchestrator_policies=DRL/' "$CONFIG_TMP"
rm -f "${CONFIG_TMP}.bak"

OUT_CSV=sim_results/carbonmobile_ablation/noise_sweep.csv
mkdir -p sim_results/carbonmobile_ablation
echo "sigma,reps,avg_cf,sd_cf" > "$OUT_CSV"

for SIGMA in $SIGMAS; do
    echo "=== sigma = $SIGMA gCO2/kWh ==="
    SUM_CF=0
    AVGS=""
    for r in $(seq 1 $REPS); do
        DIR=sim_results/carbonmobile_ablation/noise_${SIGMA}_r${r}
        rm -rf "$DIR" && mkdir -p "$DIR"
        java -cp "bin:lib/*" -Xmx2g \
             -Dcarbonmobile.ci_noise_sigma="$SIGMA" \
             edu.boun.edgecloudsim.applications.carbonmobile.MainApp \
             "$CONFIG_TMP" \
             scripts/carbonmobile/config/edge_devices.xml \
             scripts/carbonmobile/config/applications.xml \
             scripts/carbonmobile/config/carbonmobile.properties \
             "$DIR" "$r" > /dev/null 2>&1
        TASKS_CSV=$(ls "$DIR"/CARBON_*_TASKS.csv | head -1)
        AVG=$(awk -F',' 'NR>1 {s+=$11; n++} END {printf "%.6f", s/n}' "$TASKS_CSV")
        SUM_CF=$(echo "$SUM_CF + $AVG" | bc -l)
        AVGS="$AVGS $AVG"
        printf "    rep %d: avgCF=%s\n" "$r" "$AVG"
    done
    AVG_CF=$(echo "scale=6; $SUM_CF / $REPS" | bc -l)
    SD_CF=$(python3 -c "import statistics; v=[float(x) for x in '$AVGS'.split()]; print(f'{statistics.stdev(v) if len(v)>1 else 0:.6f}')")
    echo "$SIGMA,$REPS,$AVG_CF,$SD_CF" >> "$OUT_CSV"
done

rm -f "$CONFIG_TMP"
echo "Noise sweep done."
cat "$OUT_CSV"
