# Sim_train_gate — live SOAR harness for `models/Train_gate_param/`

Runs the three Train_gate_param agents in one Soar kernel with **Python as the wire
between them**, records every run, and measures the one quantity the translated
PRISM model cannot see: the cost of passing a message.

Zero SOAR changes. `Train_gate_param` already reads both durations from its
input-link and already writes both messages to its output-link, so it plugs in
as-is.

---

## Three commands

```bash
# 1. RUN + RECORD   — the only step that touches Soar
python run_sweep.py --travel 6:1:20 --mechanical 2:1:12

# 2. DETECT         — read the log, decide unsafe, write PRISM-shaped CSV
python detect.py

# 3. PLOT           — CSV -> figure
python plot.py
```

Fold 2 and 3 into one if you prefer **two** commands:

```bash
python detect.py --plot
```

Ranges use PRISM's own `start:step:end` syntax (or plain `start:end`), so the same
grid goes to both tools.

### Why the steps are separate

Only step 1 runs Soar, and it writes **facts only** — positions, counts, and each
message delivered. Whether a run was unsafe is decided in step 2 *from the log*.
So the detection rule can be changed or argued with and re-run in a second, and the
log stays the evidence either way.

| step | script | reads | writes |
|---|---|---|---|
| 1 | `run_sweep.py` | the SOAR agents | `soar_runs.log` |
| 2 | `detect.py` | `soar_runs.log` | `soar_sweep.csv`, `_matrix.txt`, `_meta.txt` |
| 3 | `plot.py` | `soar_sweep.csv` | `soar_sweep.png` |

`train_gate_env.py` is the engine all of them share; `run_once.py` traces a single
configuration when you want to read one cycle by eye.

---

## The result

```
   PRISM (translated)   safe  <=>  M <= T
   SOAR  (measured)     safe  <=>  M <= T - 3
```

`r = 3` decision cycles, identical on every cell of a 165-run grid, confirmed two
independent ways: read off the delivery lines, and derived from where safety
actually broke.

```
   travel_delay\mechanical_delay:
        2  3  4  5  6  7  8  9 10 11 12
    6   0  0  1  1  1  1  1  1  1  1  1        0 = safe
    7   0  0  0  1  1  1  1  1  1  1  1        1 = unsafe
    8   0  0  0  0  1  1  1  1  1  1  1
    9   0  0  0  0  0  1  1  1  1  1  1        boundary at M = T - 3
   ...                                         (PRISM's sits at M = T)
```

45 of 165 unsafe — exactly `9+8+7+6+5+4+3+2+1`, the triangle the law predicts.

### Where the 3 cycles go

```
  tick  7   train far->near, writes ^signal approach   -+
  tick  8   controller receives, ctrl-state 0->1        |  1  wire train->controller
  tick  9   controller applies lower, writes ^command   |  2  controller's own decision
  tick 10   gate receives, starts lowering             -+  3  wire controller->gate
```

Two hops of wire plus one cycle of the controller thinking. The translated PRISM
model fuses each sender's label with its receiver's into a single synchronised
transition, which makes all three free.

---

## How the delay arises

```
   tick N   +--------------------------------+
            |  RunAllAgents(5, sml_PHASE)    |  all 3 agents think
            +--------------------------------+
                          |
            +--------------------------------+
            |         _deliver()             |  Python moves the message
            +--------------------------------+
                          |
   tick N+1   receiver sees it on its input-link
```

Delivery runs **after** the agents, so a message written in cycle N is not visible
until cycle N+1. Nothing simulates a delay — the delay is what honest message
passing costs. A delivered message lives exactly one cycle, which is enough
because a Soar decision cycle runs propose and apply together.

---

## Comparing with PRISM

**Everything in this folder is SOAR output.** No file here holds a PRISM number, and
`detect.py` never reads one — the CSV, the matrix and the figure are all measurements
of the run log and nothing else. That keeps the two tools independently falsifiable:
if they agree, it is because they agree, not because one was fitted to the other.

Produce the PRISM side separately, next to the model it belongs to:

```
prism.bat train_gate_param_translated.pm train_gate_param_translated.props -prop 2 \
    -const travel_delay=6:1:20,mechanical_delay=2:1:18 \
    -ptamethod games -exportresults "prism_sweep.csv:csv"
```

Then compare the two by eye, or diff `soar_sweep_matrix.txt` against PRISM's
`:matrix` export — both are a plain grid of 0/1 with the same axes.

⚠️ Use `-ptamethod games` for a full grid. Under `digital` every `M > T` cell aborts
with a **Timelock error** — correct, but it stops the sweep.

---

## Reading it honestly

- **One run per cell is exact here.** Train gate has no probabilistic choice, so
  unlike `models/Sim_exp` no Monte Carlo is needed.
- **One cycle per configuration.** `train*clear*output` is commented out in the
  SOAR, so an output WME persists once written and re-writing the same value is a
  no-op — the output handler would not fire again. Harmless: the safety question
  is settled at the first crossing.
- **`count=-` in the log is not missing data.** It means the WME does not exist
  yet; the gate genuinely has no `^gate-count` until it first moves.
- **Rows whose boundary lies beyond the grid are called out, not hidden.** With
  `--mechanical 2:1:12` everything from `T=15` up is safe throughout, so those
  curves overlap on the zero line; the figure says so in a footnote and
  `soar_sweep_meta.txt` marks them `censored`. To make them step, extend
  `--mechanical` to `2:1:18`.
- **y is binary because the agents are deterministic.** One run per cell IS the
  outcome, so the axis is labelled `safe` / `unsafe` rather than a 0.0–1.0
  probability scale, which would imply a frequency nobody measured.
- **The SOAR leg is `T+1` cycles, not `T`** — `T` clock ticks plus the cycle that
  applies the move. Same for the gate at `M+1`. The two `+1`s cancel in the safety
  comparison, which is why the offset is exactly `r` and not `r` plus something.

---

## What this does and does not settle

It puts a number on `r` and confirms the general law from both sides:

```
   safe  <=>  M + r <= T          r = 0 in PRISM, r = 3 in SOAR
```

It does **not** address `Pmax`. PRISM reports `Pmax [F unsafe] = 1.0` at every
setting because its controller is clockless and may stall indefinitely. This
simulation runs one particular controller that happens to react in one cycle, so
it samples a single adversary rather than the worst one. A simulation cannot
refute a `Pmax`; only a response bound in the model can.
