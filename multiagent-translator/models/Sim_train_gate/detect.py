"""
STEP 2 of 3 — read the recorded run log, decide which runs went unsafe, write the
results CSV.

    python detect.py                 # log -> CSV
    python detect.py --plot          # log -> CSV -> PNG  (fuses step 3)

**The output holds SOAR measurements and nothing else.** No PRISM value is read,
written, or compared here; any comparison is something you do afterwards, with the
two tools' files side by side.

The rule, applied to the recorded states and nothing else:

    UNSAFE  <=>  some tick has  train=in  AND  gate!=down          -> 1
    otherwise                                                      -> 0

It is worded to mean the same thing as PRISM's
    Pmin=? [ F (state_position=2 & state_gate_position!=2) ]
so the two are answering one question — but the number here comes only from the log.

Also recovered from the log, because it falls out of the same lines:

    r = (first tick the gate was `lowering`) - (tick `approach` was delivered)

the decision cycles lost to passing the message. The PRISM model fuses each
sender's label with its receiver's, so there r is 0 by construction.

Writes:
    soar_sweep.csv          travel_delay,mechanical_delay,unsafe,first_unsafe_tick,
                            reached_crossing,completed,ticks,r
    soar_sweep_matrix.txt   the same results as a 2-D lookup table
    soar_sweep_meta.txt     where each row's boundary fell, and the implied r

Next: python plot.py
"""

import argparse
import csv
import os
import re

_HERE = os.path.dirname(os.path.abspath(__file__))

RUN_RE = re.compile(r"^=== RUN travel_delay=(\d+) mechanical_delay=(\d+) ===")
END_RE = re.compile(r"^--- END travel_delay=(\d+) mechanical_delay=(\d+) "
                    r"ticks=(\d+) completed=(\d+) ---")
TICK_RE = re.compile(r"^\s*tick\s+(\d+)\s+train=(\S+)\s+count=\s*(\S+)\s+"
                     r"ctrl=(\S+)\s+gate=(\S+)\s+count=\s*(\S+)")
DELIVER_RE = re.compile(r"^\s*>>> DELIVER tick\s+(\d+)\s+(\S+) --\[(\S+)=(\S+)\]--> (\S+)")

CROSSING = "in"
GATE_DOWN = "down"


def parse_log(path):
    """One dict per RUN block. Nothing is trusted but the recorded states."""
    runs = []
    cur = None

    with open(path, encoding="utf-8") as fh:
        for line in fh:
            m = RUN_RE.match(line)
            if m:
                cur = {
                    "travel_delay": int(m.group(1)),
                    "mechanical_delay": int(m.group(2)),
                    "unsafe": 0,
                    "first_unsafe": None,
                    "reached_crossing": 0,
                    "lowering_tick": None,
                    "approach_tick": None,
                    "ticks": 0,
                    "completed": 0,
                }
                continue

            if cur is None:
                continue

            m = TICK_RE.match(line)
            if m:
                tick, train, _tc, _ctrl, gate, _gc = m.groups()
                tick = int(tick)
                if gate == "lowering" and cur["lowering_tick"] is None:
                    cur["lowering_tick"] = tick
                if train == CROSSING:
                    cur["reached_crossing"] = 1
                    if gate != GATE_DOWN and not cur["unsafe"]:
                        cur["unsafe"] = 1
                        cur["first_unsafe"] = tick
                continue

            m = DELIVER_RE.match(line)
            if m:
                tick, _src, attr, val, _dst = m.groups()
                if attr == "signal" and val == "approach" and cur["approach_tick"] is None:
                    cur["approach_tick"] = int(tick)
                continue

            m = END_RE.match(line)
            if m:
                cur["ticks"] = int(m.group(3))
                cur["completed"] = int(m.group(4))
                if cur["lowering_tick"] is not None and cur["approach_tick"] is not None:
                    cur["r"] = cur["lowering_tick"] - cur["approach_tick"]
                else:
                    cur["r"] = None
                runs.append(cur)
                cur = None

    return runs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--log", default="soar_runs.log")
    ap.add_argument("--out", default="soar_sweep", help="output basename")
    ap.add_argument("--plot", action="store_true", help="also draw the figure (step 3)")
    args = ap.parse_args()

    log_path = _resolve(args.log)
    runs = parse_log(log_path)
    if not runs:
        raise SystemExit(f"no RUN blocks found in {log_path}")

    travels = sorted({r["travel_delay"] for r in runs})
    mechs = sorted({r["mechanical_delay"] for r in runs})
    unsafe_n = sum(r["unsafe"] for r in runs)

    print(f"read {len(runs)} runs from {os.path.basename(log_path)}")
    print(f"  grid            : travel_delay {travels[0]}..{travels[-1]}  x  "
          f"mechanical_delay {mechs[0]}..{mechs[-1]}")
    print(f"  unsafe          : {unsafe_n} / {len(runs)}")
    stalled = [r for r in runs if not r["reached_crossing"]]
    if stalled:
        print(f"  ⚠ never reached the crossing: {len(stalled)} runs")

    rs = sorted({r["r"] for r in runs if r["r"] is not None})
    print(f"  measured r      : {rs}")

    csv_path = _resolve(args.out + ".csv")
    _write_csv(runs, csv_path)
    _write_matrix(runs, travels, mechs, _resolve(args.out + "_matrix.txt"))
    _write_meta(runs, travels, mechs, rs, _resolve(args.out + "_meta.txt"))

    print(f"  wrote {args.out}.csv / _matrix.txt / _meta.txt")

    if args.plot:
        import plot
        plot.draw(csv_path, _resolve(args.out + ".png"))
    else:
        print(f"  next:  python plot.py")


def _resolve(p):
    return p if os.path.isabs(p) else os.path.join(_HERE, p)


def _open_w(path, newline=None):
    """Open for writing, but say something useful when the file is locked.

    Excel holds a CSV open for the whole time you are looking at it, and this step
    is the one that overwrites them. Nothing is lost when it fails — the run log is
    untouched, so re-running is instant."""
    try:
        return open(path, "w", newline=newline, encoding="utf-8")
    except PermissionError:
        raise SystemExit(
            f"\n  Cannot write {os.path.basename(path)} - it is open in another program."
            f"\n  Close it (Excel locks a CSV while it is open) and run detect.py again."
            f"\n  Nothing was lost: soar_runs.log is intact, so this step is instant.\n")


def _write_csv(runs, path):
    """SOAR measurements only. Every column here was observed in the run log —
    nothing is imported from PRISM or compared against it."""
    with _open_w(path, newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["travel_delay", "mechanical_delay", "unsafe",
                    "first_unsafe_tick", "reached_crossing", "completed", "ticks", "r"])
        for r in runs:
            w.writerow([r["travel_delay"], r["mechanical_delay"], r["unsafe"],
                        r["first_unsafe"] if r["first_unsafe"] else "",
                        r["reached_crossing"], r["completed"], r["ticks"],
                        r["r"] if r["r"] is not None else ""])


def _write_matrix(runs, travels, mechs, path):
    """The same SOAR results as a 2-D grid, for reading at a glance."""
    lookup = {(r["travel_delay"], r["mechanical_delay"]): r["unsafe"] for r in runs}
    with _open_w(path) as fh:
        fh.write("travel_delay\\mechanical_delay:\n")
        fh.write("\t" + "\t".join(str(m) for m in mechs) + "\n")
        for T in travels:
            fh.write(str(T) + "\t"
                     + "\t".join(str(lookup.get((T, m), "-")) for m in mechs) + "\n")


def _write_meta(runs, travels, mechs, rs, path):
    """Where each row's boundary actually fell, and what that implies for r.

    The safety law is  M + r <= T,  so the largest safe M in a row is T - r and the
    boundary itself measures r. Derived from the data, not assumed."""
    with _open_w(path) as fh:
        fh.write("SOAR train gate - measured safety boundary\n")
        fh.write("=" * 64 + "\n\n")
        fh.write("travel_delay   largest safe M   implied r = T - M_max\n")
        for T in travels:
            safe = [r["mechanical_delay"] for r in runs
                    if r["travel_delay"] == T and not r["unsafe"]]
            if safe:
                m_max = max(safe)
                censored = "  (censored: grid ends before the boundary)" \
                    if m_max == mechs[-1] else ""
                fh.write(f"{T:>12}   {m_max:>14}   {T - m_max:>21}{censored}\n")
            else:
                fh.write(f"{T:>12}   {'none':>14}   {'-':>21}\n")
        fh.write(f"\nmeasured r (approach delivered -> gate starts lowering): {rs}\n")
        fh.write("The implied r column is derived from where safety actually broke;\n"
                 "the measured r is read off the delivery lines. They must agree.\n")


if __name__ == "__main__":
    main()
