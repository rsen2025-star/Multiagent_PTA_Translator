package edu.fit.assist.translator.soar;

import edu.fit.assist.translator.gen.*;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// A load.soar file lists other .soar files. It can mean one of two things:
//   1. a LIST of AGENTS       — every file it lists is itself a load.soar (one per agent);
//                              we build one PTA model per agent, fully isolated.
//   2. ONE agent's own files — it lists at least one plain rule file (e.g. propose in one
//                              file, apply in another); we load the whole thing as one model
//                              so the two halves rejoin by operator name.
// listsAgents() tells the two apart; extractModels() is the single entry point.
public class MultiPtaLoader {

    // Matches a line like:  source "train/train.soar"  and grabs the file path inside it.
    private static final Pattern SOURCE_LINE = Pattern.compile("^\\s*source\\s+\"?([^\"\\s]+)\"?");

    /** Is this file a list of other .soar files? (yes = it points to agents, it is not an agent itself.) */
    public static boolean sourcesSoarFiles(String path) {
        try {
            for (String line : Files.readAllLines(Paths.get(path))) {
                Matcher m = SOURCE_LINE.matcher(line);
                if (m.find() && m.group(1).endsWith(".soar")) {
                    return true;
                }
            }
        } catch (IOException e) {
            // Can't open the file? Just say "no" and let the caller treat it as a single agent.
        }
        return false;
    }

    /** Does this load.soar list several agents, or one agent split across files?
     *  Several AGENTS: every file it sources is itself a load.soar (each = one agent's load file).
     *  ONE agent: it sources at least one plain rule file (e.g. propose + apply in separate files).
     *  Telling these apart is what stops us from splitting one agent's propose/apply into two. */
    public static boolean listsAgents(String path) throws IOException {
        List<String> sourced = sourcedFiles(path);
        if (sourced.isEmpty()) {
            return false;
        }
        for (String file : sourced) {
            if (!new File(file).getName().equalsIgnoreCase("load.soar")) {
                return false; // a plain rule file -> this is one agent's own list of files
            }
        }
        return true;
    }

    /** Get the list of .soar files this load file points to, as full paths (found in the same folder). */
    public static List<String> sourcedFiles(String loadPath) throws IOException {
        File dir = new File(loadPath).getAbsoluteFile().getParentFile();
        List<String> files = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get(loadPath))) {
            Matcher m = SOURCE_LINE.matcher(line);
            if (m.find() && m.group(1).endsWith(".soar")) {
                files.add(new File(dir, m.group(1)).getPath());
            }
        }
        return files;
    }

    /** The single entry point for a load.soar. If it lists several agents, build one model each
     *  (isolated). If it is one agent split across files, load the whole thing as ONE model so the
     *  propose and apply halves rejoin by operator name. */
    public static Map<String, MultiPtaModel> extractModels(String path) throws IOException {
        if (listsAgents(path)) {
            return extractPerAgent(path);
        }
        // One agent whose rules live in several files: hand the whole load.soar to JSoar, which
        // sources them all into one kernel, so nothing is split.
        Map<String, MultiPtaModel> models = new LinkedHashMap<>();
        String agent = agentName(path);
        MultiPtaModel model;
        try {
            model = extractOne(path);
        } catch (Exception e) {
            System.out.println("  (skipped " + agent + " — failed to load: " + e.getMessage() + ")");
            return models;
        }
        if (model == null) {
            System.out.println("  (skipped " + agent + " — no operators, not a PTA agent)");
            return models;
        }
        models.put(agent, model);
        return models;
    }

    /** Build one PTA model for each agent in the list. Skip files that aren't real agents (like settings.soar). */
    public static Map<String, MultiPtaModel> extractPerAgent(String loadPath) throws IOException {
        Map<String, MultiPtaModel> models = new LinkedHashMap<>();
        for (String file : sourcedFiles(loadPath)) {
            String agent = agentName(file);
            MultiPtaModel model;
            try {
                model = extractOne(file);
            } catch (Exception e) {
                System.out.println("  (skipped " + agent + " — failed to load: " + e.getMessage() + ")");
                continue;
            }
            if (model == null) {
                System.out.println("  (skipped " + agent + " — no operators, not a PTA agent)");
                continue;
            }
            models.put(agent, model);
        }
        return models;
    }

    /** Turn ONE agent file into a PTA model. Returns null if the file isn't a real agent. */
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

    /** Work out the agent's name from its file path. If the file is called load.soar, use its
     *  folder name (train/load.soar -> "train"). Otherwise use the file name without .soar
     *  (train.soar -> "train"). */
    private static String agentName(String path) {
        File f = new File(path);
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String stem = (dot > 0) ? name.substring(0, dot) : name;
        if (stem.equalsIgnoreCase("load") && f.getParentFile() != null) {
            return f.getParentFile().getName();
        }
        return stem;
    }
}
