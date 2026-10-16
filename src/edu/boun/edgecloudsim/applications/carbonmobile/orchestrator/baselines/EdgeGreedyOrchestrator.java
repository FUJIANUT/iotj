/*
 * Title:        CarbonMobile Baseline - Edge-Greedy (Closest Edge)
 *
 * Description:
 * Mobility-aware but carbon-agnostic: always send the task to the edge
 * server closest to the user (i.e., the device's currently associated
 * WLAN). This is the canonical "follow-the-user" baseline used in MEC
 * literature; it minimizes RTT but ignores CI heterogeneity.
 */

package edu.boun.edgecloudsim.applications.carbonmobile.orchestrator.baselines;

import org.cloudbus.cloudsim.core.CloudSim;

import edu.boun.edgecloudsim.applications.carbonmobile.carbon.CarbonAccounting;
import edu.boun.edgecloudsim.applications.carbonmobile.logger.CarbonLogger;
import edu.boun.edgecloudsim.applications.carbonmobile.orchestrator.BaseCarbonOrchestrator;
import edu.boun.edgecloudsim.applications.carbonmobile.orchestrator.LyapunovQueueManager;
import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_client.Task;
import edu.boun.edgecloudsim.utils.Location;

public class EdgeGreedyOrchestrator extends BaseCarbonOrchestrator {
    public EdgeGreedyOrchestrator(String policy, String simScenario,
                                  CarbonAccounting acc, LyapunovQueueManager q,
                                  CarbonLogger logger) {
        super(policy, simScenario, acc, q, logger);
    }

    @Override
    protected Target pickTarget(Task task) {
        Location loc = SimManager.getInstance().getMobilityModel()
                .getLocation(task.getMobileDeviceId(), CloudSim.clock());
        return new Target(SimSettings.GENERIC_EDGE_DEVICE_ID, loc.getServingWlanId());
    }
}
