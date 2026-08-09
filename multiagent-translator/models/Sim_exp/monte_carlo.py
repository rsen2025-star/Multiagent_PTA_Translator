"""
Monte-Carlo: run the 3-agent build many times and aggregate the tag-rate and the
attacker-id histogram. SOAR only — no PRISM reference is computed or printed.
To compare with PRISM, run PRISM and diff the two tables.

The one expectation still checked is internal to the SOAR: the attacker-id
histogram should reproduce the softmax preference weights written in
scenario-propose.soar (person 0 ~ box_accuracy, everyone else ~ (1-b)/19).

Usage:  python monte_carlo.py [--runs N] [--deadline D] [--use-box 0|1] [--acc B] [--I N]
Example: python monte_carlo.py --runs 2000 --deadline 5 --acc 0.8 --I 1
"""
import argparse
from collections import Counter
from attention_env import AttentionEnv

ap = argparse.ArgumentParser(description="Monte-Carlo the 3-agent SOAR build")
ap.add_argument("--runs", type=int, default=2000)
ap.add_argument("--deadline", type=int, default=5)
ap.add_argument("--use-box", type=int, default=1, choices=(0, 1))
ap.add_argument("--acc", type=float, default=0.8, help="box_accuracy, Collins' P(tp)")
ap.add_argument("--I", type=int, default=1, help="inspection time (Collins v1.5's I)")
args = ap.parse_args()

print(f"runs={args.runs} deadline={args.deadline} use_box={args.use_box} "
      f"box_accuracy={args.acc} inspection_time={args.I}")

env = AttentionEnv(deadline=args.deadline, use_box=args.use_box,
                   box_accuracy=args.acc, inspection_time=args.I)
env.create_agents()

outcomes = Counter()
attackers = Counter()
for i in range(args.runs):
    env.reset(seed=i)
    outcome = env.run_episode()
    outcomes[outcome] += 1
    if env.attacker_id is not None:
        attackers[env.attacker_id] += 1
    if (i + 1) % 200 == 0:
        print(f"  ...{i + 1}/{args.runs}")
env.shutdown()

tagged = outcomes.get("Complete", 0)
rate = tagged / args.runs

print("\n=== outcomes ===")
for k, v in outcomes.most_common():
    print(f"  {k:>9}: {v:6d}  ({v/args.runs:.4f})")

print("\n=== tag-rate ===")
print(f"  measured = {rate:.4f}  ({tagged}/{args.runs})")

print("\n=== attacker-id histogram (top 6) ===")
total = sum(attackers.values()) or 1
for pid, c in sorted(attackers.items())[:6]:
    print(f"  person {pid:2d}: {c:6d}  ({c/total:.4f})")
print(f"  (expect person 0 ~ {args.acc:.2f}, others ~ {(1-args.acc)/19:.4f} each)")
