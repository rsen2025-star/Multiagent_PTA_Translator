# Multi-Agent SOAR to PRISM PTA Translator

Translates a multi-agent SOAR model into a PRISM probabilistic timed automaton (`.pm`).

**Paper:** (link to be added)

## Build and run

```
mvn -q compile
mvn -q exec:java -Dexec.args="models/Train_gate_param/load.soar"
```

The `.pm` is written next to the `load.soar`.

## Case studies

- `models/Train_gate_param/` — Railroad Crossing
- `models/ICS_SOAR-attention-3agent/` — Attention
