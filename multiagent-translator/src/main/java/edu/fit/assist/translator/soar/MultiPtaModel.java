package edu.fit.assist.translator.soar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MultiPtaModel {

    // --- L / l-bar : the single location variable, its value domain, and start value ---

    /** Name of the location variable, e.g. "state_position" / "state_ctrl-state". */
    String locationVar;

    /** Ordered domain of L. A location's integer code is its index in this list. */
    List<String> locations;

    /** l-bar : initial location (raw value), e.g. "far" / "0". */
    String initialLocation;

    // --- X / inv : clocks and the invariant bound ---

    /** X : clock names. Empty => clockless PTA (no invariant, no resets). */
    List<String> clocks;

    /** inv : clock upper bound N (invariant  clock <= N). null when clockless. */
    String clockBound;

    // --- Act : action labels (the tick operator is already excluded) ---

    List<String> actions;

    // --- enab + prob : one edge per (location, action) ---

    List<PtaTransition> transitions;

    /** Validation notes surfaced during extraction (empty when the tuple is clean). */
    List<String> warnings;

    public MultiPtaModel() {
        this.locations = new ArrayList<>();
        this.clocks = new ArrayList<>();
        this.actions = new ArrayList<>();
        this.transitions = new ArrayList<>();
        this.warnings = new ArrayList<>();
    }

    /** Encoding of L: integer code of a location value = its index in the domain. */
    public int encode(String locationValue) {
        return locations.indexOf(locationValue);
    }

    public void addTransition(PtaTransition t) {
        transitions.add(t);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("MultiPtaModel\n");
        sb.append("  L (locations): ").append(locations).append("   [var=").append(locationVar).append("]\n");
        sb.append("  l-bar (init) : ").append(initialLocation).append("   [init rule]\n");
        sb.append("  clock       : ").append(clocks).append("   [bound=").append(clockBound).append("]\n");
        sb.append("  Act          : ").append(actions).append("   [operator names]\n");
        sb.append("  transitions  :   [propose + apply]\n");
        for (PtaTransition t : transitions) {
            sb.append("    ").append(t).append("\n");
        }
        if (warnings != null && !warnings.isEmpty()) {
            sb.append("  ! validation (").append(warnings.size()).append("):\n");
            for (String w : warnings) {
                sb.append("     - ").append(w).append("\n");
            }
        }
        return sb.toString();
    }

    // ---- TEMP demo: hand-filled train tuple to visualize a populated MultiPtaModel. ----
    // Hardcoded ONLY for this demo. P2 (MultiPtaExtractor) fills the model generically
    // from SoarRules for ANY file. Delete this main() once the extractor exists.
    public static void main(String[] args) {
        MultiPtaModel m = new MultiPtaModel();
        m.locationVar = "state_position";
        m.locations.add("far"); m.locations.add("near"); m.locations.add("in");
        m.initialLocation = "far";
        m.clocks.add("train_count");
        m.clockBound = "7";
        m.actions.add("approach"); m.actions.add("enter"); m.actions.add("exit");

        PtaTransition t1 = new PtaTransition("far", "approach");
        t1.guard = "train_count >= 7"; t1.targetLocation = "near"; t1.clockUpdates.put("train_count", "0");
        PtaTransition t2 = new PtaTransition("near", "enter");
        t2.guard = "train_count >= 7"; t2.targetLocation = "in"; t2.clockUpdates.put("train_count", "0");
        PtaTransition t3 = new PtaTransition("in", "exit");
        t3.guard = "train_count >= 7"; t3.targetLocation = "far"; t3.clockUpdates.put("train_count", "0");
        m.addTransition(t1); m.addTransition(t2); m.addTransition(t3);

        System.out.println(m);
    }
    // ---- END TEMP demo ----

    /**
     * One PTA edge = enab (guard) + prob (target, clock resets, extra updates).
     * Probability is fixed at 1.0, so it is not stored here.
     */
    static class PtaTransition {
        /** Value of L this edge leaves, e.g. "far" / "0". */
        String sourceLocation;

        /** Action label, e.g. "approach". */
        String action;

        /** enab : clock/signal condition, minus the location test. May be "" (always enabled). */
        String guard;

        /** Value of L this edge enters, e.g. "near" / "1". */
        String targetLocation;

        /** Clock resets on this edge: clock name -> reset value. Usually "0", but PRISM allows any
         *  integer constant, so a rule that set the timer to a specific integer keeps that value.
         *  Self-increment / arithmetic is time passing, not a reset, and is not stored here. */
        Map<String, String> clockUpdates;

        /** Extra assignments beyond the location, e.g. "state_io_command = lower". */
        List<String> updates;

        public PtaTransition(String sourceLocation, String action) {
            this.sourceLocation = sourceLocation;
            this.action = action;
            this.clockUpdates = new LinkedHashMap<>();
            this.updates = new ArrayList<>();
        }

        @Override
        public String toString() {
            String g = (guard == null || guard.isEmpty()) ? "true" : guard;
            return "[" + action + "] " + sourceLocation + " --(" + g + ")--> " + targetLocation
                    + "   resets=" + clockUpdates + " updates=" + updates;
        }
    }
}
