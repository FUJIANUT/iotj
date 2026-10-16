/*
 * Title:        CarbonMobile Baseline - Random
 *
 * Description:
 * Picks uniformly at random among Mobile, every Edge host, and Cloud.
 * Provides a sanity floor: any reasonable orchestrator should beat this.
 */

package edu.boun.edgecloudsim.applications.carbonmobile.orchestrator.baselines;

import java.util.Random;

import edu.boun.edgecloudsim.applications.carbonmobile.carbon.CarbonAccounting;
import edu.boun.edgecloudsim.applications.carbonmobile.logger.CarbonLogger;
import edu.boun.edgecloudsim.applications.carbonmobile.orchestrator.BaseCarbonOrchestrator;
import edu.boun.edgecloudsim.applications.carbonmobile.orchestrator.LyapunovQueueManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_client.Task;

public class RandomOrchestrator extends BaseCarbonOrchestrator {

    private final Random rng;

    public RandomOrchestrator(String policy, String simScenario,
                              CarbonAccounting acc, LyapunovQueueManager q,
                              CarbonLogger logger) {
        super(policy, simScenario, acc, q, logger);
        this.rng = new Random(7);  // fixed seed for reproducibility
    }

    @Override
    protected Target pickTarget(Task task) {
        boolean mobileSupported = SimSettings.getInstance().getMipsForMobileVM() > 0;
        int numEdges = SimSettings.getInstance().getNumOfEdgeHosts();
        int totalChoices = (mobileSupported ? 2 : 1) + numEdges;
        int choice = rng.nextInt(totalChoices);
        int offset = 0;
        if (mobileSupported) {
            if (choice == 0) return new Target(SimSettings.MOBILE_DATACENTER_ID, -1);
            offset = 1;
        }
        if (choice == offset) return new Target(SimSettings.CLOUD_DATACENTER_ID, -1);
        return new Target(SimSettings.GENERIC_EDGE_DEVICE_ID, choice - offset - 1);
    }
}
