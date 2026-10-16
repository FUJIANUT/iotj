/*
 * Title:        CarbonMobile - Main Application
 *
 * Description:
 * Entry point for the CarbonMobile experiments. Mirrors the structure
 * of EdgeCloudSim's sample_app1/MainApp but plugs in the carbon-aware
 * scenario factory.
 *
 * Usage (from project root):
 *   java -cp "bin:lib/*" edu.boun.edgecloudsim.applications.carbonmobile.MainApp \
 *        scripts/carbonmobile/config/default_config.properties \
 *        scripts/carbonmobile/config/edge_devices.xml \
 *        scripts/carbonmobile/config/applications.xml \
 *        scripts/carbonmobile/config/carbonmobile.properties \
 *        sim_results/carbonmobile/ite1 1
 */

package edu.boun.edgecloudsim.applications.carbonmobile;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;

import edu.boun.edgecloudsim.applications.carbonmobile.config.CarbonMobileSettings;
import edu.boun.edgecloudsim.applications.carbonmobile.config.CarbonScenarioFactory;
import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.utils.SimLogger;
import edu.boun.edgecloudsim.utils.SimUtils;

public class MainApp {

    public static void main(String[] args) {
        Log.disable();
        SimLogger.enablePrintLog();

        String configFile, edgeDevicesFile, applicationsFile, carbonConfigFile, outputFolder;
        int iterationNumber = 1;

        if (args.length == 6) {
            configFile         = args[0];
            edgeDevicesFile    = args[1];
            applicationsFile   = args[2];
            carbonConfigFile   = args[3];
            outputFolder       = args[4];
            iterationNumber    = Integer.parseInt(args[5]);
        } else {
            SimLogger.printLine("Args missing — using default config paths.");
            configFile        = "scripts/carbonmobile/config/default_config.properties";
            edgeDevicesFile   = "scripts/carbonmobile/config/edge_devices.xml";
            applicationsFile  = "scripts/carbonmobile/config/applications.xml";
            carbonConfigFile  = "scripts/carbonmobile/config/carbonmobile.properties";
            outputFolder      = "sim_results/carbonmobile/ite" + iterationNumber;
        }

        SimSettings SS = SimSettings.getInstance();
        if (!SS.initialize(configFile, edgeDevicesFile, applicationsFile)) {
            SimLogger.printLine("Cannot initialize SimSettings.");
            System.exit(1);
        }
        if (!CarbonMobileSettings.getInstance().initialize(carbonConfigFile)) {
            SimLogger.printLine("Cannot initialize CarbonMobileSettings.");
            System.exit(1);
        }

        if (SS.getFileLoggingEnabled()) {
            SimLogger.enableFileLog();
            SimUtils.cleanOutputFolder(outputFolder);
        }

        DateFormat df = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
        Date startDate = Calendar.getInstance().getTime();
        SimLogger.printLine("CarbonMobile simulation started at " + df.format(startDate));
        SimLogger.printLine("----------------------------------------------------------");

        for (int j = SS.getMinNumOfMobileDev(); j <= SS.getMaxNumOfMobileDev();
             j += SS.getMobileDevCounterSize()) {
            for (String simScenario : SS.getSimulationScenarios()) {
                for (String policy : SS.getOrchestratorPolicies()) {
                    String runId = "CARBON_" + simScenario + "_" + policy + "_" + j + "DEV";
                    SimLogger.printLine("Running: " + runId);
                    SimLogger.getInstance().simStarted(outputFolder, runId);
                    try {
                        // Pin SimUtils.RNG seed so every policy in this
                        // (scenario, devCount, iteration) combination sees the
                        // SAME task-generation random stream. Without this,
                        // task counts vary by ~50% across policies (R2-M5).
                        // Seed is independent of `policy` so all baselines are
                        // compared on identical workloads.
                        long seed = ((long) iterationNumber) * 100003L
                                  + ((long) j) * 31L
                                  + simScenario.hashCode();
                        SimUtils.RNG.setSeed(seed);

                        CloudSim.init(2, Calendar.getInstance(), false, 0.01);
                        CarbonScenarioFactory factory = new CarbonScenarioFactory(
                                j, SS.getSimulationTime(), policy, simScenario,
                                outputFolder, runId);
                        SimManager manager = new SimManager(factory, j, simScenario, policy);
                        manager.startSimulation();

                        // Persist carbon-specific report
                        try {
                            factory.getLogger().flush();
                        } catch (IOException e) {
                            SimLogger.printLine("Could not flush carbon log: " + e.getMessage());
                        }
                        SimLogger.printLine(String.format(
                                "  → tasks=%d, totalCF=%.2f gCO2eq, avgCF=%.4f, qosViol=%.3f",
                                factory.getLogger().numRecords(),
                                factory.getLogger().totalCF(),
                                factory.getLogger().averageCF(),
                                factory.getLogger().qosViolationRate()));
                    } catch (Exception e) {
                        SimLogger.printLine("Simulation failed: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }
        }

        Date endDate = Calendar.getInstance().getTime();
        SimLogger.printLine("CarbonMobile simulation finished at " + df.format(endDate)
                + " (took " + SimUtils.getTimeDifference(startDate, endDate) + ")");
    }
}
