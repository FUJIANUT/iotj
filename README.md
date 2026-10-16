# CarbonMobile

Source code for

> C. Jiang, G. Li, R. Ye, and H. Zhang, "CarbonMobile: Carbon-Efficient
> Multi-Edge Workload Orchestration for Mobile IoT," *IEEE Internet of
> Things Journal*, 2026 (accepted).

CarbonMobile is a carbon-aware workload orchestrator for edge-offloaded
mobile IoT, implemented as an extension of the
[EdgeCloudSim](https://github.com/CagataySonmez/EdgeCloudSim) simulator.
This repository contains the code only; input data (carbon-intensity
traces, mobility traces) and trained models are not included.

## Contents

```
src/edu/boun/edgecloudsim/            EdgeCloudSim core (unmodified)
src/.../applications/carbonmobile/    CarbonMobile: carbon accounting, trace-driven
                                      mobility, Greedy / Lyapunov / DRL orchestrators,
                                      and baselines (Cloud-Only, Edge-Greedy, Random,
                                      AutoScale-Energy, GreenScale-QTable,
                                      GreenScale-MultiEdge, Mobile-Only)
lib/                                  CloudSim and other jars required by EdgeCloudSim
scripts/carbonmobile/
  config/                             simulator, application and CarbonMobile settings
  drl/carbon_drl_server.py            PPO agent (PyTorch), served to the simulator over TCP
  data/convert_geolife.py             GeoLife -> per-device mobility CSVs
  data/generate_ci.py                 synthetic 8-region carbon-intensity traces
  data/generate_ci_spread.py          traces for the CI spatial-spread sweep
  run_all.sh, run_*.sh                full pipeline and individual experiment sweeps
  analyze_results.py, plot_*.py       result tables and figures
```

## Requirements

Java 17+ (tested with OpenJDK 21), Python 3.10+ with `numpy`, `torch` and
`matplotlib`, and `bash`. Run all commands from the repository root.

## Inputs

* **Mobility traces.** Download *GeoLife GPS Trajectories*
  (`Geolife Trajectories 1.3.zip`) from
  <https://www.microsoft.com/en-us/download/details.aspx?id=52367>, unzip
  it into `scripts/carbonmobile/data/geolife_raw/`, then run

  ```bash
  G="scripts/carbonmobile/data/geolife_raw/Geolife Trajectories 1.3"
  python3 scripts/carbonmobile/data/convert_geolife.py "$G" scripts/carbonmobile/data/mobility    500 1800
  python3 scripts/carbonmobile/data/convert_geolife.py "$G" scripts/carbonmobile/data/mobility_4h 100 14400
  ```

  If no traces are found, the mobility model falls back to a synthetic
  random walk; check the log for `Loaded 100 mobility traces`.
* **Carbon-intensity traces.** Generate a set with

  ```bash
  python3 scripts/carbonmobile/data/generate_ci.py --out-dir scripts/carbonmobile/data/carbon
  ```

  The archived traces used in the paper are available from the authors
  on request.

## Build and run

```bash
mkdir -p bin && javac -nowarn -d bin -cp "lib/*" $(find src -name "*.java")

# Train the PPO policy (12 episodes); the server saves
# scripts/carbonmobile/drl/checkpoints/ppo.pt
mkdir -p scripts/carbonmobile/drl/checkpoints
python3 scripts/carbonmobile/drl/carbon_drl_server.py &
bash scripts/carbonmobile/run_drl_training.sh 12
kill %1

# Serve the trained policy and run the 9-policy benchmark
python3 scripts/carbonmobile/drl/carbon_drl_server.py --eval &
mkdir -p sim_results/carbonmobile/ite1
java -cp "bin:lib/*" -Xmx2g edu.boun.edgecloudsim.applications.carbonmobile.MainApp
python3 scripts/carbonmobile/analyze_results.py sim_results/carbonmobile/ite1
```

`bash scripts/carbonmobile/run_all.sh` runs every experiment and writes
the figures to `figures/`.

## License

GNU GPL v3 (see `LICENSE`), inherited from EdgeCloudSim. The libraries in
`lib/` are distributed under their own licences. GeoLife data must be
obtained from Microsoft under its own licence.

Contact: Chunmao Jiang, jiang@fjut.edu.cn
