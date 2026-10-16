/*
 * Title:        CarbonMobile Baseline - GreenScale-MultiEdge
 *
 * Description:
 * Re-implementation of GreenScale's tabular Q-learning scheduler with
 * its action space \emph{expanded} from the original three coarse
 * tiers (Mobile / Edge / Cloud) to the same fine-grained
 * (E + 1) per-edge + cloud space that CarbonMobile uses.
 *
 * The state representation, reward shape, and Q-table update rule are
 * identical to the original {@link GreenScaleQTableOrchestrator}; only
 * the action set changes. This baseline isolates the contribution of
 * action-space granularity from the contribution of the learning
 * algorithm and lets reviewers verify that CarbonMobile's gain is not
 * a trivial consequence of giving GreenScale less to work with.
 *
 * Note that the state vector is unchanged, so the Q-table cannot
 * distinguish individual edges --- the $\arg\max$ over the larger
 * action set is taken without per-edge state features. This baseline
 * is therefore expected to perform between the original GreenScale
 * Q-table and \texttt{CarbonMobile-Greedy}: action-space granularity
 * helps even without per-edge features, but CarbonMobile's per-edge
 * CI scoring extracts more.
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

public class GreenScaleMultiEdgeOrchestrator extends BaseCarbonOrchestrator {

    private final double EPSILON = 0.1, GAMMA = 0.9, LR = 0.1;
    private final double ALPHA_PERF = 0.1, BETA_ENERGY = 0.1;

    private final Map<Long, double[]> qTable = new HashMap<>();
    private final Random rng = new Random(42);

    private final CarbonIntensityProvider ciProvider;
    private final EnergyModel energyModel = new EnergyModel();

    private final int numEdges;
    private final int numActions;        // E + 1 (cloud)

    public GreenScaleMultiEdgeOrchestrator(String policy, String simScenario,
                                           CarbonAccounting acc, LyapunovQueueManager q,
                                           CarbonLogger logger,
                                           CarbonIntensityProvider ciProvider) {
        super(policy, simScenario, acc, q, logger);
        this.ciProvider = ciProvider;
        this.numEdges = SimSettings.getInstance().getNumOfEdgeHosts();
        this.numActions = numEdges + 1;
    }

    @Override
    protected Target pickTarget(Task task) {
        long state = encodeState(task);
        double[] qs = qTable.computeIfAbsent(state, k -> new double[numActions]);

        int action;
        if (rng.nextDouble() < EPSILON) {
            action = rng.nextInt(numActions);
        } else {
            action = argmax(qs);
        }

        // Same single-step reward computation as the original
        // GreenScale Q-table: estimate carbon, latency, and energy of
        // this action and update Q immediately.
        double reward = estimateReward(task, action);
        qs[action] += LR * (reward - qs[action]);

        if (action == numActions - 1) {       // last action = cloud
            return new Target(SimSettings.CLOUD_DATACENTER_ID, -1);
        }
        return new Target(SimSettings.GENERIC_EDGE_DEVICE_ID, action);
    }

    private double estimateReward(Task task, int action) {
        double[] tp = SimSettings.getInstance().getTaskLookUpTable()[task.getTaskType()];
        double now = CloudSim.clock();

        boolean isCloud = (action == numActions - 1);
        double execS, energy, cf, latency;
        if (isCloud) {
            execS = task.getCloudletLength()
                    / (double) SimSettings.getInstance().getMipsForCloudVM() / 4.0;
            energy = energyModel.computeEnergy(Tier.CLOUD, tp[10] / 100.0, execS);
            cf = carbonAccounting.computeOperationalCF(
                    -1, Tier.CLOUD, tp[10] / 100.0, execS, now);
            latency = execS + SimSettings.getInstance().getWanPropagationDelay() * 2;
        } else {
            int edgeId = action;
            execS = task.getCloudletLength()
                    / (double) SimSettings.getInstance().getMipsForCloudVM();
            energy = energyModel.computeEnergy(Tier.EDGE, tp[9] / 100.0, execS);
            cf = carbonAccounting.computeOperationalCF(
                    edgeId, Tier.EDGE, tp[9] / 100.0, execS, now);
            latency = execS + 0.05;
        }

        double slo = (tp.length >= 14 && tp[13] > 0) ? tp[13] : 1.0;
        double perfReward = (latency <= slo) ? (slo - latency) / slo : -1.0;
        return -cf + ALPHA_PERF * perfReward - BETA_ENERGY * (energy / 100.0);
    }

    /**
     * State encoding identical to {@link GreenScaleQTableOrchestrator}: the
     * Q-table is indexed by workload class and CI buckets only --- it
     * cannot distinguish individual edges. This is intentional: the
     * point of this baseline is to ablate \emph{only} the action space.
     */
    private long encodeState(Task task) {
        double[] tp = SimSettings.getInstance().getTaskLookUpTable()[task.getTaskType()];
        double now = CloudSim.clock();
        Location loc = SimManager.getInstance().getMobilityModel()
                .getLocation(task.getMobileDeviceId(), now);
        double ratio = task.getCloudletLength() / Math.max(tp[5] + tp[6], 1.0);
        int wl = ratio < 50 ? 0 : ratio < 200 ? 1 : ratio < 1000 ? 2 : 3;
        int ciM = ciBucket(ciProvider.getCI(task.getMobileDeviceId(), now));
        int ciE = ciBucket(ciProvider.getCI(loc.getServingWlanId(), now));
        int ciC = ciBucket(ciProvider.getCI(-1, now));
        return (((long) wl) << 24) | (((long) ciM) << 16)
                | (((long) ciE) << 8) | ciC;
    }

    private int ciBucket(double ci) {
        if (ci < 0) return 1;
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
