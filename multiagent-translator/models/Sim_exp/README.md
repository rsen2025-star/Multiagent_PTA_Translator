# Attention simulation

Runs the attention agents in a live SOAR kernel and measures how often the attacker is tagged.
Needs Python 3.12 and a SOAR install (set `SOAR_BIN` to its `bin` folder).

```
python monte_carlo.py --runs 1000 --deadline 20 --acc 0.75 --I 1 --use-box 1
python sweep_plot.py --runs 500 --I 1 --use-box 1 --acc 0.75,0.80,0.85,0.90,0.95,1.00
```
