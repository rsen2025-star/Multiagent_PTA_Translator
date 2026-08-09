"""
Single live run of the 3-agent build. Prints a step trace and the ending.

Usage:  python run_once.py [--deadline D] [--use-box 0|1] [--acc B] [--I N]
Example: python run_once.py --deadline 9 --acc 0.8 --I 2
"""
import argparse
from attention_env import AttentionEnv

ap = argparse.ArgumentParser(description="one live episode of the 3-agent build")
ap.add_argument("--deadline", type=int, default=5)
ap.add_argument("--use-box", type=int, default=1, choices=(0, 1))
ap.add_argument("--acc", type=float, default=0.8, help="box_accuracy, Collins' P(tp)")
ap.add_argument("--I", type=int, default=1, help="inspection time (Collins v1.5's I)")
args = ap.parse_args()

print(f"deadline={args.deadline} use_box={args.use_box} box_accuracy={args.acc} "
      f"inspection_time={args.I}")
env = AttentionEnv(deadline=args.deadline, use_box=args.use_box,
                   box_accuracy=args.acc, inspection_time=args.I)
env.create_agents()
outcome = env.run_episode(trace=True)
print(f"\nattacker = person {env.attacker_id}")
print(f"OUTCOME  = {outcome}")
env.shutdown()
