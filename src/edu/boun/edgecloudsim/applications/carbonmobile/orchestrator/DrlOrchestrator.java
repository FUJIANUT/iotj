/*
 * Title:        CarbonMobile - DRL Orchestrator
 *
 * Description:
 * Defers each placement decision to a Python PPO agent over a socket
 * (see DRLAgentBridge). Encodes the same per-task carbon/load context
 * that CarbonMobileOrchestrator uses, plus the Lyapunov queue value Q,
 * so the agent learns a policy that combines latency, carbon, and
 * long-term budget compliance.
 *
 * State (37 floats):
 *   [4]  task type one-hot (truncated/padded if app_count != 4)
 *   [3]  task features: dataUpKB/1024, dataDownKB/1024, deadline(s)
 *   [4]  user features: x/50000, y/50000, vx/20, vy/20
 *   [24] 8 edges × 3 features: distance/50000, ci/700, ci_forecast/700
 *   [1]  cloud CI / 700
 *   [1]  Lyapunov queue Q / total_budget
 *
 * Action (Discrete, n_edges + 1):
 *   0..n_edges-1 → choose that edge server (host id)
 *   n_edges      → choose the cloud
 *
 * Reward (sent after pickTarget computes CF in the base class):
 *   reward = - (V + Q) * total_CF  - 0.1 * (latency / deadline if SLO violated)
 */

package edu.boun.edgecloudsim.applications.carbonmobile.orchestrator;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.cloudbus.cloudsim.core.CloudSim;

import edu.boun.edgecloudsim.applications.carbonmobile.carbon.CarbonAccounting;
import edu.boun.edgecloudsim.applications.carbonmobile.carbon.CarbonIntensityProvider;
import edu.boun.edgecloudsim.applications.carbonmobile.carbon.EnergyModel.Tier;
import edu.boun.edgecloudsim.applications.carbonmobile.logger.CarbonLogger;
import edu.boun.edgecloudsim.applications.carbonmobile.mobility.TraceBasedMobilityModel;
import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_client.Task;
import edu.boun.edgecloudsim.utils.Location;
import edu.boun.edgecloudsim.utils.SimLogger;

public class DrlOrchestrator extends BaseCarbonOrchestrator {

    private static final int APP_TYPE_SLOTS = 5;     // AR/Health/Heavy/Info/IoT-Sensor
    private static final int N_EDGES_FIXED = 8;      // matches our edge_devices.xml
    private static final int STATE_DIM = APP_TYPE_SLOTS + 3 + 4 + N_EDGES_FIXED * 3 + 1 + 1;

    private final CarbonIntensityProvider ciProvider;
    private DRLAgentBridge bridge;
    private double[] edgeX, edgeY;

    /** Ablation: zero out mobility features (location, velocity, predicted
     *  location) before sending state to PPO. Set via JVM property
     *  -Dcarbonmobile.ablate_mobility=true */
    private static final boolean ABLATE_MOBILITY =
            Boolean.parseBoolean(System.getProperty("carbonmobile.ablate_mobility", "false"));
    /** Ablation: zero out the trajectory prediction features only. */
    private static final boolean ABLATE_PREDICTION =
            Boolean.parseBoolean(System.getProperty("carbonmobile.ablate_prediction", "false"));

    /** Per-task: chosen action, cached state for reward callback. */
    private final Map<Integer, double[]> taskState = new HashMap<>();
    private final Map<Integer, Integer> taskAction = new HashMap<>();

    public DrlOrchestrator(String policy, String simScenario,
                           CarbonAccounting acc, LyapunovQueueManager q,
                           CarbonLogger logger, CarbonIntensityProvider ciProvider,
                           String drlHost, int drlPort) {
        super(policy, simScenario, acc, q, logger);
        this.ciProvider = ciProvider;
        cacheEdgePositions();
        try {
            bridge = new DRLAgentBridge(drlHost, drlPort);
            bridge.hello(N_EDGES_FIXED, STATE_DIM);
            SimLogger.printLine("[DRL] connected to " + drlHost + ":" + drlPort);
        } catch (IOException e) {
            SimLogger.printLine("[DRL] FAILED to connect: " + e.getMessage()
                    + " — falling back to random.");
            bridge = null;
        }
    }

    private void cacheEdgePositions() {
        org.w3c.dom.Document doc = SimSettings.getInstance().getEdgeDevicesDocument();
        org.w3c.dom.NodeList list = doc.getElementsByTagName("datacenter");
        edgeX = new double[list.getLength()];
        edgeY = new double[list.getLength()];
        for (int i = 0; i < list.getLength(); i++) {
            org.w3c.dom.Element loc = (org.w3c.dom.Element)
                    ((org.w3c.dom.Element) list.item(i))
                            .getElementsByTagName("location").item(0);
            edgeX[i] = Integer.parseInt(loc.getElementsByTagName("x_pos").item(0).getTextContent());
            edgeY[i] = Integer.parseInt(loc.getElementsByTagName("y_pos").item(0).getTextContent());
        }
    }

    @Override
    protected Target pickTarget(Task task) {
        double[] s = encodeState(task);
        int action;
        try {
            action = (bridge != null) ? bridge.decide(task.getCloudletId(), s)
                                      : (int) (Math.random() * (N_EDGES_FIXED + 1));
        } catch (IOException e) {
            SimLogger.printLine("[DRL] decide failed: " + e.getMessage());
            action = (int) (Math.random() * (N_EDGES_FIXED + 1));
        }
        taskState.put(task.getCloudletId(), s);
        taskAction.put(task.getCloudletId(), action);

        if (action >= N_EDGES_FIXED) {
            return new Target(SimSettings.CLOUD_DATACENTER_ID, -1);
        }
        // Cap edge id to actually-existing edges
        int edgeId = Math.min(action, SimSettings.getInstance().getNumOfEdgeHosts() - 1);
        return new Target(SimSettings.GENERIC_EDGE_DEVICE_ID, edgeId);
    }

    @Override
    protected void recordChosenCF(Task task, Target t) {
        super.recordChosenCF(task, t);
        // Compute reward = -(V+Q) * CF; we read the most recent record from logger.
        if (bridge == null) return;
        try {
            CarbonLogger.TaskRecord r = logger.lastRecord();
            double weight = (lyapunov != null) ? lyapunov.effectiveCarbonWeight() : 1.0;
            double[] tp = SimSettings.getInstance().getTaskLookUpTable()[task.getTaskType()];
            double slo = (tp.length >= 14 && tp[13] > 0) ? tp[13] : 1.0;
            double sloPenalty = (r.latencySec > slo) ? 0.1 * (r.latencySec / slo) : 0.0;
            double reward = -weight * r.totalCF - sloPenalty;
            // For now we mark each task's transition as terminal (done=true) so
            // we don't need to carry per-user next_state across tasks. PPO can
            // still learn a useful per-task policy from this episodic signal.
            bridge.reward(task.getCloudletId(), reward, null, true);
        } catch (IOException e) {
            SimLogger.printLine("[DRL] reward send failed: " + e.getMessage());
        }
        taskState.remove(task.getCloudletId());
        taskAction.remove(task.getCloudletId());
    }

    @Override
    public void shutdownEntity() {
        if (bridge != null) {
            try {
                bridge.episodeEnd();
                bridge.close();
            } catch (IOException ignore) {}
        }
    }

    // ---------- state encoding ----------

    private double[] encodeState(Task task) {
        double now = CloudSim.clock();
        double[] s = new double[STATE_DIM];
        int idx = 0;
        // task type one-hot
        int tt = Math.min(task.getTaskType(), APP_TYPE_SLOTS - 1);
        for (int i = 0; i < APP_TYPE_SLOTS; i++) s[idx++] = (i == tt) ? 1.0 : 0.0;
        // task features
        double[] tp = SimSettings.getInstance().getTaskLookUpTable()[task.getTaskType()];
        s[idx++] = Math.min(tp[5] / 1024.0, 5.0);  // upload (KB → MB), capped
        s[idx++] = Math.min(tp[6] / 1024.0, 5.0);  // download
        s[idx++] = (tp.length >= 14 && tp[13] > 0) ? Math.min(tp[13], 5.0) : 1.0;
        // user features  (ablation: replace with zeros to remove mobility)
        Location loc = SimManager.getInstance().getMobilityModel()
                .getLocation(task.getMobileDeviceId(), now);
        if (ABLATE_MOBILITY) {
            s[idx++] = 0; s[idx++] = 0; s[idx++] = 0; s[idx++] = 0;
        } else {
            s[idx++] = loc.getXPos() / 50000.0;
            s[idx++] = loc.getYPos() / 50000.0;
            if (SimManager.getInstance().getMobilityModel() instanceof TraceBasedMobilityModel) {
                double[] v;
                if (ABLATE_PREDICTION) {
                    v = new double[]{0, 0};
                } else {
                    v = ((TraceBasedMobilityModel) SimManager.getInstance().getMobilityModel())
                            .getVelocity(task.getMobileDeviceId(), now);
                }
                s[idx++] = Math.max(-1, Math.min(1, v[0] / 20.0));
                s[idx++] = Math.max(-1, Math.min(1, v[1] / 20.0));
            } else {
                s[idx++] = 0; s[idx++] = 0;
            }
        }
        // 8 edges × (distance, ci_now, ci_forecast)
        int numEdges = Math.min(N_EDGES_FIXED, SimSettings.getInstance().getNumOfEdgeHosts());
        for (int e = 0; e < N_EDGES_FIXED; e++) {
            if (e < numEdges) {
                double dx = loc.getXPos() - edgeXpos(e);
                double dy = loc.getYPos() - edgeYpos(e);
                double dist = Math.sqrt(dx * dx + dy * dy);
                double ciNow = ciProvider.getCI(e, now);
                double ciFut = ciProvider.getAverageCI(e, now, 600.0);
                s[idx++] = Math.min(dist / 50000.0, 1.0);
                s[idx++] = (ciNow < 0 ? 0.5 : ciNow / 700.0);
                s[idx++] = (ciFut < 0 ? 0.5 : ciFut / 700.0);
            } else {
                s[idx++] = 1.0; s[idx++] = 1.0; s[idx++] = 1.0; // padded
            }
        }
        // cloud CI
        double ciCloud = ciProvider.getCI(-1, now);
        s[idx++] = (ciCloud < 0 ? 0.5 : ciCloud / 700.0);
        // Lyapunov queue (normalized by total budget, clamped)
        if (lyapunov != null) {
            double norm = lyapunov.getQ() / Math.max(lyapunov.getTotalBudget(), 1.0);
            s[idx++] = Math.min(2.0, Math.max(0.0, norm));
        } else {
            s[idx++] = 0.0;
        }
        return s;
    }

    private double edgeXpos(int hostId) {
        return (hostId < edgeX.length) ? edgeX[hostId] : 0;
    }

    private double edgeYpos(int hostId) {
        return (hostId < edgeY.length) ? edgeY[hostId] : 0;
    }
}
