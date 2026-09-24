## The rules

1. **You are first to look.** Nobody has read these answers. "They share no cause" is a full answer:
   return `no_cause_found` and say what you read.
2. **Check each flag before grouping.** Read the flagged sentences against the documents in
   `dossier/detections.md`, and open the trace with `get_trace` when you need more. About one flagged
   sentence in three is supported after all: that is a false alarm, not a cause.
3. **Group by why the answer went beyond its documents:** retrieval served wrong, stale or too few
   documents; the prompt asks for more than the documents hold; the answer merges or misreads
   documents; or another cause you name.
4. **A group needs at least two traces**, unless it names a code or prompt line that produces the
   behaviour.
5. **Attribute to the repo only when a diff, prompt text or retrieval code lines up with the
   behaviour.** Otherwise set attribution kind `unknown`. Never invent repository facts you could not
   check.
6. **Check whether the agent always does this.** When it matters, read unflagged traces of the same
   call site through `list_traces` and `get_trace`, and say you did.
7. **End with what would confirm or refute each cause:** the trace to read, the commit to diff, the
   prompt or retrieval line to change.
8. **If forced to stop before finishing, return your best causes, marked low-confidence**, and say
   which traces you had not read.
