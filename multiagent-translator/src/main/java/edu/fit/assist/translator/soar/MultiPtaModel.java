package edu.fit.assist.translator.soar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MultiPtaModel {

    // --- L / l-bar : the discrete state variables, their domains, and start values ---

    /** L as a SET of state variables (Block 1). An agent may have several (parallel
     *  flags), exactly one (Train_gate style), or none (a stateless sampler). */
    List<StateVar> stateVars;

    // Legacy single-variable view of L. Still feeds the transition builder's
    // source/target plumbing; retired when Block 4 moves edges onto stateVars.

    /** Name of the location variable, e.g. "state_position" / "state_ctrl-state". */
    String locationVar;

    /** Ordered domain of L. A location's integer code is its index in this list. */
    List<String> locations;

    /** l-bar : initial location (raw value), e.g. "far" / "0". */
    String initialLocation;

    // --- X / inv : clocks and their bounds ---

    /** X : clock names. Empty => clockless PTA (no invariant, no resets). */
    List<String> clocks;

    /** inv : per-clock upper bound (invariant  clock <= bound). The bound is a number for
     *  a resetting clock ("6"), a symbolic expression for an accumulating one
     *  ("state_io_scenario_deadline"), and "1" for a pacing clock. */
    Map<String, String> clockBounds;

    /** The pacing clock, when one was spawned (T6): one SOAR decision cycle = one time unit.
     *  null for an agent that never accumulates time. */
    String pacingClock;

    /** Clocks that run against a threshold instead of being reset by every move. The two kinds
     *  read their guards differently: on a RESETTING clock ">= 6" means "wait until 6", so 6 is
     *  also its upper bound; on an ACCUMULATING clock ">= deadline" means "the deadline already
     *  passed", which bounds nothing. */
    Set<String> accumulatingClocks;

    // --- Act : action labels (the tick operator is already excluded) ---

    List<String> actions;

    // --- enab + prob : one edge per (location, action) ---

    List<PtaTransition> transitions;

    /** Validation notes surfaced during extraction (empty when the tuple is clean). */
    List<String> warnings;

    /** Behavioral role of every variable (Block 0.5), for display/verification:
     *  variable name -> LOCATION | COUNTER | CLOCK | OUTPUT | CONST. */
    Map<String, String> varRoles;

    /** Every name the visitor recorded as a variable. Lets a reader tell a runtime reference
     *  ("state_current_person_id") from a plain word ("transition-mission") without guessing. */
    Set<String> knownVars;

    public MultiPtaModel() {
        this.stateVars = new ArrayList<>();
        this.locations = new ArrayList<>();
        this.clocks = new ArrayList<>();
        this.clockBounds = new LinkedHashMap<>();
        this.actions = new ArrayList<>();
        this.transitions = new ArrayList<>();
        this.warnings = new ArrayList<>();
        this.varRoles = new LinkedHashMap<>();
        this.knownVars = new LinkedHashSet<>();
        this.accumulatingClocks = new LinkedHashSet<>();
    }

    /**
     * What: looks up a state variable by name (null if the name isn't state).
     * Why:  the emitter needs a var's domain to turn a raw value into its integer code.
     * PTA:  L (access to a state variable).
     */
    public StateVar stateVar(String name) {
        for (StateVar sv : stateVars) {
            if (sv.name.equals(name)) return sv;
        }
        return null;
    }

    /** Encoding of L: integer code of a location value = its index in the domain. */
    public int encode(String locationValue) {
        return locations.indexOf(locationValue);
    }

    public void addTransition(PtaTransition t) {
        transitions.add(t);
    }

    // ===================================================================================
    // ===== THE TUPLE, AS A READER SEES IT ==============================================
    // L is the PRODUCT of the state variables, so an agent with six of them has thousands
    // of locations and naming them is hopeless. But the variables are not all the same
    // kind of thing: the SYMBOLIC ones (values are words) shape the machine, the NUMERIC
    // ones (counters, flags) ride along inside it. Projecting onto the symbolic ones gives
    // the handful of places you would actually draw, and the numeric ones stay in the
    // guards where they belong.
    //
    // The split is behavioral, not a name list, and it generalises the old single-variable
    // print rather than replacing it: Train_gate's one symbolic var (state_position) still
    // yields exactly {far, near, in}.
    //
    // Display only — nothing here is read by the emitter.
    // ===================================================================================

    /** The symbolic state vars: values are words, so they name a place. */
    private List<StateVar> controlVars() {
        List<StateVar> out = new ArrayList<>();
        for (StateVar sv : stateVars) if (!sv.numeric) out.add(sv);
        return out;
    }

    /** Index of a variable among the control vars, or -1 if it is data (or a clock). */
    private int controlIndex(List<StateVar> ctrl, String name) {
        for (int i = 0; i < ctrl.size(); i++) if (ctrl.get(i).name.equals(name)) return i;
        return -1;
    }

    /**
     * What: the reachable control assignments, in discovery order (q0 = the initial one).
     * Why:  the full product is mostly unreachable; walking the edges gives the places that
     *       actually exist, which is what a state diagram has boxes for.
     * PTA:  L (a projection of it) + l-bar (q0).
     */
    private List<List<String>> reachableLocations(List<StateVar> ctrl) {
        List<List<String>> out = new ArrayList<>();
        if (ctrl.isEmpty()) {           // no symbolic vars: the agent has one place
            out.add(new ArrayList<>());
            return out;
        }
        List<String> start = new ArrayList<>();
        for (StateVar sv : ctrl) start.add(sv.init == null ? "nil" : sv.init);
        out.add(start);
        for (int i = 0; i < out.size(); i++) {          // list grows while we walk it
            for (PtaTransition t : transitions) {
                if (!enabledAt(t, ctrl, out.get(i))) continue;
                for (List<String> next : targetsFrom(t, ctrl, out.get(i))) {
                    if (!out.contains(next)) out.add(next);
                }
            }
        }
        return out;
    }

    /** True if nothing in this edge's guards contradicts the given control assignment. */
    private boolean enabledAt(PtaTransition t, List<StateVar> ctrl, List<String> at) {
        for (String g : t.guards) {
            String[] tok = g.trim().split("\\s+");
            if (tok.length != 3) continue;
            int i = controlIndex(ctrl, tok[0]);
            if (i < 0) continue;                        // a data guard says nothing about place
            boolean same = tok[2].equals(at.get(i));
            if ("=".equals(tok[1]) && !same) return false;
            if (("<>".equals(tok[1]) || "!=".equals(tok[1])) && same) return false;
        }
        return true;
    }

    /** Where this edge lands from `at` — one target per branch, or one for a certain edge. */
    private List<List<String>> targetsFrom(PtaTransition t, List<StateVar> ctrl, List<String> at) {
        List<List<String>> out = new ArrayList<>();
        if (t.branches.isEmpty()) {
            out.add(afterUpdates(t.updates, ctrl, at));
        } else {
            for (Branch b : t.branches) out.add(afterUpdates(b.updates, ctrl, at));
        }
        return out;
    }

    /** The control assignment after this edge's writes; untouched components carry over. */
    private List<String> afterUpdates(List<String> updates, List<StateVar> ctrl, List<String> at) {
        List<String> next = new ArrayList<>(at);
        for (String u : updates) {
            int eq = u.indexOf('=');
            if (eq < 0) continue;
            int i = controlIndex(ctrl, u.substring(0, eq).trim());
            if (i >= 0) next.set(i, u.substring(eq + 1).trim());
        }
        return next;
    }

    /** "{mission, Waiting}" — a location written as the values of the location variables. */
    private static String tuple(List<String> values) {
        return "{" + String.join(", ", values) + "}";
    }

    /**
     * What: merges several locations into one display tuple, "*" where they disagree.
     * Why:  a rule that does not test a location variable fires from EVERY value of it;
     *       naming one of them would claim a narrower transition than the rule allows.
     * PTA:  display of enab's source set.
     */
    private static List<String> collapse(List<List<String>> group) {
        List<String> out = new ArrayList<>(group.get(0));
        for (List<String> other : group) {
            for (int i = 0; i < out.size(); i++) {
                if (!out.get(i).equals(other.get(i))) out.set(i, "*");
            }
        }
        return out;
    }

    /** Guards that survive the projection: the ones about data, not about place. */
    private List<String> dataGuards(PtaTransition t, List<StateVar> ctrl) {
        List<String> out = new ArrayList<>();
        for (String g : t.guards) {
            String[] tok = g.trim().split("\\s+");
            if (tok.length == 3 && controlIndex(ctrl, tok[0]) >= 0) continue;
            out.add(g);
        }
        return out;
    }

    /** Writes that survive the projection: the control ones are shown by the arrow's target. */
    private List<String> dataUpdates(List<String> updates, List<StateVar> ctrl) {
        List<String> out = new ArrayList<>();
        for (String u : updates) {
            int eq = u.indexOf('=');
            if (eq > 0 && controlIndex(ctrl, u.substring(0, eq).trim()) >= 0) continue;
            out.add(u);
        }
        return out;
    }

    @Override
    public String toString() {
        List<StateVar> ctrl = controlVars();
        List<List<String>> locs = reachableLocations(ctrl);
        final String IND = "             ";     // continuation indent under a "  KEY     : " label
        StringBuilder sb = new StringBuilder("MultiPtaModel\n\n");

        sb.append("  L        : ");
        if (ctrl.isEmpty()) {
            sb.append("NONE (all state is data)\n");
        } else {
            int w = 0;
            for (StateVar sv : ctrl) w = Math.max(w, sv.name.length());
            for (int i = 0; i < ctrl.size(); i++) {
                StateVar sv = ctrl.get(i);
                sb.append(i == 0 ? "" : IND).append(pad(sv.name, w))
                  .append("  ").append(braces(sv.domain)).append("\n");
            }
        }

        List<StateVar> data = new ArrayList<>();
        for (StateVar sv : stateVars) if (sv.numeric) data.add(sv);
        if (!data.isEmpty()) {
            sb.append("\n  dataVars : ");
            for (int i = 0; i < data.size(); i++) {
                sb.append(i == 0 ? "" : IND).append(data.get(i).declaration()).append("\n");
            }
        }

        sb.append("\n  l-bar    : ").append(tuple(locs.get(0))).append("\n");

        sb.append("\n  Act      : ");
        if (actions.isEmpty()) sb.append("NONE\n");
        for (int i = 0; i < actions.size(); i++) {
            sb.append(i == 0 ? "" : IND).append(actions.get(i))
              .append(i < actions.size() - 1 ? "," : "").append("\n");
        }

        sb.append("\n  X        : ");
        if (clocks.isEmpty()) {
            sb.append("NONE (clockless agent)\n");
        } else {
            int w = 0;
            for (String c : clocks) w = Math.max(w, c.length());
            for (int i = 0; i < clocks.size(); i++) {
                String c = clocks.get(i);
                String b = clockBounds.get(c);
                sb.append(i == 0 ? "" : IND).append(pad(c, w))
                  .append(b == null ? "" : "  bound=" + b).append("\n");
            }
        }

        sb.append("\n  transitions :\n");
        for (PtaTransition t : transitions) {
            sb.append("\n    [").append(t.action).append("]\n");
            appendEdges(sb, t, ctrl, locs);
        }

        if (warnings != null && !warnings.isEmpty()) {
            sb.append("\n  ! validation (").append(warnings.size()).append("):\n");
            for (String w : warnings) sb.append("     - ").append(w).append("\n");
        }
        return sb.toString();
    }

    private static String pad(String s, int w) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < w) b.append(' ');
        return b.toString();
    }

    /**
     * What: renders one edge as "src --( enab )--> tgt" plus its resets and data writes.
     * Why:  the arrow form is only readable once the place tests are folded into src/tgt.
     * PTA:  enab + prob for one action.
     */
    private void appendEdges(StringBuilder sb, PtaTransition t,
                             List<StateVar> ctrl, List<List<String>> locs) {
        // every reachable place this edge can leave from
        List<List<String>> sources = new ArrayList<>();
        for (List<String> at : locs) if (enabledAt(t, ctrl, at)) sources.add(at);
        if (sources.isEmpty()) sources.add(locs.get(0));

        List<String> guards = dataGuards(t, ctrl);

        if (t.branches.isEmpty()) {
            // An edge that writes only SOME of the control vars lands somewhere different
            // depending on where it started, so group the sources by where they end up —
            // collapsing them onto one arrow would state a transition that does not exist.
            Map<String, List<List<String>>> byTarget = new LinkedHashMap<>();
            for (List<String> s : sources) {
                String tgt = tuple(afterUpdates(t.updates, ctrl, s));
                byTarget.computeIfAbsent(tgt, k -> new ArrayList<>()).add(s);
            }
            for (Map.Entry<String, List<List<String>>> e : byTarget.entrySet()) {
                // an agent with no location variables has one place, so the arrow says nothing
                if (!ctrl.isEmpty()) {
                    sb.append("        ").append(tuple(collapse(e.getValue())))
                      .append(" --> ").append(e.getKey()).append("\n");
                }
                appendWrites(sb, guards, t.clockUpdates, dataUpdates(t.updates, ctrl));
            }
        } else {
            if (!ctrl.isEmpty()) {
                sb.append("        ").append(tuple(collapse(sources))).append(" -->\n");
            }
            appendWrites(sb, guards, t.clockUpdates, new ArrayList<>());
            for (int i = 0; i < t.branches.size(); i++) {
                Branch b = t.branches.get(i);
                sb.append(i == 0 ? "        prob    = " : "                + ")
                  .append(b.probability).append(" : ");
                if (!ctrl.isEmpty()) {
                    sb.append(tuple(afterUpdates(b.updates, ctrl, sources.get(0)))).append(" ");
                }
                sb.append(braces(dataUpdates(b.updates, ctrl))).append("\n");
            }
        }
    }

    private void appendWrites(StringBuilder sb, List<String> guards,
                              Map<String, String> resets, List<String> updates) {
        if (!guards.isEmpty())  sb.append("        guards  = ").append(braces(guards)).append("\n");
        if (!resets.isEmpty())  sb.append("        resets  = ").append(resets).append("\n");
        if (!updates.isEmpty()) sb.append("        updates = ").append(braces(updates)).append("\n");
    }

    private static String braces(List<String> items) {
        return "{" + String.join(", ", items) + "}";
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
        m.clockBounds.put("train_count", "7");
        m.actions.add("approach"); m.actions.add("enter"); m.actions.add("exit");

        PtaTransition t1 = new PtaTransition("approach");
        t1.guards.add("state_position = far"); t1.guards.add("train_count >= 7");
        t1.updates.add("state_position = near"); t1.clockUpdates.put("train_count", "0");
        PtaTransition t2 = new PtaTransition("enter");
        t2.guards.add("state_position = near"); t2.guards.add("train_count >= 7");
        t2.updates.add("state_position = in"); t2.clockUpdates.put("train_count", "0");
        PtaTransition t3 = new PtaTransition("exit");
        t3.guards.add("state_position = in"); t3.guards.add("train_count >= 7");
        t3.updates.add("state_position = far"); t3.clockUpdates.put("train_count", "0");
        m.addTransition(t1); m.addTransition(t2); m.addTransition(t3);

        System.out.println(m);
    }
    // ---- END TEMP demo ----

    /**
     * One discrete state variable of L (Block 1).
     * ENUM   : domain lists the raw values in order; a value's integer code is its index
     *          ("nil", when reachable, sits at index 0 — absence encodes as 0).
     * NUMERIC: an integer range lo..hi; values are their own codes ("nil" folds to lo).
     */
    static class StateVar {
        String name;
        boolean numeric;
        List<String> domain;    // ENUM only: ordered raw values
        int lo, hi;             // NUMERIC only: the range
        String init;            // raw initial value ("nil", "far", "0", ...)

        StateVar(String name) {
            this.name = name;
            this.domain = new ArrayList<>();
        }

        /**
         * What: gives a raw value's integer code (-1 if it isn't in this var's domain).
         * Why:  PRISM stores state as integers; enum values index the domain, numbers are themselves.
         * PTA:  L (encoding a state value).
         */
        int code(String value) {
            if (numeric) {
                if ("nil".equals(value)) return lo;
                try {
                    int v = Integer.parseInt(value.trim());
                    return (v >= lo && v <= hi) ? v : -1;
                } catch (NumberFormatException e) { return -1; }
            }
            return domain.indexOf(value);
        }

        /**
         * What: builds the PRISM declaration body, e.g. "state_position : [0..2] init 0".
         * Why:  every state variable needs a typed range and start value in the module.
         * PTA:  L + l-bar (the variable's declaration).
         */
        String declaration() {
            int last = numeric ? hi : domain.size() - 1;
            int first = numeric ? lo : 0;
            int initCode = code(init);
            if (initCode < 0) initCode = first;
            return name + " : [" + first + ".." + last + "] init " + initCode;
        }

        @Override
        public String toString() {
            return declaration() + (numeric ? "" : "   " + domain);
        }
    }

    /**
     * One PTA edge = enab (guards) + prob (updates, clock resets).
     * Probability is fixed at 1.0, so it is not stored here (Block 4b adds it).
     *
     * No variable is privileged. An agent is not "in one place": it is the whole tuple of its
     * state variables at once (scanning AND person 7 AND not-yet-tagged). So an edge carries
     * EVERY test its rules make and EVERY write its apply makes, and the emitter turns the
     * whole lot into one PRISM command. The earlier single source/target pair could not
     * express that, and silently dropped any rule that ignored the chosen variable.
     */
    static class PtaTransition {
        /** Action label, e.g. "approach". */
        String action;

        /** enab : every test this edge makes, each as "var op value". Empty = always enabled. */
        List<String> guards;

        /** Clock resets on this edge: clock name -> reset value. Usually "0", but PRISM allows any
         *  integer constant, so a rule that set the timer to a specific integer keeps that value.
         *  Self-increment / arithmetic is time passing, not a reset, and is not stored here. */
        Map<String, String> clockUpdates;

        /** prob : every write this edge makes, each as "var = value". Used when the edge has
         *  one certain outcome — i.e. when `branches` is empty. */
        List<String> updates;

        /** prob : the possible outcomes when this edge is a dice roll (T3). Empty means the
         *  single certain outcome in `updates`; otherwise these replace it and their
         *  probabilities must sum to 1. */
        List<Branch> branches;

        public PtaTransition(String action) {
            this.action = action;
            this.guards = new ArrayList<>();
            this.clockUpdates = new LinkedHashMap<>();
            this.updates = new ArrayList<>();
            this.branches = new ArrayList<>();
        }

        @Override
        public String toString() {
            String g = guards.isEmpty() ? "true" : String.join(" & ", guards);
            String result = branches.isEmpty()
                    ? updates.toString()
                    : branches.size() + " outcomes " + branches;
            return "[" + action + "] " + g + "  ->  " + result
                    + (clockUpdates.isEmpty() ? "" : "   resets=" + clockUpdates);
        }
    }

    /**
     * One outcome of a probabilistic edge: how likely, and what it writes.
     * The probability stays an EXPRESSION ("box_accuracy", "(1-box_accuracy)/19") because a
     * Soar weight is usually written in terms of an input the model checker treats as a
     * constant — evaluating it here would throw away the very knob we want to sweep.
     */
    static class Branch {
        String probability;
        List<String> updates;

        Branch(String probability) {
            this.probability = probability;
            this.updates = new ArrayList<>();
        }

        @Override
        public String toString() {
            return probability + " : " + updates;
        }
    }
}
