package edu.fit.assist.translator.soar;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MultiPtaEmitter {

    // Signals (io_signal / io_command) are how agents talk to each other. A move that
    // sends or reads one becomes a shared label, and the raw signal test is left out.
    private static final String IO_PREFIX = "state_io_";

    /** Write ONE agent as a full PRISM file (the "pta" line + one module). */
    public String emit(String agentName, MultiPtaModel model) {
        return "pta\n\n" + emitModule(agentName, model);
    }

    /**
     * Write ALL agents into one PRISM file: one "pta" line at the top, then one module each.
     * (A file can only have one "pta" line, so we can't just stick full files together.)
     */
    public String emitSystem(Map<String, MultiPtaModel> agents) {
        StringBuilder sb = new StringBuilder();
        sb.append("pta\n\n");
        for (Map.Entry<String, MultiPtaModel> agent : agents.entrySet()) {
            sb.append(emitModule(agent.getKey(), agent.getValue()));
            sb.append("\n");
        }
        return sb.toString();
    }

    /** Write one agent's "module ... endmodule" block. */
    public String emitModule(String agentName, MultiPtaModel model) {
        StringBuilder sb = new StringBuilder();
        sb.append("module ").append(sanitize(agentName)).append("\n\n");

        if (model.locationVar == null || model.locations.isEmpty()) {
            sb.append("endmodule\n"); // no state to describe -> empty module
            return sb.toString();
        }

        sb.append(emitLocationVar(model));
        for (String clock : model.clocks) {
            sb.append("    ").append(clock).append(" : clock;\n");
        }
        sb.append("\n");

        sb.append(emitInvariant(model));

        for (MultiPtaModel.PtaTransition t : model.transitions) {
            sb.append(emitTransition(model, t));
        }

        sb.append("\nendmodule\n");
        return sb.toString();
    }

    // The state variable, e.g. `state_position : [0..2] init 0;`  (0 = the start state).
    private String emitLocationVar(MultiPtaModel model) {
        int last = model.locations.size() - 1;
        int init = model.encode(model.initialLocation);
        if (init < 0) init = 0; // start value missing -> just use 0
        return "    " + model.locationVar + " : [0.." + last + "] init " + init + ";\n";
    }

    // The time limits: "while in this state, the clock can't go past N".
    // Only add a limit for a state that actually waits on the clock. Adding one to a
    // state that doesn't would invent a deadline the agent never had.
    private String emitInvariant(MultiPtaModel model) {
        List<String> clauses = new ArrayList<>();
        for (String loc : model.locations) {
            if (!hasClockGatedExit(model, loc)) continue;
            int code = model.encode(loc);
            for (String clock : model.clocks) {
                clauses.add("(" + model.locationVar + "=" + code
                        + " => " + clock + "<=" + model.clockBound + ")");
            }
        }
        if (clauses.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("    invariant\n");
        for (int i = 0; i < clauses.size(); i++) {
            sb.append("        ").append(clauses.get(i));
            sb.append(i < clauses.size() - 1 ? " &\n" : "\n");
        }
        sb.append("    endinvariant\n\n");
        return sb.toString();
    }

    // True if a move LEAVING this state waits for the clock (has a "clock >= N" check).
    private boolean hasClockGatedExit(MultiPtaModel model, String loc) {
        for (MultiPtaModel.PtaTransition t : model.transitions) {
            if (loc.equals(t.sourceLocation) && guardWaitsOnClock(model, t.guard)) {
                return true;
            }
        }
        return false;
    }

    // True if the check looks like "clock >= N".
    private boolean guardWaitsOnClock(MultiPtaModel model, String guard) {
        for (String conj : splitConjuncts(guard)) {
            String[] tok = conj.split("\\s+");
            if (tok.length >= 2 && model.clocks.contains(tok[0]) && ">=".equals(tok[1])) {
                return true;
            }
        }
        return false;
    }

    // One move, written as one PRISM line:
    // `[label] state=here & <clock check> -> (state'=there) & (clock'=0) & <other changes>;`
    private String emitTransition(MultiPtaModel model, MultiPtaModel.PtaTransition t) {
        int src = model.encode(t.sourceLocation);
        int tgt = model.encode(t.targetLocation);
        if (src < 0 || tgt < 0) {
            return ""; // can't find the states -> skip this move
        }

        // The check: which state we are in, plus any non-signal checks.
        List<String> guardParts = new ArrayList<>();
        guardParts.add(model.locationVar + "=" + src);
        for (String conj : splitConjuncts(t.guard)) {
            if (!referencesIo(conj)) guardParts.add(conj);
        }

        // The result: new state, reset the clocks, and any non-signal changes.
        List<String> updateParts = new ArrayList<>();
        updateParts.add(model.locationVar + "'=" + tgt);
        for (Map.Entry<String, String> e : t.clockUpdates.entrySet()) {
            updateParts.add(e.getKey() + "'=" + e.getValue());
        }
        for (String u : t.updates) {
            if (!referencesIo(u)) updateParts.add(prime(u));
        }

        // The label. If this move sends or reads a signal, use the shared channel name so
        // the two agents fire together. Otherwise just use the move's own name.
        String label = syncLabel(t);
        if (label == null) label = sanitize(t.action);

        return "    [" + label + "] "
                + String.join(" & ", guardParts)
                + " -> "
                + parenJoin(updateParts)
                + ";\n";
    }

    // If this move uses a signal, return its shared channel name; otherwise null.
    // A move that READS a signal has it in the check; one that SENDS a signal has it in the result.
    private String syncLabel(MultiPtaModel.PtaTransition t) {
        for (String conj : splitConjuncts(t.guard)) {   // reads a signal
            if (referencesIo(conj)) return channelName(conj);
        }
        for (String u : t.updates) {                    // sends a signal
            if (referencesIo(u)) return channelName(u);
        }
        return null;
    }

    // Make the channel name from a signal: variable + "_" + value.
    // "state_io_command = lower" -> "state_io_command_lower". The sender and the reader
    // build it the same way, so their labels always match.
    private String channelName(String ioRef) {
        String[] tok = ioRef.trim().split("\\s+");
        String var = tok[0];
        String val = tok[tok.length - 1];
        return sanitize(var + "_" + val);
    }

    // ---- small helpers ----

    // Break a check like "a >= 1 & b = 2" into its parts: ["a >= 1", "b = 2"].
    private List<String> splitConjuncts(String guard) {
        List<String> parts = new ArrayList<>();
        if (guard == null || guard.isEmpty()) return parts;
        for (String p : guard.split("&")) {
            String s = p.trim();
            if (!s.isEmpty()) parts.add(s);
        }
        return parts;
    }

    // Is this check/change about a signal (io_*)? (the variable is the first word)
    private boolean referencesIo(String s) {
        if (s == null) return false;
        String var = s.trim().split("[\\s=]", 2)[0];
        return var.startsWith(IO_PREFIX);
    }

    // Turn "var = value" into PRISM's "var'=value" (the ' means "its new value").
    private String prime(String update) {
        int eq = update.indexOf('=');
        if (eq < 0) return update.trim();
        return update.substring(0, eq).trim() + "'=" + update.substring(eq + 1).trim();
    }

    // Put each change in ( ) and join them with " & ".
    private String parenJoin(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(" & ");
            sb.append("(").append(parts.get(i)).append(")");
        }
        return sb.toString();
    }

    // PRISM names can't have "-", so change it to "_".
    private String sanitize(String name) {
        return (name == null) ? null : name.replace('-', '_');
    }
}
