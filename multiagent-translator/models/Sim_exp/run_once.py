"""
Single live run of the 3-agent build. Prints a step trace and the ending.

Usage:  python run_once.py [deadline] [use_box] [box_accuracy]
Example: python run_once.py 5 1 0.8
"""
import sys
from attention_env import AttentionEnv

deadline = int(sys.argv[1]) if len(sys.argv) > 1 else 5
use_box = int(sys.argv[2]) if len(sys.argv) > 2 else 1
box_accuracy = float(sys.argv[3]) if len(sys.argv) > 3 else 0.8

print(f"deadline={deadline} use_box={use_box} box_accuracy={box_accuracy}")
env = AttentionEnv(deadline=deadline, use_box=use_box, box_accuracy=box_accuracy)
env.create_agents()
outcome = env.run_episode(trace=True)
print(f"\nattacker = person {env.attacker_id}")
print(f"OUTCOME  = {outcome}")
env.shutdown()
