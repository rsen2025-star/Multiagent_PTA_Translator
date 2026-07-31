"""
Monte-Carlo: run the 3-agent build many times, aggregate the tag-rate and the
attacker-id histogram, and compare against the PRISM Pmin/Pmax bracket.

Expectation (from the static analysis):
  * empirical tag-rate should converge to ~Pmax  (discrete SOAR sits at the
    best-case end, because its strict `<` always completes the boundary examine)
  * attacker-id histogram: person 0 ~ box_accuracy, everyone else ~ (1-b)/19

Usage:  python monte_carlo.py [runs] [deadline] [use_box] [box_accuracy]
Example: python monte_carlo.py 2000 5 1 0.8
"""
import sys
from collections import Counter
from attention_env import AttentionEnv


def pmax(deadline, box_accuracy, use_box):
    # closed form from attention_crafted.props (use_box=1); use_box=0 -> uniform
    if use_box == 0:
        return min(1.0, deadline / 20.0)
    if deadline <= 0:
        return 0.0
    return box_accuracy + (deadline - 1) * (1 - box_accuracy) / 19


def pmin(deadline, box_accuracy, use_box):
    if use_box == 0:
        return max(0.0, (deadline - 1) / 20.0)
    if deadline <= 1:
        return 0.0
    return box_accuracy + (deadline - 2) * (1 - box_accuracy) / 19


runs = int(sys.argv[1]) if len(sys.argv) > 1 else 2000
deadline = int(sys.argv[2]) if len(sys.argv) > 2 else 5
use_box = int(sys.argv[3]) if len(sys.argv) > 3 else 1
box_accuracy = float(sys.argv[4]) if len(sys.argv) > 4 else 0.8

print(f"runs={runs} deadline={deadline} use_box={use_box} box_accuracy={box_accuracy}")

env = AttentionEnv(deadline=deadline, use_box=use_box, box_accuracy=box_accuracy)
env.create_agents()

outcomes = Counter()
attackers = Counter()
for i in range(runs):
    env.reset(seed=i)
    outcome = env.run_episode()
    outcomes[outcome] += 1
    if env.attacker_id is not None:
        attackers[env.attacker_id] += 1
    if (i + 1) % 200 == 0:
        print(f"  ...{i + 1}/{runs}")
env.shutdown()

tagged = outcomes.get("Complete", 0)
rate = tagged / runs
lo, hi = pmin(deadline, box_accuracy, use_box), pmax(deadline, box_accuracy, use_box)

print("\n=== outcomes ===")
for k, v in outcomes.most_common():
    print(f"  {k:>9}: {v:6d}  ({v/runs:.4f})")

print("\n=== tag-rate vs PRISM bracket ===")
print(f"  empirical tag-rate = {rate:.4f}")
print(f"  PRISM Pmin/Pmax    = {lo:.4f} / {hi:.4f}")
inside = lo - 0.02 <= rate <= hi + 0.02
print(f"  near Pmax end?     = {abs(rate - hi) <= 0.02}   (inside bracket: {inside})")

print("\n=== attacker-id histogram (top 6) ===")
total = sum(attackers.values()) or 1
for pid, c in sorted(attackers.items())[:6]:
    print(f"  person {pid:2d}: {c:6d}  ({c/total:.4f})")
print(f"  (expect person 0 ~ {box_accuracy:.2f}, others ~ {(1-box_accuracy)/19:.4f} each)")
