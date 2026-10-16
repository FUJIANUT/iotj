/*
 * Title:        CarbonMobile - Scenario Factory
 *
 * Description:
 * Wires together the CarbonMobile components into the EdgeCloudSim
 * ScenarioFactory pattern. Dispatches to the appropriate orchestrator
 * implementation based on the orchestratorPolicy string passed by
 * MainApp's outer policy loop, so a single command-line invocation can
 * sweep across all baselines + our proposed orchestrator.
 *
 * Carbon-aware scenarios reuse EdgeCloudSim's default network model,
 * task generator, and server managers; only the mobility model and
 * orchestrator are replaced.
 */

package edu.boun.edgecloudsim.applications.carbonmobile.config;

import java.io.File;
import java.io.IOException;

import edu.boun.edgecloudsim.applications.carbonmobile.carbon.CarbonAccounting;
import edu.boun.edgecloudsim.applications.carbonmobile.carbon.CarbonIntensityProvider;
import edu.boun.edgecloudsim.applications.carbonmobile.carbon.EmbodiedCarbonModel;
import edu.boun.edgecloudsim.applications.carbonmobile.carbon.EnergyModel;
import edu.boun.edgecloudsim.applications.carbonmobile.logger.CarbonLogger;
import edu.boun.edgecloudsim.applications.carbonmobile.mobility.TraceBasedMobilityModel;
import edu.boun.edgecloudsim.applications.carbonmobile.orchestrator.CarbonMobileOrchestrator;
import edu.boun.edgecloudsim.applications.carbonmobile.orchestrator.DrlOrchestrator;
import edu.boun.edgecloudsim.applications.carbonmobile.orchestrator.LyapunovQueueManager;
import edu.boun.edgecloudsim.applications.carbonmobile.orchestrator.baselines.AutoScaleEnergyOrchestrator;
import edu.boun.edgecloudsim.applications.carbonmobile.orchestrator.baselines.CloudOnlyOrchestrator;
import edu.boun.edgecloudsim.applications.carbonmobile.orchestrator.baselines.EdgeGreedyOrchestrator;
import edu.boun.edgecloudsim.applications.carbonmobile.orchestrator.baselines.GreenScaleMultiEdgeOrchestrator;
import edu.boun.edgecloudsim.applications.carbonmobile.orchestrator.baselines.GreenScaleQTableOrchestrator;
import edu.boun.edgecloudsim.applications.carbonmobile.orchestrator.baselines.MobileOnlyOrchestrator;
import edu.boun.edgecloudsim.applications.carbonmobile.orchestrator.baselines.RandomOrchestrator;
import edu.boun.edgecloudsim.cloud_server.CloudServerManager;
import edu.boun.edgecloudsim.cloud_server.DefaultCloudServerManager;
import edu.boun.edgecloudsim.core.ScenarioFactory;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_client.DefaultMobileDeviceManager;
import edu.boun.edgecloudsim.edge_client.MobileDeviceManager;
import edu.boun.edgecloudsim.edge_client.mobile_processing_unit.DefaultMobileServerManager;
import edu.boun.edgecloudsim.edge_client.mobile_processing_unit.MobileServerManager;
import edu.boun.edgecloudsim.edge_orchestrator.EdgeOrchestrator;
import edu.boun.edgecloudsim.edge_server.DefaultEdgeServerManager;
import edu.boun.edgecloudsim.edge_server.EdgeServerManager;
import edu.boun.edgecloudsim.mobility.MobilityModel;
import edu.boun.edgecloudsim.network.MM1Queue;
import edu.boun.edgecloudsim.network.NetworkModel;
import edu.boun.edgecloudsim.task_generator.IdleActiveLoadGenerator;
import edu.boun.edgecloudsim.task_generator.LoadGeneratorModel;

public class CarbonScenarioFactory implements ScenarioFactory {

    // Region order observed by the paper's runs; the R* entries are the
    // F8 spread-sweep traces written by generate_ci_spread.py.
    private static final String[] REGION_ORDER = {
        "WYOMING", "HYDRO_QUEBEC", "PJM", "CAISO",
        "NORDIC", "NYISO", "ERCOT", "MISO",
        "R8", "R1", "R3", "R2", "R6", "R7", "R5", "R4"
    };

    private final int numOfDevices;
    private final double simulationTime;
    private final String orchestratorPolicy;
    private final String simScenario;
    private final String outputFolder;
    private final String runId;

    private CarbonAccounting carbonAccounting;
    private CarbonIntensityProvider ciProvider;
    private LyapunovQueueManager lyapunov;
    private CarbonLogger logger;

    public CarbonScenarioFactory(int numOfDevices, double simulationTime,
                                 String orchestratorPolicy, String simScenario,
                                 String outputFolder, String runId) {
        this.numOfDevices = numOfDevices;
        this.simulationTime = simulationTime;
        this.orchestratorPolicy = orchestratorPolicy;
        this.simScenario = simScenario;
        this.outputFolder = outputFolder;
        this.runId = runId;
        bootstrap();
    }

    private void bootstrap() {
        CarbonMobileSettings cms = CarbonMobileSettings.getInstance();
        ciProvider = new CarbonIntensityProvider(cms.getEpochStartSeconds());
        loadCarbonTraces(ciProvider, cms.getCarbonTracesDir());

        EnergyModel energy = new EnergyModel();
        EmbodiedCarbonModel emb = new EmbodiedCarbonModel();
        carbonAccounting = new CarbonAccounting(ciProvider, energy, emb);

        // Lyapunov queue is used by both LYAPUNOV (the heuristic) and DRL
        // (so the agent can learn to respect the long-term carbon budget).
        if (orchestratorPolicy.equalsIgnoreCase("LYAPUNOV")
                || orchestratorPolicy.equalsIgnoreCase("DRL")) {
            lyapunov = new LyapunovQueueManager(
                    cms.getTotalCarbonBudgetG(), simulationTime, cms.getLyapunovV());
        }

        logger = new CarbonLogger(outputFolder, runId);
    }

    private void loadCarbonTraces(CarbonIntensityProvider ci, String dir) {
        File traces = new File(dir);
        File[] files = traces.exists()
                ? traces.listFiles((d, name) -> name.endsWith(".csv"))
                : null;
        if (files == null || files.length == 0) return;
        for (File f : files) {
            String regionId = f.getName().replace(".csv", "");
            try {
                ci.loadRegionTrace(regionId, f.getAbsolutePath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        // File.listFiles() order is filesystem-dependent, so the binding is
        // pinned to the order the paper's experiments saw (macOS/APFS):
        // edge e -> REGION_ORDER[e % 8], cloud -> REGION_ORDER[0].
        java.util.List<String> order = java.util.Arrays.asList(REGION_ORDER);
        java.util.Arrays.sort(files, (a, b) -> {
            String ra = a.getName().replace(".csv", "");
            String rb = b.getName().replace(".csv", "");
            int ia = order.indexOf(ra), ib = order.indexOf(rb);
            if (ia < 0) ia = Integer.MAX_VALUE;
            if (ib < 0) ib = Integer.MAX_VALUE;
            return ia != ib ? Integer.compare(ia, ib) : ra.compareTo(rb);
        });
        // Naive binding: distribute edges round-robin across loaded regions.
        int numEdges = SimSettings.getInstance().getNumOfEdgeDatacenters();
        for (int e = 0; e < numEdges; e++) {
            String region = files[e % files.length].getName().replace(".csv", "");
            ci.bindNodeToRegion(e, region);
        }
        // Cloud (id = -1) gets first region by convention.
        ci.bindNodeToRegion(-1, files[0].getName().replace(".csv", ""));
    }

    @Override
    public LoadGeneratorModel getLoadGeneratorModel() {
        return new IdleActiveLoadGenerator(numOfDevices, simulationTime, simScenario);
    }

    @Override
    public EdgeOrchestrator getEdgeOrchestrator() {
        CarbonMobileSettings cms = CarbonMobileSettings.getInstance();
        switch (orchestratorPolicy.toUpperCase()) {
            case "MOBILE_ONLY":
                return new MobileOnlyOrchestrator(orchestratorPolicy, simScenario,
                        carbonAccounting, lyapunov, logger);
            case "CLOUD_ONLY":
                return new CloudOnlyOrchestrator(orchestratorPolicy, simScenario,
                        carbonAccounting, lyapunov, logger);
            case "EDGE_GREEDY":
                return new EdgeGreedyOrchestrator(orchestratorPolicy, simScenario,
                        carbonAccounting, lyapunov, logger);
            case "RANDOM":
                return new RandomOrchestrator(orchestratorPolicy, simScenario,
                        carbonAccounting, lyapunov, logger);
            case "AUTOSCALE_ENERGY":
                return new AutoScaleEnergyOrchestrator(orchestratorPolicy, simScenario,
                        carbonAccounting, lyapunov, logger);
            case "GREENSCALE_QTABLE":
                return new GreenScaleQTableOrchestrator(orchestratorPolicy, simScenario,
                        carbonAccounting, lyapunov, logger, ciProvider);
            case "GREENSCALE_MULTIEDGE":
                return new GreenScaleMultiEdgeOrchestrator(orchestratorPolicy, simScenario,
                        carbonAccounting, lyapunov, logger, ciProvider);
            case "DRL":
                return new DrlOrchestrator(orchestratorPolicy, simScenario,
                        carbonAccounting, lyapunov, logger, ciProvider,
                        cms.getDrlHost(), cms.getDrlPort());
            case "CARBON_GREEDY":
            case "LYAPUNOV":
            default:
                return new CarbonMobileOrchestrator(orchestratorPolicy, simScenario,
                        carbonAccounting, lyapunov, logger,
                        cms.getAlphaLatency(), cms.getBetaMigration());
        }
    }

    @Override
    public MobilityModel getMobilityModel() {
        // The model auto-detects whether to load CSV traces or fall back
        // to synthetic random walk based on CarbonMobileSettings.
        return new TraceBasedMobilityModel(numOfDevices, simulationTime);
    }

    @Override
    public NetworkModel getNetworkModel() {
        return new MM1Queue(numOfDevices, simScenario);
    }

    @Override
    public EdgeServerManager getEdgeServerManager() {
        return new DefaultEdgeServerManager();
    }

    @Override
    public CloudServerManager getCloudServerManager() {
        return new DefaultCloudServerManager();
    }

    @Override
    public MobileServerManager getMobileServerManager() {
        return new DefaultMobileServerManager();
    }

    @Override
    public MobileDeviceManager getMobileDeviceManager() throws Exception {
        return new DefaultMobileDeviceManager();
    }

    public CarbonLogger getLogger()                  { return logger; }
    public LyapunovQueueManager getLyapunov()        { return lyapunov; }
    public CarbonAccounting getCarbonAccounting()    { return carbonAccounting; }
    public CarbonIntensityProvider getCiProvider()   { return ciProvider; }
}
