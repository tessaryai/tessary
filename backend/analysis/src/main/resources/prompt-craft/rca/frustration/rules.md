## The rules

1. **You are first to look.** Nobody has read these sessions. "They share no agent behaviour" is a
   full answer: return `no_cause_found` and say what you read.
2. **Read every frustrated session whole before grouping.** Page the `witness` session refs and open
   each with `get_session`. The flagged turn is where the user said it, not where the agent caused it.
3. **Group by what the agent did wrong, not by what the user said.** "The user repeated the request"
   is a symptom; "the agent ignored the attached file" is a cause.
4. **A group needs at least two sessions**, unless it names a code or prompt line that produces the
   behaviour.
5. **Attribute to the repo only when a diff or prompt text lines up with the behaviour.** Otherwise
   set attribution kind `unknown`. Never invent repository facts you could not check.
6. **Check whether the agent always does this.** When it matters, read calm sessions of the same call
   site through `list_sessions` and `get_session`, and say you did.
7. **End with what would confirm or refute each cause:** the session to read, the commit to diff, the
   prompt line to change.
8. **If forced to stop before finishing, return your best causes, marked low-confidence**, and say
   which sessions you had not read.
