/*
 * Title:        CarbonMobile Baseline - Cloud Only
 *
 * Description:
 * Always offloads to the central cloud. This is the "DC (Best)"
 * counterpart in GreenScale terminology when the cloud has the
 * lowest expected CF (which is rarely true in mobile scenarios).
 */

package edu.boun.edgecloudsim.applications.carbonmobile.orchestrator.baselines;

import edu.boun.edgecloudsim.applications.carbonmobile.carbon.CarbonAccounting;
import edu.boun.edgecloudsim.applications.carbonmobile.logger.CarbonLogger;
import edu.boun.edgecloudsim.applications.carbonmobile.orchestrator.BaseCarbonOrchestrator;
import edu.boun.edgecloudsim.applications.carbonmobile.orchestrator.LyapunovQueueManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_client.Task;

public class CloudOnlyOrchestrator extends BaseCarbonOrchestrator {
    public CloudOnlyOrchestrator(String policy, String simScenario,
                                 CarbonAccounting acc, LyapunovQueueManager q,
                                 CarbonLogger logger) {
        super(policy, simScenario, acc, q, logger);
    }

    @Override
    protected Target pickTarget(Task task) {
        return new Target(SimSettings.CLOUD_DATACENTER_ID, -1);
    }
}
