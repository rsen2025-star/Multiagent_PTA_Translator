"""
Sweep deadline x box_accuracy, run the SOAR sim at each grid point, and plot the
empirical SOAR tag-rate against the PRISM Pmin/Pmax analytic curves — the same
grid PRISM uses, one color per accuracy.

Overlay (choose with --overlay):
  both  -> solid = PRISM Pmax, dotted = PRISM Pmin, 'o' = SOAR  (default)
  pmax  -> solid = PRISM Pmax only + SOAR dots   ("do they match?")
  pmin  -> solid = PRISM Pmin only + SOAR dots   (matches your Pmin screenshot;
           SOAR sits ABOVE, since discrete SOAR = Pmax)

Everything is a CLI arg; each run re-sweeps and OVERWRITES the PNG + CSV.

Examples:
  python sweep_plot.py
  python sweep_plot.py --runs 2000 --acc 0.7,0.8,0.9,1.0 --overlay pmin
  python sweep_plot.py --dmin 0 --dmax 20 --use-box 1 --out mytest.png
"""
import argparse, csv, os, sys, contextlib
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from attention_env import AttentionEnv

HERE = os.path.dirname(os.path.abspath(__file__))


@contextlib.contextmanager
def silence_soar():
    """Redirect C-level stdout/stderr (fd 1/2) to null so the agents' echo/write
    trace lines don't flood — and slow — the terminal. Python progress prints
    happen OUTSIDE this block so they stay visible."""
    sys.stdout.flush(); sys.stderr.flush()
    saved_out, saved_err = os.dup(1), os.dup(2)
    devnull = os.open(os.devnull, os.O_WRONLY)
    os.dup2(devnull, 1); os.dup2(devnull, 2)
    try:
        yield
    finally:
        os.dup2(saved_out, 1); os.dup2(saved_err, 2)
        os.close(devnull); os.close(saved_out); os.close(saved_err)


def pmax(d, b):
    if d <= 0:
        return 0.0
    return min(1.0, b + (d - 1) * (1 - b) / 19)


def pmin(d, b):
    if d <= 1:
        return 0.0
    return b + (d - 2) * (1 - b) / 19


def soar_rate(deadline, b, use_box, runs):
    with silence_soar():
        env = AttentionEnv(deadline=deadline, use_box=use_box, box_accuracy=b)
        env.create_agents()
        tagged = 0
        for i in range(runs):
            env.reset(seed=i)
            if env.run_episode() == "Complete":
                tagged += 1
        env.shutdown()
    return tagged / runs


def main():
    ap = argparse.ArgumentParser(description="SOAR sim vs PRISM Pmin/Pmax sweep+plot")
    ap.add_argument("--runs", type=int, default=1000, help="episodes per grid point")
    ap.add_argument("--acc", type=str, default="0.70,0.75,0.80,0.85,0.90,0.95,1.00",
                    help="comma list of box_accuracy values")
    ap.add_argument("--dmin", type=int, default=0, help="min deadline")
    ap.add_argument("--dmax", type=int, default=20, help="max deadline")
    ap.add_argument("--use-box", type=int, default=1, choices=(0, 1))
    ap.add_argument("--overlay", choices=("both", "pmax", "pmin", "none"), default="both",
                    help="which PRISM lines to draw ('none' = SOAR dots only)")
    ap.add_argument("--out", type=str, default="soar_vs_prism.png")
    args = ap.parse_args()

    accuracies = [float(x) for x in args.acc.split(",")]
    deadlines = list(range(args.dmin, args.dmax + 1))

    total_cells = len(accuracies) * len(deadlines)
    print(f"sweep: {len(accuracies)} accuracies x {len(deadlines)} deadlines "
          f"x {args.runs} runs = {total_cells * args.runs} episodes "
          f"(use_box={args.use_box}, overlay={args.overlay})")
    results = {b: {} for b in accuracies}
    done = 0
    for b in accuracies:
        for d in deadlines:
            results[b][d] = soar_rate(d, b, args.use_box, args.runs)
            done += 1
            print(f"\r  {done:3d}/{total_cells} cells  "
                  f"(b={b:.2f}, deadline={d}, rate={results[b][d]:.3f})   ",
                  end="", flush=True)
    print()

    # CSV
    csv_path = os.path.join(HERE, os.path.splitext(args.out)[0] + ".csv")
    with open(csv_path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["box_accuracy", "deadline", "soar_rate", "pmin", "pmax"])
        for b in accuracies:
            for d in deadlines:
                w.writerow([b, d, f"{results[b][d]:.5f}",
                            f"{pmin(d,b):.5f}", f"{pmax(d,b):.5f}"])
    print(f"wrote {csv_path}")

    # plot
    cmap = plt.get_cmap("turbo")
    fig, ax = plt.subplots(figsize=(9, 10))
    n = max(1, len(accuracies) - 1)
    for k, b in enumerate(accuracies):
        color = cmap(k / n)
        if args.overlay in ("both", "pmax"):
            ax.plot(deadlines, [pmax(d, b) for d in deadlines], "-", color=color, lw=1.6)
        if args.overlay in ("both", "pmin"):
            style = ":" if args.overlay == "both" else "-"
            ax.plot(deadlines, [pmin(d, b) for d in deadlines], style, color=color, lw=1.4)
        # SOAR: connect dots with a line when there are no PRISM lines to read against
        soar_style = "o-" if args.overlay == "none" else "o"
        ax.plot(deadlines, [results[b][d] for d in deadlines], soar_style,
                color=color, ms=5, label=f"b={b:.2f}")

    key = {"both": "solid=Pmax  dotted=Pmin  dots=SOAR",
           "pmax": "solid=Pmax  dots=SOAR",
           "pmin": "solid=Pmin  dots=SOAR",
           "none": "SOAR simulation only"}[args.overlay]
    ax.set_xlabel("deadline")
    ax.set_ylabel("probability of tagging attacker")
    ax.set_title(f"SOAR sim vs PRISM  ({key})\n"
                 f"use_box={args.use_box}, {args.runs} runs per point")
    ax.set_xlim(args.dmin, args.dmax + 1)
    ax.set_ylim(0, 1.05)
    ax.grid(True, alpha=0.3)
    ax.legend(title="box_accuracy", loc="lower right")
    png_path = os.path.join(HERE, args.out)
    fig.savefig(png_path, dpi=110, bbox_inches="tight")
    print(f"wrote {png_path}")


if __name__ == "__main__":
    main()
