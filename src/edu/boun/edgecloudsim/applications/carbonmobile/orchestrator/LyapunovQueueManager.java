/*
 * Title:        CarbonMobile - Lyapunov Virtual Queue Manager
 *
 * Description:
 * Implements the virtual carbon queue used by the drift-plus-penalty
 * (DPP) framework of Neely (2010) to convert a long-term carbon-budget
 * constraint into per-slot stability targets:
 *
 *     Q(t+1) = max( Q(t) + sum_i CF_i(t) - b(t),  0 )
 *
 * where b(t) = B_total / T_horizon is the average per-slot budget.
 *
 * The DPP weight V trades off short-term cost vs. long-term constraint
 * compliance: larger V → lower average cost but bigger queue (Neely 2010,
 * Theorem 4.5: O(1/V) optimality, O(V) queue bound).
 */

package edu.boun.edgecloudsim.applications.carbonmobile.orchestrator;

public class LyapunovQueueManager {

    /** Current virtual queue backlog Q(t) in gCO2eq. */
    private double Q;

    /** Average budget per second (gCO2eq/s). */
    private final double bRate;

    /** DPP weight V. */
    private final double V;

    /** Cumulative carbon emitted so far (for monitoring). */
    private double cumulativeCF;

    /** Total carbon budget for the entire horizon (gCO2eq). */
    private final double totalBudget;

    /** Last simulation time when queue was updated. */
    private double lastUpdateTime;

    public LyapunovQueueManager(double totalBudget, double horizonSeconds, double V) {
        this.totalBudget = totalBudget;
        this.bRate = totalBudget / horizonSeconds;
        this.V = V;
        this.Q = 0;
        this.cumulativeCF = 0;
        this.lastUpdateTime = 0;
    }

    /**
     * Update the virtual queue after observing emissions emittedCF
     * accumulated since the previous update. The elapsed time since the
     * previous update is used to compute the budget deduction.
     */
    public void update(double simTime, double emittedCF) {
        double elapsed = Math.max(simTime - lastUpdateTime, 0);
        double budgetEarned = bRate * elapsed;
        Q = Math.max(Q + emittedCF - budgetEarned, 0);
        cumulativeCF += emittedCF;
        lastUpdateTime = simTime;
    }

    /**
     * Reset the queue (call between simulation runs).
     */
    public void reset() {
        Q = 0;
        cumulativeCF = 0;
        lastUpdateTime = 0;
    }

    /**
     * Effective per-task carbon weight: (V + Q).
     * The DPP-augmented per-slot decision minimizes
     *   (V + Q) * CF_i + cost_i
     * so that high queue backlog (i.e. budget overspent) penalizes
     * carbon-heavy choices more strongly.
     */
    public double effectiveCarbonWeight() {
        return V + Q;
    }

    public double getQ()             { return Q; }
    public double getV()             { return V; }
    public double getCumulativeCF()  { return cumulativeCF; }
    public double getTotalBudget()   { return totalBudget; }
    public double getBudgetUsageRatio() {
        return totalBudget == 0 ? 0 : cumulativeCF / totalBudget;
    }
}
