# Sim_exp — live SML simulation of the 3-agent SOAR build

Runs the ICS attention agents (`environment`, `scenario`, `mission`) from
`models/ICS_SOAR-attention-3agent/` inside a real Soar kernel with **Python as the
wire between them**, and records what they do.

**SOAR only.** This harness computes no PRISM reference and draws no PRISM curve.
A closed form kept next to the simulation cannot check it — if the formula were
wrong the two would agree anyway and hide it. To compare with PRISM, run PRISM and
diff the two tables.

## Files
- `attention_env.py` — the SML bridge: kernel, io-link wiring, scheduling.
- `run_once.py`      — one traced episode.
- `monte_carlo.py`   — N episodes → tag-rate + attacker-id histogram.
- `sweep_plot.py`    — deadline x accuracy sweep → PNG + CSV.

## Requirements
- Python **3.12** (matches the prebuilt SML `.pyd`).
- Reuses the Train_gate SOAR runtime by absolute path:
  `D:\Rajarshi\Courses\MBSE\Multi-agent Train_gate\SOAR\bin` (see `_SOAR_BIN` in
  `attention_env.py`).

## Run
```
python run_once.py    --deadline 5 --acc 0.8 --I 1
python monte_carlo.py --runs 1000 --deadline 5 --acc 0.8 --I 1
python sweep_plot.py  --runs 500 --I 1 --out soar_sweep.png
```
| flag | default | meaning |
|---|---|---|
| `--I` | 1 | inspection time — Collins v1.5's *I* |
| `--deadline` | 5 | |
| `--acc` | 0.8 | `box_accuracy` (comma list in `sweep_plot`) |
| `--use-box` | 1 | 0 = no ATR cue |
| `--runs` | 2000 / 1000 | episodes per point |

---

## Scheduling — the Train_gate concept

Same design as `Multi-agent Train_gate\env\environment.py` and
`models/Sim_train_gate/`:

```
   cycle N   +--------------------------------+
             |  RunAllAgents(5, sml_PHASE)    |  all 3 agents think
             +--------------------------------+
                           |
             +--------------------------------+
             |          _deliver()            |  Python moves the messages
             +--------------------------------+
                           |
   cycle N+1   receiver sees it on its input-link
```

Delivery runs **after** the agents, so a message written in cycle N is not visible
until cycle N+1 — one cycle per hop. Nothing simulates a delay; that is what honest
message passing costs. It also makes the kernel's internal agent order irrelevant,
because no agent can read another's output within a cycle.

### Mission starts before it knows the attacker — and that is fine

The environment's `^signal` reaches scenario and mission in the same cycle, so
mission initializes while scenario is still rolling its dice. The attacker-id
arrives one cycle later:

```
  cycle 3   env writes ^signal                      (delivered after the cycle)
  cycle 4   scenario picks attacker | mission initializes, wall-clock = 0
  cycle 5   attacker-id delivered   | mission ticks, wall 0 -> 1
  cycle 6                           | mission examines person 0
```

`initialize` only zeroes the clocks and points at person 0 — nothing there needs
the attacker. And no examine can be proposed until `^inspect-clock` reaches
`^inspection-time`, which costs a tick anyway; that tick is exactly the cycle the
attacker-id needs. Confirmed in a live trace.

The deadline is not consumed while waiting, because `^wall-clock` advances only by
mission's own `tick` operator. PRISM writes the same fact as the invariant
`state_name=0 => state_wall_clock<=0`.

⚠️ The slack is exactly one cycle, so this holds for **I >= 1**. At `--I 0` an
examine would be proposed before the attacker-id lands, with no apply rule able to
match (`waitsnc` does not cover an operator-no-change).

### Message lifetime differs by kind

```
  ^signal       one-shot   ▁▇▁▁▁▁▁▁   destroyed after one cycle, like Train_gate's
  ^attacker-id  persistent ▁▁▇▇▇▇▇▇   re-read on every one of the 20 examines
```

`^signal` is caught with `AddOutputHandler` rather than polled: the SOAR never
clears its own output-link, so polling would re-send it every cycle. `^attacker-id`
is polled and latched, because the SOAR writes it nested under `^scenario`.

## io-link wiring (what the bridge supplies / routes)
- **environment** input: `systemdata.sys-start-mission=1`, `environment.crowd`.
- **scenario** input: `scenario.{use-box,box-accuracy}`, `environment.crowd.person[0..19].id`.
- **mission** input: `scenario.{use-box,deadline,inspection-time}`.
  ⚠️ `inspection-time` is what the apply rules advance `^wall-clock` by. Omit it and no
  examine can ever fire — every episode returns `TIMEOUT` with no error.
- routed: env `^signal transition-mission` -> scenario + mission;
  scenario `^scenario.attacker-id` -> mission.

---

## Measurements (2026-08-07, b=0.8, I=1, 1000 runs, deliver-after scheduling)

| config | tag-rate |
|---|---|
| d=1  box    | 0.0000 |
| d=5  box    | 0.8540 |
| d=20 box    | 0.9880 |
| d=5  no-box | 0.2080 |

Attacker-id histogram at d=5 box: person 0 = 0.818, others ~0.010–0.014 —
reproducing the softmax weights in `scenario-propose.soar` (person 0 ~
`box_accuracy`, others ~ `(1-box_accuracy)/19`). The dice-roll is wired correctly.
No tie-impasse hangs.

**The scheduling change did not move these.** Under the previous deliver-between
scheduling the same seeds gave 0.0000 / 0.8560 / 0.9880 — differences of two
episodes in a thousand, well inside noise. Expected: the delay precedes
`wall-clock = 0`, so it cannot eat deadline budget. (The residual difference is the
random stream, not behaviour: `RunAllAgents` changes how many decisions precede
scenario's draw, so a given seed maps to a different sample of the same
distribution.)

Contrast Train_gate, where the same one-cycle-per-hop cost *does* move the answer
(`safe <=> M <= T - 3`) because safety there is a race between two independent
clocks. See `models/Sim_train_gate/`.

## Note on I >= 2 — a semantics difference, not a bug

At `I >= 2` the simulation does **one more inspection** than the PTA, because the
two encode different ideas of what an action is: SOAR tests the deadline once
*before* an atomic step and then adds `I` to the counter, while the PTA treats the
step as *occupying* `I` time units with the deadline invariant holding throughout.
So SOAR starts an inspection at `wall=8` with `deadline=9` and finishes at 10; the
PTA will not. NOT a discrete-vs-continuous artifact — PRISM's figures come from
`-ptamethod digital`, which is integer time. Which reading is correct is an open
modelling decision; Collins v1.5 defines *I* as time the user spends, which favours
the PTA reading.

## Reading it honestly

- **Monte Carlo is required here**, unlike `models/Sim_train_gate` — the scenario
  agent makes a genuine probabilistic choice, so one run per cell is not the answer.
- **Seeds are reused across grid points** (`seed = run index` in every cell), so
  neighbouring points are correlated and the curves look smoother than independent
  sampling would give.
- **The mission is handed the ground-truth attacker-id** and "examines" by comparing
  it to `^current-person-id`. That is the model's design — the PTA does the same —
  but it means all the uncertainty lives in the one softmax draw; examining is not a
  sensing act with its own error.
- **A simulation cannot refute a `Pmax`.** It samples one particular scheduler, not
  the worst one.
