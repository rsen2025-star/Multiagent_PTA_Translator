package edu.fit.assist.translator.soar;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

// Turns one SOAR agent into a PTA (a state machine with timers). It fills the 8 parts of
// the PTA one by one:  P = (L, l-bar, X, Act, inv, enab, prob, L).

public class MultiPtaExtractor {

    // Special SOAR words (built into the language, not our own domain).
    private static final String OP_NAME_VAR = "state_operator_name";
    private static final String SUPERSTATE_PREFIX = "state_superstate";
    // Signals to/from other agents (SOAR's input-link / output-link). A move whose GUARD
    // reads one is the agent RECEIVING input; a move whose result writes one is it SENDING.
    private static final String IO_PREFIX = "state_io_";

    // Name of the fake timer we add when an agent has no real one.
    private static final String SYNTH_CLOCK = "t";

    private static final Set<String> ARITH_OPS =
            new LinkedHashSet<>(java.util.Arrays.asList("+", "-", "*", "/"));

    private final SoarRules rules;

    // ---- shared results: each section fills these, later sections read them ----
    private MultiPtaModel model;
    private Map<String, Rule> proposeByOp;              // move name -> its propose rule
    private Map<String, Rule> applyByOp;                // move name -> its apply rule
    private final Set<String> clocks = new LinkedHashSet<>();   // X: the timer variables
    private final Set<String> tickOps = new LinkedHashSet<>();  // operators that only tick a clock
    private String locationVar;                         // the state variable (L)
    private String synthClock;                          // set only if the agent has no real timer

    public MultiPtaExtractor(SoarRules rules) {
        this.rules = rules;
    }

    // The pipeline. Runs each section in order (see the class note on why the order is fixed).
    public MultiPtaModel extract() {
        model = new MultiPtaModel();
        splitRulesByOperator();   // sort rules into propose vs apply
        detectClocks();           // X: the timers
        detectTickOps();          // find clock-only operators (not real moves)
        detectLocations();        // L + start location
        detectActions();          // Act: the moves
        detectBound();            // inv: the clock limit
        buildTransitions();       // enab + prob: the edges
        validate();               // warn if something is missing
        return model;             // L(l) labels come later
    }


    // ===================================================================================
    // propose vs apply
    // Each SOAR move is two rules: a propose (names the move) and an apply (does it).
    // We store both by the move's name so a later step can match them up.
    // ===================================================================================

    private void splitRulesByOperator() {
        proposeByOp = new LinkedHashMap<>();
        applyByOp = new LinkedHashMap<>();
        for (Rule r : rules.rules) {
            String proposed = proposedOperator(r);
            if (proposed != null) { proposeByOp.put(proposed, r); continue; }
            String applied = appliedOperator(r);
            if (applied != null) { applyByOp.put(applied, r); }
        }
    }

    // A propose rule WRITES the move name (in its THEN part). Return it, or null.
    private String proposedOperator(Rule r) {
        return r.valueMap.get(OP_NAME_VAR);
    }

    // An apply rule CHECKS the move name (in its IF part). Return it, or null.
    private String appliedOperator(Rule r) {
        String prefix = OP_NAME_VAR + " = ";
        for (String g : r.guards) {
            if (g.startsWith(prefix)) return g.substring(prefix.length());
        }
        return null;
    }


    // ===================================================================================
    // X : clocks
    // A clock is a variable that counts itself up or down, like count = count + 1.
    // ===================================================================================

    private void detectClocks() {
        for (Rule apply : applyByOp.values()) {
            for (Map.Entry<String, String> e : apply.valueMap.entrySet()) {
                if (isSelfArithmetic(e.getKey(), e.getValue())) {
                    clocks.add(e.getKey());
                }
            }
        }
        model.clocks.addAll(clocks);
    }

    // True if the value is math on the same variable, e.g. "c + 1" or "1 + c".
    private boolean isSelfArithmetic(String var, String value) {
        if (var == null || value == null) return false;
        String[] t = value.trim().split("\\s+");
        return t.length == 3 && ARITH_OPS.contains(t[1]) && (t[0].equals(var) || t[2].equals(var));
    }


    // ===================================================================================
    // tick operators
    // A tick operator only bumps a clock — it's timekeeping, not a real action. We find them
    // so we can leave them out of the moves, and read the clock limit from them later.
    // ===================================================================================

    private void detectTickOps() {
        for (Map.Entry<String, Rule> e : applyByOp.entrySet()) {
            if (isTickApply(e.getValue())) {
                tickOps.add(e.getKey());
            }
        }
    }

    // True if this rule changes only clocks (nothing else).
    private boolean isTickApply(Rule apply) {
        if (apply.valueMap.isEmpty()) return false;
        for (String key : apply.valueMap.keySet()) {
            if (!clocks.contains(key)) return false;
        }
        return true;
    }


    // ===================================================================================
    // L + start location
    // The location is the state the agent is in (like far / near / in). It's the non-clock
    // variable that rules both change and check. Its start value is the first location.
    // ===================================================================================

    private void detectLocations() {
        locationVar = detectLocationVariable();
        model.locationVar = locationVar;
        if (locationVar != null && rules.variables.containsKey(locationVar)) {
            for (String v : rules.variables.get(locationVar).values) {
                if (!"nil".equals(v)) {
                    model.locations.add(v);
                }
            }
            // start location: the value the visitor saved
            model.initialLocation = rules.variables.get(locationVar).initialValue;
        }
    }

    // Pick the location: of the checked variables, the one apply rules change the most.
    // (A variable that is changed but never checked is an output, not a location.)
    private String detectLocationVariable() {
        // variables that any rule checks
        Set<String> testedInGuards = new LinkedHashSet<>();
        for (Rule r : rules.rules) {
            for (String g : r.guards) {
                testedInGuards.add(g.split(" ")[0]);
            }
        }
        // count how often apply rules change each variable (skip clocks and bookkeeping)
        Map<String, Integer> reassignCount = new LinkedHashMap<>();
        for (Rule apply : applyByOp.values()) {
            for (Map.Entry<String, String> e : apply.valueMap.entrySet()) {
                String var = e.getKey();
                String val = e.getValue();
                if (clocks.contains(var)) continue;
                if (var.equals(OP_NAME_VAR) || var.endsWith("_operator_name")) continue;
                if (isSelfArithmetic(var, val)) continue;
                reassignCount.merge(var, 1, Integer::sum);
            }
        }
        // the most-changed variable that is also checked wins
        String best = null;
        int bestCount = -1;
        for (Map.Entry<String, Integer> e : reassignCount.entrySet()) {
            if (!testedInGuards.contains(e.getKey())) continue; // changed but never checked => output
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }


    // ===================================================================================
    // Act : the moves
    // The moves the agent can make, from the propose and apply rules. We drop "nil" and
    // the tick operators. Example: approach, enter, exit.
    // ===================================================================================

    private void detectActions() {
        Set<String> allOps = new LinkedHashSet<>();
        allOps.addAll(proposeByOp.keySet());
        allOps.addAll(applyByOp.keySet());
        for (String op : allOps) {
            if ("nil".equals(op) || tickOps.contains(op)) continue;
            model.actions.add(op);
        }
    }


    // ===================================================================================
    // inv : the clock limit
    // SOAR checks "= N" but acts one step later, so the real limit is N+1.
    // If there is no clock, add a fake timer with limit 1 (a PTA must have a clock).
    // ===================================================================================

    private void detectBound() {
        String rawBound = detectClockBound(); // the real clock's number; null if there is none
        if (!clocks.isEmpty()) {
            model.clockBound = plusOne(rawBound);
        } else {
            synthClock = SYNTH_CLOCK;
            clocks.add(synthClock);
            model.clocks.add(synthClock);
            model.clockBound = "1";
        }
    }

    // Find the number the tick operator waits for.
    private String detectClockBound() {
        for (String tick : tickOps) {
            Rule propose = proposeByOp.get(tick);
            if (propose == null) continue;
            for (String g : propose.guards) {
                String var = g.split(" ")[0];
                if (clocks.contains(var)) {
                    String[] parts = g.split("\\s+");
                    return parts[parts.length - 1]; // the number
                }
            }
        }
        return null; // no clock
    }

    // Add 1 to the limit (the one-step delay). Leaves non-numbers alone.
    private String plusOne(String bound) {
        if (bound == null) return null;
        try { return String.valueOf(Integer.parseInt(bound.trim()) + 1); }
        catch (NumberFormatException e) { return bound; }
    }


    // ===================================================================================
    // enab + prob : the edges
    // One edge per move: its propose gives the condition (enab), its apply gives the result
    // (new location, clock resets, other changes). Probability is always 1.0.
    // ===================================================================================

    private void buildTransitions() {
        for (String op : model.actions) {
            // skip if proposed but never applied; warn if applied but never proposed.
            Rule apply = applyByOp.get(op);
            if (apply == null) {
                model.warnings.add("action '" + op + "' is proposed but never applied: no transition built");
                continue;
            }
            if (proposeByOp.get(op) == null) {
                model.warnings.add("action '" + op + "' is applied but never proposed: enabling condition may be incomplete");
            }
            // build the edge from the propose+apply pair.
            model.addTransition(buildTransition(op, proposeByOp.get(op), apply));
        }
    }

    private MultiPtaModel.PtaTransition buildTransition(String op, Rule propose, Rule apply) {
        // where the edge starts: the location checked in the guards (try apply, then propose)
        String source = locationValueInGuards(apply);
        if (source == null && propose != null) {
            source = locationValueInGuards(propose);
        }
        MultiPtaModel.PtaTransition t = new MultiPtaModel.PtaTransition(source, op);

        // condition to fire: the guards, minus the location and operator checks.
        // A real clock's check becomes "clock >= limit".
        LinkedHashSet<String> enab = new LinkedHashSet<>();
        addEnabGuards(enab, propose);
        addEnabGuards(enab, apply);

        // Fake clock = the internal SOAR decision-cycle delay. A move that READS an input
        // signal is a "receive": it fires the instant the signal arrives (no wait), so we
        // don't gate it. Any other move is "process/send": it takes one cycle (t >= limit).
        boolean receivesSignal = readsSignal(enab);
        if (synthClock != null && model.clockBound != null && !receivesSignal) {
            enab.add(synthClock + " >= " + model.clockBound);
        }
        t.guard = String.join(" & ", enab);

        // result: new location, then clock resets and other changes, from the apply
        t.targetLocation = (locationVar != null) ? apply.valueMap.get(locationVar) : null;
        for (Map.Entry<String, String> e : apply.valueMap.entrySet()) {
            String var = e.getKey();
            String val = e.getValue();
            if (var.equals(locationVar)) continue;
            if (clocks.contains(var)) {
                t.clockUpdates.put(var, clockResetValue(val));
            } else if (var.equals(OP_NAME_VAR) || var.endsWith("_operator_name")) {
                // operator bookkeeping -> ignore
            } else {
                t.updates.add(var + " = " + val);
            }
        }
        // The fake clock is reset only when the agent RECEIVES a signal: that starts the
        // one-cycle "thinking" timer that the following process/send move waits on. A move
        // that just waits on t (t >= limit) must not reset it, or it could never reach limit.
        if (synthClock != null && receivesSignal) {
            t.clockUpdates.put(synthClock, "0");
        }
        return t;
    }

    // True if any enabling guard reads an input signal (io_*): the agent is receiving.
    private boolean readsSignal(Set<String> enab) {
        for (String g : enab) {
            if (g.trim().startsWith(IO_PREFIX)) return true;
        }
        return false;
    }

    // What value to reset a clock to. In PRISM a clock can only be reset to an integer CONSTANT
    // (not an expression on the clock), so we keep a literal integer the rule set (e.g. "2");
    // self-increment (c+1 = time passing) or anything non-integer falls back to 0.
    private String clockResetValue(String val) {
        if (val != null && val.trim().matches("-?\\d+")) return val.trim();
        return "0";
    }

    // Keep only the real conditions: drop the location, operator, and init checks;
    // turn a clock check into "clock >= limit".
    private void addEnabGuards(LinkedHashSet<String> out, Rule rule) {
        if (rule == null) return;
        for (String g : rule.guards) {
            String var = g.split(" ")[0];
            if (locationVar != null && g.startsWith(locationVar + " ")) continue; // location check
            if (g.startsWith(OP_NAME_VAR)) continue;                               // operator check
            if (g.startsWith(SUPERSTATE_PREFIX)) continue;                         // init marker
            if (clocks.contains(var)) {                                            // clock check ->
                if (model.clockBound != null) out.add(var + " >= " + model.clockBound); // clock >= limit
                continue;
            }
            out.add(g);
        }
    }

    // Get the location value from a rule's guards (e.g. "state_position = far" -> "far").
    private String locationValueInGuards(Rule rule) {
        if (rule == null || locationVar == null) return null;
        String prefix = locationVar + " = ";
        for (String g : rule.guards) {
            if (g.startsWith(prefix)) return g.substring(prefix.length());
        }
        return null;
    }


    // ===================================================================================
    // validation
    // Warn if the location, start location, or moves are missing. This doesn't stop
    // anything — it just makes gaps visible instead of silently wrong.
    // ===================================================================================

    private void validate() {
        if (model.locationVar == null) {
            model.warnings.add("no location variable found: no state machine to translate");
        } else if (model.locations.isEmpty()) {
            model.warnings.add("location variable '" + model.locationVar + "' has no values (empty domain)");
        }
        if (model.initialLocation == null) {
            model.warnings.add("no initial location: needs an init rule with a top-state marker (^superstate nil / ^type state)");
        }
        if (model.actions.isEmpty()) {
            model.warnings.add("no actions (no operators found): not a PTA agent");
        }
    }
}
