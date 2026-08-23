package edu.fit.assist.translator.soar;

import java.io.File;
import java.io.PrintWriter;
import java.util.Map;


public class main {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: main <path-to-load.soar>");
            return;
        }
        String loadPath = args[0];

        if (!MultiPtaLoader.sourcesSoarFiles(loadPath)) {
            System.err.println("Not a multi-agent load.soar (no sourced agents): " + loadPath);
            return;
        }

        Map<String, MultiPtaModel> agents = MultiPtaLoader.extractModels(loadPath);
        for (Map.Entry<String, MultiPtaModel> agent : agents.entrySet()) {
            System.out.println("\n########## agent: " + agent.getKey() + " ##########");
            System.out.println(agent.getValue());
        }

        String prism = new MultiPtaEmitter().emitSystem(agents);
        File loadFile = new File(loadPath).getAbsoluteFile();
        File outFile = new File(loadFile.getParentFile(),
                loadFile.getParentFile().getName().toLowerCase() + "_translated.pm");
        try (PrintWriter pw = new PrintWriter(outFile)) {
            pw.print(prism);
        }
        System.out.println("\nWrote PRISM PTA -> " + outFile.getPath());
    }
}
