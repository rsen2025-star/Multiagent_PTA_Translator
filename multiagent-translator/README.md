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
src/main/antlr4/edu/fit/assist/soar/Soar.g4   ⚠️ STALE — see the warning below. Do NOT regenerate.
models/Train_gate/                 sample input (train + controller + gate agents)
models/ICS_SOAR-attention-3agent/  the certified 3-agent model + its property battery
models/Sim_exp/                    live SOAR Monte-Carlo harness (Python 3.12)
pom.xml              Java 17; deps: antlr4-runtime, jsoar-core/tcl, slf4j
```

## ⚠️ Never regenerate `gen/` from `Soar.g4`

The checked-in `.g4` is **stale**: the real parser was generated from a grammar that no longer
exists, and differs from what this `.g4` produces (`MultiPtaVisitor` relies on `ctx.value(1)`,
which the on-disk grammar cannot emit). Regenerating breaks the build. The `gen/*.java` files are
the source of truth — treat the `.g4` as read-only reference.

## Build & run
```
mvn -q compile
mvn -q exec:java -Dexec.mainClass=edu.fit.assist.translator.soar.main -Dexec.args="models/Train_gate/load.soar"
# or:
java -cp target/classes:<deps> edu.fit.assist.translator.soar.main models/Train_gate/load.soar
```
Output is written next to the load.soar as `models/Train_gate/train_gate_translated.pm`.

## Verifying a change

Two regression checks, both cheap:

**1. Train_gate is the byte-exact anchor.** Re-run it and the SHA256 of
`models/Train_gate/train_gate_translated.pm` must equal
`4C522C4D4A9DA2164A72CA009219DB64FBD9E8CAD341DBB4FC15A89C881E1546`
(train bound 6, gate bound 2, controller clockless). The hand-written `Train_gate.pm` in that
folder is **not** the target — it bakes in an old `+1` delay rule that was reversed.

**2. The ICS model must reproduce its certified battery.** Run PRISM from its own `bin` dir:
```
Push-Location "C:\Program Files\prism-4.8.1\bin"
.\prism.bat <abs .pm> <abs models/ICS_SOAR-attention-3agent/ics_translated.props> `
    -const deadline=5,box_accuracy=0.8,use_box=1,sys_start_mission=1 -ptamethod digital
Pop-Location
```
| deadline (b=0.8, use_box=1) | Pmin | Pmax |
|---|---|---|
| 1  | 0       | 0.8     |
| 5  | 0.83158 | 0.84211 |
| 20 | 0.98947 | 1.0     |

`sys_start_mission=1` is **required** — the SOAR really tests `^sys-start-mission > 0`, and this
translator renders the source as written. Omit it and every number reads 0: a missing constant,
not a broken model.

## Reading the printed tuple

Each agent prints its PTA 8-tuple before the `.pm` is written:

```
  L        : state_name    {nil, mission}          the LOCATION variables and their domains
             state_action  {nil, Waiting, ...}
  dataVars : state_current_person_id : [0..20] init 0    counters/flags — they live in guards
  l-bar    : {nil, nil}                            the initial location
  Act      : initialize, examine-box, ...          SOAR operator names
  X        : state_wall_clock  bound=...           clocks + their invariant bounds
  transitions :
    [examine-box-hit]
        {mission, Waiting} --> {mission, Tagged}
        guards  = {...}   resets = {...}   updates = {...}
```

- **Locations vs data.** A variable whose values are *words* shapes the machine and is printed
  under `L`; a *numeric* one is data and is printed under `dataVars`. `L` is the product of the
  location variables, so a location is written as a value tuple.
- **`*`** in a source tuple means that component is unconstrained — the rule tests nothing about
  it, so the edge fires from every value of it.
- An agent with no word-valued variables prints `L : NONE (all state is data)`.

## Notes
- The input `load.soar` must **source** several agent folders (that's how the loader detects
  a multi-agent model). A single flat `.soar` is not this pipeline's job.
- Each agent's own `load.soar` should source `../settings.soar`, **not** the top-level one:
  the loader treats a top `load.soar` as a list of agents only if every file it sources is
  itself named `load.soar`. Putting `source "settings.soar"` in the top file collapses all
  agents into one merged module.
- `Input.java` uses jsoar to load productions, so jsoar-core + jsoar-tcl are required at runtime.
- To translate a different model, point the arg at that model's `load.soar`.
- The translator emits **no `label` lines** — naming an outcome is the job of whoever asks the
  question, so the `.props` file uses plain state tests. `state_action` codes its values in
  first-seen order, so **adding or removing a `^action` value shifts every later code** and the
  properties will silently measure the wrong outcome. Re-read the printed `L` after any such
  change and fix the `.props` in the same pass.
