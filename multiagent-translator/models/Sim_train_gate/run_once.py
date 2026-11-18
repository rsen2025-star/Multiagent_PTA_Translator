"""
run_once.py — trace ONE configuration, decision cycle by decision cycle.

    python run_once.py --travel 6 --mechanical 2

Prints every tick plus the Python message deliveries, then reports the measured
round-trip cost `r`: how many decision cycles pass between the train signalling
`approach` and the gate actually starting to lower. That number is the whole
reason this harness exists — it is the quantity the PRISM model assumes is zero.
"""

import argparse

from train_gate_env import TrainGateEnv


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--travel", type=int, default=6, help="travel_delay (train leg)")
    ap.add_argument("--mechanical", type=int, default=2, help="mechanical_delay (gate move)")
    ap.add_argument("--max-ticks", type=int, default=400)
    ap.add_argument("--quiet", action="store_true", help="suppress the per-tick trace")
    args = ap.parse_args()

    env = TrainGateEnv(travel_delay=args.travel, mechanical_delay=args.mechanical)
    env.create_agents()

    print(f"travel_delay={args.travel}  mechanical_delay={args.mechanical}  "
          f"(slack S = {args.travel - args.mechanical})")
    print("-" * 72)

    try:
        result = env.run_cycle(max_ticks=args.max_ticks,
                               sink=None if args.quiet else print)

        print("-" * 72)
        print("Python deliveries (the wire):")
        for tick, src, dst, attr, val in env.deliveries:
            print(f"  tick {tick:3d}:  {src:<10} --[{attr}={val}]--> {dst}")

        print("-" * 72)
        verdict = "UNSAFE" if result["unsafe"] else "safe"
        print(f"  verdict          : {verdict}")
        if result["first_unsafe"]:
            print(f"  first unsafe at  : tick {result['first_unsafe']}")
        print(f"  reached crossing : {result['reached_crossing']}")
        print(f"  cycle completed  : {result['completed']}")
        print(f"  ticks used       : {result['ticks']}")

        r = result["handover"]
        if r is not None:
            print(f"  measured r       : {r} decision cycles "
                  f"(train signals approach -> gate starts lowering)")
            print(f"  predicted safe   : mechanical_delay <= travel_delay - {r}"
                  f"   ->  {args.mechanical} <= {args.travel - r}")
    finally:
        env.shutdown()


if __name__ == "__main__":
    main()
