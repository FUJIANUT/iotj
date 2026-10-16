/*
 * Title:        CarbonMobile - Carbon Logger
 *
 * Description:
 * Records per-task carbon footprint, network/processing breakdown,
 * latency, and migration cost. At the end of the simulation it
 * writes a CSV that downstream Python/MATLAB scripts can analyse.
 *
 * One row per task (when deep logging is enabled), plus one summary
 * row at the end with aggregate statistics.
 */

package edu.boun.edgecloudsim.applications.carbonmobile.logger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class CarbonLogger {

    public static class TaskRecord {
        public final int taskId;
        public final int deviceId;
        public final int targetTier;          // 0=mobile, 1=edge, 2=cloud
        public final int targetNodeId;
        public final double startTimeSec;
        public final double endTimeSec;
        public final double opCompCF;
        public final double opCommCF;
        public final double embodiedCF;
        public final double migrationCF;
        public final double totalCF;
        public final double latencySec;
        public final boolean qosViolated;
        public final double queueQ;            // virtual queue value at decision time

        public TaskRecord(int taskId, int deviceId, int targetTier, int targetNodeId,
                          double startTimeSec, double endTimeSec,
                          double opCompCF, double opCommCF, double embodiedCF,
                          double migrationCF, double latencySec, boolean qosViolated,
                          double queueQ) {
            this.taskId = taskId;
            this.deviceId = deviceId;
            this.targetTier = targetTier;
            this.targetNodeId = targetNodeId;
            this.startTimeSec = startTimeSec;
            this.endTimeSec = endTimeSec;
            this.opCompCF = opCompCF;
            this.opCommCF = opCommCF;
            this.embodiedCF = embodiedCF;
            this.migrationCF = migrationCF;
            this.totalCF = opCompCF + opCommCF + embodiedCF + migrationCF;
            this.latencySec = latencySec;
            this.qosViolated = qosViolated;
            this.queueQ = queueQ;
        }
    }

    private final List<TaskRecord> records = new ArrayList<>();
    private final String outputFolder;
    private final String runId;

    public CarbonLogger(String outputFolder, String runId) {
        this.outputFolder = outputFolder;
        this.runId = runId;
    }

    public void recordTask(TaskRecord r) {
        records.add(r);
    }

    public TaskRecord lastRecord() {
        return records.isEmpty() ? null : records.get(records.size() - 1);
    }

    public int numRecords() { return records.size(); }

    public double totalCF() {
        return records.stream().mapToDouble(r -> r.totalCF).sum();
    }

    public double averageCF() {
        return records.isEmpty() ? 0 : totalCF() / records.size();
    }

    public double qosViolationRate() {
        long viol = records.stream().filter(r -> r.qosViolated).count();
        return records.isEmpty() ? 0 : (double) viol / records.size();
    }

    /** Write per-task CSV + summary. */
    public void flush() throws IOException {
        File dir = new File(outputFolder);
        if (!dir.exists()) dir.mkdirs();

        File csv = new File(dir, runId + "_TASKS.csv");
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(csv))) {
            bw.write("task_id,device_id,target_tier,target_node,start_s,end_s,"
                    + "op_comp_cf,op_comm_cf,embodied_cf,migration_cf,total_cf,"
                    + "latency_s,qos_violated,Q\n");
            for (TaskRecord r : records) {
                bw.write(String.format("%d,%d,%d,%d,%.3f,%.3f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%.4f%n",
                        r.taskId, r.deviceId, r.targetTier, r.targetNodeId,
                        r.startTimeSec, r.endTimeSec,
                        r.opCompCF, r.opCommCF, r.embodiedCF, r.migrationCF, r.totalCF,
                        r.latencySec, r.qosViolated ? 1 : 0, r.queueQ));
            }
        }

        File summary = new File(dir, runId + "_SUMMARY.txt");
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(summary))) {
            bw.write("CarbonMobile run summary\n");
            bw.write("Run ID:               " + runId + "\n");
            bw.write("Total tasks logged:   " + records.size() + "\n");
            bw.write(String.format("Total CF (gCO2eq):    %.4f%n", totalCF()));
            bw.write(String.format("Average CF/task:      %.6f%n", averageCF()));
            bw.write(String.format("QoS violation rate:   %.4f%n", qosViolationRate()));
        }
    }
}
