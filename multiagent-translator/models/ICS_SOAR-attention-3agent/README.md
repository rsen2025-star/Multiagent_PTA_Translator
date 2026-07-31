# ICS Attention — 3-Agent SOAR Model

Reverse-engineered from the certified reference PTA
`models/ICS_SOAR-attention-simplified/attention_crafted.pm` (Step 3 of the plan in
`.claude/PTA/ICS_MAP.md`). One folder = one agent = one PRISM module (Train_gate layout).
The original `ICS_SOAR-attention-simplified/` is left untouched as the read-only
baseline that produced `output1.pm`.

| Agent | PRISM module | Status |
|---|---|---|
| `scenario/` | `scenario` — hidden attacker id, box_accuracy dice-roll | ✅ drafted |
| `environment/` | `environment` — go/crowd handshake | ✅ drafted |
| `mission/` | `mission` — scan loop, clocks, endings | ✅ drafted (flip-free) |

## How the scenario dice-roll works
3 propose rules + softmax replace the legacy config-file paste:

| Rule | Matches | Weight (numeric-indifferent) |
|---|---|---|
| `*boxed` | person 0, use-box=1 | `box-accuracy` |
| `*unboxed` | persons 1–19, use-box=1 | `(1 − box-accuracy)/19` |
| `*uniform` | all 20, use-box=0 | `1/20` |

Weights sum to 1; `indifferent-selection --softmax` is value-proportional, so
P(attacker-id = i) = weight(i) — exactly the two `[transition_mission]` commands in the
crafted PRISM scenario module. One rule instantiates once per matching person
(2–3 rules → 20 candidates); the translator (feature T3) must expand this back to
20 PRISM branches.

## IO contract (what the bridge / outside world must provide)
**scenario** — Input-link: `^signal transition-mission`, `^scenario.use-box`, `^scenario.box-accuracy`,
`^environment.crowd.person` (20 entries with `^id 0..19`).
Output-link: `^scenario.attacker-id <id>` — published once; the bridge copies it to
mission's input-link (`state_io_scenario_attacker_id` in the crafted PM).

**environment** — Input-link: `^systemdata.sys-start-mission` (>0 = go) and
`^environment.crowd` (crowd data present = synced). Output-link: `^signal transition-mission`
— published once; the bridge copies it to scenario's and mission's input-links (this is the
shared `[transition_mission]` sync label in the crafted PM). Flags use the absence-gate idiom
(no attribute = 0/no; add `yes` once) so no remove-add flip is needed; the one-shot guard is
the local `^mission-started` flag.

## Grammar conformance
All 6 files parse under the shipped parser (verified with the real ANTLR SoarParser generated from
the committed `gen/*.java`). Corrected finding (2026-07-26): the parser REJECTS only the remove-add
flip (`^attr old - new +`); inline arithmetic (`(+ <x> 1)`) parses FINE on its own (the earlier note
lumping arithmetic in was wrong). NOTE: the checked-in `src/main/antlr4/.../Soar.g4` is STALE — it does
not match the committed parser (which was generated from a now-gone `D:/SoarToPrismTranslator` grammar),
so do NOT regenerate the parser from that `.g4`.

Mission uses the **flip-free idiom** to avoid the one rejected pattern: each `^attr old - new +` is
split into two legal actions — `(<s> ^attr <old> -)` (remove) then `(<s> ^attr <new>)` (add).
person-id `+1` stays as the already-legal `(+ <pid> 1)`. Mission also uses an operator-based
`initialize` (like the simplified `top-level.soar`) so phase/action WMEs get o-support and can be
legally changed. ⚠️ TODO (Step 4): confirm the translator produces the crafted PRISM from the
two-line remove/add form (Visitor.java was written for the one-line flip via `ctx.value(1)`).

## Not modeled on purpose
No comms agent: the 1-tick inter-agent hop lands in setup (before `wall_clock` starts),
so it is free — see handoff.md "SOAR processing delay = two kinds".
