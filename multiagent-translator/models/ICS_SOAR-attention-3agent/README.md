# ICS Attention — 3-Agent SOAR Model

Three SOAR agents that model an attention/inspection task. One folder = one agent = one PRISM
module (same layout as `models/Train_gate/`). This is the translator's certified input: its
output reproduces the property battery in `ics_translated.props`.

| Agent | PRISM module | Does |
|---|---|---|
| `environment/` | `environment` | go/crowd handshake, then hands off |
| `scenario/` | `scenario` | picks the hidden attacker id by softmax dice-roll |
| `mission/` | `mission` | scan loop, two clocks, three endings |

## Layout
```
load.soar              all three agents
load_env_mission.soar  environment + mission only, no scenario (2-agent subset)
settings.soar          waitsnc --on; indifferent-selection --softmax
environment/  environment-propose.soar  operators/environment-apply.soar
scenario/     scenario-propose.soar     operators/scenario-apply.soar
mission/      mission-propose.soar      operators/mission-apply.soar
ics_soar-attention-3agent_translated.pm   translator output
ics_translated.props                      certification battery
```
Each agent's `load.soar` sources `../settings.soar`, not the top-level one. The loader treats a
top `load.soar` as an agent list only if every file it sources is itself named `load.soar`.

## Operators

- **environment** — `start-mission`, `sync-environment`, `transition-mission`
- **scenario** — `set-attacker-id` (3 propose rules, one apply)
- **mission** — `initialize`, `examine-box`, `examine`, `tick`, `deadline-reached`,
  `mission-complete-untagged`, `mission-complete-tagged`

`tick` advances both clocks and touches nothing else, so it folds into zero PRISM edges. It
exists so the inspect clock's bound can be read from its propose guard.

## How the scenario dice-roll works

3 propose rules + softmax. `indifferent-selection --softmax` is value-proportional, so
P(attacker-id = i) = weight(i).

| Rule | Matches | Weight (numeric-indifferent) |
|---|---|---|
| `*boxed` | person 0, use-box=1 | `box-accuracy` |
| `*unboxed` | persons 1–19, use-box=1 | `(1 − box-accuracy)/19` |
| `*uniform` | all 20, use-box=0 | `1/20` |

Weights sum to 1. One rule instantiates once per matching person, so 2–3 rules produce 20
candidates. The translator expands this back to 20 PRISM branches — feature T3,
`MultiPtaExtractor.buildProbabilisticFan`. The population size is not written anywhere in the
rules; T3 reads it from the divisor in the weight (`(/ 1.0 20)` says twenty).

## IO contract (what the bridge must provide)

**environment** — Input-link: `^systemdata.sys-start-mission` (>0 = go), `^environment.crowd`
(present = synced). Output-link: `^signal transition-mission`, published once; the bridge copies
it to scenario's and mission's input-links. This is the shared `[transition_mission]` sync label
in the emitted PM.

**scenario** — Input-link: `^signal transition-mission`, `^scenario.use-box`,
`^scenario.box-accuracy`, `^environment.crowd.person` (20 entries, `^id 0..19`).
Output-link: `^scenario.attacker-id <id>`, published once; the bridge copies it to mission.

**mission** — Input-link: `^scenario.deadline`, `^scenario.inspection-time`, and the
attacker-id above. `inspection-time` is what the apply rules advance `^wall-clock` by. Omit it
and no examine can fire.

Flags use the **absence-gate idiom**: no attribute = no/0, add `yes` once. No remove-add flip is
needed. The one-shot guard is a local flag (`^handoff-sent` in environment, `-^attacker-id` in
scenario, `-^name` in mission).

## Grammar conformance

All 6 rule files parse under the shipped parser (the 9 other `.soar` files are `load`/`settings`
plumbing). The parser rejects exactly one pattern: the remove-add flip `^attr old - new +`.
Inline arithmetic `(+ <x> 1)` parses fine on its own.

Mission uses the **flip-free idiom** to avoid the rejected pattern: each `^attr old - new +` is
split into two legal actions — `(<s> ^attr <old> -)` then `(<s> ^attr <new>)`. person-id `+1`
stays as `(+ <pid> 1)`. Mission's `initialize` is operator-based so phase/action WMEs get
o-support and can be legally changed.

⚠️ The checked-in `src/main/antlr4/edu/fit/assist/soar/Soar.g4` is **stale** and does not match
the committed `gen/*.java` parser. Do not regenerate. See the root README.

## Certification

```
Push-Location "C:\Program Files\prism-4.8.1\bin"
.\prism.bat <abs ics_soar-attention-3agent_translated.pm> <abs ics_translated.props> `
    -const deadline=5,box_accuracy=0.8,use_box=1,sys_start_mission=1 -ptamethod digital
Pop-Location
```

| deadline (b=0.8, use_box=1) | Pmin | Pmax |
|---|---|---|
| 1  | 0       | 0.8     |
| 5  | 0.83158 | 0.84211 |
| 20 | 0.98947 | 1.0     |

`sys_start_mission=1` is required — the SOAR tests `^sys-start-mission > 0` and the translator
renders the source as written. Omit it and every number reads 0.

`ics_translated.props` writes the endings as plain state tests, not labels: the translator emits
no `label` lines. `state_action` codes its values in first-seen order —
`0 nil, 1 Waiting, 2 Tagged, 3 Deadline, 4 Not-Tagged, 5 Complete`. **Adding or removing an
`^action` value shifts every later code**, and the properties will then measure the wrong
outcome. Re-read the printed `L` after any such change and fix the `.props` in the same pass.

## Not modeled on purpose

No comms agent. The one-cycle inter-agent hop lands during setup, before mission's `initialize`
creates `^wall-clock`, so it costs no deadline budget. PRISM writes the same fact as the
invariant `state_name=0 => state_wall_clock<=0`. `models/Sim_exp/README.md` traces this cycle by
cycle against a live kernel and confirms it holds for inspection-time >= 1.
