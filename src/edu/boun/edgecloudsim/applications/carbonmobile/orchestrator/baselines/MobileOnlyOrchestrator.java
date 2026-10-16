/*
 * Title:        CarbonMobile Baseline - Mobile Only
 *
 * Description:
 * Always executes the task on the originating mobile device. Lower-bound
 * baseline: no offloading, hence no network/data-center carbon, but
 * potentially long execution times and high mobile-device energy.
 */

package edu.boun.edgecloudsim.applications.carbonmobile.orchestrator.baselines;

import edu.boun.edgecloudsim.applications.carbonmobile.carbon.CarbonAccounting;
import edu.boun.edgecloudsim.applications.carbonmobile.logger.CarbonLogger;
import edu.boun.edgecloudsim.applications.carbonmobile.orchestrator.BaseCarbonOrchestrator;
import edu.boun.edgecloudsim.applications.carbonmobile.orchestrator.LyapunovQueueManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_client.Task;

public class MobileOnlyOrchestrator extends BaseCarbonOrchestrator {
    public MobileOnlyOrchestrator(String policy, String simScenario,
                                  CarbonAccounting acc, LyapunovQueueManager q,
                                  CarbonLogger logger) {
        super(policy, simScenario, acc, q, logger);
    }

    @Override
    protected Target pickTarget(Task task) {
        return new Target(SimSettings.MOBILE_DATACENTER_ID, -1);
    }
}
