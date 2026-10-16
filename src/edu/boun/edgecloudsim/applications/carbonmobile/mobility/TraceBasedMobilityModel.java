/*
 * Title:        CarbonMobile - Trace-Based Mobility Model
 *
 * Description:
 * Mobility model that loads real GPS trajectories from CSV/GeoLife
 * files. Supports two formats:
 *   1) Simple CSV: deviceId,timestampSec,x,y    (preferred for simulation)
 *   2) GeoLife .plt: lat,lon,_,alt,daysSince1899,date,time
 *
 * Also supports synthetic trace generation for smoke-testing when no
 * real data is available.
 *
 * Each device's trajectory is stored as TreeMap<simTime, (x,y)>.
 * On getLocation(), the model returns a Location whose servingWlanId
 * is set to the nearest edge server (by Euclidean distance to the
 * edge servers configured in edge_devices.xml).
 */

package edu.boun.edgecloudsim.applications.carbonmobile.mobility;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Random;
import java.util.TreeMap;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.mobility.MobilityModel;
import edu.boun.edgecloudsim.utils.Location;
import edu.boun.edgecloudsim.utils.SimLogger;

public class TraceBasedMobilityModel extends MobilityModel implements TrajectoryPredictor {

    /** Per-device trajectory: time → (x, y). */
    private List<TreeMap<Double, double[]>> trajectories;

    /** Edge server positions: edge_id → (x, y). */
    private List<double[]> edgePositions;

    /** Place type index per edge (kept consistent with EdgeCloudSim semantics). */
    private List<Integer> edgePlaceTypes;

    /** Linear prediction error growth: stdDev = baseStd + k * horizon */
    private double predictionBaseStdMeters = 5.0;
    private double predictionStdGrowthPerSec = 0.5;
    private final boolean requireCsvTraces;

    public TraceBasedMobilityModel(int numberOfMobileDevices, double simulationTime) {
        this(numberOfMobileDevices, simulationTime, false);
    }

    public TraceBasedMobilityModel(int numberOfMobileDevices, double simulationTime,
                                   boolean requireCsvTraces) {
        super(numberOfMobileDevices, simulationTime);
        this.requireCsvTraces = requireCsvTraces;
    }

    /**
     * Initialize the model.
     *
     * If CarbonMobileSettings points to a non-empty directory of
     * device_<i>.csv files, those real traces are loaded. Otherwise the
     * model falls back to per-device synthetic random walks (useful for
     * smoke tests).
     */
    @Override
    public void initialize() {
        loadEdgePositionsFromXml();
        trajectories = new ArrayList<>();

        // If carbonmobile.mobility_speed_mps is set non-default, force the
        // synthetic random-walk path so the speed override actually takes
        // effect (real CSV traces have fixed time stamps and cannot be
        // re-paced post-hoc).
        boolean forceSynthetic = System.getProperty(
                "carbonmobile.mobility_speed_mps") != null;

        // Try to load real traces first (unless overridden by speed config).
        try {
            if (forceSynthetic) {
                throw new IllegalStateException(
                        "mobility_speed_mps override → using synthetic mobility");
            }
            String dir = edu.boun.edgecloudsim.applications.carbonmobile.config
                    .CarbonMobileSettings.getInstance().getMobilityTracesDir();
            java.io.File traces = new java.io.File(dir);
            String[] names = traces.isDirectory()
                    ? traces.list((d, n) -> n.startsWith("device_") && n.endsWith(".csv"))
                    : null;
            if (names != null && names.length > 0) {
                loadCsvTraces(dir);
                edu.boun.edgecloudsim.utils.SimLogger.printLine(
                        "[CarbonMobile] Loaded "
                        + numberOfMobileDevices + " mobility traces from " + dir);
                return;
            }
        } catch (Exception e) {
            if (requireCsvTraces) {
                throw new IllegalStateException(
                        "Required CSV mobility traces could not be loaded", e);
            }
            edu.boun.edgecloudsim.utils.SimLogger.printLine(
                    "[CarbonMobile] Failed to load CSV mobility traces — falling back to synthetic: "
                    + e.getMessage());
        }

        // Synthetic fallback: random walk in a 50km × 50km city to match our edge layout.
        Random rng = new Random(42);
        double areaSize = 50_000.0;
        for (int i = 0; i < numberOfMobileDevices; i++) {
            TreeMap<Double, double[]> tm = new TreeMap<>();
            double x = rng.nextDouble() * areaSize;
            double y = rng.nextDouble() * areaSize;
            tm.put(SimSettings.CLIENT_ACTIVITY_START_TIME, new double[] { x, y });
            double t = SimSettings.CLIENT_ACTIVITY_START_TIME;
            // Walk speed default; override via -Dcarbonmobile.mobility_speed_mps=<v>
            double speedMps = Double.parseDouble(
                    System.getProperty("carbonmobile.mobility_speed_mps", "1.4"));
            while (t < simulationTime) {
                t += 60.0;
                double angle = rng.nextDouble() * 2 * Math.PI;
                x = clamp(x + speedMps * 60 * Math.cos(angle), 0, areaSize);
                y = clamp(y + speedMps * 60 * Math.sin(angle), 0, areaSize);
                tm.put(t, new double[] { x, y });
            }
            trajectories.add(tm);
        }
    }

    /**
     * Load real trajectories from a directory of CSVs.
     * Each CSV file represents one device, lines: timestamp,x,y
     * Files should be named device_0.csv, device_1.csv, ...
     */
    public void loadCsvTraces(String directory) throws IOException {
        loadEdgePositionsFromXml();
        trajectories = new ArrayList<>();
        for (int i = 0; i < numberOfMobileDevices; i++) {
            File f = new File(directory, "device_" + i + ".csv");
            if (!f.exists()) {
                if (requireCsvTraces) {
                    throw new IOException("Required trace file missing: " + f);
                }
                SimLogger.printLine("Warning: trace file missing: " + f.getName()
                        + " — using synthetic for device " + i);
                trajectories.add(syntheticForDevice(i));
                continue;
            }
            TreeMap<Double, double[]> tm = new TreeMap<>();
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line = br.readLine(); // header
                while ((line = br.readLine()) != null) {
                    String[] p = line.split(",");
                    double t = Double.parseDouble(p[0].trim());
                    double x = Double.parseDouble(p[1].trim());
                    double y = Double.parseDouble(p[2].trim());
                    tm.put(t, new double[] { x, y });
                }
            }
            trajectories.add(tm);
        }
    }

    private TreeMap<Double, double[]> syntheticForDevice(int seed) {
        Random rng = new Random(seed);
        TreeMap<Double, double[]> tm = new TreeMap<>();
        double x = rng.nextDouble() * 10_000;
        double y = rng.nextDouble() * 10_000;
        tm.put(SimSettings.CLIENT_ACTIVITY_START_TIME, new double[] { x, y });
        double t = SimSettings.CLIENT_ACTIVITY_START_TIME;
        while (t < simulationTime) {
            t += 60.0;
            double angle = rng.nextDouble() * 2 * Math.PI;
            x = clamp(x + 1.4 * 60 * Math.cos(angle), 0, 10_000);
            y = clamp(y + 1.4 * 60 * Math.sin(angle), 0, 10_000);
            tm.put(t, new double[] { x, y });
        }
        return tm;
    }

    private void loadEdgePositionsFromXml() {
        edgePositions = new ArrayList<>();
        edgePlaceTypes = new ArrayList<>();
        Document doc = SimSettings.getInstance().getEdgeDevicesDocument();
        NodeList datacenterList = doc.getElementsByTagName("datacenter");
        for (int i = 0; i < datacenterList.getLength(); i++) {
            Node dc = datacenterList.item(i);
            Element loc = (Element) ((Element) dc).getElementsByTagName("location").item(0);
            int x = Integer.parseInt(loc.getElementsByTagName("x_pos").item(0).getTextContent());
            int y = Integer.parseInt(loc.getElementsByTagName("y_pos").item(0).getTextContent());
            int pt = Integer.parseInt(loc.getElementsByTagName("attractiveness").item(0).getTextContent());
            edgePositions.add(new double[] { x, y });
            edgePlaceTypes.add(pt);
        }
    }

    private double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Returns interpolated (x,y) of device at given simulation time. */
    private double[] interpolate(int deviceId, double time) {
        TreeMap<Double, double[]> tm = trajectories.get(deviceId);
        Entry<Double, double[]> floor = tm.floorEntry(time);
        Entry<Double, double[]> ceil = tm.ceilingEntry(time);
        if (floor == null) return tm.firstEntry().getValue();
        if (ceil == null || floor.getKey().equals(ceil.getKey())) return floor.getValue();
        double t0 = floor.getKey(), t1 = ceil.getKey();
        double frac = (time - t0) / (t1 - t0);
        double[] p0 = floor.getValue(), p1 = ceil.getValue();
        return new double[] { p0[0] + frac * (p1[0] - p0[0]),
                              p0[1] + frac * (p1[1] - p0[1]) };
    }

    /** Find the nearest edge server to (x, y). */
    private int nearestEdgeId(double x, double y) {
        int bestId = 0;
        double bestDist = Double.POSITIVE_INFINITY;
        for (int i = 0; i < edgePositions.size(); i++) {
            double[] p = edgePositions.get(i);
            double dx = p[0] - x, dy = p[1] - y;
            double d = dx * dx + dy * dy;
            if (d < bestDist) { bestDist = d; bestId = i; }
        }
        return bestId;
    }

    @Override
    public Location getLocation(int deviceId, double time) {
        double[] pos = interpolate(deviceId, time);
        int edgeId = nearestEdgeId(pos[0], pos[1]);
        int placeType = edgePlaceTypes.get(edgeId);
        return new Location(placeType, edgeId, (int) pos[0], (int) pos[1]);
    }

    /**
     * Estimate instantaneous velocity (m/s) using a 10-second backward window.
     */
    public double[] getVelocity(int deviceId, double time) {
        double dt = 10.0;
        if (time - dt < SimSettings.CLIENT_ACTIVITY_START_TIME) return new double[] { 0, 0 };
        double[] now = interpolate(deviceId, time);
        double[] prev = interpolate(deviceId, time - dt);
        return new double[] { (now[0] - prev[0]) / dt, (now[1] - prev[1]) / dt };
    }

    public MobilityContext getContext(int deviceId, double time, double predictionHorizon) {
        Location current = getLocation(deviceId, time);
        double[] v = getVelocity(deviceId, time);
        MobilityContext ctx = new MobilityContext(deviceId, time, current, v[0], v[1]);
        Location pred = predict(deviceId, time, predictionHorizon);
        ctx.setPrediction(pred, predictionHorizon, getPredictionStdDev(predictionHorizon));
        return ctx;
    }

    // ---------- TrajectoryPredictor implementation ----------

    @Override
    public Location predict(int deviceId, double currentTime, double horizonSec) {
        // Linear extrapolation using current velocity
        double[] now = interpolate(deviceId, currentTime);
        double[] v = getVelocity(deviceId, currentTime);
        double xPred = now[0] + v[0] * horizonSec;
        double yPred = now[1] + v[1] * horizonSec;
        int edgeId = nearestEdgeId(xPred, yPred);
        int placeType = edgePlaceTypes.get(edgeId);
        return new Location(placeType, edgeId, (int) xPred, (int) yPred);
    }

    @Override
    public double getPredictionStdDev(double horizonSec) {
        return predictionBaseStdMeters + predictionStdGrowthPerSec * horizonSec;
    }
}
