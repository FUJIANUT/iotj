/*
 * Title:        CarbonMobile - Energy Model
 *
 * Description:
 * Computes operational energy (Joules) for a task at a given execution
 * target, plus communication energy across edge/core network.
 *
 * Computation energy uses a linear power model:
 *   P(u) = P_idle + (P_max - P_idle) * u
 * with utilization u ∈ [0,1], integrated over execution time T:
 *   E_comp = P_avg * T
 *
 * Communication energy is data_size_bits / bandwidth_bps * P_link,
 * applied separately to mobile-WLAN, edge-MAN, and core-WAN segments.
 *
 * Reference: linear power model is widely used in DC literature
 * (e.g., GreenScale [Son et al. IoT-J 2025] uses a frequency- and
 * utilization-based power model).
 */

package edu.boun.edgecloudsim.applications.carbonmobile.carbon;

public class EnergyModel {

    /** Tier types for parameterizing power constants. */
    public enum Tier { MOBILE, EDGE, CLOUD }

    /** Idle / max power in Watts for each tier (representative values). */
    private final double[] pIdle = new double[3];
    private final double[] pMax = new double[3];

    /** Network link power in Watts (fixed during transmission). */
    private double pWlan = 1.5;
    private double pMan = 5.0;
    private double pWan = 8.0;

    public EnergyModel() {
        // Defaults from public hardware specs (can be overridden via setters,
        // or via -Dcarbonmobile.{edge,cloud}_{idle,max}_w system properties
        // for sensitivity experiments; absent properties keep the defaults).
        // Mobile (Pixel 3 SoC): ~0.5W idle, ~3.5W max under load.
        pIdle[Tier.MOBILE.ordinal()] = 0.5;
        pMax[Tier.MOBILE.ordinal()] = 3.5;

        // Edge DC (single V100 server, ~250W idle, 1000W full load).
        pIdle[Tier.EDGE.ordinal()] = Double.parseDouble(
                System.getProperty("carbonmobile.edge_idle_w", "250"));
        pMax[Tier.EDGE.ordinal()] = Double.parseDouble(
                System.getProperty("carbonmobile.edge_max_w", "1000"));

        // Hyperscale DC (8x A100 server amortized to single VM, ~80W idle, 400W max).
        pIdle[Tier.CLOUD.ordinal()] = Double.parseDouble(
                System.getProperty("carbonmobile.cloud_idle_w", "80"));
        pMax[Tier.CLOUD.ordinal()] = Double.parseDouble(
                System.getProperty("carbonmobile.cloud_max_w", "400"));
    }

    public void setPower(Tier tier, double idleW, double maxW) {
        pIdle[tier.ordinal()] = idleW;
        pMax[tier.ordinal()] = maxW;
    }

    public void setNetworkPower(double pWlan, double pMan, double pWan) {
        this.pWlan = pWlan;
        this.pMan = pMan;
        this.pWan = pWan;
    }

    /**
     * Compute computation energy in Joules.
     *
     * @param tier execution tier
     * @param utilization 0..1
     * @param execSeconds execution time in seconds
     */
    public double computeEnergy(Tier tier, double utilization, double execSeconds) {
        double pAvg = pIdle[tier.ordinal()]
                + (pMax[tier.ordinal()] - pIdle[tier.ordinal()]) * utilization;
        return pAvg * execSeconds;
    }

    /**
     * Communication energy on a single network segment.
     *
     * @param dataKB data size in KB
     * @param bandwidthMbps link bandwidth in Mbps
     * @param segmentPower power consumed by the network equipment in W
     */
    public double commEnergy(double dataKB, double bandwidthMbps, double segmentPower) {
        double dataMb = dataKB * 8.0 / 1024.0; // KB → Mb
        double seconds = dataMb / Math.max(bandwidthMbps, 1e-6);
        return seconds * segmentPower;
    }

    public double getWlanPower() { return pWlan; }
    public double getManPower() { return pMan; }
    public double getWanPower() { return pWan; }
}
