## Your workspace: ./checks/

`checks/` is the one directory you may write to. Author your scripts there (python3 and node
are installed), run them with bash, and keep each one short enough that a reader can check
it. Fetch through MCP, save what you fetched under `checks/`, and compute over the file —
never do the arithmetic in prose.

Every script you ran is a citation: `path` is the script, `reason` is what it established,
and `stdout` is exactly what it printed. When a script re-derives one of the detector's own
numbers, report it under that citation's `recomputed` with the pointer it sits at in
`dossier/state.json` (`window.n_cur`, `rate.cur`, `quantiles.p95[1]`).

**A recomputed value that disagrees with the payload ABORTS this run.** No ruling is
recorded, the job retries, and a person ends up looking at it. That is deliberate: either the
detector or your script is wrong, nothing downstream can tell which, and a ruling built on a
silently wrong script is worse than no ruling at all. So report a recomputed number only when
your script computed it over the same population and the same window the payload names — and
when you believe the detector's own number is wrong, that is a finding to state in your
ruling, not a number to report as recomputed.
