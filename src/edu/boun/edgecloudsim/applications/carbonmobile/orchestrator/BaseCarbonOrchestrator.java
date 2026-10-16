/*
 * Title:        CarbonMobile - Base Carbon-Aware Orchestrator
 *
 * Description:
 * Common scaffolding shared by all CarbonMobile orchestrators (greedy,
 * Lyapunov, DRL, and all baselines). Subclasses only implement
 * pickTarget(task), and this base class:
 *   - estimates per-task CF (computation + communication + embodied)
 *   - records the result via CarbonLogger
 *   - updates the Lyapunov virtual queue (if provided)
 *   - hands the actual VM selection to least-loaded fit
 *
 * The aim is a fair, apples-to-apples comparison across baselines:
 * every baseline produces tasks, every baseline emits the same CSV
 * schema, only the target-selection policy differs.
 */

package edu.boun.edgecloudsim.applications.carbonmobile.orchestrator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;

import edu.boun.edgecloudsim.applications.carbonmobile.carbon.CarbonAccounting;
import edu.boun.edgecloudsim.applications.carbonmobile.carbon.EnergyModel.Tier;
import edu.boun.edgecloudsim.applications.carbonmobile.logger.CarbonLogger;
import edu.boun.edgecloudsim.cloud_server.CloudVM;
import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_client.CpuUtilizationModel_Custom;
import edu.boun.edgecloudsim.edge_client.Task;
import edu.boun.edgecloudsim.edge_orchestrator.EdgeOrchestrator;
import edu.boun.edgecloudsim.edge_server.EdgeVM;

public abstract class BaseCarbonOrchestrator extends EdgeOrchestrator {

    /** Target descriptor returned by pickTarget(). */
    public static class Target {
        public final int deviceTypeId;  // SimSettings.GENERIC_EDGE_DEVICE_ID, CLOUD_DATACENTER_ID, MOBILE_DATACENTER_ID
        public final int hostId;        // 0..N-1 for edges; -1 otherwise
        public Target(int deviceTypeId, int hostId) {
            this.deviceTypeId = deviceTypeId;
            this.hostId = hostId;
        }
    }

    protected final CarbonAccounting carbonAccounting;
    protected final LyapunovQueueManager lyapunov;   // may be null
    protected final CarbonLogger logger;

    protected final Map<Integer, Integer> taskToHostId = new HashMap<>();
    /** Tracks each user's *current* edge assignment so we can charge a
     *  migration CF whenever the orchestrator switches them to a different
     *  edge.  -1 means "no prior assignment / executed on cloud or mobile". */
    protected final Map<Integer, Integer> userCurrentEdge = new HashMap<>();
    /** Per-session state size (KB) carried during a migration.  Defaults to
     *  the realistic AR/VR figure of 5\,MB; overridable via the JVM property
     *  `-Dcarbonmobile.migration_kb=N` for sensitivity analysis. */
    protected static final double MIGRATION_STATE_KB =
            Double.parseDouble(System.getProperty("carbonmobile.migration_kb", "5000"));
    private int recordCounter = 0;

    public BaseCarbonOrchestrator(String policy, String simScenario,
                                  CarbonAccounting carbonAccounting,
                                  LyapunovQueueManager lyapunov,
                                  CarbonLogger logger) {
        super(policy, simScenario);
        this.carbonAccounting = carbonAccounting;
        this.lyapunov = lyapunov;
        this.logger = logger;
    }

    /** Subclasses implement the per-task placement decision. */
    protected abstract Target pickTarget(Task task);

    @Override
    public void initialize() { /* default no-op; subclass may override */ }

    @Override
    public int getDeviceToOffload(Task task) {
        Target t = pickTarget(task);
        if (t.deviceTypeId == SimSettings.GENERIC_EDGE_DEVICE_ID) {
            taskToHostId.put(task.getCloudletId(), t.hostId);
        }
        recordChosenCF(task, t);
        return t.deviceTypeId;
    }

    @Override
    public Vm getVmToOffload(Task task, int deviceId) {
        if (deviceId == SimSettings.CLOUD_DATACENTER_ID) {
            return pickLeastLoadedCloudVm(task);
        }
        if (deviceId == SimSettings.MOBILE_DATACENTER_ID) {
            // Mobile execution: try mobile VM if MobileServerManager is non-default
            List<? extends Vm> vms = SimManager.getInstance()
                    .getMobileServerManager().getVmList(task.getMobileDeviceId());
            return (vms == null || vms.isEmpty()) ? null : vms.get(0);
        }
        Integer hostId = taskToHostId.remove(task.getCloudletId());
        if (hostId == null) {
            hostId = SimManager.getInstance().getMobilityModel()
                    .getLocation(task.getMobileDeviceId(), CloudSim.clock())
                    .getServingWlanId();
        }
        return pickLeastLoadedEdgeVm(task, hostId);
    }

    // ---------- shared CF estimation & recording ----------

    protected void recordChosenCF(Task task, Target t) {
        double now = CloudSim.clock();
        double[] taskProps = SimSettings.getInstance().getTaskLookUpTable()[task.getTaskType()];
        double dataUpKB   = taskProps[5];
        double dataDownKB = taskProps[6];

        double[] cf;
        int tier;
        int nodeId;
        double execSeconds;
        if (t.deviceTypeId == SimSettings.CLOUD_DATACENTER_ID) {
            tier = 2;
            nodeId = -1;
            double utilCloud = taskProps[10] / 100.0;
            execSeconds = task.getCloudletLength()
                    / (double) SimSettings.getInstance().getMipsForCloudVM();
            cf = carbonAccounting.computeTaskCF(
                    -1, Tier.CLOUD, utilCloud, execSeconds,
                    dataUpKB, dataDownKB,
                    SimSettings.getInstance().getWanBandwidth() / 1000.0,
                    8.0, 4, now);
        } else if (t.deviceTypeId == SimSettings.MOBILE_DATACENTER_ID) {
            tier = 0;
            nodeId = task.getMobileDeviceId();
            double utilMobile = taskProps[11] / 100.0;
            int mips = SimSettings.getInstance().getMipsForMobileVM();
            execSeconds = (mips > 0)
                    ? task.getCloudletLength() / (double) mips
                    : task.getCloudletLength() / 5000.0;
            cf = carbonAccounting.computeTaskCF(
                    nodeId, Tier.MOBILE, utilMobile, execSeconds,
                    0, 0, /* no over-the-air for local exec */
                    SimSettings.getInstance().getWlanBandwidth() / 1000.0,
                    1.5, 1, now);
        } else {
            tier = 1;
            nodeId = t.hostId;
            double utilEdge = taskProps[9] / 100.0;
            int mipsCloud = SimSettings.getInstance().getMipsForCloudVM();
            execSeconds = task.getCloudletLength() / (double) mipsCloud;
            cf = carbonAccounting.computeTaskCF(
                    nodeId, Tier.EDGE, utilEdge, execSeconds,
                    dataUpKB, dataDownKB,
                    SimSettings.getInstance().getWlanBandwidth() / 1000.0,
                    1.5, 1, now);
        }

        // Migration cost: if the user was already executing on a *different*
        // edge, account for the carbon overhead of moving session state.
        double migrationCF = 0.0;
        int devId = task.getMobileDeviceId();
        Integer prevEdge = userCurrentEdge.get(devId);
        if (t.deviceTypeId == SimSettings.GENERIC_EDGE_DEVICE_ID) {
            if (prevEdge != null && prevEdge >= 0 && prevEdge != t.hostId) {
                double bwManMbps = SimSettings.getInstance().getManBandwidth() / 1000.0;
                if (bwManMbps <= 0) bwManMbps = 50; // realistic inter-edge link
                // Higher link power than the per-flow estimate to reflect the
                // active state (router fans, transceivers) during migration.
                migrationCF = carbonAccounting.migrationCF(
                        MIGRATION_STATE_KB, bwManMbps,
                        /* link power */ 12.0, prevEdge, now);
            }
            userCurrentEdge.put(devId, t.hostId);
        } else {
            userCurrentEdge.put(devId, -1);
        }

        double q = (lyapunov != null) ? lyapunov.getQ() : 0.0;
        double totalWithMig = cf[3] + migrationCF;
        if (lyapunov != null) {
            lyapunov.update(now, totalWithMig);
        }
        // Compute the orchestrator-side latency estimate (network + compute)
        // and compare to the task's max_delay_requirement for an honest
        // per-app SLO miss flag. The look-up table column 13 is the SLO in
        // seconds; column 5/6 are upload/download in KB.
        double[] tpForSlo = SimSettings.getInstance().getTaskLookUpTable()[task.getTaskType()];
        double slo = (tpForSlo.length >= 14 && tpForSlo[13] > 0) ? tpForSlo[13] : Double.POSITIVE_INFINITY;
        double netLat = 0.0;
        if (t.deviceTypeId == SimSettings.GENERIC_EDGE_DEVICE_ID) {
            double bwMbps = SimSettings.getInstance().getWlanBandwidth() / 1000.0;
            netLat = ((tpForSlo[5] + tpForSlo[6]) * 8.0 / 1024.0) / Math.max(bwMbps, 1e-6);
        } else if (t.deviceTypeId == SimSettings.CLOUD_DATACENTER_ID) {
            double bwMbps = SimSettings.getInstance().getWanBandwidth() / 1000.0;
            netLat = ((tpForSlo[5] + tpForSlo[6]) * 8.0 / 1024.0) / Math.max(bwMbps, 1e-6)
                   + 2.0 * SimSettings.getInstance().getWanPropagationDelay();
        }
        double e2eLat = execSeconds + netLat;
        boolean qosViolated = e2eLat > slo;
        logger.recordTask(new CarbonLogger.TaskRecord(
                ++recordCounter, devId, tier, nodeId,
                now, now + execSeconds,
                cf[0], cf[1], cf[2], migrationCF, e2eLat, qosViolated, q));
    }

    // ---------- VM selection helpers (shared) ----------

    protected boolean hasEdgeCapacity(Task task, int hostId) {
        List<EdgeVM> vms = SimManager.getInstance().getEdgeServerManager().getVmList(hostId);
        if (vms == null || vms.isEmpty()) return false;
        double required = ((CpuUtilizationModel_Custom) task.getUtilizationModelCpu())
                .predictUtilization(vms.get(0).getVmType());
        for (EdgeVM vm : vms) {
            double avail = 100.0 - vm.getCloudletScheduler().getTotalUtilizationOfCpu(CloudSim.clock());
            if (avail >= required) return true;
        }
        return false;
    }

    protected EdgeVM pickLeastLoadedEdgeVm(Task task, int hostId) {
        List<EdgeVM> vms = SimManager.getInstance().getEdgeServerManager().getVmList(hostId);
        EdgeVM chosen = null;
        double bestAvail = -1;
        double required = ((CpuUtilizationModel_Custom) task.getUtilizationModelCpu())
                .predictUtilization(vms.get(0).getVmType());
        for (EdgeVM vm : vms) {
            double avail = 100.0 - vm.getCloudletScheduler().getTotalUtilizationOfCpu(CloudSim.clock());
            if (avail >= required && avail > bestAvail) {
                bestAvail = avail;
                chosen = vm;
            }
        }
        return chosen;
    }

    protected Vm pickLeastLoadedCloudVm(Task task) {
        Vm chosen = null;
        double bestAvail = -1;
        List<Host> list = SimManager.getInstance().getCloudServerManager()
                .getDatacenter().getHostList();
        for (int hi = 0; hi < list.size(); hi++) {
            List<CloudVM> vms = SimManager.getInstance().getCloudServerManager().getVmList(hi);
            for (CloudVM vm : vms) {
                double required = ((CpuUtilizationModel_Custom) task.getUtilizationModelCpu())
                        .predictUtilization(vm.getVmType());
                double avail = 100.0 - vm.getCloudletScheduler().getTotalUtilizationOfCpu(CloudSim.clock());
                if (avail >= required && avail > bestAvail) {
                    bestAvail = avail;
                    chosen = vm;
                }
            }
        }
        return chosen;
    }

    @Override public void processEvent(SimEvent ev) {}
    @Override public void startEntity()             {}
    @Override public void shutdownEntity()          {}
}
