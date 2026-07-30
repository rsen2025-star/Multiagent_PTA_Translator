# Isolated Multi-Agent PTA Translator

A self-contained copy of the `Multi*` SOAR→PRISM PTA pipeline — just the files needed to
translate a multi-agent SOAR model (one folder = one agent = one PRISM module) into a
composed PRISM `.pm`. Legacy paths (config-driven `PtaTranslator`, `TimeBasedTranslator`,
DTMC `Translate`, the old `Visitor`) are NOT included.

## What's here
```
src/main/java/edu/fit/assist/translator/
  soar/   main.java            entry (trimmed: multi-agent path only)
          MultiPtaLoader.java  reads load.soar, splits into per-agent models
          MultiPtaVisitor.java parse tree -> SoarRules
          MultiPtaExtractor.java SoarRules -> MultiPtaModel (PTA tuple)
          MultiPtaModel.java   per-agent IR
          MultiPtaEmitter.java composes agents -> one PRISM .pm
          SoarRules.java / Rule.java / Variable.java  intermediate representation
          Input.java           reads .soar via jsoar (getSoarRules)
  gen/    Soar{Lexer,Parser,Visitor,BaseVisitor,Listener,BaseListener}.java  ANTLR-generated parser
src/main/antlr4/edu/fit/assist/soar/Soar.g4   grammar source (regenerate gen/ if you change it)
models/Train_gate/   sample input (train + controller + gate agents)
pom.xml              Java 17; deps: antlr4-runtime, jsoar-core/tcl, slf4j
```

## Build & run
```
mvn -q compile
mvn -q exec:java -Dexec.mainClass=edu.fit.assist.translator.soar.main -Dexec.args="models/Train_gate/load.soar"
# or:
java -cp target/classes:<deps> edu.fit.assist.translator.soar.main models/Train_gate/load.soar
```
Output is written next to the load.soar as `models/Train_gate/train_gate_translated.pm`.
Compare against the hand-written `models/Train_gate/Train_gate.pm`.

## Notes
- The input `load.soar` must **source** several agent folders (that's how the loader detects
  a multi-agent model). A single flat `.soar` is not this pipeline's job.
- `Input.java` uses jsoar to load productions, so jsoar-core + jsoar-tcl are required at runtime.
- To translate a different model, point the arg at that model's `load.soar`.
