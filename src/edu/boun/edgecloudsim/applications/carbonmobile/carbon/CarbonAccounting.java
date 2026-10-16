/*
 * Title:        CarbonMobile - Carbon Accounting
 *
 * Description:
 * Central facade that integrates CarbonIntensityProvider, EnergyModel,
 * and EmbodiedCarbonModel to compute the total carbon footprint of
 * a single task execution, including:
 *
 *   CF = OP_CF_compute + OP_CF_communication + Embodied_CF + Migration_CF
 *
 *   OP_CF_compute  = E_compute (J) / 3.6e6 * CI_target   [gCO2eq]
 *   OP_CF_comm     = sum over network segments
 *   Embodied_CF    = amortized share over device lifetime
 *   Migration_CF   = (data_migrated_KB / BW) * P_link / 3.6e6 * CI_src
 *
 * (1 kWh = 3.6e6 J, so energy_J / 3.6e6 = energy_kWh.)
 */

package edu.boun.edgecloudsim.applications.carbonmobile.carbon;

import edu.boun.edgecloudsim.applications.carbonmobile.carbon.EnergyModel.Tier;

public class CarbonAccounting {

    private static final double J_PER_KWH = 3.6e6;

    /**
     * Optional per-device mobile-power heterogeneity. When the JVM
     * property `carbonmobile.hetero_mobile_multipliers` is set to a comma
     * list (e.g. "0.5,1.0,2.0"), device nodeId is mapped round-robin onto
     * the list and the MOBILE-tier compute energy is scaled by the
     * device's multiplier. Unset (the default) leaves every device at
     * 1.0. The multiplier is applied inside computeOperationalCF so the
     * orchestrator's estimates and the recording path see the same
     * heterogeneous devices.
     */
    private static final double[] HETERO_MOBILE_MULT = parseHeteroMultipliers();

    private static double[] parseHeteroMultipliers() {
        String s = System.getProperty("carbonmobile.hetero_mobile_multipliers");
        if (s == null || s.trim().isEmpty()) return null;
        String[] parts = s.split(",");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Double.parseDouble(parts[i].trim());
        }
        return out;
    }

    private static double mobileHeteroMultiplier(int nodeId) {
        if (HETERO_MOBILE_MULT == null || HETERO_MOBILE_MULT.length == 0) {
            return 1.0;
        }
        return HETERO_MOBILE_MULT[Math.floorMod(nodeId, HETERO_MOBILE_MULT.length)];
    }

    private final CarbonIntensityProvider ciProvider;
    private final EnergyModel energyModel;
    private final EmbodiedCarbonModel embodiedModel;

    public CarbonAccounting(CarbonIntensityProvider ciProvider,
                            EnergyModel energyModel,
                            EmbodiedCarbonModel embodiedModel) {
        this.ciProvider = ciProvider;
        this.energyModel = energyModel;
        this.embodiedModel = embodiedModel;
    }

    /**
     * Compute operational CF for computation only.
     *
     * @return CF in gCO2eq
     */
    public double computeOperationalCF(int nodeId, Tier tier,
                                        double utilization, double execSeconds,
                                        double simTime) {
        double energyJ = energyModel.computeEnergy(tier, utilization, execSeconds);
        if (tier == Tier.MOBILE) {
            energyJ *= mobileHeteroMultiplier(nodeId);
        }
        double ci = ciProvider.getCI(nodeId, simTime);
        if (ci < 0) ci = 400; // fallback default
        return (energyJ / J_PER_KWH) * ci;
    }

    /**
     * Compute operational CF for one network segment.
     *
     * @param dataKB data transferred
     * @param bwMbps link bandwidth
     * @param segmentPower power of the network equipment
     * @param associatedNodeId node whose CI to use (typically the AP/router region)
     */
    public double commCF(double dataKB, double bwMbps, double segmentPower,
                         int associatedNodeId, double simTime) {
        double energyJ = energyModel.commEnergy(dataKB, bwMbps, segmentPower);
        double ci = ciProvider.getCI(associatedNodeId, simTime);
        if (ci < 0) ci = 400;
        return (energyJ / J_PER_KWH) * ci;
    }

    /**
     * Embodied CF amortized to one task.
     */
    public double embodiedCF(Tier tier, double execSeconds, int sharingDenom) {
        return embodiedModel.amortizedShare(tier, execSeconds, sharingDenom);
    }

    /**
     * Migration CF: cost of moving service state from src to dst.
     */
    public double migrationCF(double migratedKB, double bwMbps, double pLink,
                              int srcNodeId, double simTime) {
        if (migratedKB <= 0) return 0;
        return commCF(migratedKB, bwMbps, pLink, srcNodeId, simTime);
    }

    /**
     * Convenience wrapper to compute total CF for a task.
     * Returns a record-like array: [opCompCF, opCommCF, embodiedCF, totalCF].
     */
    public double[] computeTaskCF(int nodeId, Tier tier,
                                  double utilization, double execSeconds,
                                  double dataUpKB, double dataDownKB,
                                  double bwMbps, double linkPower,
                                  int sharingDenom, double simTime) {
        double opComp = computeOperationalCF(nodeId, tier, utilization, execSeconds, simTime);
        double opCommUp = commCF(dataUpKB, bwMbps, linkPower, nodeId, simTime);
        double opCommDown = commCF(dataDownKB, bwMbps, linkPower, nodeId, simTime);
        double opComm = opCommUp + opCommDown;
        double emb = embodiedCF(tier, execSeconds, sharingDenom);
        return new double[] { opComp, opComm, emb, opComp + opComm + emb };
    }

    /**
     * Forecast-side counterpart of computeTaskCF.
     *
     * This is intentionally separate from the accounting path: schedulers can
     * score candidate placements using a noisy CI forecast, while completed
     * task records still use the unperturbed configured CI via computeTaskCF.
     */
    public double[] computeTaskForecastCF(int nodeId, Tier tier,
                                          double utilization, double execSeconds,
                                          double dataUpKB, double dataDownKB,
                                          double bwMbps, double linkPower,
                                          int sharingDenom, double simTime) {
        double ci = ciProvider.getForecastCI(nodeId, simTime);
        if (ci < 0) ci = 400;
        double compEnergyJ = energyModel.computeEnergy(tier, utilization, execSeconds);
        double commUpEnergyJ = energyModel.commEnergy(dataUpKB, bwMbps, linkPower);
        double commDownEnergyJ = energyModel.commEnergy(dataDownKB, bwMbps, linkPower);
        double opComp = (compEnergyJ / J_PER_KWH) * ci;
        double opComm = ((commUpEnergyJ + commDownEnergyJ) / J_PER_KWH) * ci;
        double emb = embodiedCF(tier, execSeconds, sharingDenom);
        return new double[] { opComp, opComm, emb, opComp + opComm + emb };
    }
}
