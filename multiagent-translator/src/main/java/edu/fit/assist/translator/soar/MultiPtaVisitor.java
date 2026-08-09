package edu.fit.assist.translator.soar;
import java.util.ArrayList;
import java.util.List;
import edu.fit.assist.translator.gen.*;
import org.antlr.v4.runtime.tree.AbstractParseTreeVisitor;

// Copy of Visitor.java for the PTA path, with two fixes:
//   (1) varType is populated here (the DTMC path relied on Output.generateType()).
//   (2) init-value detection is structural (^superstate nil), not name-based.
public class MultiPtaVisitor<Object> extends AbstractParseTreeVisitor<Object> implements SoarVisitor<Object> {
    /** Where a numeric-indifferent preference's weight is filed on its rule (T3, softmax). */
    static final String OP_PREFERENCE_VAR = "state_operator_preference";

    /** A preference whose whole weight is one bound variable, e.g. the "<b>" of "^operator <o> = <b>". */
    private static final java.util.regex.Pattern BARE_PREF_VARIABLE =
            java.util.regex.Pattern.compile("=\\s*(<[^<>\\s]+>)\\s*$");

    Rule currentRule;
    String currentContext = "";
    boolean negateCondition = false;

    public SoarRules rules;
    String currentActionContextVar = "";

    /**
     * Visit a parse tree produced by {@link SoarParser#soar}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitSoar(SoarParser.SoarContext ctx) {
        Object result = visitChildren(ctx);
        // FIX 1: populate Variable.varType for every variable. The DTMC path did this in
        // Output.generateType(), which never runs on the PTA path, leaving varType=INVALID.
        for (Variable var : rules.variables.values()) {
            var.generateType();
        }
        // FIX 3 (M-7): clean up guard strings once every rule is built. A condition like
        // `^wall-clock <wc> < <deadline>` makes the parser mint an auto-var (<w*1>) for the
        // attribute and bind it in contextMap only AFTER the guard string was assembled, so
        // the guard keeps the raw `<w*1>` token. We (a) resolve any leftover <..> token to
        // its variable, and (b) canonicalize an ordered comparison so the agent's OWN
        // variable sits on the left (an io input is a bound, not the subject) — which is
        // also what lets clock detection see which quantity is bounded.
        for (Rule r : rules.rules) {
            for (int i = 0; i < r.guards.size(); i++) {
                r.guards.set(i, canonicalizeGuard(r.guards.get(i), r));
            }
        }
        return result;
    }

    /**
     * What: resolves any leftover <..> token in a guard and orients "io-var op state-var" comparisons.
     * Why:  a late-bound auto-var (<w*1>) can survive in the string, and clock detection needs the
     *       agent's own variable on the left ("wall_clock < deadline", not "deadline > <w*1>").
     * PTA:  feeds enab (clean guards) and X (lets a bounded var be seen as a clock).
     */
    private String canonicalizeGuard(String guard, Rule r) {
        if (guard == null) return guard;
        String[] tok = guard.trim().split("\\s+");
        if (tok.length != 3) return guard;              // only plain "A op B" guards
        String a = resolveGuardToken(tok[0], r);
        String op = tok[1];
        String b = resolveGuardToken(tok[2], r);
        // an ordered test between two variables: put the non-io (agent's own) var first,
        // flipping the operator, so the bounded quantity is the guard's subject.
        if (isOrderedRel(op) && isVarToken(a) && isVarToken(b)
                && isIoVar(a) && !isIoVar(b)) {
            return b + " " + flipRel(op) + " " + a;
        }
        return a + " " + op + " " + b;
    }

    /**
     * What: replaces a bound-variable token (<x>) with the variable it maps to, else leaves it.
     * Why:  the parser may bind an auto-var in contextMap only after the guard string was built.
     * PTA:  helper for enab (guard cleanup).
     */
    private String resolveGuardToken(String t, Rule r) {
        return (t.startsWith("<") && t.endsWith(">") && r.contextMap.containsKey(t))
                ? r.contextMap.get(t) : t;
    }

    /**
     * What: true if a token names a model variable (starts with "state_").
     * Why:  orientation only applies between two real variables, not a number.
     * PTA:  helper for enab (guard orientation).
     */
    private static boolean isVarToken(String t) {
        return t != null && t.startsWith("state_");
    }

    /**
     * What: true if a token is an io-link variable (starts with "state_io_").
     * Why:  an io input is a bound, so it belongs on the RIGHT of a comparison.
     * PTA:  helper for enab (guard orientation).
     */
    private static boolean isIoVar(String t) {
        return t != null && t.startsWith("state_io_");
    }

    /**
     * What: true if the operator is an ordered comparison (< > <= >=).
     * Why:  only ordered tests have a direction worth flipping; "=" / "!=" are symmetric.
     * PTA:  helper for enab (guard orientation).
     */
    private static boolean isOrderedRel(String op) {
        return "<".equals(op) || ">".equals(op) || "<=".equals(op) || ">=".equals(op);
    }

    /**
     * What: returns the mirrored operator (< <-> >, <= <-> >=).
     * Why:  swapping the two sides of a comparison must reverse its direction.
     * PTA:  helper for enab (guard orientation).
     */
    private static String flipRel(String op) {
        switch (op) {
            case "<":  return ">";
            case ">":  return "<";
            case "<=": return ">=";
            case ">=": return "<=";
            default:   return op;
        }
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#soar_production}.
     * Runs when Parser encounters a New Production
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitSoar_production(SoarParser.Soar_productionContext ctx) {

        //Create a new rule
        String ruleName = ctx.sym_constant().getText();
        boolean currentIsElaboration;
//        System.out.println(ctx.sym_constant().Sym_constant().getText());
        currentIsElaboration = ruleName.contains("elaborate");
        // System.out.println(ruleName);
        rules.createNewRule(ruleName);


        // Get all the statements that precede the arrow (-->)
        SoarParser.Condition_sideContext conditions = ctx.condition_side();

        currentRule = rules.getRuleByName(ruleName);
        currentRule.isElaboration = currentIsElaboration;
        // sp{ ruleName
        visit(ctx.condition_side());
        // -->
        visit(ctx.action_side());
        //}
        return null;
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#flags}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitFlags(SoarParser.FlagsContext ctx) {
        return visitChildren(ctx);
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#condition_side}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitCondition_side(SoarParser.Condition_sideContext ctx) {

        visit(ctx.state_imp_cond());
        // Visit the rest of the guards (if any)
        List<SoarParser.CondContext> guards = ctx.cond();
        for(SoarParser.CondContext guard : guards){
            visit(guard);
        }
        return null;
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#state_imp_cond}.
     * Assumes first guard starts with (state <s> ...)
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitState_imp_cond(SoarParser.State_imp_condContext ctx) {

        String stateKeyword = ctx.STATE().getSymbol().getText();
        currentContext = stateKeyword;
        // get '<s>'
        String var = (String)visit(ctx.id_test().test().simple_test());

//        System.out.println(var);
        currentRule.addContext(var, currentContext);

        for(SoarParser.Attr_value_testsContext attribute : ctx.attr_value_tests()){
            visit(attribute);
        }

        return null;
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#cond}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitCond(SoarParser.CondContext ctx) {

        negateCondition = ctx.Negative_pref() != null;
        visit(ctx.positive_cond());
        return null;
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#positive_cond}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitPositive_cond(SoarParser.Positive_condContext ctx) {

        visit(ctx.conds_for_one_id());
        return null;
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#conds_for_one_id}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitConds_for_one_id(SoarParser.Conds_for_one_idContext ctx) {

        // get '<var>'
        String var = (String)visit(ctx.id_test().test().simple_test());
        currentContext = currentRule.getContext(var);
//        System.out.println(var);


        for(SoarParser.Attr_value_testsContext attribute : ctx.attr_value_tests()){
            visit(attribute);
        }
        return null;
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#id_test}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitId_test(SoarParser.Id_testContext ctx) {
        return visitChildren(ctx);
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#attr_value_tests}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitAttr_value_tests(SoarParser.Attr_value_testsContext ctx) {

        String line = ctx.getText();
//        System.out.println(line);
        String variable = ctx.attr_test(0).getText();
        variable = currentContext+"_"+variable;
        variable = cleanVariableName(variable);
//        System.out.println(currentContext+"_"+variable);


        // get value check
        List<SoarParser.Value_testContext> values = ctx.value_test();
        boolean existsCheck = values.size()==0;

        if (!variable.equals("state_io") && !variable.equals("state_operator")){
            currentRule.addVariable(variable);
            rules.addVariableValue(variable, "nil");
        }

        // Attribute has no value, so guard is checking for existance
        if(existsCheck){
            if(ctx.Negative_pref() != null){ // check for not existing
                currentRule.addGuard(variable+" = nil");
            }else{ // check for presence
                currentRule.addGuard(variable+" != nil");
            }
        }else{ // Attribute has a value
            String value = (String)visit(values.get(0));

//            System.out.println(variable + " " + value);

            // Handle null value case: never drop silently — flag what we couldn't represent.
            if (value == null) {
                System.out.println("WARNING: [MultiPtaVisitor] dropped unhandled value test '"
                        + values.get(0).getText() + "' on " + variable
                        + " in rule " + currentRule.ruleName);
                return null;
            }

            // 4b: disjunction << a b >> — record each member as an observed value AND keep a
            // faithful "one of" guard. Checked BEFORE 4a because JSoar may wrap a lone
            // disjunction inside conjunctive braces { }, in which case `value` is the "<< .. >>".
            if (value.startsWith("<<") && value.endsWith(">>")) {
                for (String member : value.substring(2, value.length() - 2).trim().split("\\s+")) {
                    if (!member.isEmpty()) rules.addVariableValue(variable, member);
                }
                currentRule.addGuard(variable + " " + value);
                return null;
            }

            // 4a: conjunctive/range test { > 0 < 100 } — keep every part (one guard per bound),
            // read straight off the parse tree so nothing is lost.
            SoarParser.TestContext testCtx = values.get(0).test();
            if (testCtx != null && testCtx.conjunctive_test() != null) {
                for (SoarParser.Simple_testContext st : testCtx.conjunctive_test().simple_test()) {
                    String part = (String) visit(st);
                    if (part != null && !part.isEmpty()) {
                        if (part.startsWith("<") && part.endsWith(">") && !part.contains(" ")) {
                            currentRule.addContext(part, variable); // a bound variable like <c>
                        } else {
                            currentRule.addGuard(variable + " " + part);
                            String num = part.replaceAll("^[<>=!]+\\s*", "").trim(); // record a numeric bound
                            try { Integer.parseInt(num); rules.addVariableValue(variable, num); } catch (Exception ignore) {}
                        }
                    } else if (st.disjunction_test() != null) {
                        // a disjunction nested in a multi-part conjunctive — don't drop it
                        String disj = (String) visit(st.disjunction_test());
                        if (disj != null && disj.startsWith("<<") && disj.endsWith(">>")) {
                            for (String member : disj.substring(2, disj.length() - 2).trim().split("\\s+")) {
                                if (!member.isEmpty()) rules.addVariableValue(variable, member);
                            }
                            currentRule.addGuard(variable + " " + disj);
                        }
                    }
                }
                return null;
            }

            // Check is value is in the format: <x>
            String[] valueList =  value.split(" ");
            String attributePartOfValue = valueList[valueList.length-1];
//            if(currentRule.ruleName.startsWith("top-state*elaborate*error-info*warn-condition*low")){
//                //System.err.println(attributePartOfValue +"; "+attributePartOfValue+"E");
//            }
            if(!((value.startsWith("<") && value.endsWith(">") && !value.contains(" "))|| attributePartOfValue.contains("*"))){
                //if(value.contains("*")){
                //System.err.println(value +"; "+attributePartOfValue+"E");
                //}
                // Removes the conditional check from value
                String onlyValue = value.replaceAll("<= ", "").replaceAll(">= ", "").replaceAll("= ", "");
                onlyValue = onlyValue.replaceAll("< ", "").replaceAll("> ", "");

                // Don't add inequalities to the list of values a variable can have
                if(onlyValue.equals(value)){
                    rules.addVariableValue(variable, onlyValue);
                }else{
                    // It is an inequality, hence we can use it for setting Types
                    // Example: ^var1 <= <v2>, where <v2> is ^var2
                    // Hence, type of ^var1 is the same as ^var2
                    String leftSide = variable;
                    // if value is a number directly add the type instead of adding it to the graph

                    try {
                        int rightValue = Integer.parseInt(onlyValue);
                        rules.addVariableValue(variable, onlyValue);
                    }catch(Exception e){}

                    try {
                        double rightValue = Double.parseDouble(onlyValue);
                        rules.addVariableValue(variable, onlyValue);
                    }catch(Exception e2){}

                    String rightSide = currentRule.getContext(onlyValue);
//                    System.out.println(leftSide);
//                    System.out.println(rightSide);
                    rules.addTypeNode(leftSide, rightSide);
                    rules.addTypeNode(rightSide, leftSide);

                }

                for(String key : currentRule.contextMap.keySet()){
                    if(value.contains(key)){
                        value = value.replaceAll(key, currentRule.contextMap.get(key));
                        break;
                    }
                }

                if(value.contains("<") || value.contains(">")){
                    currentRule.addGuard(variable+" "+value);
                }else{
                    if(ctx.Negative_pref() != null ^ negateCondition){
                        currentRule.addGuard(variable+" != "+value);

                        if(rules.variables.containsKey(value)){
                            String leftSide = variable;
                            String rightSide = value;
                            //System.out.println(leftSide);
                            //System.out.println(rightSide);
                            rules.addTypeNode(leftSide, rightSide);
                            rules.addTypeNode(rightSide, leftSide);
                        }


                    }else{
                        currentRule.addGuard(variable+" = "+value);

                    }
                }
            }else{

                // check for negation

                // Negate if one negation exists, if two exist, then don't
                /*if(ctx.Negative_pref() != null ^ negateCondition){

                    value = "!" + value;
                    currentRule.addContext(value, variable);
                }else{
                    currentRule.addContext(value, variable);
                }*/
                if(!currentRule.contextMap.containsKey(value)){
                    // assignment
                    currentRule.addContext(value, variable);
                }else{
                    // equality check

                    String leftSide = variable;
                    value = currentRule.getContext(value);
                    String rightSide = value;
                    //System.out.println(leftSide);
                    //System.out.println(rightSide);
                    rules.addTypeNode(leftSide, rightSide);
                    rules.addTypeNode(rightSide, leftSide);


                    if(ctx.Negative_pref() != null ^ negateCondition){
                        currentRule.addGuard(variable+" != "+value);
                    }else{
                        currentRule.addGuard(variable+" = "+value);
                    }

                }
            }
        }
        return null;
    }

    public String cleanVariableName(String s){
        s=s.replaceAll("\\_output\\-link", "");
        s=s.replaceAll("\\_input\\-link", "");
        s=s.replaceAll("\\_systemdata", "");
        // Normalize remaining hyphens to underscores so every spelling of a variable matches
        // the contextMap (which Rule.addContext already stores with underscores). Done after the
        // link/systemdata strips above, which rely on the hyphens still being present.
        s=s.replace("-", "_");
        return s;
    }

    // Soar top/initial-state markers. ^superstate nil is the definitive marker of the state
    // created in decision cycle 0; ^type state is the official equivalent (Soar tutorial) and is
    // what some agents (e.g. train_v2) use. Both are architecture-reserved, so this is domain-free.
    private static boolean isTopStateMarker(String guard) {
        return "state_superstate = nil".equals(guard) || "state_type = state".equals(guard);
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#attr_test}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitAttr_test(SoarParser.Attr_testContext ctx) {
        return visitChildren(ctx);
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#value_test}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitValue_test(SoarParser.Value_testContext ctx) {

        SoarParser.TestContext testContext = ctx.test();
        if(testContext == null){
            return null;
        }
        return visit(testContext);

    }

    /**
     * Visit a parse tree produced by {@link SoarParser#test}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitTest(SoarParser.TestContext ctx) {

        // Case 1: it is a 'simple test'
        if(ctx.simple_test() != null){
            return visit(ctx.simple_test());
        }
        // Case 2: it is a 'Conjunctive test'
        if(ctx.conjunctive_test() != null){
            return visit(ctx.conjunctive_test());
        }
        return null;
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#conjunctive_test}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitConjunctive_test(SoarParser.Conjunctive_testContext ctx) {

        //System.err.println("Conjunctive test not supported in the following "+ctx.getText());
        for(SoarParser.Simple_testContext temp:ctx.simple_test()){
            return visitChildren(temp);
        }
        return visitChildren(ctx);

    }

    /**
     * Visit a parse tree produced by {@link SoarParser#simple_test}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitSimple_test(SoarParser.Simple_testContext ctx) {

        if(ctx.relational_test() != null){
            return visit(ctx.relational_test());
        }
        return null;
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#multi_value_test}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitMulti_value_test(SoarParser.Multi_value_testContext ctx) {
        return visitChildren(ctx);
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#disjunction_test}.
     * Extracts values from << value1 value2 ... >> syntax
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitDisjunction_test(SoarParser.Disjunction_testContext ctx) {
        // Extract all constants from << ... >>
        List<SoarParser.ConstantContext> constants = ctx.constant();
        if (constants != null && !constants.isEmpty()) {
            StringBuilder result = new StringBuilder();
            result.append("<< ");
            for (int i = 0; i < constants.size(); i++) {
                result.append(constants.get(i).getText());
                if (i < constants.size() - 1) {
                    result.append(" ");
                }
            }
            result.append(" >>");
            return (Object) result.toString();
        }
        return visitChildren(ctx);
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#relational_test}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitRelational_test(SoarParser.Relational_testContext ctx) {

        String output = "";
        if(ctx.relation() != null){
            output += (String)visit(ctx.relation());
        }


        if(ctx.single_test() != null){
            output += (String)visit(ctx.single_test());
        }


        return (Object)output;

    }

    /**
     * Visit a parse tree produced by {@link SoarParser#relation}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitRelation(SoarParser.RelationContext ctx) {

        return (Object)(ctx.getText()+" ");
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#single_test}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitSingle_test(SoarParser.Single_testContext ctx) {

        return visitChildren(ctx);
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#variable}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitVariable(SoarParser.VariableContext ctx) {

        // return variable name (delegate to visitSym_constant so `state` is handled too)
        return (Object)("<" + visit(ctx.sym_constant()) + ">");
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#constant}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitConstant(SoarParser.ConstantContext ctx) {
//        System.out.println(ctx.getText());
        if (ctx.sym_constant() != null){
            return visit(ctx.sym_constant()); // delegate so `state`-as-a-value is handled too
        }
        if(ctx.Int_constant() != null){
            return (Object)ctx.Int_constant().getSymbol().getText();
        }
        if(ctx.Float_constant() != null){
            return (Object)ctx.Float_constant().getSymbol().getText();
        }
        if(ctx.Print_string() != null){
            return (Object)ctx.Print_string().getSymbol().getText();
        }
        return null;
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#action_side}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitAction_side(SoarParser.Action_sideContext ctx) {

        if(ctx.action() != null){
            for(SoarParser.ActionContext action : ctx.action()){
                visit(action);
            }
        }
        return null;
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#action}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitAction(SoarParser.ActionContext ctx) {
        // context
        String contextVar = (String)visit(ctx.variable());
        currentActionContextVar = contextVar;
//        System.out.println(contextVar);
        currentContext = currentRule.getContext(contextVar);
        List<SoarParser.Attr_value_makeContext> attributes = ctx.attr_value_make();
        for(SoarParser.Attr_value_makeContext attribute : attributes){
            visit(attribute);
        }
        return null;
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#print}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitPrint(SoarParser.PrintContext ctx) {
        return visitChildren(ctx);
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#func_call}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitFunc_call(SoarParser.Func_callContext ctx) {

        String operation = (String)visit(ctx.func_name());
        String operand1 = (String)visit(ctx.value(0));
        String operand2 = (String)visit(ctx.value(1));
        // Soar's prefix form is unambiguous; infix is not. Keep the bracketing of a nested
        // operand or "(/ (- 1.0 <b>) 19)" flattens to "1.0 - <b> / 19", which reads as
        // 1.0 - (<b>/19) — a different number.
        String infixValue = group(operand1) + " " + operation + " " + group(operand2);

        return (Object)infixValue;
    }

    /**
     * Wrap an operand in brackets when it is itself a compound expression.
     * A bare name or number is left alone, so simple arithmetic keeps its existing shape.
     */
    private String group(String operand) {
        if (operand == null || operand.trim().isEmpty()) return operand;
        String s = operand.trim();
        return s.contains(" ") ? "(" + s + ")" : s;
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#func_name}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitFunc_name(SoarParser.Func_nameContext ctx) {

        return (Object)ctx.getText();

    }

    /**
     * Visit a parse tree produced by {@link SoarParser#value}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitValue(SoarParser.ValueContext ctx) {
//        System.out.println(ctx.getText());
        return visitChildren(ctx);
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#attr_value_make}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitAttr_value_make(SoarParser.Attr_value_makeContext ctx) {
        String attributeName = (String)visit(ctx.variable_or_sym_constant(0).sym_constant());
        String variable = currentContext + "_" + attributeName;
        variable = cleanVariableName(variable);
        SoarParser.Value_makeContext value = ctx.value_make();
        currentContext = variable;
        String val = (String)visit(value);

        // Never drop silently — flag an action value we couldn't represent.
        if (val == null) {
            System.out.println("WARNING: [MultiPtaVisitor] dropped unhandled action value '"
                    + value.getText() + "' on " + variable
                    + " in rule " + currentRule.ruleName);
            return null;
        }

        // Handle symbolic variable references.
        // A LONE <x> only: an expression whose last operand happens to be a variable
        // ("<wc> + <bt>") also starts with '<' and ends with '>', and reading that as a
        // variable reference silently drops the write. Arithmetic always contains a space.
        if (val.matches("<[^<>\\s]+>")) {
            if (currentRule.contextMap.containsKey(val) && !currentRule.getContext(val).equals(variable)) {
                String resolved = currentRule.getContext(val);
                currentRule.addAttrValue(variable, resolved);
                rules.addVariableValue(variable, "^VAR" + resolved);
                rules.addTypeNode(variable, resolved);
                rules.addTypeNode(resolved, variable);
            } else {
                currentRule.addContext(val, variable);
            }
            return null;
        }

        // Resolve EVERY bound variable <x> in the value to its context name, any position/count.
        // e.g. "<c> + 1" -> "state_train_count + 1", "1 + <c>" -> "1 + state_train_count".
        // Faithful math capture — no role label; the extractor reads the arithmetic itself (5b).
        if (val.contains("<") && val.contains(">")) {
            for (String key : currentRule.contextMap.keySet()) {
                if (val.contains(key)) {
                    val = val.replace(key, currentRule.contextMap.get(key));
                }
            }
        }

        // Store actual value
        currentRule.addAttrValue(variable, val);
        rules.addVariableValue(variable, val);

        // FIX 2: track initial value for INIT rules, identified structurally by a Soar
        // top-state marker as the sole condition, not by rule name (the old
        // isElaboration = ruleName.contains("elaborate") flag missed train*init/controller*init).
        // Recognizes both ^superstate nil and its documented equivalent ^type state.
        if (currentRule.guards.size() == 1 && isTopStateMarker(currentRule.guards.get(0))) {
            rules.variables.get(variable).initialValue = val;
        }

        return null;
    }


    /**
     * Visit a parse tree produced by {@link SoarParser#variable_or_sym_constant}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitVariable_or_sym_constant(SoarParser.Variable_or_sym_constantContext ctx) {
        return visitChildren(ctx);
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#value_make}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitValue_make(SoarParser.Value_makeContext ctx) {
//        System.out.println(ctx.getText());
        String value = "";
        String pref_specifier = null;
        value = (String)visit(ctx.value(0));
        // get the value of the attribute Example: <o> in (<s> ^operator <o> +)
        if(ctx.pref_specifier(2) != null){

            pref_specifier = (String)visit(ctx.pref_specifier(2));
//            System.out.println(pref_specifier);
        }


        // visit the preference specifier Example = 0.0 in (<s> ^opetator <o> = 0.0)
        if(ctx.pref_specifier(0) != null){
            pref_specifier = (String)visit(ctx.pref_specifier(0));
//            System.out.println(currentContext);
//            System.out.println(value +" " +pref_specifier);
            // check for second preference specifier Example = in (<s> ^opetator <o> >,=)
            if(ctx.pref_specifier(1) != null){
                pref_specifier += ","+(String)visit(ctx.pref_specifier(1));
            }


        }

        if(currentContext.equals("state_operator") && pref_specifier != null){
            // Check if rule is a learning Rule (<s> ^operator <o> = 0.0)
            //System.out.println(pref_specifier);
            // check if rule is a learning rule
            if(pref_specifier.contains("=")){
                currentRule.isLearningRule = true;
            }
            // if it is a learning rule, get the priority
            if(pref_specifier.startsWith("=") && pref_specifier.length() > 1){

                // The text after "=" is this rule's WEIGHT: with softmax on, Soar picks among
                // the tied proposals in proportion to it, which is exactly a probability
                // distribution (T3). Keep it verbatim — a weight is usually symbolic
                // ("<b>", "(/ (- 1.0 <b>) 19)") and only the plain-number case fits the
                // numeric `priority` field. Filed under state_operator_* so every block that
                // skips operator bookkeeping already ignores it.
                String weight = pref_specifier.substring(1).trim();
                // A weight that is just a bound variable ("= <b>") comes back garbled: the
                // shipped parser reads the opening "<" as a preference symbol of its own and
                // splits the name apart. That one shape is unambiguous in the source text, so
                // take it from there. Compound weights parse correctly and are left alone.
                java.util.regex.Matcher bare = BARE_PREF_VARIABLE.matcher(ctx.getText());
                if(bare.find()){
                    weight = bare.group(1);
                }
                for(String key : currentRule.contextMap.keySet()){
                    if(weight.contains(key)){
                        weight = weight.replace(key, currentRule.contextMap.get(key));
                    }
                }
                if(!weight.isEmpty()){
                    currentRule.addAttrValue(OP_PREFERENCE_VAR, weight);
                }
                // set priority value (numeric weights only; a symbolic one simply has none)
                try{
                    currentRule.priority = Double.parseDouble(weight);
                }catch(NumberFormatException ignored){}
                // Search for the name of the operator of which we are setting the preference.
                String op_name = "";
                for(String guard : currentRule.guards){

                    if(guard.startsWith("state_operator_name = ")){
                        op_name = guard.substring("state_operator_name = ".length());
                    }
                }
                // Only adopt a name found in the GUARDS (apply/learning rules put it there).
                // A PROPOSE rule names its operator in the ACTION (^name ...), already stored
                // in valueMap; don't clobber that with an empty guard-derived name (this is
                // what blanked the softmax fan's operator name and dropped one propose rule).
                if(!op_name.isEmpty()){
                    currentRule.addAttrValue("state_operator_name", op_name);
                }
                // check if rule has max priority
            }else if(pref_specifier.contains(">")){
                // set priority value
                currentRule.priority = 10_000;
                // Search for the name of the operator of which we are setting the preference.
                String op_name = "";
                for(String guard : currentRule.guards){

                    if(guard.startsWith("state_operator_name = ")){
                        op_name = guard.substring("state_operator_name = ".length());
                    }
                }
                // Only adopt a name found in the GUARDS (apply/learning rules put it there).
                // A PROPOSE rule names its operator in the ACTION (^name ...), already stored
                // in valueMap; don't clobber that with an empty guard-derived name (this is
                // what blanked the softmax fan's operator name and dropped one propose rule).
                if(!op_name.isEmpty()){
                    currentRule.addAttrValue("state_operator_name", op_name);
                }
            }
        }


        // get the value of the attribute when using a compressed notation Example: new_val in (<s> ^value old_val - new_val +)
        if(ctx.value(1) != null){
            value = (String)visit(ctx.value(1));
        }
        if(pref_specifier !=null && pref_specifier.equals("-")){
            value = "nil";
        }


        return (Object)value;
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#pref_specifier}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitPref_specifier(SoarParser.Pref_specifierContext ctx) {
        if (ctx.unary_pref() != null){
            return visit(ctx.unary_pref());
        }
        if (ctx.unary_or_binary_pref() != null){
            String pref = (String)visit(ctx.unary_or_binary_pref());

            if(ctx.value() != null){
                String val = (String)visit(ctx.value());
                return (Object)(pref + " " + val);
            }
            return (Object)pref;
        }


        return null;
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#unary_pref}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitUnary_pref(SoarParser.Unary_prefContext ctx) {

        if(ctx.Negative_pref() != null){
            return (Object)(ctx.Negative_pref().getSymbol().getText());
        }
        return null;
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#unary_or_binary_pref}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitUnary_or_binary_pref(SoarParser.Unary_or_binary_prefContext ctx) {
        // Return the symbol itself ("=", "+", ">"). Returning null here made every preference
        // read back as "null <weight>", so no caller could ever recognise one — which is why
        // the numeric-indifferent weights of a softmax fan were being dropped (T3).
        return (Object)ctx.getText();
    }

    /**
     * Visit a parse tree produced by {@link SoarParser#sym_constant}.
     *
     * @param ctx the parse tree
     * @return the visitor result
     */
    @Override
    public Object visitSym_constant(SoarParser.Sym_constantContext ctx) {
        // A sym_constant is either a normal Sym_constant token OR the reserved word `state`
        // (grammar: sym_constant : Sym_constant | STATE). Handle BOTH — reading only the
        // Sym_constant branch NPEs on legitimate `state`-as-a-value (e.g. ^type state).
        if (ctx.Sym_constant() != null) {
            return (Object)(ctx.Sym_constant().getSymbol().getText());
        }
        if (ctx.STATE() != null) {
            return (Object)(ctx.STATE().getSymbol().getText());
        }
        return null;
    }
}