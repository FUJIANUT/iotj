/*
 * Title:        CarbonMobile - Carbon Intensity Provider
 *
 * Description:
 * Provides time- and location-dependent carbon intensity (CI) values
 * for edge servers, cloud datacenters, and mobile devices.
 *
 * Data source: configured CSV profiles.
 * One CSV per named region with hourly resolution:
 *   timestamp,gCO2eq_per_kWh
 *
 * Each edge server / datacenter is mapped to a region by the scenario
 * factory.
 */

package edu.boun.edgecloudsim.applications.carbonmobile.carbon;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CarbonIntensityProvider {

    /** region-id → list of (epoch_seconds, gCO2eq_per_kWh) sorted by time */
    private final Map<String, List<double[]>> traceByRegion = new HashMap<>();

    /** edge/cloud node-id → region-id */
    private final Map<Integer, String> nodeToRegion = new HashMap<>();

    /** simulation epoch_start in seconds (so simulation time t maps to wall clock) */
    private final long epochStartSeconds;

    public CarbonIntensityProvider(long epochStartSeconds) {
        this.epochStartSeconds = epochStartSeconds;
    }

    /**
     * Load a CSV trace for one power grid region. Lines starting with
     * {@code #} are treated as comments (used by our CI generator to record
     * the profile provenance); the first non-comment line is treated as
     * the column header and skipped.
     */
    public void loadRegionTrace(String regionId, String csvPath) throws IOException {
        List<double[]> series = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
            String line;
            boolean headerSkipped = false;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                if (!headerSkipped) { headerSkipped = true; continue; }
                String[] parts = t.split(",");
                long ts = Long.parseLong(parts[0].trim());
                double ci = Double.parseDouble(parts[1].trim());
                series.add(new double[] { ts, ci });
            }
        }
        traceByRegion.put(regionId, series);
    }

    /** Bind a simulation node (edge/cloud) to a power-grid region. */
    public void bindNodeToRegion(int nodeId, String regionId) {
        nodeToRegion.put(nodeId, regionId);
    }

    /**
     * Get carbon intensity (gCO2eq/kWh) for a node at simulation time t.
     * Linear interpolation between hourly samples.
     *
     * @param nodeId edge or cloud node id
     * @param simTimeSeconds simulation time in seconds (relative to start)
     * @return CI in gCO2eq/kWh, or -1 if no data
     */
    public double getCI(int nodeId, double simTimeSeconds) {
        String region = nodeToRegion.get(nodeId);
        if (region == null) return -1;
        List<double[]> series = traceByRegion.get(region);
        if (series == null || series.isEmpty()) return -1;

        long wallTime = epochStartSeconds + (long) simTimeSeconds;

        // binary search for surrounding samples
        int lo = 0, hi = series.size() - 1;
        if (wallTime <= series.get(0)[0]) return series.get(0)[1];
        if (wallTime >= series.get(hi)[0]) return series.get(hi)[1];
        while (hi - lo > 1) {
            int mid = (lo + hi) / 2;
            if (series.get(mid)[0] <= wallTime) lo = mid;
            else hi = mid;
        }
        double t0 = series.get(lo)[0], t1 = series.get(hi)[0];
        double c0 = series.get(lo)[1], c1 = series.get(hi)[1];
        double frac = (wallTime - t0) / (t1 - t0);
        return c0 + frac * (c1 - c0);
    }

    /**
     * Get a noisy carbon-intensity forecast for a node at simulation time t.
     *
     * The underlying trace value remains unchanged and is still used by
     * accounting paths that call {@link #getCI}. This method is meant for
     * scheduler-side candidate scoring when running forecast-error
     * sensitivity experiments.
     */
    public double getForecastCI(int nodeId, double simTimeSeconds) {
        double ci = getCI(nodeId, simTimeSeconds);
        double sigma = edu.boun.edgecloudsim.applications.carbonmobile.config
                .CarbonMobileSettings.getInstance().getCiForecastNoiseSigma();
        if (sigma > 0 && ci > 0) {
            ci += sigma * forecastRng.nextGaussian();
            if (ci < 1.0) ci = 1.0;
        }
        return ci;
    }

    /**
     * Average CI over the next horizonSeconds, used as a simple forecast.
     *
     * If CarbonMobileSettings.getCiForecastNoiseSigma() is positive, the
     * returned value is perturbed with i.i.d. Gaussian noise of that
     * standard deviation (in gCO2/kWh), simulating realistic Electricity
     * Maps / WattTime forecast errors. Used by the F2 robustness ablation.
     */
    public double getAverageCI(int nodeId, double simTimeSeconds, double horizonSeconds) {
        int samples = 12;
        double sum = 0;
        for (int i = 0; i < samples; i++) {
            sum += getCI(nodeId, simTimeSeconds + i * horizonSeconds / samples);
        }
        double avg = sum / samples;

        double sigma = edu.boun.edgecloudsim.applications.carbonmobile.config
                .CarbonMobileSettings.getInstance().getCiForecastNoiseSigma();
        if (sigma > 0 && avg > 0) {
            avg += sigma * forecastRng.nextGaussian();
            if (avg < 1.0) avg = 1.0;
        }
        return avg;
    }

    /** Dedicated RNG so noise is reproducible and independent of other randomness. */
    private final java.util.Random forecastRng = new java.util.Random(11);
}
