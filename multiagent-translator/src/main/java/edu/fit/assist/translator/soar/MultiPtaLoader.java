package edu.fit.assist.translator.soar;

import edu.fit.assist.translator.gen.*;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// on a multi-file model is now a hard error, never a fallback.
public class MultiPtaLoader {

    // Any include line:  source "train/load.soar"   — used only to tell a multi-file model from a
    // single plain rule file. It answers "does this file include others?", never "who is an agent?".
    private static final Pattern SOURCE_LINE = Pattern.compile("^\\s*source\\s+\"?([^\"\\s]+)\"?");

    // One agent per marked include:  source "train/load.soar"   # @agent train
    private static final Pattern AGENT_LINE = Pattern.compile(
            "^\\s*source\\s+\"?([^\"\\s]+)\"?\\s*#+\\s*@agent\\s+(\\S+)");

    // Standalone marker: this whole load.soar is ONE agent.  # @agent simplified
    private static final Pattern SELF_AGENT = Pattern.compile("^\\s*#+\\s*@agent\\s+(\\S+)\\s*$");

    /** Does this file include other .soar files? (i.e. is it a multi-file model rather than one
     *  plain rule file). Says nothing about agents — {@link #roster} answers that. */
    public static boolean sourcesSoarFiles(String path) {
        try {
            for (String line : Files.readAllLines(Paths.get(path))) {
                Matcher m = SOURCE_LINE.matcher(line);
                if (m.find() && m.group(1).endsWith(".soar")) {
                    return true;
                }
            }
        } catch (IOException e) {
            // Can't open the file? Just say "no" and let the caller treat it as a single file.
        }
        return false;
    }

    /** The declared agent roster: agent name -> the .soar file that agent is loaded from.
     *  Read from THIS file only; children are never inspected. Empty means nothing was declared.
     *
     *  Two passes, not one, so a marker written on its own line ABOVE a source line cannot be
     *  mistaken for the whole-file form. Marked includes always win; the standalone form is only
     *  consulted when there are none. */
    public static Map<String, String> roster(String loadPath) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(loadPath));
        File dir = new File(loadPath).getAbsoluteFile().getParentFile();
        Map<String, String> roster = new LinkedHashMap<>();

        // Pass 1 — the multi-agent form: one agent per marked include, in file order.
        for (String line : lines) {
            Matcher m = AGENT_LINE.matcher(line);
            if (m.find()) {
                declare(roster, m.group(2), new File(dir, m.group(1)).getPath(), loadPath);
            }
        }
        if (!roster.isEmpty()) {
            return roster;
        }

        // Pass 2 — the single-agent form: the whole file is one agent, JSoar sources all of it.
        for (String line : lines) {
            Matcher m = SELF_AGENT.matcher(line);
            if (m.find()) {
                declare(roster, m.group(1), loadPath, loadPath);
                break;
            }
        }
        return roster;
    }

    /** Add one roster entry. A repeated name would drop a module without a trace, so it is fatal. */
    private static void declare(Map<String, String> roster, String name, String path, String loadPath)
            throws IOException {
        if (roster.put(name, path) != null) {
            throw new IOException("Duplicate @agent name '" + name + "' in " + loadPath
                    + " — names must be unique or a module is silently lost.");
        }
    }

    /** Build one PTA model per declared agent, in roster order (LinkedHashMap keeps the PRISM
     *  module order stable, which is what keeps the output hash reproducible).
     *
     *  Every declared agent MUST produce a model: the roster is a statement of intent, so a bad
     *  path or an agent with no operators is an error, not something to skip past. A missing
     *  module changes the composed semantics, and that must never happen quietly. */
    public static Map<String, MultiPtaModel> extractModels(String path) throws IOException {
        Map<String, String> roster = roster(path);
        if (roster.isEmpty()) {
            throw new IOException(
                    "No @agent markers in " + path + "\n"
                  + "  The outermost load.soar must declare its agents. Either mark each include:\n"
                  + "      source \"mission/load.soar\"   # @agent mission\n"
                  + "  or, if the whole file is a single agent, add one standalone line:\n"
                  + "      # @agent <name>");
        }

        Map<String, MultiPtaModel> models = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : roster.entrySet()) {
            String agent = entry.getKey();
            String file = entry.getValue();
            MultiPtaModel model;
            try {
                model = extractOne(file);
            } catch (Exception e) {
                throw new IOException("Declared agent '" + agent + "' (" + file
                        + ") failed to load: " + e.getMessage(), e);
            }
            if (model == null) {
                throw new IOException("Declared agent '" + agent + "' (" + file
                        + ") has no operators, so it is not a PTA agent — remove its @agent marker"
                        + " if it is a plain include (like settings.soar).");
            }
            models.put(agent, model);
        }
        return models;
    }

    /** Turn ONE agent's entry file into a PTA model. JSoar resolves that agent's own `source` tree,
     *  so propose and apply rejoin by operator name. Returns null if the file holds no real agent. */
    private static MultiPtaModel extractOne(String soarFile) throws Exception {
        String soarText = Input.getSoarRules(soarFile);

        ANTLRInputStream input = new ANTLRInputStream(soarText);
        SoarLexer lexer = new SoarLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        SoarParser parser = new SoarParser(tokens);
        SoarParser.SoarContext tree = parser.soar();

        MultiPtaVisitor visitor = new MultiPtaVisitor();
        visitor.rules = new SoarRules();
        visitor.visit(tree);

        if (visitor.rules.rules.isEmpty()) {
            return null; // no rules inside -> not an agent (like settings.soar)
        }
        MultiPtaModel model = new MultiPtaExtractor(visitor.rules).extract();
        if (model.actions.isEmpty()) {
            return null; // has rules but no operators (no real moves) -> not a PTA agent
        }
        return model;
    }
}
