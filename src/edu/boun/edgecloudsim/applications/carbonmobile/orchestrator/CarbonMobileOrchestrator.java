/*
 * Title:        CarbonMobile - Carbon-Aware Mobility-Driven Edge Orchestrator
 *
 * Description:
 * Main "smart" orchestrator. Scores every candidate target (every edge
 * server + cloud + mobile) with the Lyapunov-augmented objective:
 *
 *     score(target) = (V + Q) * CF_estimate(target) + α * latency_estimate(target)
 *
 * picks the minimum, and dispatches. Setting V=0 and disabling the
 * Lyapunov queue reduces this to a pure carbon-greedy baseline
 * ("CARBON_GREEDY" policy); enabling them gives the Lyapunov-guided
 * variant ("LYAPUNOV" policy). The DRL variant ("DRL") is left for
 * the Python PPO bridge in a follow-up step.
 */

package edu.boun.edgecloudsim.applications.carbonmobile.orchestrator;

import org.cloudbus.cloudsim.core.CloudSim;

import edu.boun.edgecloudsim.applications.carbonmobile.carbon.CarbonAccounting;
import edu.boun.edgecloudsim.applications.carbonmobile.carbon.EnergyModel.Tier;
import edu.boun.edgecloudsim.applications.carbonmobile.logger.CarbonLogger;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_client.Task;
import edu.boun.edgecloudsim.utils.Location;

public class CarbonMobileOrchestrator extends BaseCarbonOrchestrator {

    private final double alphaLatency;

    private int numberOfHost;
    /** Cached XY positions of each edge host, keyed by hostId. */
    private double[] edgeX, edgeY;
    /** Speed-of-light constant for prop-delay (m/s). */
    private static final double C_LIGHT = 3e8;
    /**
     * Optional maximum user--edge distance (metres) for candidate edges,
     * read from -Dcarbonmobile.max_edge_distance_m. Default -1 disables the
     * filter and preserves the original relaxed-reachability behavior
     * bit-for-bit; setting it restricts candidate edges to those within
     * the given distance of the user.
     */
    private static final double MAX_EDGE_DISTANCE_M = Double.parseDouble(
            System.getProperty("carbonmobile.max_edge_distance_m", "-1"));

    public CarbonMobileOrchestrator(String policy, String simScenario,
                                    CarbonAccounting carbonAccounting,
                                    LyapunovQueueManager lyapunov,
                                    CarbonLogger logger,
                                    double alphaLatency, double betaMigration) {
        super(policy, simScenario, carbonAccounting, lyapunov, logger);
        this.alphaLatency = alphaLatency;
        // betaMigration accepted but not yet used (migration support is upcoming)
    }

    @Override
    public void initialize() {
        numberOfHost = SimSettings.getInstance().getNumOfEdgeHosts();
        cacheEdgePositions();
    }

    /**
     * Parse edge_devices.xml once at startup so we can score every candidate
     * target with a *real* distance estimate, not the zero-distance bug
     * that previously made this orchestrator location-blind.
     */
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
        double now = CloudSim.clock();
        Location loc = edu.boun.edgecloudsim.core.SimManager.getInstance()
                .getMobilityModel().getLocation(task.getMobileDeviceId(), now);
        double[] tp = SimSettings.getInstance().getTaskLookUpTable()[task.getTaskType()];
        double utilEdge = tp[9] / 100.0;
        double utilCloud = tp[10] / 100.0;
        double execEdge = task.getCloudletLength()
                / (double) SimSettings.getInstance().getMipsForCloudVM();
        double execCloud = execEdge / 4.0;
        double dataUpKB = tp[5];
        double dataDownKB = tp[6];
        // SLO budget: tp[13] is max_delay_requirement (s); 0 means none.
        double slo = (tp.length >= 14 && tp[13] > 0) ? tp[13] : Double.POSITIVE_INFINITY;

        double weight = (lyapunov != null) ? lyapunov.effectiveCarbonWeight() : 1.0;

        double bestScore = Double.POSITIVE_INFINITY;
        Target bestTarget = new Target(SimSettings.GENERIC_EDGE_DEVICE_ID,
                                       loc.getServingWlanId());

        for (int h = 0; h < numberOfHost; h++) {
            if (!hasEdgeCapacity(task, h)) continue;
            if (MAX_EDGE_DISTANCE_M > 0
                    && distanceToEdge(loc, h) > MAX_EDGE_DISTANCE_M) continue;
            double[] cf = carbonAccounting.computeTaskCF(
                    h, Tier.EDGE, utilEdge, execEdge,
                    dataUpKB, dataDownKB,
                    SimSettings.getInstance().getWlanBandwidth() / 1000.0,
                    1.5, 1, now);
            double latency = estimateEdgeLatency(loc, h, dataUpKB + dataDownKB, execEdge);
            double score = sloAwareScore(weight, cf[3], latency, slo);
            if (score < bestScore) {
                bestScore = score;
                bestTarget = new Target(SimSettings.GENERIC_EDGE_DEVICE_ID, h);
            }
        }

        double[] cfCloud = carbonAccounting.computeTaskCF(
                -1, Tier.CLOUD, utilCloud, execCloud,
                dataUpKB, dataDownKB,
                SimSettings.getInstance().getWanBandwidth() / 1000.0,
                8.0, 4, now);
        double cloudLatency = execCloud
                + SimSettings.getInstance().getWanPropagationDelay() * 2 + 0.05;
        double cloudScore = sloAwareScore(weight, cfCloud[3], cloudLatency, slo);
        if (cloudScore < bestScore) {
            bestTarget = new Target(SimSettings.CLOUD_DATACENTER_ID, -1);
        }

        return bestTarget;
    }

    /**
     * Score a candidate target. Carbon (weighted by V+Q) is the primary
     * objective; latency is treated as a *constraint*, not a continuous
     * cost: it only enters the score if the estimated latency exceeds the
     * task's SLO budget. This matches GreenScale's reward design (Eq.~7
     * of Son et al., IoT-J 2025) and avoids the latency term dominating
     * the carbon term in the greedy variant where V=Q=0.
     */
    private double sloAwareScore(double carbonWeight, double cf,
                                 double latency, double slo) {
        double base = carbonWeight * cf;
        if (latency > slo) {
            // Linear over-budget penalty proportional to alpha so the
            // operator can dial the trade-off via alpha_latency.
            base += alphaLatency * (latency - slo);
        }
        return base;
    }

    /**
     * End-to-end latency estimate for executing the task on edge {@code edgeId}.
     * Uses (i) propagation delay proportional to user--edge Euclidean distance,
     * (ii) WLAN serialisation for in/out data, and (iii) the per-target
     * compute time. This replaces the original constant {@code 0.05} proxy
     * that effectively disabled distance information in the score function.
     */
    private double distanceToEdge(Location userLoc, int edgeId) {
        double dx = userLoc.getXPos() - (edgeId < edgeX.length ? edgeX[edgeId] : 0);
        double dy = userLoc.getYPos() - (edgeId < edgeY.length ? edgeY[edgeId] : 0);
        return Math.sqrt(dx * dx + dy * dy);
    }

    private double estimateEdgeLatency(Location userLoc, int edgeId,
                                       double dataKB, double execSeconds) {
        double dx = userLoc.getXPos() - (edgeId < edgeX.length ? edgeX[edgeId] : 0);
        double dy = userLoc.getYPos() - (edgeId < edgeY.length ? edgeY[edgeId] : 0);
        double distMeters = Math.sqrt(dx * dx + dy * dy);
        // For metropolitan-scale distances (km) propagation latency is sub-ms,
        // but the per-edge LAN/MAN forwarding cost grows linearly with the
        // WLAN cell distance. We model that hop cost as 1us per metre, which
        // matches typical fibre/MAN hop budgets for city-scale edge fabrics.
        double propagation = SimSettings.getInstance().getInternalLanDelay()
                + distMeters / C_LIGHT
                + 1e-6 * distMeters;
        double tx = (dataKB * 8.0 / 1024.0)
                / (SimSettings.getInstance().getWlanBandwidth() / 1000.0);
        return propagation + tx + execSeconds;
    }
}
