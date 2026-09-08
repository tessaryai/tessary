## Before you begin

Confirm you can reach the evidence: call `get_project`, then `get_finding_evidence` with
`count_only=true` for this finding. Both must answer.

If either does not — the MCP surface refuses, times out, or is not there — STOP and rule
`blocked`, naming what failed. Do not rule on the dossier alone: it carries the detector's
own numbers, so a ruling drawn from it only restates the claim back at us. `blocked` is not
a verdict and closes nothing; the run is retried once the surface is reachable.

