"""chart.py: what is left of each old per-frame cost, per screen (#267)."""
import json, os, sys
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
# The readings: PROBE267_DATA, or data/raw beside this script (the layout the branch commits: sc/, mm/, mali/).
S = os.environ.get('PROBE267_DATA') or os.path.join(os.path.dirname(os.path.abspath(__file__)), 'data', 'raw')
d = json.load(open(f'{S}/summary.json'))
SURFACE, INK, INK2, MUTED, GRID = '#fcfcfb', '#0b0b0b', '#52514e', '#8a8984', '#e4e3df'
SERIES = '#2a78d6'
rows = [
    ('megamerge-draft-open', 'Mega Merge: card draft'),
    ('megamerge-draft-dealing', 'Mega Merge: cards dealing'),
    ('megamerge-board-still', 'Mega Merge: board'),
    ('showcase-widgets-still', 'Showcase: widgets'),
    ('showcase-widgets-scroll', 'Showcase: widgets, scrolling'),
    ('showcase-game-animated', 'Showcase: game page, moving'),
    ('showcase-effects-still', 'Showcase: effects'),
    ('showcase-home-animated', 'Showcase: home, moving'),
    ('snake-menu', 'Snake: menu'),
    ('snake-playing', 'Snake: playing'),
]
cols = [
    ('cpu', 'CPU per frame', lambda v: f'{v:.2f} ms' if v < 10 else f'{v:.1f} ms'),
    ('kb', 'Garbage per frame', lambda v: f'{v:.0f} KB' if v >= 10 else f'{v:.1f} KB'),
    ('calls', 'GL calls per frame', lambda v: f'{v:,.0f}'),
    ('gpu57', 'Phone GPU, estimated', lambda v: f'{v:.1f} ms'),
]
plt.rcParams.update({'font.family': 'DejaVu Sans', 'font.size': 12})
fig, axes = plt.subplots(1, len(cols), figsize=(18, 7.6), sharey=True, facecolor=SURFACE)
y = list(range(len(rows)))[::-1]
for ax, (key, title, fmt) in zip(axes, cols):
    ax.set_facecolor(SURFACE)
    for yi, (scene, _) in zip(y, rows):
        b, a = d[scene][key]
        share = a / b if b else 0
        change = (a - b) / b * 100
        label = f'{change:+.0f}%'
        if key == 'cpu' and scene == 'megamerge-board-still':
            # Bimodal frames, so the mean per frame (review round 2), bar and label both.
            import statistics
            b, a = (statistics.median(v) for v in d[scene]['cpu_mean_runs'])
            share = a / b
            change = (a - b) / b * 100
            label = f'{change:+.0f}% (mean)'
        elif key == 'cpu' and d[scene].get('pairs_lower', 5) < 4:
            label = 'no clear change'
        ax.barh(yi, share * 100, height=0.56, color=SERIES, zorder=2)
        ax.text(max(share * 100, 0) + 3, yi + 0.13, label, va='center', ha='left', color=INK, fontsize=12, fontweight='bold', zorder=3, bbox=dict(facecolor=SURFACE, edgecolor='none', pad=0.6))
        ax.text(max(share * 100, 0) + 3, yi - 0.22, f'{fmt(b)} → {fmt(a)}', va='center', ha='left', color=INK2, fontsize=10, zorder=3, bbox=dict(facecolor=SURFACE, edgecolor='none', pad=0.6))
    ax.axvline(100, color=MUTED, linewidth=1.5, linestyle=(0, (4, 3)), zorder=1)
    ax.set_xlim(0, 175)
    ax.set_xticks([0, 50, 100])
    ax.set_xticklabels(['0', '50%', 'before'], color=INK2)
    ax.tick_params(axis='x', length=0, labelsize=11)
    ax.tick_params(axis='y', length=0)
    ax.grid(axis='x', color=GRID, linewidth=0.8, zorder=0)
    for side in ['top', 'right', 'left', 'bottom']:
        ax.spines[side].set_visible(False)
    ax.set_title(title, color=INK, fontsize=14, fontweight='bold', loc='left', pad=10)
axes[0].set_yticks(y)
axes[0].set_yticklabels([r[1] for r in rows], color=INK, fontsize=12)
fig.suptitle('ComposeGL per frame: today as a share of 27 Sep (before #234). Shorter bar = cheaper.', x=0.01, ha='left', color=INK, fontsize=16, fontweight='bold')
fig.text(0.01, 0.015, 'CPU: ComposeGL\'s input and render (Mega Merge: its whole game thread), desktop JVM, median frame over several runs. Garbage: bytes allocated per frame, escape analysis off.\n'
         'Phone GPU: pixels painted x Arm Mali compiler cycles, Mali-G57 MC2, an estimate. Home, moving: in its first seconds after a cold start it is 27% slower (#268).',
         color=INK2, fontsize=10.5, ha='left', va='bottom')
fig.tight_layout(rect=(0, 0.06, 1, 0.94))
out = sys.argv[1] if len(sys.argv) > 1 else f'{S}/before-after.png'
fig.savefig(out, dpi=100, facecolor=SURFACE)
print(out)
