"""
STEP 1 of 3 — run the SOAR agents across a range of delays and RECORD every run.

    python run_sweep.py --travel 6:1:20 --mechanical 2:1:12

Ranges use PRISM's own `start:step:end` syntax (or plain `start:end`), so the same
grid can be handed to both tools.

Writes ONE long log — `soar_runs.log` by default — holding every decision cycle of
every configuration. The log records only FACTS: train position and count, ctrl
state, gate position and count, and each message the Python wire delivered.

**No verdict is written here.** Whether a run was unsafe is worked out by
`detect.py` from these states. Keeping the two apart means the detection rule can
be changed, argued with, or re-run without paying for the simulation again — and
the log stays the evidence either way.

Next: python detect.py
"""

import argparse
import os

from train_gate_env import TrainGateEnv

_HERE = os.path.dirname(os.path.abspath(__file__))


def parse_range(spec):
    """'6' | '6:20' | '6:1:20'  ->  list of ints. Middle field is the STEP (PRISM)."""
    parts = [int(p) for p in spec.split(":")]
    if len(parts) == 1:
        return parts
    if len(parts) == 2:
        return list(range(parts[0], parts[1] + 1))
    if len(parts) == 3:
        return list(range(parts[0], parts[2] + 1, parts[1]))
    raise ValueError(f"bad range {spec!r}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--travel", default="6:1:20", help="travel_delay range, start:step:end")
    ap.add_argument("--mechanical", default="2:1:12", help="mechanical_delay range")
    ap.add_argument("--max-ticks", type=int, default=400)
    ap.add_argument("--out", default="soar_runs.log", help="run log to write")
    args = ap.parse_args()

    travels = parse_range(args.travel)
    mechs = parse_range(args.mechanical)
    log_path = args.out if os.path.isabs(args.out) else os.path.join(_HERE, args.out)

    print(f"grid: travel_delay {travels[0]}..{travels[-1]}  x  "
          f"mechanical_delay {mechs[0]}..{mechs[-1]}   "
          f"= {len(travels) * len(mechs)} runs")
    print(f"recording to {log_path}")
    print("-" * 72)

    env = TrainGateEnv(travel_delay=travels[0], mechanical_delay=mechs[0])
    env.create_agents()

    n = 0
    try:
        with open(log_path, "w", encoding="utf-8") as fh:
            write = lambda line: fh.write(line + "\n")   # noqa: E731

            write("# SOAR train gate - raw run log")
            write(f"# travel_delay={args.travel}  mechanical_delay={args.mechanical}")
            write("# one RUN block per configuration; states only, no verdict.")
            write("# unsafe is derived by detect.py as: train=in AND gate!=down")
            write("")

            for T in travels:
                for M in mechs:
                    env.reset(travel_delay=T, mechanical_delay=M)
                    write(f"=== RUN travel_delay={T} mechanical_delay={M} ===")
                    res = env.run_cycle(max_ticks=args.max_ticks, sink=write)
                    write(f"--- END travel_delay={T} mechanical_delay={M} "
                          f"ticks={res['ticks']} completed={int(res['completed'])} ---")
                    write("")
                    n += 1
                    fh.flush()
                print(f"  travel_delay={T:>3}  ...{len(mechs)} runs recorded")
    finally:
        env.shutdown()

    size = os.path.getsize(log_path)
    print("-" * 72)
    print(f"  {n} runs recorded, {size / 1024:.0f} KB")
    print(f"  next:  python detect.py --log {os.path.basename(log_path)}")


if __name__ == "__main__":
    main()
