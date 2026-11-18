"""
STEP 3 of 3 — draw the raw SOAR sweep. No overlay, no derived quantities.

    python plot.py
    python plot.py --csv soar_sweep.csv --out soar_sweep.png

x = mechanical_delay, y = unsafe (1) or safe (0), one curve per travel_delay.

The SOAR agents are deterministic, so y is genuinely binary — this is the observed
outcome of a single run per cell, not an estimate. The y axis is therefore labelled
with the two states rather than a 0.0-1.0 probability scale, which would imply a
frequency that was never measured.

travel_delay is an ordered quantity, not a category, so the curves are coloured on
one light->dark ramp with a colourbar rather than 15 unrelated hues.
"""

import argparse
import csv
import os

import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt          # noqa: E402
from matplotlib import colors as mcolors  # noqa: E402

_HERE = os.path.dirname(os.path.abspath(__file__))

INK = "#1f1f1f"
MUTED = "#6e6e6e"
GRIDC = "#dcdcdc"


def read_grid(path):
    """{(travel_delay, mechanical_delay): unsafe} from the SOAR results CSV."""
    grid = {}
    with open(path, newline="", encoding="utf-8") as fh:
        reader = csv.reader(fh)
        header = next(reader)
        ti = header.index("travel_delay")
        mi = header.index("mechanical_delay")
        ri = header.index("unsafe")
        for row in reader:
            if not row or len(row) <= max(ti, mi, ri):
                continue
            grid[(int(row[ti]), int(row[mi]))] = int(row[ri])
    return grid


def draw(soar_csv, out_png=None):
    grid = read_grid(soar_csv)
    if not grid:
        raise SystemExit(f"no rows in {soar_csv}")

    travels = sorted({T for T, _ in grid})
    mechs = sorted({M for _, M in grid})
    out_png = out_png or os.path.join(_HERE, "soar_sweep.png")

    # one hue, light -> dark: travel_delay is ordered, so its colour should be too
    base = plt.get_cmap("Blues")
    ramp = mcolors.LinearSegmentedColormap.from_list(
        "travel", base(np.linspace(0.32, 0.97, 256)))
    norm = mcolors.Normalize(vmin=travels[0], vmax=travels[-1])

    fig, ax = plt.subplots(figsize=(10, 5.5))

    for T in travels:
        ys = [grid.get((T, M), np.nan) for M in mechs]
        ax.plot(mechs, ys, marker="o", markersize=4.5, linewidth=2,
                color=ramp(norm(T)), zorder=2)

    # identity must not rest on colour alone. Only curves that actually step can be
    # pointed at, so labels are picked from those; the strip above y=1 is free.
    steps = {T: [M for M in mechs if grid.get((T, M)) == 1] for T in travels}
    labelled = [T for T in travels if steps[T]]
    picks = ([labelled[0], labelled[len(labelled) // 2], labelled[-1]]
             if len(labelled) >= 3 else labelled)
    for T in dict.fromkeys(picks):
        ax.annotate(f"travel_delay={T}", xy=(steps[T][0], 1.0),
                    xytext=(steps[T][0], 1.08), fontsize=8, color=MUTED,
                    ha="center", va="bottom",
                    arrowprops=dict(arrowstyle="-", color=GRIDC, linewidth=1))

    # curves with no step lie on top of each other at 0 — say so, or they read as one
    flat = [T for T in travels if not steps[T]]
    if flat:
        ax.text(mechs[-1], -0.07,
                f"travel_delay {flat[0]}–{flat[-1]} never step: "
                f"{len(flat)} curves overlap on the 0 line",
                ha="right", va="center", fontsize=8, color=MUTED)

    ax.set_xlabel("mechanical_delay", color=INK)
    ax.set_ylabel("outcome", color=INK)
    ax.set_title("SOAR train gate — unsafe outcome per configuration",
                 color=INK, fontsize=12, pad=12)

    ax.set_yticks([0, 1])
    ax.set_yticklabels(["0   safe", "1   unsafe"], color=INK)
    ax.set_ylim(-0.12, 1.30)
    ax.set_xticks(mechs)
    ax.tick_params(colors=MUTED)

    ax.grid(alpha=0.55, linestyle=":", color=GRIDC, zorder=0)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    for side in ("left", "bottom"):
        ax.spines[side].set_color(GRIDC)

    cbar = fig.colorbar(plt.cm.ScalarMappable(norm=norm, cmap=ramp),
                        ax=ax, ticks=travels, pad=0.02)
    cbar.set_label("travel_delay", color=INK)
    cbar.ax.tick_params(colors=MUTED, length=0)
    cbar.outline.set_visible(False)

    fig.tight_layout()
    try:
        fig.savefig(out_png, dpi=150)
    except PermissionError:
        raise SystemExit(
            f"\n  Cannot write {os.path.basename(out_png)} - it is open in another"
            f" program.\n  Close the image viewer and run plot.py again.\n")
    print(f"  wrote {os.path.basename(out_png)}")
    return out_png


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", default="soar_sweep.csv")
    ap.add_argument("--out", default="soar_sweep.png")
    args = ap.parse_args()

    def r(p):
        return p if os.path.isabs(p) else os.path.join(_HERE, p)

    draw(r(args.csv), r(args.out))


if __name__ == "__main__":
    main()
