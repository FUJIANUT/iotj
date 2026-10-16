/*
 * Title:        CarbonMobile - Trajectory Predictor
 *
 * Description:
 * Predicts a device's future location given its recent trajectory.
 *
 * The default implementation (LinearKalmanPredictor) extrapolates
 * position using the most recent velocity estimate, with a Gaussian
 * uncertainty model whose variance grows linearly with horizon.
 *
 * A more sophisticated LSTM-based predictor can be plugged in by
 * implementing this interface.
 */

package edu.boun.edgecloudsim.applications.carbonmobile.mobility;

import edu.boun.edgecloudsim.utils.Location;

public interface TrajectoryPredictor {

    /**
     * Predict location of device at a future time.
     *
     * @param deviceId    target device
     * @param currentTime simulation time now (seconds)
     * @param horizonSec  how far into the future to predict (seconds)
     * @return predicted Location, with the predictionStdDev field populated
     *         in the returned MobilityContext via TraceBasedMobilityModel.
     */
    Location predict(int deviceId, double currentTime, double horizonSec);

    /**
     * Standard deviation (in meters) of the prediction error at the given horizon.
     */
    double getPredictionStdDev(double horizonSec);
}
