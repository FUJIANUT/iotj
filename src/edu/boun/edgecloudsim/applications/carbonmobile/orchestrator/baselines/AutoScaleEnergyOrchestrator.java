/*
 * Title:        CarbonMobile Baseline - AutoScale (Energy-Greedy)
 *
 * Description:
 * A simplified re-implementation of the AutoScale energy-aware
 * scheduler (Kim & Wu, MICRO 2020) used as the SOTA "non-carbon"
 * baseline in GreenScale.
 *
 * It picks the target with the lowest *expected operational energy*,
 * ignoring carbon intensity. Useful to demonstrate that energy-optimal
 * is NOT carbon-optimal — exactly the takeaway #1 from GreenScale that
 * we want to confirm and extend.
 */

package edu.boun.edgecloudsim.applications.carbonmobile.orchestrator.baselines;

import org.cloudbus.cloudsim.core.CloudSim;

import edu.boun.edgecloudsim.applications.carbonmobile.carbon.CarbonAccounting;
import edu.boun.edgecloudsim.applications.carbonmobile.carbon.EnergyModel;
import edu.boun.edgecloudsim.applications.carbonmobile.carbon.EnergyModel.Tier;
import edu.boun.edgecloudsim.applications.carbonmobile.logger.CarbonLogger;
import edu.boun.edgecloudsim.applications.carbonmobile.orchestrator.BaseCarbonOrchestrator;
import edu.boun.edgecloudsim.applications.carbonmobile.orchestrator.LyapunovQueueManager;
import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_client.Task;
import edu.boun.edgecloudsim.utils.Location;

public class AutoScaleEnergyOrchestrator extends BaseCarbonOrchestrator {

    private final EnergyModel energyModel = new EnergyModel();

    public AutoScaleEnergyOrchestrator(String policy, String simScenario,
                                       CarbonAccounting acc, LyapunovQueueManager q,
                                       CarbonLogger logger) {
        super(policy, simScenario, acc, q, logger);
    }

    @Override
    protected Target pickTarget(Task task) {
        double[] tp = SimSettings.getInstance().getTaskLookUpTable()[task.getTaskType()];
        double dataUpKB   = tp[5];
        double dataDownKB = tp[6];

        double execMobile = (SimSettings.getInstance().getMipsForMobileVM() > 0)
                ? task.getCloudletLength() / (double) SimSettings.getInstance().getMipsForMobileVM()
                : task.getCloudletLength() / 5000.0;
        double execEdge  = task.getCloudletLength()
                / (double) SimSettings.getInstance().getMipsForCloudVM();
        double execCloud = execEdge / 4.0;

        double bwWlanMbps = SimSettings.getInstance().getWlanBandwidth() / 1000.0;
        double bwWanMbps  = SimSettings.getInstance().getWanBandwidth() / 1000.0;

        boolean mobileSupported = SimSettings.getInstance().getMipsForMobileVM() > 0;
        double eEdge   = energyModel.computeEnergy(Tier.EDGE,   tp[9] / 100.0, execEdge)
                       + energyModel.commEnergy(dataUpKB, bwWlanMbps, energyModel.getWlanPower())
                       + energyModel.commEnergy(dataDownKB, bwWlanMbps, energyModel.getWlanPower());
        double eCloud  = energyModel.computeEnergy(Tier.CLOUD,  tp[10] / 100.0, execCloud)
                       + energyModel.commEnergy(dataUpKB, bwWanMbps, energyModel.getWanPower())
                       + energyModel.commEnergy(dataDownKB, bwWanMbps, energyModel.getWanPower());

        Location loc = SimManager.getInstance().getMobilityModel()
                .getLocation(task.getMobileDeviceId(), CloudSim.clock());
        Target best = new Target(SimSettings.GENERIC_EDGE_DEVICE_ID, loc.getServingWlanId());
        double bestE = eEdge;

        if (mobileSupported) {
            double eMobile = energyModel.computeEnergy(Tier.MOBILE, tp[11] / 100.0, execMobile);
            if (eMobile < bestE) {
                best = new Target(SimSettings.MOBILE_DATACENTER_ID, -1);
                bestE = eMobile;
            }
        }
        if (eCloud < bestE) {
            best = new Target(SimSettings.CLOUD_DATACENTER_ID, -1);
        }
        return best;
    }
}
