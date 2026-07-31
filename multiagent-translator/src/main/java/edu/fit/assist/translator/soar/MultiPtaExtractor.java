package edu.fit.assist.translator.soar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Turns one SOAR agent into a PTA (a state machine with timers). It fills the 8 parts of
// the PTA one by one:  P = (L, l-bar, X, Act, inv, enab, prob, L).
//
// The class is organized in BLOCKS, one per concern, so each can be read and grown alone:
//   0.   RULE GROUPING (shared)   propose/apply rules per operator — a LIST each (an operator
//                                 may legally have several: probabilistic fan, hit/miss split)
//   0.5  VARIABLE ROLES (shared)  every variable classified by BEHAVIOR into one role
//   1.   LOCATION  (L, l-bar)     the discrete state variables + initial values
//   2.   CLOCK     (X, inv)       the timers and their bound
//   3.   ACTION    (Act)          the moves
//   4.   TRANSITIONS (enab, prob) the edges
//   5.   LABELS    (L(l))         (not built yet)
//   6.   VALIDATION               warnings
// extract() runs them in dependency order (clocks are needed before locations pick vars).

public class MultiPtaExtractor {

    // Special SOAR words (built into the language, not our own domain).
    private static final String OP_NAME_VAR = "state_operator_name";
    private static final String OP_VAR_PREFIX = "state_operator";
    private static final String SUPERSTATE_PREFIX = "state_superstate";
    // Variables on SOAR's io link (input-link / output-link): the agent's window to others.
    private static final String IO_VAR_PREFIX = "state_io_";

    private static final Set<String> ARITH_OPS =
            new LinkedHashSet<>(java.util.Arrays.asList("+", "-", "*", "/"));

    private static final Set<String> REL_OPS =
            new LinkedHashSet<>(java.util.Arrays.asList("<", ">", "<=", ">="));

    /** What a variable IS, judged by how the rules use it (behavior, not name):
     *  LOCATION — tested in guards AND changed by applies: discrete state, part of L.
     *  COUNTER  — self-incremented but bounded by a plain number: integer state, part of L.
     *  CLOCK    — self-incremented and (dedicated tick operator OR bounded symbolically):
     *             time, part of X.
     *  OUTPUT   — written but never tested here: something this agent tells others.
     *  CONST    — only read (io input or a set-once tag): not state at all. */
    enum Role { LOCATION, COUNTER, CLOCK, OUTPUT, CONST }

    private final SoarRules rules;

    // ---- shared results: each block fills these, later blocks read them ----
    private MultiPtaModel model;
    private Map<String, List<Rule>> proposesByOp;       // move name -> ALL its propose rules
    private Map<String, List<Rule>> appliesByOp;        // move name -> ALL its apply rules
    private Map<String, Role> roles;                    // variable -> its behavioral role
    private final Set<String> clocks = new LinkedHashSet<>();   // X: the timer variables
    private final Set<String> accumulatingClocks = new LinkedHashSet<>(); // never reset by moves
    private String pacingClock;                         // spawned beside an accumulating clock
    private final Set<String> tickOps = new LinkedHashSet<>();  // operators that only tick a clock
    private String locationVar;                         // the state variable (L)

    public MultiPtaExtractor(SoarRules rules) {
        this.rules = rules;
    }

    /**
     * What: runs every block in order and returns the finished PTA tuple.
     * Why:  the order is fixed by dependency — clocks must be known before locations pick vars.
     * PTA:  all of P = (L, l-bar, X, Act, inv, enab, prob, 𝓛).
     */
    public MultiPtaModel extract() {
        model = new MultiPtaModel();
        model.knownVars.addAll(rules.variables.keySet()); // word vs variable, for every later block
        groupRulesByOperator();     // 0   : propose/apply lists per operator
        classifyVariableRoles();    // 0.5 : the role table
        detectClocks();             // 2   : X, the timers
        detectTickOps();            // 2   : clock-only operators (not real moves)
        detectLocations();          // 1   : legacy single-var view (transition plumbing)
        buildStateVars();           // 1   : L + l-bar, the full set of state variables
        detectActions();            // 3   : Act, the moves
        detectBounds();             // 2   : inv, one bound per clock
        buildTransitions();         // 4   : enab + prob, the edges
        validate();                 // 6   : warn if something is missing
        return model;               // 5   : L(l) labels come later
    }


    // ===================================================================================
    // ===== 0. RULE GROUPING (shared) ===================================================
    // Each SOAR move is propose rules (name the move) + apply rules (do it). An operator
    // may legally have SEVERAL of each — e.g. several proposes carrying probability
    // weights (T3), or several applies for different outcomes (hit/miss, T4). So each map
    // keeps a LIST per operator; collapsing to one silently dropped rules before.
    // ===================================================================================

    /**
     * What: files every rule under its operator as a propose-list / apply-list.
     * Why:  one operator may have many rules (softmax fan, hit/miss) — none may be dropped.
     * PTA:  feeds Act, enab, prob (every edge-building block reads these lists).
     */
    private void groupRulesByOperator() {
        proposesByOp = new LinkedHashMap<>();
        appliesByOp = new LinkedHashMap<>();
        for (Rule r : rules.rules) {
            String proposed = proposedOperator(r);
            if (proposed != null) {
                proposesByOp.computeIfAbsent(proposed, k -> new ArrayList<>()).add(r);
                continue;
            }
            String applied = appliedOperator(r);
            if (applied != null) {
                appliesByOp.computeIfAbsent(applied, k -> new ArrayList<>()).add(r);
            }
            // rules that are neither (init / elaborations) carry no operator; the visitor
            // already recorded their initial-value writes on the variables themselves.
        }
    }

    /**
     * What: returns the move a rule PROPOSES (writes ^name), or null.
     * Why:  a propose rule names its move and carries that move's firing condition.
     * PTA:  Act (the move's identity).
     */
    private String proposedOperator(Rule r) {
        return r.valueMap.get(OP_NAME_VAR);
    }

    /**
     * What: returns the move a rule APPLIES (guards on ^operator.name), or null.
     * Why:  an apply rule carries the move's effect (new state, clock resets).
     * PTA:  Act (the move's identity).
     */
    private String appliedOperator(Rule r) {
        String prefix = OP_NAME_VAR + " = ";
        for (String g : r.guards) {
            if (g.startsWith(prefix)) return g.substring(prefix.length());
        }
        return null;
    }

    /**
     * What: flattens every operator's apply-list into one list.
     * Why:  role/clock/state scans just want "every apply", regardless of which operator.
     * PTA:  helper for L and X (state + clock detection read all applies).
     */
    private List<Rule> allApplies() {
        List<Rule> all = new ArrayList<>();
        for (List<Rule> list : appliesByOp.values()) all.addAll(list);
        return all;
    }

    /**
     * What: returns the first rule of a list, or null if empty.
     * Why:  until 4b/4c merge multi-rule operators, the edge-builder uses just the first.
     * PTA:  helper for enab / prob.
     */
    private Rule firstOrNull(List<Rule> list) {
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }


    // ===================================================================================
    // ===== 0.5 VARIABLE ROLES (shared) =================================================
    // One place decides what each variable IS, from how the rules USE it — never from its
    // name. Later blocks just read the table: Location takes LOCATION+COUNTER, Clock takes
    // CLOCK, the emitter drops CONST and treats OUTPUT as talk. Keeping the cross-cutting
    // decision here is what lets those blocks stay independent of each other.
    // ===================================================================================

    /**
     * What: labels every variable LOCATION / COUNTER / CLOCK / OUTPUT / CONST by how rules use it.
     * Why:  one behavioral verdict lets later blocks stay independent (they just read the label).
     * PTA:  decides which vars become L (state), which become X (clocks), which are dropped.
     */
    private void classifyVariableRoles() {
        roles = new LinkedHashMap<>();

        // usage facts, gathered once
        Set<String> testedInGuards = new LinkedHashSet<>();      // appears in any rule's IF
        Set<String> symbolicallyBounded = new LinkedHashSet<>(); // compared to a NON-number
        for (Rule r : rules.rules) {
            for (String g : r.guards) {
                String[] tok = g.trim().split("\\s+");
                testedInGuards.add(tok[0]);
                if (tok.length >= 3 && REL_OPS.contains(tok[1]) && !isNumeric(tok[tok.length - 1])) {
                    symbolicallyBounded.add(tok[0]);
                }
            }
        }
        Set<String> assignedByApply = new LinkedHashSet<>();     // changed when a move runs
        Set<String> selfArithmeticVars = new LinkedHashSet<>();  // v = v + 1 style
        Set<String> dedicatedTickVars = new LinkedHashSet<>();   // an apply that ONLY bumps v
        for (Rule apply : allApplies()) {
            for (Map.Entry<String, String> e : apply.valueMap.entrySet()) {
                assignedByApply.add(e.getKey());
                if (isSelfArithmetic(e.getKey(), e.getValue())) {
                    selfArithmeticVars.add(e.getKey());
                    if (apply.valueMap.size() == 1) dedicatedTickVars.add(e.getKey());
                }
            }
        }

        // the verdicts
        for (String var : rules.variables.keySet()) {
            if (var.startsWith(OP_VAR_PREFIX) || var.startsWith(SUPERSTATE_PREFIX)) {
                continue; // SOAR bookkeeping, not model state
            }
            Role role;
            if (var.startsWith(IO_VAR_PREFIX)) {
                // io link: written here = this agent talking; read-only = an open input.
                role = assignedByApply.contains(var) ? Role.OUTPUT : Role.CONST;
            } else if (selfArithmeticVars.contains(var)) {
                // self-incrementing: TIME if it has its own tick operator (train-count) or is
                // bounded by a symbolic threshold (wall-clock < deadline); otherwise it is an
                // enumeration COUNTER (current-person-id < 20) and stays an integer.
                role = (dedicatedTickVars.contains(var) || symbolicallyBounded.contains(var))
                        ? Role.CLOCK : Role.COUNTER;
            } else if (assignedByApply.contains(var) && testedInGuards.contains(var)) {
                role = Role.LOCATION;   // checked and changed: real discrete state
            } else if (testedInGuards.contains(var)) {
                role = Role.CONST;      // only ever read: a set-once tag (^name) or input
            } else if (assignedByApply.contains(var)) {
                role = Role.OUTPUT;     // only ever written: something told to others
            } else {
                role = Role.CONST;      // touched by no rule that matters
            }
            roles.put(var, role);
        }

        // surface the table on the model so every run shows its classification
        for (Map.Entry<String, Role> e : roles.entrySet()) {
            model.varRoles.put(e.getKey(), e.getValue().name());
        }
    }

    /**
     * What: true if the token parses as a number.
     * Why:  a symbolic bound (deadline) marks a clock; a numeric bound (20) marks a counter.
     * PTA:  helper for the role verdict (X vs L).
     */
    private boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        try { Double.parseDouble(s); return true; } catch (NumberFormatException e) { return false; }
    }


    // ===================================================================================
    // ===== 1. LOCATION (L, l-bar) ======================================================
    // L = every variable the agent both CHECKS and CHANGES (role LOCATION or COUNTER):
    // several parallel flags, one cycling place-variable, or none at all (a stateless
    // sampler). Each becomes its own [0..n] variable:
    //   - "nil" (absence) is a real value = 0, but ONLY when the agent can actually be
    //     in it: the var is absence-tested (= nil guard) or starts absent (no init rule).
    //   - all-numeric values => an integer range [min..max] (nil folds onto the low end),
    //     so counters like tagged-attacker 0/1 don't get nonsense enumeration codes.
    //   - runtime-bound values (^VAR..., references to other variables) are not domain
    //     constants; they are excluded here and resolved by later blocks (T2/T3).
    //   - a domain of one value can't distinguish anything => not state, dropped.
    // l-bar: the init-rule value when one exists, else nil (the agent starts absent —
    // this is why operator-based initialize needs no special case: it's a normal edge
    // OUT of the nil state).
    // ===================================================================================

    /**
     * What: turns each LOCATION/COUNTER var into a [0..n] state variable with a start value.
     * Why:  an ICS agent has SEVERAL parallel state vars and "nil"=0 is real — one pick loses state.
     * PTA:  L (the state variables) + l-bar (each one's initial value).
     */
    private void buildStateVars() {
        for (Map.Entry<String, Role> e : roles.entrySet()) {
            String var = e.getKey();
            if (e.getValue() != Role.LOCATION && e.getValue() != Role.COUNTER) continue;
            if (clocks.contains(var)) continue; // held by X (legacy until Block 2 splits)
            Variable v = rules.variables.get(var);
            if (v == null) continue;

            // observed constant values, in first-seen order; references are not constants
            List<String> consts = new ArrayList<>();
            List<String> refs = new ArrayList<>();
            boolean allNumeric = true;
            for (String val : v.values) {
                if ("nil".equals(val)) continue;
                if (val.contains(" ") || val.startsWith("^") || rules.variables.containsKey(val)) {
                    refs.add(val);
                    continue;
                }
                consts.add(val);
                if (!isInteger(val)) allNumeric = false;
            }
            if (!refs.isEmpty()) {
                model.warnings.add("state var '" + var + "': runtime-bound value(s) " + refs
                        + " are not domain constants (resolved by later blocks)");
            }

            String initValue = (v.initialValue == null || v.initialValue.isEmpty())
                    ? null : v.initialValue;
            boolean nilReachable = hasNilGuard(var) || initValue == null;

            MultiPtaModel.StateVar sv = new MultiPtaModel.StateVar(var);
            if (!consts.isEmpty() && allNumeric) {
                // numeric state: a range, values are their own codes, absence = the low end
                sv.numeric = true;
                sv.lo = Integer.MAX_VALUE;
                sv.hi = Integer.MIN_VALUE;
                for (String c : consts) {
                    int n = Integer.parseInt(c.trim());
                    sv.lo = Math.min(sv.lo, n);
                    sv.hi = Math.max(sv.hi, n);
                }
                if (nilReachable) sv.lo = Math.min(sv.lo, 0);
                sv.init = (initValue != null && isInteger(initValue)) ? initValue
                        : String.valueOf(sv.lo);
                if (sv.lo == sv.hi) {
                    model.warnings.add("state var '" + var + "' dropped: domain is the single value "
                            + sv.lo + " (cannot distinguish states)");
                    continue;
                }
            } else {
                // enumerated state: nil first (= 0) when reachable, then the seen values
                if (nilReachable) sv.domain.add("nil");
                sv.domain.addAll(consts);
                sv.init = (initValue != null && sv.domain.contains(initValue)) ? initValue
                        : (nilReachable ? "nil" : (sv.domain.isEmpty() ? null : sv.domain.get(0)));
                if (sv.domain.size() <= 1) {
                    model.warnings.add("state var '" + var + "' dropped: domain " + sv.domain
                            + " has nothing to distinguish"
                            + (refs.isEmpty() ? "" : " (its runtime-bound values may fill it later)"));
                    continue;
                }
            }
            model.stateVars.add(sv);
        }
    }

    /**
     * What: true if any rule tests the variable for absence ("= nil" / "!= nil").
     * Why:  absence is only a real state (value 0) when the agent actually checks for it.
     * PTA:  L (whether nil earns slot 0 in a var's domain).
     */
    private boolean hasNilGuard(String var) {
        for (Rule r : rules.rules) {
            for (String g : r.guards) {
                if (g.equals(var + " = nil") || g.equals(var + " != nil")) return true;
            }
        }
        return false;
    }

    /**
     * What: true if the token parses as a whole number.
     * Why:  an all-integer domain becomes a [min..max] range instead of an enum.
     * PTA:  L (a state var's domain shape).
     */
    private boolean isInteger(String s) {
        if (s == null || s.isEmpty()) return false;
        try { Integer.parseInt(s.trim()); return true; } catch (NumberFormatException e) { return false; }
    }

    /**
     * What: fills the LEGACY single-location view (one var, its values, its start).
     * Why:  the transition builder still plumbs edges through one locationVar (retired in 4a).
     * PTA:  L / l-bar (legacy view; feeds enab source/target for now).
     */
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

    /**
     * What: picks the one checked variable that apply rules change the most.
     * Why:  the legacy edge-builder needs a single "where am I" var (changed-not-checked = output).
     * PTA:  L (the legacy single-location pick).
     */
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
        for (Rule apply : allApplies()) {
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
    // ===== 2. CLOCK (X, inv) ===========================================================
    // X = the variables with role CLOCK (0.5's verdict); COUNTER stays integer state.
    // Bounds map 1:1 to the SOAR threshold — no processing-delay "+1", and a clockless
    // agent stays clockless (2026-07-27 directive: translate SOAR as written).
    //
    // Two clock kinds, told apart by how the moves touch the counter:
    //   RESETTING    — a dedicated tick operator does the +1; each real move RESETS the
    //                  counter (train-count). Measures time BETWEEN moves. One clock,
    //                  guard "clock >= bound", reset on every move.
    //   ACCUMULATING — the move ITSELF does the +1 as a side effect and only a start
    //                  edge sets it back to a literal (wall-clock). Measures TOTAL time
    //                  against a threshold. T6: this ONE counter becomes TWO clocks —
    //                    its VALUE  -> the clock itself (deadline race; strict guards
    //                                  relaxed, < to <=, digital clocks ban strict), and
    //                    its +1 EVENT -> a PACING clock (guard >=1 / bound 1 / reset on
    //                                  each increment): one SOAR decision cycle = one
    //                                  time unit, straight from SOAR's own semantics.
    // ===================================================================================

    /**
     * What: collects CLOCK-role vars, splits resetting vs accumulating, spawns pacing clock t1.
     * Why:  SOAR only counts; a counter raced against a deadline IS a clock, and its +1 also paces time.
     * PTA:  X (the clock set).
     */
    private void detectClocks() {
        for (Map.Entry<String, Role> e : roles.entrySet()) {
            if (e.getValue() == Role.CLOCK) clocks.add(e.getKey());
        }
        // kind: incremented inside a real move (an apply that changes other things too)
        // and never by a dedicated tick => accumulating.
        for (String c : clocks) {
            boolean dedicatedTick = false, incrementedInMove = false;
            for (Rule apply : allApplies()) {
                String val = apply.valueMap.get(c);
                if (val != null && isSelfArithmetic(c, val)) {
                    if (apply.valueMap.size() == 1) dedicatedTick = true;
                    else incrementedInMove = true;
                }
            }
            if (incrementedInMove && !dedicatedTick) accumulatingClocks.add(c);
        }
        // T6: spawn the pacing clock beside the accumulating clock(s).
        if (!accumulatingClocks.isEmpty()) {
            pacingClock = uniqueClockName("t1");
            clocks.add(pacingClock);
        }
        model.clocks.addAll(clocks);
        model.pacingClock = pacingClock;
        model.accumulatingClocks.addAll(accumulatingClocks);
    }

    /**
     * What: returns a clock name that clashes with nothing the agent already has.
     * Why:  the spawned pacing clock must not collide with an existing variable.
     * PTA:  X (names the pacing clock).
     */
    private String uniqueClockName(String base) {
        String name = base;
        int n = 1;
        while (rules.variables.containsKey(name) || clocks.contains(name)) {
            name = base.substring(0, 1) + (++n);
        }
        return name;
    }

    /**
     * What: true if a value is math on the same variable ("c + 1", "1 + c").
     * Why:  self-increment is what marks a counter/clock (time passing), not a plain assignment.
     * PTA:  helper for X (clock detection) and enab (increment vs reset on an edge).
     */
    private boolean isSelfArithmetic(String var, String value) {
        if (var == null || value == null) return false;
        String[] t = value.trim().split("\\s+");
        return t.length == 3 && ARITH_OPS.contains(t[1]) && (t[0].equals(var) || t[2].equals(var));
    }

    /**
     * What: finds operators whose applies do nothing but bump a clock.
     * Why:  a pure tick is timekeeping, not a real move — keep it out of Act, read its bound later.
     * PTA:  Act (exclusion) + inv (its guard gives a resetting clock's bound).
     */
    private void detectTickOps() {
        for (Map.Entry<String, List<Rule>> e : appliesByOp.entrySet()) {
            boolean allTick = !e.getValue().isEmpty();
            for (Rule apply : e.getValue()) {
                if (!isTickApply(apply)) { allTick = false; break; }
            }
            if (allTick) {
                tickOps.add(e.getKey());
            }
        }
    }

    /**
     * What: true if a rule changes clocks and nothing else.
     * Why:  that is the signature of a timekeeping tick, not a state-changing move.
     * PTA:  Act (marks a tick op for exclusion).
     */
    private boolean isTickApply(Rule apply) {
        if (apply.valueMap.isEmpty()) return false;
        for (String key : apply.valueMap.keySet()) {
            if (!clocks.contains(key)) return false;
        }
        return true;
    }

    /**
     * What: fills one upper bound per clock (pacing=1, accumulating=threshold, resetting=tick count).
     * Why:  each clock kind reads its limit from a different place.
     * PTA:  inv (the clock invariants) + enab (clock guards).
     */
    private void detectBounds() {
        for (String c : clocks) {
            String bound;
            if (c.equals(pacingClock)) {
                bound = "1"; // one decision cycle
            } else if (accumulatingClocks.contains(c)) {
                bound = orderedGuardBoundOf(c); // the threshold it races against
            } else {
                bound = tickBoundOf(c); // the number the tick operator waits for
            }
            if (bound != null) model.clockBounds.put(c, bound);
        }
    }

    /**
     * What: reads a resetting clock's limit from its tick operator's propose ("count < 6" -> 6).
     * Why:  a resetting clock runs until the tick stops proposing.
     * PTA:  inv (a resetting clock's bound).
     */
    private String tickBoundOf(String clock) {
        for (String tick : tickOps) {
            for (Rule propose : proposesByOp.getOrDefault(tick, java.util.Collections.emptyList())) {
                for (String g : propose.guards) {
                    if (g.split(" ")[0].equals(clock)) {
                        String[] parts = g.split("\\s+");
                        return parts[parts.length - 1];
                    }
                }
            }
        }
        return null;
    }

    /**
     * What: reads an accumulating clock's threshold from its ordered guard ("wall < deadline" -> deadline).
     * Why:  the deadline it races against is named right in the comparison.
     * PTA:  inv (an accumulating clock's bound).
     */
    private String orderedGuardBoundOf(String clock) {
        for (Rule r : rules.rules) {
            for (String g : r.guards) {
                String[] tok = g.trim().split("\\s+");
                if (tok.length == 3 && tok[0].equals(clock) && REL_OPS.contains(tok[1])) {
                    return tok[2];
                }
            }
        }
        return null;
    }


    // ===================================================================================
    // ===== 3. ACTION (Act) =============================================================
    // The moves the agent can make, from the propose and apply rules. We drop "nil" and
    // the tick operators. Example: approach, enter, exit.
    // ===================================================================================

    /**
     * What: collects the real moves, dropping nil, tick ops, and nameless operators.
     * Why:  only genuine, named operators become PTA edges; a blank name signals an upstream loss.
     * PTA:  Act (the move set).
     */
    private void detectActions() {
        Set<String> allOps = new LinkedHashSet<>();
        allOps.addAll(proposesByOp.keySet());
        allOps.addAll(appliesByOp.keySet());
        for (String op : allOps) {
            if ("nil".equals(op) || tickOps.contains(op)) continue;   // no-op / pure timekeeping
            if (op == null || op.trim().isEmpty()) {                  // a move must have a name
                model.warnings.add("skipped an operator with no name ("
                        + proposesByOp.getOrDefault(op, java.util.Collections.emptyList()).size()
                        + " propose / "
                        + appliesByOp.getOrDefault(op, java.util.Collections.emptyList()).size()
                        + " apply rules): its name was lost upstream (see Visitor)");
                continue;
            }
            model.actions.add(op);
        }
    }


    // ===================================================================================
    // ===== 4. TRANSITIONS (enab + prob) ================================================
    // One edge per move: its propose gives the condition (enab), its apply gives the result
    // (state writes, clock resets). Probability is always 1.0 for now: Block 4b will merge
    // several proposes into ONE probabilistic edge (T3), and Block 4c will expand several
    // applies into one edge per outcome (T4). Until then we take the FIRST rule and say so
    // in a warning, instead of silently dropping the rest.
    //
    // An edge has NO single source or target. An agent is the whole tuple of its state
    // variables at once, so every test becomes a guard and every write becomes an update.
    // The earlier encoding routed edges through one chosen "location" variable, which threw
    // away any rule that never mentioned it (whole agents came out empty).
    // ===================================================================================

    /**
     * What: builds one edge per move from its (first) propose+apply, warning on multi-rule ops.
     * Why:  each move is a PTA edge; the fans (T3) and splits (T4) wait for blocks 4b/4c.
     * PTA:  enab + prob (the edges).
     */
    private void buildTransitions() {
        for (String op : model.actions) {
            List<Rule> proposes = proposesByOp.getOrDefault(op, java.util.Collections.emptyList());
            List<Rule> applies = appliesByOp.getOrDefault(op, java.util.Collections.emptyList());
            // skip if proposed but never applied; warn if applied but never proposed.
            if (applies.isEmpty()) {
                model.warnings.add("action '" + op + "' is proposed but never applied: no transition built");
                continue;
            }
            if (proposes.isEmpty()) {
                model.warnings.add("action '" + op + "' is applied but never proposed: enabling condition may be incomplete");
            }
            if (proposes.size() > 1 && buildProbabilisticFan(op, proposes, firstOrNull(applies))) {
                continue;   // T3: the whole fan became one probabilistic edge per group
            }
            if (proposes.size() > 1) {
                model.warnings.add("action '" + op + "' has " + proposes.size()
                        + " propose rules but no enumerable parameter: using only the first");
            }
            // T4: one propose, several applies. In Soar every apply whose conditions match
            // fires, so each is a case of the same move, told apart by its own test on the
            // data (attacker-id equal to this person, or not). Each becomes its own edge,
            // sharing the propose's condition and adding the apply's. Mirror image of the
            // fan above: several proposes are ONE random choice, several applies are MANY
            // data-driven outcomes.
            for (Rule apply : applies) {
                MultiPtaModel.PtaTransition t =
                        buildTransition(op, firstOrNull(proposes), apply);
                if (applies.size() > 1) {
                    t.action = caseName(apply, op);   // distinguish the cases by name
                }
                model.addTransition(t);
            }
        }
    }

    /**
     * What: names one case of a multi-outcome move after the apply rule that defines it.
     * Why:  the rules already carry the distinction ("...*apply*examine-box-hit"); reusing the
     *       modeller's own label beats inventing hit/miss vocabulary the translator can't know.
     *       Only used when a move HAS several cases, so a plain one-apply move keeps its name.
     * PTA:  Act (the label of one edge).
     */
    private String caseName(Rule apply, String fallback) {
        if (apply == null || apply.ruleName == null) return fallback;
        String name = apply.ruleName;
        int last = name.lastIndexOf('*');           // agent*apply*THE-CASE
        if (last >= 0 && last < name.length() - 1) {
            name = name.substring(last + 1);
        }
        return name.trim().isEmpty() ? fallback : name.trim();
    }

    /**
     * What: assembles one edge — every test it makes, every write it does, clock resets.
     * Why:  the propose gives the firing condition, the apply gives the effect; no variable
     *       is singled out, so a rule that touches none of "the" location still becomes an edge.
     * PTA:  enab (the guards) + prob (the updates).
     */
    private MultiPtaModel.PtaTransition buildTransition(String op, Rule propose, Rule apply) {
        MultiPtaModel.PtaTransition t = new MultiPtaModel.PtaTransition(op);

        // enab: every test both rules make, minus SOAR's own bookkeeping.
        LinkedHashSet<String> enab = new LinkedHashSet<>();
        addEnabGuards(enab, propose);
        addEnabGuards(enab, apply);

        // prob: every write the apply makes. Clocks are resets; everything else is an update.
        for (Map.Entry<String, String> e : apply.valueMap.entrySet()) {
            String var = e.getKey();
            String val = e.getValue();
            if (clocks.contains(var)) {
                if (isSelfArithmetic(var, val)) {
                    // the +1 on an accumulating clock is TIME PASSING, not a reset: the
                    // clock keeps running; the pacing clock marks the cycle (>=1, then 0).
                    if (pacingClock != null) {
                        enab.add(pacingClock + " >= 1");
                        t.clockUpdates.put(pacingClock, "0");
                    }
                } else {
                    // a literal write is a real reset; restarting an accumulating clock
                    // also restarts its pacing cycle.
                    t.clockUpdates.put(var, clockResetValue(val));
                    if (accumulatingClocks.contains(var) && pacingClock != null) {
                        t.clockUpdates.put(pacingClock, "0");
                    }
                }
            } else if (isOperatorBookkeeping(var)) {
                // which operator was chosen is SOAR's own machinery, not model state
            } else {
                t.updates.add(var + " = " + val); // counters keep their +1 as a real update
            }
        }
        t.guards.addAll(enab);
        return t;
    }

    // --- T3: the probabilistic fan ----------------------------------------------------
    // Several propose rules, ONE operator, each carrying a numeric-indifferent preference.
    // With softmax on, Soar picks among them in proportion to those weights — that IS a
    // probability distribution, and it becomes ONE probabilistic PRISM command.
    //
    // Each rule contributes a RANGE of outcomes, not one, because it matches a whole input
    // collection through the operator's parameter (^target-id). Three shapes:
    //   ^id 0              a literal        -> that one value
    //   ^id { <id> <> 0 }  all but a value  -> the rest of the range
    //   ^id <id>           free             -> the whole range
    // The size of the range is not written anywhere in the rules — but the weight states it:
    // a rule that shares probability equally over N matches divides by N. So "(/ 1.0 20)"
    // says twenty, and "(/ (- 1.0 <b>) 19)" says nineteen-besides-the-tip. Reading it from
    // there keeps the population out of the translator and out of any config file.

    /**
     * What: turns a multi-propose operator into one probabilistic edge per guard grouping.
     * Why:  softmax over numeric preferences is a dice roll; collapsing to one rule lost it.
     * PTA:  enab + prob (a probabilistic edge). Returns false if this isn't an enumerable fan.
     */
    private boolean buildProbabilisticFan(String op, List<Rule> proposes, Rule apply) {
        if (apply == null) return false;
        String param = operatorParameter(proposes);         // ^target-id -> state_operator_target_id
        if (param == null) return false;
        String enumVar = enumeratedVariable(proposes, param); // the input it ranges over
        if (enumVar == null) return false;
        int size = populationSize(proposes);                  // how many things it ranges over
        if (size <= 0) return false;

        // Rules that differ only in WHICH values they cover belong to the same command; rules
        // that differ in any other condition (use-box 1 vs 0) are genuinely different commands.
        Map<String, List<Rule>> groups = new LinkedHashMap<>();
        for (Rule p : proposes) {
            groups.computeIfAbsent(groupKey(p, enumVar), k -> new ArrayList<>()).add(p);
        }

        for (Map.Entry<String, List<Rule>> group : groups.entrySet()) {
            MultiPtaModel.PtaTransition t = new MultiPtaModel.PtaTransition(op);
            LinkedHashSet<String> enab = new LinkedHashSet<>();
            for (Rule p : group.getValue()) {
                for (String g : p.guards) {
                    if (isOperatorBookkeeping(g.split(" ")[0])) continue;
                    if (g.split(" ")[0].equals(enumVar)) continue;   // a selector, now a branch
                    enab.add(g);
                }
            }
            addEnabGuards(enab, apply);
            t.guards.addAll(enab);

            for (Rule p : group.getValue()) {
                String weight = p.valueMap.get(MultiPtaVisitor.OP_PREFERENCE_VAR);
                if (weight == null) {
                    model.warnings.add("action '" + op + "': a propose rule in the fan carries no"
                            + " preference weight; its outcomes cannot be given a probability");
                    return false;
                }
                for (int id : coveredValues(p, enumVar, size)) {
                    MultiPtaModel.Branch b = new MultiPtaModel.Branch(weight);
                    for (Map.Entry<String, String> e : apply.valueMap.entrySet()) {
                        if (isOperatorBookkeeping(e.getKey())) continue;
                        b.updates.add(e.getKey() + " = "
                                + e.getValue().replace(param, String.valueOf(id)));
                    }
                    t.branches.add(b);
                }
            }
            declareEnumeratedTarget(t, size);
            checkWeightsSumToOne(op, t);
            model.addTransition(t);
        }
        return true;
    }

    /**
     * What: finds the operator's parameter — the extra attribute a propose hands to its apply.
     * Why:  the fan's outcomes differ only in that value, so it is what each branch fixes.
     * PTA:  prob (what a branch writes).
     */
    private String operatorParameter(List<Rule> proposes) {
        for (Rule p : proposes) {
            for (String key : p.valueMap.keySet()) {
                if (key.startsWith(OP_VAR_PREFIX) && !key.equals(OP_NAME_VAR)
                        && !key.equals(MultiPtaVisitor.OP_PREFERENCE_VAR)) {
                    return key;
                }
            }
        }
        return null;
    }

    /**
     * What: finds the input variable the parameter draws its values from.
     * Why:  that variable's guards are what carve each rule's slice of the range.
     * PTA:  prob (the branch set).
     */
    private String enumeratedVariable(List<Rule> proposes, String param) {
        for (Rule p : proposes) {
            String val = p.valueMap.get(param);
            if (val != null && rules.variables.containsKey(val)) return val;
        }
        return null;
    }

    /**
     * What: reads how many things the fan ranges over, from the divisor in its weights.
     * Why:  a rule spreading probability evenly over its matches divides by how many there are,
     *       so the population is stated in the model itself — no config, no hardcoded count.
     * PTA:  prob (the size of the branch set).
     */
    private int populationSize(List<Rule> proposes) {
        int size = 0;
        for (Rule p : proposes) {
            String weight = p.valueMap.get(MultiPtaVisitor.OP_PREFERENCE_VAR);
            if (weight == null) continue;
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("/\\s*(\\d+)\\s*$").matcher(weight.trim());
            if (m.find()) size = Math.max(size, Integer.parseInt(m.group(1)));
        }
        return size;
    }

    /**
     * What: everything a rule tests EXCEPT the values it selects — its identity as a command.
     * Why:  two rules covering different slices of the same range are one command; two rules
     *       disagreeing on anything else (use-box 1 vs 0) are two.
     * PTA:  enab (which edges merge).
     */
    private String groupKey(Rule propose, String enumVar) {
        List<String> keep = new ArrayList<>();
        for (String g : propose.guards) {
            String var = g.split(" ")[0];
            if (var.equals(enumVar) || isOperatorBookkeeping(var)) continue;
            keep.add(g);
        }
        java.util.Collections.sort(keep);
        return String.join(" & ", keep);
    }

    /**
     * What: the values one rule of the fan covers, given the range 0..size-1.
     * Why:  a literal test picks one, a "not equal" test picks all the others, no test at all
     *       picks everything — that is how one rule stands for many outcomes.
     * PTA:  prob (this rule's branches).
     */
    private List<Integer> coveredValues(Rule propose, String enumVar, int size) {
        List<Integer> excluded = new ArrayList<>();
        for (String g : propose.guards) {
            String[] tok = g.trim().split("\\s+");
            if (tok.length != 3 || !tok[0].equals(enumVar) || !isInteger(tok[2])) continue;
            int v = Integer.parseInt(tok[2].trim());
            if ("=".equals(tok[1])) return java.util.Collections.singletonList(v); // just this one
            if ("<>".equals(tok[1]) || "!=".equals(tok[1])) excluded.add(v);
        }
        List<Integer> covered = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (!excluded.contains(i)) covered.add(i);
        }
        return covered;
    }

    /**
     * What: gives the variable the branches write a real domain, so the module can declare it.
     * Why:  the fan is what reveals the range; before it, the value was an unresolved reference
     *       and Block 1 had nothing to build a domain from.
     * PTA:  L (a state variable the dice roll creates).
     */
    private void declareEnumeratedTarget(MultiPtaModel.PtaTransition t, int size) {
        if (t.branches.isEmpty()) return;
        String target = null;
        for (String u : t.branches.get(0).updates) {
            String var = u.split(" ")[0];
            if (model.stateVar(var) != null) return;   // already declared, nothing to do
            // Prefer the copy on the io link: that is the one other agents read, and the
            // private copy is bookkeeping Block 1 already judged not to be state.
            if (target == null || (!target.startsWith(IO_VAR_PREFIX) && var.startsWith(IO_VAR_PREFIX))) {
                target = var;
            }
        }
        if (target == null) return;
        MultiPtaModel.StateVar sv = new MultiPtaModel.StateVar(target);
        sv.numeric = true;
        sv.lo = 0;
        sv.hi = size - 1;
        sv.init = "0";
        model.stateVars.add(sv);
    }

    /**
     * What: checks a probabilistic edge's weights add up to 1, when they are plain numbers.
     * Why:  a distribution that doesn't sum to 1 is a modelling error PRISM will reject later.
     *       Symbolic weights can't be added up here, so their composition is reported instead.
     * PTA:  prob (validation).
     */
    private void checkWeightsSumToOne(String op, MultiPtaModel.PtaTransition t) {
        double total = 0.0;
        Map<String, Integer> symbolic = new LinkedHashMap<>();
        for (MultiPtaModel.Branch b : t.branches) {
            Double v = asNumber(b.probability);
            if (v == null) symbolic.merge(b.probability, 1, Integer::sum);
            else total += v;
        }
        if (symbolic.isEmpty()) {
            if (Math.abs(total - 1.0) > 1e-9) {
                model.warnings.add("action '" + op + "': the " + t.branches.size()
                        + " outcome probabilities sum to " + total + ", not 1");
            }
            return;
        }
        model.warnings.add("action '" + op + "': " + t.branches.size()
                + " outcomes with symbolic weights " + symbolic
                + (total > 0 ? " plus " + total : "") + " - verify they sum to 1");
    }

    /**
     * What: evaluates a weight when it is arithmetic over plain numbers ("1.0 / 20" -> 0.05).
     * Why:  lets the sum check run on the cases where it can actually be decided.
     * PTA:  prob (validation helper).
     */
    private Double asNumber(String expr) {
        if (expr == null) return null;
        String s = expr.trim();
        if (isNumeric(s)) return Double.parseDouble(s);
        String[] tok = s.split("\\s+");
        if (tok.length == 3 && ARITH_OPS.contains(tok[1]) && isNumeric(tok[0]) && isNumeric(tok[2])) {
            double a = Double.parseDouble(tok[0]), b = Double.parseDouble(tok[2]);
            switch (tok[1]) {
                case "+": return a + b;
                case "-": return a - b;
                case "*": return a * b;
                case "/": return b == 0 ? null : a / b;
            }
        }
        return null;
    }

    /**
     * What: true if a variable is SOAR's operator/superstate machinery rather than model state.
     * Why:  which operator got selected is how SOAR decides, not something the PTA represents.
     * PTA:  excluded from both enab and prob.
     */
    private boolean isOperatorBookkeeping(String var) {
        if (var == null) return false;
        return var.startsWith(OP_VAR_PREFIX)
                || var.startsWith(SUPERSTATE_PREFIX)
                || var.endsWith("_operator_name");
    }

    /**
     * What: gives the integer a clock is reset to (a literal is kept, anything else -> 0).
     * Why:  PRISM only resets a clock to an integer constant, never an expression on the clock.
     * PTA:  prob (a clock reset on an edge).
     */
    private String clockResetValue(String val) {
        if (val != null && val.trim().matches("-?\\d+")) return val.trim();
        return "0";
    }

    /**
     * What: keeps every real firing condition, dropping only SOAR's operator/init bookkeeping.
     * Why:  a state test is a guard no matter which variable it names — singling one out as
     *       "the location" and deleting it lost the test entirely when the edge was dropped.
     * PTA:  enab (the edge guards). Clock checks are rewritten by kind (relax accumulating,
     *       ">= bound" for a resetting tick).
     */
    private void addEnabGuards(LinkedHashSet<String> out, Rule rule) {
        if (rule == null) return;
        for (String g : rule.guards) {
            String var = g.split(" ")[0];
            if (isOperatorBookkeeping(var)) continue;   // chosen-operator / superstate marker
            if (clocks.contains(var)) {
                if (accumulatingClocks.contains(var)) {
                    out.add(relaxStrict(g));   // the deadline race; strict is banned on clocks
                } else {
                    String bound = model.clockBounds.get(var);
                    if (bound != null) out.add(var + " >= " + bound); // "reached the count"
                }
                continue;
            }
            out.add(g);
        }
    }

    /**
     * What: turns a strict clock comparison into its non-strict form (< to <=, > to >=).
     * Why:  PRISM's digital clocks reject strict on clocks; relaxing is sound and keeps the deadline race.
     * PTA:  enab (an accumulating-clock guard).
     */
    private String relaxStrict(String guard) {
        String[] tok = guard.trim().split("\\s+");
        if (tok.length == 3) {
            if ("<".equals(tok[1])) return tok[0] + " <= " + tok[2];
            if (">".equals(tok[1])) return tok[0] + " >= " + tok[2];
        }
        return guard;
    }

    // ===================================================================================
    // ===== 5. LABELS (L(l)) ============================================================
    // Terminal states -> PRISM `label "..."` lines (T5). Not built yet.
    // ===================================================================================


    // ===================================================================================
    // ===== 6. VALIDATION ===============================================================
    // Warn if the location, start location, or moves are missing. This doesn't stop
    // anything — it just makes gaps visible instead of silently wrong.
    // ===================================================================================

    /**
     * What: adds a warning whenever the location, start value, or moves are missing.
     * Why:  surface gaps loudly instead of emitting a silently-wrong module.
     * PTA:  none (diagnostics over the whole tuple).
     */
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
