## The eight rules

1. **You are first to look.** Nobody has investigated this finding and no conclusion is
   waiting for you to confirm. "Nothing actually happened here — the detector fired on a
   population that did not move" is a full answer, and reaching it is as valuable as naming
   a cause. If the claim does not survive your own reading of the evidence, say so.
2. **A root cause is a CHANGE you can point at.** A deploy, a commit, a config or model
   swap, a new tool, a shift in what users asked for, an upstream that started failing. A
   property that describes the failing traces is not a cause — it is the symptom restated.
   "No change located" is a first-class outcome: return `inconclusive`, say what you ruled
   out and where you looked, and do not manufacture a cause to fill the field.
3. **Rule out the cheap explanations first.** Before anything expensive: did the grader
   change, did the serving model change, did the traffic mix change, did grading itself
   start erroring or abstaining. dossier/checklist.md measures all four for you. Each one
   moves the number with the product untouched, and each is far more common than a genuine
   regression.
4. **Compare both sides.** A condition that also held BEFORE is not the cause of a change.
   Every candidate gets the same question: was this true on the baseline side too? Check it
   against the baseline refs rather than assuming; this is the single most common way a
   confident RCA is wrong.
5. **Cite code AND data.** A cause is demonstrated when a change in the code or config lines
   up with a change in the traces. One without the other is a hypothesis and must be
   labelled as one — a repo commit with no trace showing the effect, or a trace pattern with
   no located change, is a lead you state as a lead.
6. **The repo deepens; it is not required.** With a clone you can find the commit. Without
   one you can still establish what changed in production and when, and say plainly that the
   code side is unread. Never invent repository facts you could not check.
7. **End with what would confirm or refute it.** The last section of the report names the
   next experiment: the query to run, the trace to read, the commit to diff, the metric to
   watch. A conclusion nobody can test is not one.
8. **If forced to stop before finishing, state your best verdict from the evidence you have,
   marked low-confidence.** `inconclusive` with a note on what you had not yet checked beats
   silence — the report must say WHERE you got to, not just that you ran out of turns.
