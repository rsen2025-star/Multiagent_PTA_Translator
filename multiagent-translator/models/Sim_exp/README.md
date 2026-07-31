# Sim_exp — live SML simulation of the 3-agent SOAR build

Runs the ICS attention agents (`environment`, `scenario`, `mission`) from
`models/ICS_SOAR-attention-3agent/` inside a real Soar kernel and wires their
io-links the way the PRISM model composes its modules. Turns the static PTA into
a running Monte-Carlo simulation, so we can check the actual tag-rate against the
PRISM Pmin/Pmax bracket.

## Files
- `attention_env.py` — the SML bridge (typed + nested WMEs; broadcast + p2p routing).
- `run_once.py`      — one traced episode.
- `monte_carlo.py`   — N episodes → tag-rate + attacker-id histogram vs Pmin/Pmax.

## Requirements
- Python **3.12** (matches the prebuilt SML `.pyd`).
- Reuses the Train_gate SOAR runtime by absolute path:
  `D:\Rajarshi\Courses\MBSE\Multi-agent Train_gate\SOAR\bin` (see `_SOAR_BIN` in
  `attention_env.py`).

## Run
```
python run_once.py 5 1 0.8              # deadline use_box box_accuracy
python monte_carlo.py 2000 5 1 0.8      # runs deadline use_box box_accuracy
```

## io-link wiring (what the bridge supplies / routes)
- **environment** input: `systemdata.sys-start-mission=1`, `environment.crowd`.
- **scenario** input: `scenario.{use-box,box-accuracy}`, `environment.crowd.person[0..19].id`.
- **mission** input: `scenario.{use-box,deadline}`.
- routed: env `^signal transition-mission` -> scenario + mission;
  scenario `^scenario.attacker-id` -> mission.

## Result (2026-07-27) — SOAR sits at the Pmax end
Discrete SOAR (strict `<` counter) always completes the boundary examine, so its
tag-rate matches PRISM **Pmax**, never the Pmin. Verified:

| config | empirical | Pmin / Pmax |
|---|---|---|
| d=1  box     | 0.8033 | 0.0000 / 0.8000 |
| d=5  box     | 0.8450 | 0.8316 / 0.8421 |
| d=20 box     | 1.0000 | 0.9895 / 1.0000 |
| d=5  no-box  | 0.2567 | 0.2000 / 0.2500 |

Attacker-id histogram matches the softmax weights (person 0 ~ `box_accuracy`,
others ~ `(1-box_accuracy)/19`), confirming the probabilistic dice-roll (T3) is
wired correctly. No tie-impasse hangs observed.
