package edu.fit.assist.translator.soar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MultiPtaEmitter {

    // Signals (io_signal / io_command) are how agents talk to each other. A move that
    // sends or reads one becomes a shared label, and the raw signal test is left out.
    private static final String IO_PREFIX = "state_io_";

    /** Any io name mentioned inside an expression, however deeply. */
    private static final Pattern IO_NAME = Pattern.compile("\\b" + IO_PREFIX + "[A-Za-z0-9_]*\\b");

    // ---- Block 5: whole-system resolution, decided once before any module is printed ----

    /** Open input -> the constant it becomes ("state_io_scenario_deadline" -> "deadline"). */
    private final Map<String, String> constants = new LinkedHashMap<>();

    /** Constant -> its PRISM type ("int" / "double"). */
    private final Map<String, String> constantTypes = new LinkedHashMap<>();

    /** io variables that SOME module declares — legal for another module to read. */
    private final Set<String> sharedIoVars = new LinkedHashSet<>();

    /** Names the module currently being printed declares itself. */
    private Set<String> moduleVars = new LinkedHashSet<>();

    /** Notes raised while emitting (e.g. a constant whose type had to be guessed). */
    public final List<String> warnings = new ArrayList<>();

    /**
     * What: writes ONE agent as a complete PRISM file ("pta" header + its module).
     * Why:  lets a single agent be translated or inspected on its own.
     * PTA:  one agent's whole tuple, printed as PRISM text.
     */
    public String emit(String agentName, MultiPtaModel model) {
        Map<String, MultiPtaModel> one = new LinkedHashMap<>();
        one.put(agentName, model);
        return emitSystem(one);
    }

    /**
     * What: writes ALL agents into one PRISM file — one "pta" header, then a module each.
     * Why:  a PRISM file allows only one "pta" line, so full files can't just be concatenated.
     * PTA:  every agent's tuple, composed into one system.
     */
    public String emitSystem(Map<String, MultiPtaModel> agents) {
        resolveOpenInputs(agents);   // Block 5 — must run before anything is printed

        StringBuilder sb = new StringBuilder();
        sb.append("pta\n\n");
        for (Map.Entry<String, String> c : constants.entrySet()) {
            sb.append("const ").append(constantTypes.get(c.getValue()))
              .append(" ").append(c.getValue()).append(";\n");
        }
        if (!constants.isEmpty()) sb.append("\n");

        for (Map.Entry<String, MultiPtaModel> agent : agents.entrySet()) {
            sb.append(emitModule(agent.getKey(), agent.getValue()));
            sb.append("\n");
        }
        return sb.toString();
    }


    // ===================================================================================
    // ===== BLOCK 5a: OPEN INPUTS -> CONSTANTS ==========================================
    // An io variable the agent READS but no module WRITES has no producer anywhere in the
    // system: it is handed in from outside, so in PRISM it is a constant, not state. That
    // test needs the whole system in view — one agent cannot tell whether another declares
    // the variable — which is why this runs here and not in the per-agent extractor.
    // ===================================================================================

    /**
     * What: finds every io name nobody declares, names it, and gives it a type.
     * Why:  those are the model's dials (deadline, box_accuracy); PRISM needs them declared.
     * PTA:  the constants a model is parameterised by.
     */
    private void resolveOpenInputs(Map<String, MultiPtaModel> agents) {
        constants.clear();
        constantTypes.clear();
        sharedIoVars.clear();

        Set<String> declared = new LinkedHashSet<>();
        for (MultiPtaModel m : agents.values()) {
            for (MultiPtaModel.StateVar sv : m.stateVars) {
                declared.add(sv.name);
                if (sv.name.startsWith(IO_PREFIX)) sharedIoVars.add(sv.name);
            }
            declared.addAll(m.clocks);
        }

        // Every io name that survives into printed text and belongs to no module.
        Set<String> open = new LinkedHashSet<>();
        for (MultiPtaModel m : agents.values()) {
            for (String expr : printedExpressions(m)) {
                Matcher mt = IO_NAME.matcher(expr);
                while (mt.find()) {
                    if (!declared.contains(mt.group())) open.add(mt.group());
                }
            }
        }

        for (String var : open) {
            constants.put(var, constantNameFor(var, agents.keySet(), declared));
        }
        for (MultiPtaModel m : agents.values()) {
            inferConstantTypes(m);
        }
        for (String name : constants.values()) {
            if (!constantTypes.containsKey(name)) {
                constantTypes.put(name, "int");
                warnings.add("constant '" + name + "': nothing in the rules shows whether it is a"
                        + " whole number or a fraction; assuming int");
            }
        }
    }

    /**
     * What: every expression that will actually reach the output for one agent.
     * Why:  scanning the raw model instead would pick up channel handshakes and dropped
     *       writes, and invent constants for names that are never printed.
     * PTA:  helper — mirrors what emitTransition keeps.
     */
    private List<String> printedExpressions(MultiPtaModel model) {
        List<String> out = new ArrayList<>();
        for (MultiPtaModel.PtaTransition t : model.transitions) {
            for (String g : t.guards) {
                if (!isChannel(model, g)) out.add(g);
            }
            for (String u : t.updates) {
                if (isWritable(model, u) && !isChannel(model, u)) out.add(u);
            }
            for (MultiPtaModel.Branch b : t.branches) {
                out.add(b.probability);
                for (String u : b.updates) {
                    if (isWritable(model, u) && !isChannel(model, u)) out.add(u);
                }
            }
        }
        out.addAll(model.clockBounds.values());
        return out;
    }

    /**
     * What: shortens an io path to a usable constant name, keeping it unique.
     * Why:  the path spells out where the value came in ("io.input-link.scenario.deadline");
     *       the owner segment is routing, and the attribute is what people actually name in a
     *       property or on the command line.
     * PTA:  helper — naming a constant.
     */
    private String constantNameFor(String var, Set<String> agentNames, Set<String> declared) {
        String rest = var.substring(IO_PREFIX.length());
        for (String owner : agentNames) {
            String prefix = sanitize(owner) + "_";
            if (rest.startsWith(prefix) && rest.length() > prefix.length()) {
                rest = rest.substring(prefix.length());
                break;
            }
        }
        // Keep lengthening back towards the full path if the short name is already taken.
        String candidate = rest;
        if (declared.contains(candidate) || constants.containsValue(candidate)) {
            candidate = var.substring(IO_PREFIX.length());
        }
        while (declared.contains(candidate) || constants.containsValue(candidate)) {
            candidate = "io_" + candidate;
        }
        return candidate;
    }

    /**
     * What: decides int vs double for each constant from the company it keeps.
     * Why:  Soar never states a type. But a value used as a probability must be a fraction,
     *       and one raced against a clock or matched to a whole number must be an integer.
     * PTA:  the constants' declarations.
     */
    private void inferConstantTypes(MultiPtaModel model) {
        for (MultiPtaModel.PtaTransition t : model.transitions) {
            for (MultiPtaModel.Branch b : t.branches) {
                Matcher mt = IO_NAME.matcher(b.probability);
                while (mt.find()) {
                    typeConstant(mt.group(), "double");     // a probability is a fraction
                }
            }
            for (String g : t.guards) {
                String[] tok = g.trim().split("\\s+");
                if (tok.length != 3) continue;
                boolean leftOpen = constants.containsKey(tok[0]);
                boolean rightOpen = constants.containsKey(tok[2]);
                if (leftOpen && (isNumber(tok[2]) || model.clocks.contains(tok[2]))) {
                    typeConstant(tok[0], "int");
                }
                if (rightOpen && (isNumber(tok[0]) || model.clocks.contains(tok[0]))) {
                    typeConstant(tok[2], "int");
                }
            }
            for (String u : allUpdates(t)) {
                String[] tok = u.trim().split("\\s+", 3);
                if (tok.length != 3 || !constants.containsKey(tok[2])) continue;
                MultiPtaModel.StateVar sv = model.stateVar(tok[0]);
                if (sv != null && sv.numeric) typeConstant(tok[2], "int"); // stored in an int slot
            }
        }
    }

    /**
     * What: records a constant's type, letting "double" win a disagreement.
     * Why:  a value used once as a fraction cannot be an integer, whatever else it is used for.
     * PTA:  helper — a constant's declaration.
     */
    private void typeConstant(String var, String type) {
        String name = constants.get(var);
        if (name == null) return;
        if ("double".equals(type) || !constantTypes.containsKey(name)) {
            constantTypes.put(name, type);
        }
    }

    /**
     * What: every write on an edge, certain or probabilistic.
     * Why:  several passes need to look at all of them without caring which kind it is.
     * PTA:  helper for prob.
     */
    private List<String> allUpdates(MultiPtaModel.PtaTransition t) {
        List<String> out = new ArrayList<>(t.updates);
        for (MultiPtaModel.Branch b : t.branches) out.addAll(b.updates);
        return out;
    }

    /**
     * What: swaps every open-input path in an expression for its constant name.
     * Why:  the long io path is where the value entered; the constant is what it IS.
     * PTA:  enab + prob (printed form).
     */
    private String resolveConstants(String expr) {
        if (expr == null || constants.isEmpty()) return expr;
        Matcher mt = IO_NAME.matcher(expr);
        StringBuffer sb = new StringBuffer();
        while (mt.find()) {
            String name = constants.get(mt.group());
            mt.appendReplacement(sb, Matcher.quoteReplacement(name == null ? mt.group() : name));
        }
        mt.appendTail(sb);
        return sb.toString();
    }

    /**
     * What: true if the module can legitimately mention this name.
     * Why:  a module may use its own variables and clocks, a constant, or an io variable some
     *       other module declares (that is how they share data). Anything else is Soar
     *       scaffolding — a self-applied tag, or a variable Block 1 judged not to be state —
     *       and has no counterpart in PRISM. This is 4a's write rule applied to reads.
     * PTA:  enab (which atoms survive).
     */
    private boolean isKnownName(String var) {
        return moduleVars.contains(var)
                || sharedIoVars.contains(var)
                || constants.containsKey(var)
                || constants.containsValue(var);
    }

    /**
     * What: writes one agent's "module ... endmodule" block (vars, clocks, invariant, edges).
     * Why:  each SOAR agent maps to exactly one PRISM module.
     * PTA:  L + X + inv + enab + prob for one agent.
     */
    public String emitModule(String agentName, MultiPtaModel model) {
        moduleVars = new LinkedHashSet<>();
        for (MultiPtaModel.StateVar sv : model.stateVars) moduleVars.add(sv.name);
        moduleVars.addAll(model.clocks);

        StringBuilder sb = new StringBuilder();
        sb.append("module ").append(sanitize(agentName)).append("\n\n");

        if (model.stateVars.isEmpty() && model.clocks.isEmpty() && model.transitions.isEmpty()) {
            sb.append("endmodule\n"); // nothing to describe -> empty module
            return sb.toString();
        }

        // L: one declaration per state variable (Block 1). An agent may legitimately have
        // none (a stateless sampler) — it still emits its edges.
        for (MultiPtaModel.StateVar sv : model.stateVars) {
            sb.append("    ").append(sv.declaration()).append(";\n");
        }
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

    /**
     * What: bounds how long the agent may sit in any state it can still act from.
     * Why:  SOAR has no clocks and cannot wait — every operator application costs exactly one
     *       decision cycle. So a state with an enabled operator must be bounded. By what:
     *         - the edge's own clock test, when it has one (that IS what the rules said);
     *         - otherwise one decision cycle (the pacing clock), the SOAR default.
     *       Stacking the pacing cap on top of a clock-gated edge would timelock any state whose
     *       only exit waits longer than a cycle (e.g. "count >= 6"), so it is a fallback, never
     *       an addition. A state with no outgoing edge is terminal: unbounded, time flows on.
     * PTA:  inv (the clock invariants).
     */
    private String emitInvariant(MultiPtaModel model) {
        List<String> clauses = new ArrayList<>();
        for (MultiPtaModel.PtaTransition t : model.transitions) {
            String where = stateCondition(model, t);
            if (where == null) continue;  // nothing declared to key the clause on

            boolean timed = false;
            for (String conj : t.guards) {
                String[] tok = conj.split("\\s+");
                if (tok.length != 3 || !model.clocks.contains(tok[0])) continue;
                timed = true;
                boolean bounds = "<=".equals(tok[1])                                   // a budget
                        || (">=".equals(tok[1]) && !model.accumulatingClocks.contains(tok[0]));
                if (bounds) {
                    addClause(clauses, "(" + where + " => " + tok[0] + "<="
                            + resolveConstants(tok[2]) + ")");
                }
            }
            // Fall back to one decision cycle only when the rules named no clock at all, and
            // only when acting actually escapes the condition — a cap the edge cannot relieve
            // would leave the agent unable to move and unable to let time pass.
            if (!timed && model.pacingClock != null && writesOwnCondition(model, t)) {
                addClause(clauses, "(" + where + " => " + model.pacingClock + "<=1)");
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

    /**
     * What: prints one edge — "[label] guards -> updates;" — encoding state words to integers.
     * Why:  each PTA edge becomes one PRISM command; a signal becomes a shared sync label.
     *       Nothing is dropped: an edge that touches no state variable is still a real edge.
     * PTA:  enab (guards) + prob (updates) + the sync label.
     */
    private String emitTransition(MultiPtaModel model, MultiPtaModel.PtaTransition t) {
        // The check: every test, minus the one that is really a channel handshake. State tests
        // are printed first and clock tests last — a fixed order keeps the output stable no
        // matter which order the rule happened to list its conditions in.
        List<String> guardParts = new ArrayList<>();
        List<String> timingParts = new ArrayList<>();
        for (String conj : t.guards) {
            if (isChannel(model, conj)) continue;
            String var = conj.trim().split("\\s+")[0];
            if (!isKnownName(var)) continue;   // Soar scaffolding, nothing to test in PRISM
            (model.stateVar(var) != null ? guardParts : timingParts)
                    .add(resolveConstants(encodeStateAtom(model, conj)));
        }
        guardParts.addAll(timingParts);

        // The label. If this move sends or reads a signal, use the shared channel name so
        // the two agents fire together. Otherwise just use the move's own name.
        String label = syncLabel(model, t);
        if (label == null) label = sanitize(t.action);

        String head = "    [" + label + "] "
                + (guardParts.isEmpty() ? "true" : String.join(" & ", guardParts))
                + " -> ";

        // A dice roll prints one line per outcome; a certain move prints its writes inline.
        if (!t.branches.isEmpty()) {
            StringBuilder sb = new StringBuilder(head.replaceAll("\\s+$", "")).append("\n");
            for (int i = 0; i < t.branches.size(); i++) {
                MultiPtaModel.Branch b = t.branches.get(i);
                List<String> parts = writeParts(model, t, b.updates);
                sb.append(i == 0 ? "          " : "        + ")
                  .append(resolveConstants(b.probability)).append(" : ")
                  .append(parts.isEmpty() ? "true" : parenJoin(parts))
                  .append(i < t.branches.size() - 1 ? "\n" : ";\n");
            }
            return sb.toString();
        }

        List<String> updateParts = writeParts(model, t, t.updates);
        return head + (updateParts.isEmpty() ? "true" : parenJoin(updateParts)) + ";\n";
    }

    /**
     * What: turns an outcome's raw writes into printable PRISM updates, plus the clock resets.
     * Why:  a write to something the module never declared cannot be expressed at all; a write
     *       that only mirrors another in the same edge is redundant; a channel send carries no
     *       data. The clocks reset whichever outcome is taken.
     * PTA:  prob (one outcome's updates).
     */
    private List<String> writeParts(MultiPtaModel model, MultiPtaModel.PtaTransition t,
                                    List<String> updates) {
        List<String> parts = new ArrayList<>();
        for (String u : updates) {
            if (!isWritable(model, u)) continue;
            if (isChannel(model, u) || isMirror(model, t, u)) continue;
            parts.add(prime(resolveConstants(encodeStateAtom(model, u))));
        }
        for (Map.Entry<String, String> e : t.clockUpdates.entrySet()) {
            parts.add(e.getKey() + "'=" + e.getValue());
        }
        return parts;
    }

    /**
     * What: joins an edge's tests on DECLARED state variables into one condition, or null.
     * Why:  an invariant clause must name states PRISM knows about; leftover scaffolding on
     *       undeclared variables would make the clause unresolvable.
     * PTA:  inv (the antecedent of a clause).
     */
    private String stateCondition(MultiPtaModel model, MultiPtaModel.PtaTransition t) {
        List<String> parts = new ArrayList<>();
        for (String conj : t.guards) {
            String var = conj.trim().split("\\s+")[0];
            if (model.stateVar(var) == null) continue;
            parts.add(encodeStateAtom(model, conj));
        }
        return parts.isEmpty() ? null : String.join(" & ", parts);
    }

    /**
     * What: true if the edge writes one of the very variables its own state condition tests.
     * Why:  a "you must act by now" cap is only safe if acting gets the agent out of the state
     *       it caps. An edge that leaves those variables untouched still satisfies the guard
     *       afterwards, so the cap would bind forever with no way to relieve it.
     * PTA:  inv (guards the pacing fallback).
     */
    private boolean writesOwnCondition(MultiPtaModel model, MultiPtaModel.PtaTransition t) {
        List<String> tested = new ArrayList<>();
        for (String conj : t.guards) {
            String var = conj.trim().split("\\s+")[0];
            if (model.stateVar(var) != null) tested.add(var);
        }
        for (String u : t.updates) {
            if (tested.contains(u.trim().split("\\s+")[0])) return true;
        }
        return false;
    }

    /**
     * What: adds a clause unless it is already there.
     * Why:  several edges out of the same state produce the same bound; print it once.
     * PTA:  inv (clause list).
     */
    private void addClause(List<String> clauses, String clause) {
        if (!clauses.contains(clause)) clauses.add(clause);
    }

    /**
     * What: returns a move's shared channel name if it reads/sends a signal, else null.
     * Why:  two agents sharing a signal must carry the SAME label to fire together.
     * PTA:  the edge's action label (synchronization).
     */
    private String syncLabel(MultiPtaModel model, MultiPtaModel.PtaTransition t) {
        for (String conj : t.guards) {                  // reads a signal
            if (isChannel(model, conj)) return channelName(conj);
        }
        for (String u : t.updates) {                    // sends a signal
            if (isChannel(model, u) && !isMirror(model, t, u)) return channelName(u);
        }
        return null;
    }

    /**
     * What: true if this module can actually assign the variable an update names.
     * Why:  a module writes its own declared state, its clocks, or the io variable it owns.
     *       Block 1 deliberately drops variables that turned out not to be state (a domain of
     *       one value distinguishes nothing); an assignment left pointing at one of those has
     *       no target and cannot be emitted.
     * PTA:  prob (which updates survive).
     */
    private boolean isWritable(MultiPtaModel model, String update) {
        String var = update.trim().split("\\s+")[0];
        if (constants.containsKey(var)) return false;   // an input is handed in, never assigned
        return model.stateVar(var) != null || model.clocks.contains(var) || var.startsWith(IO_PREFIX);
    }

    /**
     * What: true if an io atom is a handshake between agents rather than ordinary data.
     * Why:  an io variable can be either, and only the value it is set against tells them apart.
     *       A NAMED event ("io_signal = transition-mission") is something both agents agree
     *       happened — a channel. Another VARIABLE ("io_attacker_id = state_current_person_id")
     *       is the agent checking what it was told against what it knows. A NUMBER
     *       ("io_use_box = 1") is a setting it was handed. Only the first is an event; the other
     *       two are data and must stay in the command. Treating the second as a channel deleted
     *       the test and collapsed hit and miss into one edge; the third would have merged the
     *       two halves of the dice roll into one.
     * PTA:  the sync label vs enab / prob.
     */
    private boolean isChannel(MultiPtaModel model, String atom) {
        if (atom == null) return false;
        String[] tok = atom.trim().split("\\s+");
        if (tok.length != 3) return false;
        if (!tok[0].startsWith(IO_PREFIX)) return false;
        if (!"=".equals(tok[1])) return false;              // <, >, != are data comparisons
        if (model.knownVars.contains(tok[2])) return false; // a variable => data
        return !isNumber(tok[2]);                           // a named event => channel
    }

    /**
     * What: true if a token is a plain number.
     * Why:  a number handed over io is a setting, never the name of an event.
     * PTA:  helper for the channel test.
     */
    private boolean isNumber(String s) {
        if (s == null || s.isEmpty()) return false;
        try { Double.parseDouble(s.trim()); return true; } catch (NumberFormatException e) { return false; }
    }

    /**
     * What: true if an io write just copies a value the edge already writes to declared state.
     * Why:  agents often mirror their working memory onto the output link for an outside
     *       reader. The mirror carries no new information and is not an event, so it must not
     *       become a channel or a second update. It only counts as a mirror when the variable
     *       it copies is one the module really declared — otherwise the io write is the only
     *       surviving copy and dropping it would lose the value entirely.
     * PTA:  prob (a redundant write) + the sync label (not an event).
     */
    private boolean isMirror(MultiPtaModel model, MultiPtaModel.PtaTransition t, String update) {
        String[] tok = update.trim().split("\\s+", 3);
        if (tok.length != 3 || !tok[0].startsWith(IO_PREFIX)) return false;
        for (String other : t.updates) {
            if (other.equals(update)) continue;
            String[] o = other.trim().split("\\s+", 3);
            if (o.length == 3 && model.stateVar(o[0]) != null && o[2].equals(tok[2])) return true;
        }
        return false;
    }

    /**
     * What: builds a channel name from a signal — "state_io_command = lower" -> "state_io_command_lower".
     * Why:  sender and reader derive it identically, so their labels always match.
     * PTA:  the edge's action label (channel).
     */
    private String channelName(String ioRef) {
        String[] tok = ioRef.trim().split("\\s+");
        String var = tok[0];
        String val = tok[tok.length - 1];
        return sanitize(var + "_" + val);
    }

    /**
     * What: rewrites "var = value" to "var=code" when var is a state variable (word -> integer).
     * Why:  PRISM stores state as integers, so a guard/update must use the code, not the raw word.
     *       Comparisons other than =/!= (a counter's "< 20") are copied through untouched: the
     *       value is already a number and the strictness is meaningful on an integer.
     * PTA:  enab + prob (integer-encoded atoms).
     */
    private String encodeStateAtom(MultiPtaModel model, String atom) {
        if (atom == null) return null;
        String[] tok = atom.trim().split("\\s+");
        if (tok.length != 3) return atom;
        // Soar writes "not equal" as <>; PRISM writes it as !=. Same test, different spelling.
        if ("<>".equals(tok[1])) tok[1] = "!=";
        String normalized = tok[0] + " " + tok[1] + " " + tok[2];
        MultiPtaModel.StateVar sv = model.stateVar(tok[0]);
        if (sv == null) return normalized;
        if (!"=".equals(tok[1]) && !"!=".equals(tok[1])) return normalized;
        int code = sv.code(tok[2]);
        if (code < 0) return normalized; // value not in the domain (yet) -> leave it visible
        return tok[0] + tok[1] + code;
    }

    // ---- small helpers ----

    /**
     * What: turns "var = value" into PRISM's "var'=value" (the prime = "its new value").
     * Why:  a PRISM update writes the primed variable on the right of an edge.
     * PTA:  prob (an edge update).
     */
    private String prime(String update) {
        int eq = update.indexOf('=');
        if (eq < 0) return update.trim();
        return update.substring(0, eq).trim() + "'=" + update.substring(eq + 1).trim();
    }

    /**
     * What: wraps each update in ( ) and joins them with " & ".
     * Why:  PRISM edge updates are parenthesized and conjoined.
     * PTA:  prob (formatting the update list).
     */
    private String parenJoin(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(" & ");
            sb.append("(").append(parts.get(i)).append(")");
        }
        return sb.toString();
    }

    /**
     * What: replaces "-" with "_" in a name.
     * Why:  PRISM identifiers can't contain hyphens.
     * PTA:  helper (legal module/label names).
     */
    private String sanitize(String name) {
        return (name == null) ? null : name.replace('-', '_');
    }
}
