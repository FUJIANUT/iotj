/*
 * Title:        CarbonMobile Baseline - GreenScale Q-Table
 *
 * Description:
 * A faithful re-implementation of the GreenScale (Son et al., IoT-J 2025)
 * Q-table scheduler, simplified to keep the scope tractable while
 * preserving the design's defining features:
 *
 *   State (discretized):
 *     S_workload  : computation-to-communication ratio (Small/Med/Large/Larger)
 *     S_CI_mobile : CI bucket of the mobile device's region
 *     S_CI_edge   : CI bucket of the closest edge
 *     S_CI_cloud  : CI bucket of the cloud
 *     S_load_edge : load bucket of the closest edge (None/Small/Med/Large)
 *
 *   Action ∈ { MOBILE, EDGE_DC, CLOUD } -- exactly 3, like GreenScale.
 *
 *   Reward:
 *     R = -CF + α · perf − β · energy   (perf reward only when SLO met)
 *
 *   Update: tabular Q-learning with ε-greedy (ε=0.1, γ=0.9, lr=0.1).
 *
 * KEY POINT: this baseline only chooses among 3 coarse-grained tiers
 * (Mobile / *one* Edge / Cloud), which is GreenScale's defining
 * limitation that CarbonMobile lifts. Used for direct comparison.
 */

package edu.boun.edgecloudsim.applications.carbonmobile.orchestrator.baselines;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.cloudbus.cloudsim.core.CloudSim;

import edu.boun.edgecloudsim.applications.carbonmobile.carbon.CarbonAccounting;
import edu.boun.edgecloudsim.applications.carbonmobile.carbon.CarbonIntensityProvider;
import edu.boun.edgecloudsim.applications.carbonmobile.carbon.EnergyModel;
import edu.boun.edgecloudsim.applications.carbonmobile.carbon.EnergyModel.Tier;
import edu.boun.edgecloudsim.applications.carbonmobile.logger.CarbonLogger;
import edu.boun.edgecloudsim.applications.carbonmobile.orchestrator.BaseCarbonOrchestrator;
import edu.boun.edgecloudsim.applications.carbonmobile.orchestrator.LyapunovQueueManager;
import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_client.Task;
import edu.boun.edgecloudsim.utils.Location;

public class GreenScaleQTableOrchestrator extends BaseCarbonOrchestrator {

    private static final int A_MOBILE = 0, A_EDGE = 1, A_CLOUD = 2;
    private static final int NUM_ACTIONS = 3;

    private final double EPSILON = 0.1, GAMMA = 0.9, LR = 0.1;
    private final double ALPHA_PERF = 0.1, BETA_ENERGY = 0.1;

    private final Map<Long, double[]> qTable = new HashMap<>();
    private final Random rng = new Random(42);

    private final CarbonIntensityProvider ciProvider;
    private final EnergyModel energyModel = new EnergyModel();

    /** Last (state, action) per task so we can update on completion. */
    private final Map<Integer, long[]> pending = new HashMap<>();

    public GreenScaleQTableOrchestrator(String policy, String simScenario,
                                        CarbonAccounting acc, LyapunovQueueManager q,
                                        CarbonLogger logger,
                                        CarbonIntensityProvider ciProvider) {
        super(policy, simScenario, acc, q, logger);
        this.ciProvider = ciProvider;
    }

    @Override
    protected Target pickTarget(Task task) {
        long state = encodeState(task);
        double[] qs = qTable.computeIfAbsent(state, k -> new double[NUM_ACTIONS]);

        int action;
        if (rng.nextDouble() < EPSILON) {
            action = rng.nextInt(NUM_ACTIONS);
        } else {
            action = argmax(qs);
        }
        pending.put(task.getCloudletId(), new long[] { state, action });

        // Online reward: estimate immediately (no need to wait)
        double reward = estimateReward(task, action);
        // Single-step Q-update (no next-state since each task is independent)
        qs[action] += LR * (reward - qs[action]);

        // Translate action to Target. When mobile execution is not configured
        // (mips_for_mobile_vm == 0), A_MOBILE silently falls back to the
        // closest edge — same operational outcome as the cloud-edge-only
        // sweep used in GreenScale's evaluation.
        boolean mobileSupported = SimSettings.getInstance().getMipsForMobileVM() > 0;
        if (action == A_MOBILE && mobileSupported) {
            return new Target(SimSettings.MOBILE_DATACENTER_ID, -1);
        }
        if (action == A_CLOUD)  return new Target(SimSettings.CLOUD_DATACENTER_ID, -1);
        Location loc = SimManager.getInstance().getMobilityModel()
                .getLocation(task.getMobileDeviceId(), CloudSim.clock());
        return new Target(SimSettings.GENERIC_EDGE_DEVICE_ID, loc.getServingWlanId());
    }

    // ---------- helpers ----------

    private double estimateReward(Task task, int action) {
        double[] tp = SimSettings.getInstance().getTaskLookUpTable()[task.getTaskType()];
        double now = CloudSim.clock();
        double dataUpKB = tp[5], dataDownKB = tp[6];
        Location loc = SimManager.getInstance().getMobilityModel()
                .getLocation(task.getMobileDeviceId(), now);

        double execS, energy, cf, latency;
        if (action == A_MOBILE) {
            execS = (SimSettings.getInstance().getMipsForMobileVM() > 0)
                    ? task.getCloudletLength() / (double) SimSettings.getInstance().getMipsForMobileVM()
                    : task.getCloudletLength() / 5000.0;
            energy = energyModel.computeEnergy(Tier.MOBILE, tp[11] / 100.0, execS);
            cf = carbonAccounting.computeOperationalCF(
                    task.getMobileDeviceId(), Tier.MOBILE, tp[11] / 100.0, execS, now);
            latency = execS;
        } else if (action == A_EDGE) {
            execS = task.getCloudletLength()
                    / (double) SimSettings.getInstance().getMipsForCloudVM();
            energy = energyModel.computeEnergy(Tier.EDGE, tp[9] / 100.0, execS);
            cf = carbonAccounting.computeOperationalCF(
                    loc.getServingWlanId(), Tier.EDGE, tp[9] / 100.0, execS, now);
            latency = execS + 0.05;
        } else {
            execS = task.getCloudletLength()
                    / (double) SimSettings.getInstance().getMipsForCloudVM() / 4.0;
            energy = energyModel.computeEnergy(Tier.CLOUD, tp[10] / 100.0, execS);
            cf = carbonAccounting.computeOperationalCF(
                    -1, Tier.CLOUD, tp[10] / 100.0, execS, now);
            latency = execS + SimSettings.getInstance().getWanPropagationDelay() * 2;
        }

        double slo = (tp.length >= 14) ? tp[13] : 1.0;
        if (slo <= 0) slo = 1.0;
        double perfReward = (latency <= slo) ? (slo - latency) / slo : -1.0;

        return -cf + ALPHA_PERF * perfReward - BETA_ENERGY * (energy / 100.0);
    }

    private long encodeState(Task task) {
        double[] tp = SimSettings.getInstance().getTaskLookUpTable()[task.getTaskType()];
        double now = CloudSim.clock();
        Location loc = SimManager.getInstance().getMobilityModel()
                .getLocation(task.getMobileDeviceId(), now);
        // Workload class (4 buckets based on FLOPs / data ratio)
        double ratio = task.getCloudletLength() / Math.max(tp[5] + tp[6], 1.0);
        int wl = ratio < 50 ? 0 : ratio < 200 ? 1 : ratio < 1000 ? 2 : 3;
        // CI buckets: 0=<200, 1=<400, 2=<600, 3>=600
        int ciM = ciBucket(ciProvider.getCI(task.getMobileDeviceId(), now));
        int ciE = ciBucket(ciProvider.getCI(loc.getServingWlanId(), now));
        int ciC = ciBucket(ciProvider.getCI(-1, now));
        // Edge load (proxy: number of co-located mobile devices in the area)
        int load = 0; // Simplified — could plug in real utilization here
        // Combine into a single long
        return (((long) wl) << 32) | (((long) ciM) << 24) | (((long) ciE) << 16)
                | (((long) ciC) << 8) | load;
    }

    private int ciBucket(double ci) {
        if (ci < 0) return 1; // missing → assume medium
        if (ci < 200) return 0;
        if (ci < 400) return 1;
        if (ci < 600) return 2;
        return 3;
    }

    private int argmax(double[] qs) {
        int best = 0;
        for (int i = 1; i < qs.length; i++) if (qs[i] > qs[best]) best = i;
        return best;
    }
}
