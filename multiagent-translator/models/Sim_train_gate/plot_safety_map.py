"""
Draw the sweep as a 2-D safety map: travel_delay x mechanical_delay, one cell per run.

    python plot_safety_map.py
    python plot_safety_map.py --csv soar_sweep.csv --out soar_safety_map.png
    python plot_safety_map.py --prism        # add the M = T reference line

Why a grid and not a curve. `unsafe` is not a function of mechanical_delay alone --
the same M is safe at one travel_delay and unsafe at another. A plain
mechanical_delay-vs-unsafe plot has to hide that second variable or smear it into
one line, so it cannot show the thing the sweep was run to find: the boundary.
`plot.py` draws the per-travel_delay curves; this draws the whole plane, and the
boundary is then visible as the staircase it actually is.

Every number in the figure is read off the CSV. The boundary is not the law
M = T - r drawn over the data -- it is the first unsafe cell of each row, so if a
row disagreed with the law the staircase would visibly kink.

unsafe is a state (safe / unsafe), not a magnitude, so the fills are the two status
colours rather than a ramp, and every cell also carries its 0/1 -- the pale fill is
below 3:1 on the surface, so colour is never the only channel.
"""

import argparse
import os

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt                 # noqa: E402
from matplotlib.patches import Rectangle        # noqa: E402
from matplotlib.lines import Line2D             # noqa: E402

from plot import read_grid, INK, MUTED, GRIDC   # noqa: E402  (same folder)

_HERE = os.path.dirname(os.path.abspath(__file__))

UNSAFE_FILL = "#d03b3b"   # status: critical
SAFE_FILL = "#e8e6e1"     # a wash, not a series colour
ON_UNSAFE = "#ffffff"

GAP = 0.06                # surface gap between neighbouring cells (in cell units)


def boundaries(grid, travels, mechs):
    """{travel_delay: lowest mechanical_delay that was unsafe}, omitting safe rows."""
    out = {}
    for T in travels:
        unsafe = [M for M in mechs if grid.get((T, M)) == 1]
        if unsafe:
            out[T] = unsafe[0]
    return out


def staircase(bounds, travels):
    """The safe/unsafe frontier as one polyline along cell edges."""
    rows = [T for T in travels if T in bounds]
    xs, ys = [], []
    prev_x = None
    for T in rows:
        x = bounds[T] - 0.5
        if prev_x is not None:                 # step across to this row's edge
            xs += [prev_x, x]
            ys += [T - 0.5, T - 0.5]
        xs += [x, x]                           # then down the row
        ys += [T - 0.5, T + 0.5]
        prev_x = x
    return xs, ys


def draw(soar_csv, out_png=None, show_prism=False):
    grid = read_grid(soar_csv)
    if not grid:
        raise SystemExit(f"no rows in {soar_csv}")

    travels = sorted({T for T, _ in grid})
    mechs = sorted({M for _, M in grid})
    out_png = out_png or os.path.join(_HERE, "soar_safety_map.png")

    fig, ax = plt.subplots(figsize=(11, 7))

    for (T, M), unsafe in grid.items():
        ax.add_patch(Rectangle(
            (M - 0.5 + GAP / 2, T - 0.5 + GAP / 2), 1 - GAP, 1 - GAP,
            facecolor=UNSAFE_FILL if unsafe else SAFE_FILL,
            edgecolor="none", zorder=1))
        ax.text(M, T, "1" if unsafe else "0", ha="center", va="center",
                fontsize=7.5, color=ON_UNSAFE if unsafe else MUTED, zorder=3)

    bounds = boundaries(grid, travels, mechs)
    if bounds:
        xs, ys = staircase(bounds, travels)
        ax.plot(xs, ys, color=INK, linewidth=2, solid_joinstyle="miter", zorder=4)

    # the translated PRISM model's boundary, for side-by-side reading only. Off by
    # default: this folder holds SOAR measurements, and M = T is a claim from the
    # other tool, not something this CSV measured.
    if show_prism:
        # same frontier convention as the measured one: PRISM's first unsafe cell
        # is M = T + 1, so its edge is x = T + 0.5. Rows whose edge lies past the
        # grid have no line to draw.
        prism = {T: T + 1 for T in travels if mechs[0] <= T + 1 <= mechs[-1] + 1}
        px, py = staircase(prism, [T for T in travels if T in prism])
        ax.plot(px, py, color=MUTED, linewidth=1.6, linestyle=(0, (5, 3)),
                solid_joinstyle="miter", zorder=4)

    ax.set_xlim(mechs[0] - 0.5, mechs[-1] + 0.5)
    ax.set_ylim(travels[0] - 0.5, travels[-1] + 0.5)
    ax.set_xticks(mechs)
    ax.set_yticks(travels)
    ax.set_xlabel("mechanical_delay  (M)", color=INK)
    ax.set_ylabel("travel_delay  (T)", color=INK)
    ax.set_title("SOAR train gate - which configurations are unsafe",
                 color=INK, fontsize=12, pad=26, loc="left")
    ax.tick_params(colors=MUTED, length=0)
    for side in ("top", "right", "left", "bottom"):
        ax.spines[side].set_visible(False)
    ax.set_aspect("equal")

    handles = [Rectangle((0, 0), 1, 1, facecolor=SAFE_FILL, edgecolor="none"),
               Rectangle((0, 0), 1, 1, facecolor=UNSAFE_FILL, edgecolor="none"),
               Line2D([0], [0], color=INK, linewidth=2)]
    labels = ["0   safe", "1   unsafe", "measured boundary"]
    if show_prism:
        handles.append(Line2D([0], [0], color=MUTED, linewidth=1.6,
                              linestyle=(0, (5, 3))))
        labels.append("PRISM boundary  M = T")
    leg = ax.legend(handles, labels, loc="lower left", bbox_to_anchor=(0, 1.005),
                    ncol=len(labels), frameon=False, handlelength=1.4,
                    handleheight=1.0, borderpad=0, columnspacing=1.6, fontsize=9)
    for text in leg.get_texts():
        text.set_color(MUTED)

    # the caption is derived, not typed: if a row broke the law it would say so
    unsafe_n = sum(grid.values())
    offsets = sorted({T - M for T, M in bounds.items()})
    if len(offsets) == 1:
        law = (f"unsafe on every cell with M >= T - {offsets[0]}, "
               f"i.e. safe <=> M <= T - {offsets[0] + 1}   (r = {offsets[0] + 1} "
               f"decision cycles, identical on all {len(bounds)} rows)")
    else:
        law = (f"boundary offset T - M is NOT constant: it takes values "
               f"{offsets} across {len(bounds)} rows")
    censored = [T for T in travels if T not in bounds]
    note = (f"{unsafe_n} unsafe of {len(grid)} runs.  {law}.")
    if censored:
        note += (f"  travel_delay {censored[0]}-{censored[-1]} never step inside "
                 f"this grid - extend --mechanical to see their boundary.")
    fig.text(0.012, 0.015, note, fontsize=8, color=MUTED, ha="left", va="bottom")

    fig.tight_layout(rect=(0, 0.045, 1, 1))
    try:
        fig.savefig(out_png, dpi=150)
    except PermissionError:
        raise SystemExit(
            f"\n  Cannot write {os.path.basename(out_png)} - it is open in another"
            f" program.\n  Close the image viewer and run plot_safety_map.py again.\n")
    print(f"  wrote {os.path.basename(out_png)}")
    print(f"  {note}")
    return out_png


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", default="soar_sweep.csv")
    ap.add_argument("--out", default="soar_safety_map.png")
    ap.add_argument("--prism", action="store_true",
                    help="overlay the translated PRISM model's M = T boundary")
    args = ap.parse_args()

    def r(p):
        return p if os.path.isabs(p) else os.path.join(_HERE, p)

    draw(r(args.csv), r(args.out), show_prism=args.prism)


if __name__ == "__main__":
    main()
