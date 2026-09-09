# SPDX-License-Identifier: Apache-2.0
"""Bernoulli CUSUM calibration — EXACT, on the integer lattice.

The log-likelihood-ratio CUSUM over tool calls is, per call:
    failure -> S += ln(p1/p0)
    success -> S += ln((1-p1)/(1-p0))      [negative]
    S = max(0, S);  alarm when S >= h

Divide every weight by the magnitude of the success step. A success is then exactly -1 and a
failure exactly +r, so S lives on the integers and the Markov chain is EXACT rather than
discretized — which matters here because at p0=0.1% the success step is ~350x smaller than the
failure step, and any uniform grid coarse enough to solve either rounds the success step to zero.

ARL0 = expected CALLS to a false alarm while the rate really is p0. Higher is better.
ARL1 = expected calls to detection once the rate has moved. Lower is better.
"""
import math
import numpy as np


MIN_FAILURE_STEPS = 60


def lattice(p0, p1, min_r=MIN_FAILURE_STEPS):
    """(r, down, unit) — a failure is +r steps, a success is -down, in units of `unit` log-odds.

    The naive lattice takes the success step as the unit, so a failure is round(wf/ws) steps and a
    success is exactly 1. That is essentially exact when failures are rare — at p0=0.1% a failure is
    ~350 success steps, so rounding costs 0.3% — and badly wrong when they are not. At p0=20% a
    failure is 2.41 success steps, rounds to 2, and the solved threshold comes out 2.7 too low.
    That error is invisible: the chain is still exact, just for weights that are not the detector's.

    So the unit is refined until a failure spans at least `min_r` steps, which bounds the rounding
    error below ~1% everywhere. The chain grows by the same factor and stays trivially solvable.
    """
    wf = math.log(p1 / p0)
    ws = -math.log((1 - p1) / (1 - p0))  # positive magnitude
    k = max(1, math.ceil(min_r * ws / wf))
    unit = ws / k
    return max(1, round(wf / unit)), k, unit


def arl(p, r, down, hh):
    """Expected run length from S=0 on the integer lattice, alarm at S >= hh.

    Dense, on purpose. This was a scipy.sparse build plus `spsolve`, which is the natural shape for
    a matrix with two entries per row — but scipy is not in this package's base dependencies (it
    arrives only through the `quality`/`train` extras), and this file is published as part of the
    open edition, so a scipy import here would have put the whole scientific stack into its declared
    dependency set for a script nothing imports. The problem is small enough that it does not
    matter: `solve_hh` binary-searches to a 250,000-call budget, which tops out at n=2048 over 155
    solves for the whole report. Measured against the sparse version it produced byte-identical
    output, the same `(r, down, unit, hh)` at every base rate, and 4.7e-08 max relative difference
    across every point the search visits. Do not "optimise" this back to scipy.
    """
    n = hh
    s = np.arange(n)
    M = np.eye(n)
    up = s + r
    live = up < n                        # otherwise absorbed
    M[s[live], up[live]] -= p
    M[s, np.maximum(0, s - down)] -= 1 - p
    return float(np.linalg.solve(M, np.ones(n))[0])


def solve_hh(p0, p1, target):
    r, down, unit = lattice(p0, p1)
    lo, hi = 2 * down, 4 * down
    while arl(p0, r, down, hi) < target and hi < 400_000:
        lo, hi = hi, hi * 2
    while lo + 1 < hi:
        mid = (lo + hi) // 2
        if arl(p0, r, down, mid) < target:
            lo = mid
        else:
            hi = mid
    return r, down, unit, hi


def fit(points):
    """Least squares of h against ln(p0) — the two constants ToolErrorConfig ships."""
    xs = [math.log(p) for p, _ in points]
    ys = [h for _, h in points]
    mx, my = sum(xs) / len(xs), sum(ys) / len(ys)
    slope = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sum((x - mx) ** 2 for x in xs)
    return my - slope * mx, slope


TARGET = 250_000
RATES = (0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.10, 0.20, 0.30)

# The threshold that hits the budget at each base rate, and what a FLAT 6.0 delivers instead. The
# second column is why ToolErrorConfig stopped shipping a constant: it is not flat, it is a slope.
hdr = (f"{'p0':>7} {'p1':>7} {'exact h':>8} {'ARL0':>10} {'lag @p1':>9} "
       f"{'ARL0 @ flat 6':>14}")
print(hdr)
print("-" * len(hdr))
exact = []
for p0 in RATES:
    p1 = min(max(2 * p0, p0 + 0.005), 0.99)
    r, down, unit, hh = solve_hh(p0, p1, TARGET)
    h = hh * unit
    exact.append((p0, h))
    print(f"{p0:>7.3f} {p1:>7.3f} {h:>8.2f} {arl(p0, r, down, hh):>10,.0f} "
          f"{arl(p1, r, down, hh):>9,.0f} {arl(p0, r, down, round(6.0 / unit)):>14,.0f}")

# Fitted over the multiplicative regime only. Below ~0.5% the shift FLOOR rather than the multiple
# sets p1, so the relationship changes shape and the exact thresholds flatten out at 5.8-6.1 —
# which is what ToolErrorConfig.MIN_DECISION_INTERVAL clamps to rather than extrapolating a line
# into a regime that does not have one.
intercept, slope = fit([(p, h) for p, h in exact if p >= 0.005])
print(f"\nToolErrorConfig fit: h = {intercept:.2f} + {slope:.3f} * ln(p0), clamped to [6, 12]")
print(f"{'p0':>7} {'exact':>7} {'fitted':>7} {'error':>7}")
for p0, h in exact:
    fitted = max(6.0, min(12.0, intercept + slope * math.log(p0)))
    print(f"{p0:>7.3f} {h:>7.2f} {fitted:>7.2f} {fitted - h:>+7.2f}")

# The exchange rate behind the arl_target dial: how far the threshold has to move to buy a factor
# on the budget. Flat enough at ~1.0 that the dial needs no correction term.
print("\nd(ln ARL0)/dh:")
for p0 in (0.005, 0.01, 0.05, 0.20):
    p1 = min(max(2 * p0, p0 + 0.005), 0.99)
    r, down, unit = lattice(p0, p1)
    lo, hi = (arl(p0, r, down, round(h / unit)) for h in (7.0, 9.0))
    print(f"  p0={p0:>6.1%}  {math.log(hi / lo) / 2:.3f} per unit h")
