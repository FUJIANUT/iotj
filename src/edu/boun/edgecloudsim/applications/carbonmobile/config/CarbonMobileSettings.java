/*
 * Title:        CarbonMobile - Settings
 *
 * Description:
 * Centralizes the carbon-aware-specific configuration parameters that
 * are NOT covered by EdgeCloudSim's default SimSettings.
 *
 * Loaded from a properties file, e.g. carbonmobile.properties:
 *   total_carbon_budget_g    = 50000   # gCO2eq for the whole horizon
 *   lyapunov_v               = 100
 *   alpha_latency            = 1.0
 *   beta_migration           = 0.1
 *   prediction_horizon_sec   = 60
 *   carbon_traces_dir        = scripts/carbonmobile/data/carbon
 *   mobility_traces_dir      = scripts/carbonmobile/data/mobility
 *   epoch_start_seconds      = 1735689600   # 2025-01-01 00:00 UTC
 *
 * Most values can be overridden with -Dcarbonmobile.<key>=... so
 * experiment scripts can sweep trace windows without mutating config files.
 */

package edu.boun.edgecloudsim.applications.carbonmobile.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class CarbonMobileSettings {

    private static CarbonMobileSettings instance;

    private double totalCarbonBudgetG;
    private double lyapunovV;
    private double alphaLatency;
    private double betaMigration;
    private double predictionHorizonSec;
    private String carbonTracesDir;
    private String mobilityTracesDir;
    private long epochStartSeconds;
    private String orchestratorMode;
    private String drlHost;
    private int drlPort;
    /** Std-dev (Gaussian, in gCO2/kWh) added to forecast queries. 0 = oracle. */
    private double ciForecastNoiseSigma;

    private CarbonMobileSettings() {}

    public static CarbonMobileSettings getInstance() {
        if (instance == null) instance = new CarbonMobileSettings();
        return instance;
    }

    public boolean initialize(String propertiesFile) {
        try (InputStream in = new FileInputStream(propertiesFile)) {
            Properties p = new Properties();
            p.load(in);
            totalCarbonBudgetG = Double.parseDouble(System.getProperty(
                    "carbonmobile.total_carbon_budget_g",
                    p.getProperty("total_carbon_budget_g", "50000")));
            lyapunovV          = Double.parseDouble(System.getProperty(
                    "carbonmobile.lyapunov_v",
                    p.getProperty("lyapunov_v", "100")));
            alphaLatency       = Double.parseDouble(System.getProperty(
                    "carbonmobile.alpha_latency",
                    p.getProperty("alpha_latency", "1.0")));
            betaMigration      = Double.parseDouble(System.getProperty(
                    "carbonmobile.beta_migration",
                    p.getProperty("beta_migration", "0.1")));
            predictionHorizonSec = Double.parseDouble(System.getProperty(
                    "carbonmobile.prediction_horizon_sec",
                    p.getProperty("prediction_horizon_sec", "60")));
            carbonTracesDir    = System.getProperty(
                    "carbonmobile.carbon_traces_dir",
                    p.getProperty("carbon_traces_dir", "scripts/carbonmobile/data/carbon"));
            mobilityTracesDir  = System.getProperty(
                    "carbonmobile.mobility_traces_dir",
                    p.getProperty("mobility_traces_dir", "scripts/carbonmobile/data/mobility"));
            epochStartSeconds  = Long.parseLong(System.getProperty(
                    "carbonmobile.epoch_start_seconds",
                    p.getProperty("epoch_start_seconds", "1735689600")));
            orchestratorMode   = System.getProperty(
                    "carbonmobile.orchestrator_mode",
                    p.getProperty("orchestrator_mode", "CARBON_GREEDY"));
            drlHost            = System.getProperty(
                    "carbonmobile.drl_host",
                    p.getProperty("drl_host", "127.0.0.1"));
            drlPort            = Integer.parseInt(System.getProperty(
                    "carbonmobile.drl_port",
                    p.getProperty("drl_port", "9999")));
            ciForecastNoiseSigma = Double.parseDouble(System.getProperty(
                    "carbonmobile.ci_noise_sigma",
                    p.getProperty("ci_forecast_noise_sigma", "0.0")));
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public double getTotalCarbonBudgetG()   { return totalCarbonBudgetG; }
    public double getLyapunovV()            { return lyapunovV; }
    public double getAlphaLatency()         { return alphaLatency; }
    public double getBetaMigration()        { return betaMigration; }
    public double getPredictionHorizonSec() { return predictionHorizonSec; }
    public String getCarbonTracesDir()      { return carbonTracesDir; }
    public String getMobilityTracesDir()    { return mobilityTracesDir; }
    public long getEpochStartSeconds()      { return epochStartSeconds; }
    public String getOrchestratorMode()     { return orchestratorMode; }
    public String getDrlHost()              { return drlHost; }
    public int    getDrlPort()              { return drlPort; }
    public double getCiForecastNoiseSigma() { return ciForecastNoiseSigma; }
}
