/*
 * Title:        CarbonMobile - Mobility Context
 *
 * Description:
 * Lightweight POJO bundling current location, velocity, and predicted
 * future location (with uncertainty) for a mobile device.
 *
 * Used by CarbonMobileOrchestrator as part of its decision state.
 */

package edu.boun.edgecloudsim.applications.carbonmobile.mobility;

import edu.boun.edgecloudsim.utils.Location;

public class MobilityContext {

    private final int deviceId;
    private final double simTime;
    private final Location currentLocation;
    private final double velocityXMps;
    private final double velocityYMps;

    /** Predicted location at simTime + horizon, may be null if no predictor. */
    private Location predictedLocation;
    private double predictedHorizonSec;
    /** Standard deviation in meters of the prediction (Gaussian assumption). */
    private double predictionStdDev;

    public MobilityContext(int deviceId, double simTime, Location currentLocation,
                           double velocityXMps, double velocityYMps) {
        this.deviceId = deviceId;
        this.simTime = simTime;
        this.currentLocation = currentLocation;
        this.velocityXMps = velocityXMps;
        this.velocityYMps = velocityYMps;
    }

    public void setPrediction(Location predicted, double horizonSec, double stdDev) {
        this.predictedLocation = predicted;
        this.predictedHorizonSec = horizonSec;
        this.predictionStdDev = stdDev;
    }

    public int getDeviceId()              { return deviceId; }
    public double getSimTime()             { return simTime; }
    public Location getCurrentLocation()   { return currentLocation; }
    public double getVelocityXMps()        { return velocityXMps; }
    public double getVelocityYMps()        { return velocityYMps; }
    public Location getPredictedLocation() { return predictedLocation; }
    public double getPredictedHorizonSec() { return predictedHorizonSec; }
    public double getPredictionStdDev()    { return predictionStdDev; }

    public double getSpeedMps() {
        return Math.sqrt(velocityXMps * velocityXMps + velocityYMps * velocityYMps);
    }
}
