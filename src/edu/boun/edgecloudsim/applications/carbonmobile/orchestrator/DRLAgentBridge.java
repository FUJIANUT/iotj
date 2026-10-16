/*
 * Title:        CarbonMobile - DRL Agent Bridge
 *
 * Description:
 * Minimal newline-delimited-JSON socket client that talks to the
 * Python PPO server (see scripts/carbonmobile/drl/carbon_drl_server.py).
 *
 * The server is expected to be running before any Java MainApp run.
 * This class is intentionally dependency-free (only stdlib JSON via
 * org.json is *not* used — we hand-roll JSON encoding for simplicity
 * since our messages are tiny and well-typed).
 */

package edu.boun.edgecloudsim.applications.carbonmobile.orchestrator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class DRLAgentBridge implements AutoCloseable {

    private final Socket sock;
    private final PrintWriter out;
    private final BufferedReader in;

    public DRLAgentBridge(String host, int port) throws IOException {
        this.sock = new Socket(host, port);
        this.out = new PrintWriter(sock.getOutputStream(), false);
        this.in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
    }

    /** Announce setup; must be called once at the start of an episode. */
    public void hello(int nEdges, int stateDim) throws IOException {
        StringBuilder sb = new StringBuilder("{\"type\":\"hello\",\"n_edges\":");
        sb.append(nEdges).append(",\"state_dim\":").append(stateDim).append("}");
        sendLine(sb.toString());
        readLine(); // ack
    }

    /**
     * Request an action for the given state. Blocks until the server
     * returns a response.
     *
     * @return integer action 0..nEdges (cloud is the last index)
     */
    public int decide(int taskId, double[] state) throws IOException {
        StringBuilder sb = new StringBuilder("{\"type\":\"decide\",\"task_id\":");
        sb.append(taskId).append(",\"state\":").append(arrayToJson(state)).append("}");
        sendLine(sb.toString());
        String resp = readLine();
        // Look for "action":<int>
        int idx = resp.indexOf("\"action\"");
        if (idx < 0) throw new IOException("Bad action response: " + resp);
        int colon = resp.indexOf(':', idx);
        int end = resp.indexOf(',', colon);
        if (end < 0) end = resp.indexOf('}', colon);
        return Integer.parseInt(resp.substring(colon + 1, end).trim());
    }

    /** Send a reward observation; fire-and-forget (still reads ack). */
    public void reward(int taskId, double reward, double[] nextState, boolean done) throws IOException {
        StringBuilder sb = new StringBuilder("{\"type\":\"reward\",\"task_id\":");
        sb.append(taskId).append(",\"reward\":").append(reward);
        if (nextState != null) sb.append(",\"next_state\":").append(arrayToJson(nextState));
        sb.append(",\"done\":").append(done).append("}");
        sendLine(sb.toString());
        readLine(); // ack
    }

    /** Tell the server an episode has ended (triggers PPO update + checkpoint). */
    public void episodeEnd() throws IOException {
        sendLine("{\"type\":\"episode_end\"}");
        readLine();
    }

    @Override
    public void close() {
        try {
            in.close();
            out.close();
            sock.close();
        } catch (IOException ignore) {}
    }

    // ---------- helpers ----------

    private void sendLine(String s) {
        out.print(s);
        out.print('\n');
        out.flush();
    }

    private String readLine() throws IOException {
        String line = in.readLine();
        if (line == null) throw new IOException("Server closed connection.");
        return line;
    }

    private static String arrayToJson(double[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            // Compact representation; avoid scientific notation when possible
            sb.append(String.format("%.5g", a[i]));
        }
        sb.append("]");
        return sb.toString();
    }
}
