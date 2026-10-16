/*
 * Title:        CarbonMobile - Embodied Carbon Model
 *
 * Description:
 * Computes the share of embodied (manufacturing) carbon emissions
 * amortized to a single task execution, following GreenScale (IoT-J 2025):
 *
 *     E_CF_share = E_CF_total * (T_task / LT_device) * (N_used / N_total)
 *
 * where E_CF_total is the device's total embodied carbon (gCO2eq),
 * LT is its lifetime in seconds, N_used / N_total accounts for sharing
 * across co-located applications.
 *
 * Default totals are calibrated against:
 *  - ACT (Architectural Carbon Tool, Wu et al. 2022) for servers
 *  - Pirson & Bol (2021) bottom-up LCA for IoT/mobile devices
 */

package edu.boun.edgecloudsim.applications.carbonmobile.carbon;

import edu.boun.edgecloudsim.applications.carbonmobile.carbon.EnergyModel.Tier;

public class EmbodiedCarbonModel {

    /** Total embodied carbon per device, in gCO2eq. */
    private final double[] eCfTotal = new double[3];

    /** Device lifetime in seconds. */
    private final double[] lifetimeSec = new double[3];

    public EmbodiedCarbonModel() {
        // Defaults from public LCA reports.  JVM property overrides
        // for embodied-carbon sensitivity analysis:
        //   -Dcarbonmobile.embodied_mobile_kg=<x>
        //   -Dcarbonmobile.embodied_edge_kg=<x>
        //   -Dcarbonmobile.embodied_cloud_kg=<x>
        // Pixel 3: ~85 kg CO2eq, lifetime ~3 years.
        eCfTotal[Tier.MOBILE.ordinal()] =
            propOr("carbonmobile.embodied_mobile_kg", 85.0) * 1000.0;
        lifetimeSec[Tier.MOBILE.ordinal()] = 3 * 365 * 24 * 3600.0;

        // Edge DC server (V100 + chassis): ~1500 kg CO2eq, lifetime ~5 years.
        eCfTotal[Tier.EDGE.ordinal()] =
            propOr("carbonmobile.embodied_edge_kg", 1500.0) * 1000.0;
        lifetimeSec[Tier.EDGE.ordinal()] = 5 * 365 * 24 * 3600.0;

        // Hyperscale DC server (8x A100): ~3500 kg CO2eq, lifetime ~5 years.
        eCfTotal[Tier.CLOUD.ordinal()] =
            propOr("carbonmobile.embodied_cloud_kg", 3500.0) * 1000.0;
        lifetimeSec[Tier.CLOUD.ordinal()] = 5 * 365 * 24 * 3600.0;
    }

    private static double propOr(String key, double dflt) {
        String v = System.getProperty(key);
        if (v == null || v.isEmpty()) return dflt;
        try { return Double.parseDouble(v); }
        catch (NumberFormatException e) { return dflt; }
    }

    public void setDevice(Tier tier, double totalGCO2, double lifetimeYears) {
        eCfTotal[tier.ordinal()] = totalGCO2;
        lifetimeSec[tier.ordinal()] = lifetimeYears * 365 * 24 * 3600.0;
    }

    /**
     * Amortized embodied CF for a single task in gCO2eq.
     *
     * @param tier execution tier
     * @param taskExecSeconds task execution time
     * @param sharingDenom number of co-located apps sharing the device
     */
    public double amortizedShare(Tier tier, double taskExecSeconds, int sharingDenom) {
        if (sharingDenom <= 0) sharingDenom = 1;
        double total = eCfTotal[tier.ordinal()];
        double lt = lifetimeSec[tier.ordinal()];
        return total * (taskExecSeconds / lt) / sharingDenom;
    }
}
