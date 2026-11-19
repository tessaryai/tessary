// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import ai.tessary.fix.FixAgent.FixAnswer;
import ai.tessary.fix.FixRunRow.FixCheck;
import ai.tessary.rca.RcaDtos.Cause;
import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The words of a fix pull request: what happened first, then the evidence, the causes, the change and the checks.
 * Rewritten on every commit, so the description always describes the newest change.
 */
final class FixPrText {

    private FixPrText() {}

    /**
     * @param caseUrl null when the deployment has no app address to link to @param earlier keys the pull request's
     * earlier commits fix
     */
    record Facts(
            String caseReference,
            String caseTitle,
            @Nullable String caseUrl,
            @Nullable String reportSummary,
            List<FixableCause> causes,
            List<String> earlier,
            FixAnswer answer) {}

    static String title(Facts f) {
        String t = f.answer().title();
        return t.isBlank()
                ? "Fix " + f.caseReference() + ": " + f.causes().get(0).cause().title()
                : t;
    }

    static String commitMessage(Facts f) {
        return title(f) + "\n\n" + f.answer().why() + "\n\nCase: " + f.caseReference() + "\nCauses: "
                + String.join(", ", FixableCause.keys(f.causes())) + "\n";
    }

    static String body(Facts f) {
        StringBuilder b = new StringBuilder();
        b.append("Tessary opened this pull request to fix case ")
                .append(f.caseReference())
                .append(": ")
                .append(f.caseTitle().replaceAll("\\.+$", ""))
                .append(".\n");
        String summary = f.reportSummary();
        if (summary != null && !summary.isBlank())
            b.append("\n## What happened\n\n").append(summary.strip()).append('\n');
        b.append("\n## Causes\n\n");
        for (FixableCause fc : f.causes()) {
            Cause c = fc.cause();
            b.append("- ").append(c.title()).append(" (`").append(fc.path()).append("`). ");
            String how = c.howItCausedThis();
            if (how != null && !how.isBlank()) b.append(how.strip()).append(' ');
            b.append(evidence(c)).append('\n');
        }
        if (!f.earlier().isEmpty()) {
            b.append("\nEarlier commits on this branch fix ")
                    .append(f.earlier().stream().map(k -> "`" + k + "`").collect(Collectors.joining(", ")))
                    .append(".\n");
        }
        b.append("\n## The change\n\n").append(f.answer().why()).append('\n');
        b.append("\n## Checks\n\n");
        if (f.answer().checks().isEmpty()) {
            b.append("The agent ran no checks. Your CI is the gate.\n");
        } else {
            for (FixCheck c : f.answer().checks()) {
                b.append("- `")
                        .append(c.command())
                        .append("`: ")
                        .append(c.result().replace('_', ' '));
                String note = c.note();
                if (note != null && !note.isBlank()) b.append(". ").append(note.strip());
                b.append('\n');
            }
        }
        b.append('\n');
        String url = f.caseUrl();
        if (url != null)
            b.append("[Open case ")
                    .append(f.caseReference())
                    .append(" in Tessary](")
                    .append(url)
                    .append(").\n\n");
        b.append("Tessary does not merge this pull request. Review it like any other change.\n");
        return b.toString();
    }

    private static String evidence(Cause c) {
        int traces = c.evidenceTraceIds().size();
        int sessions = c.evidenceSessionIds().size();
        if (sessions > 0) return "Seen in " + sessions + (sessions == 1 ? " flagged session." : " flagged sessions.");
        if (traces > 0) return "Seen in " + traces + (traces == 1 ? " flagged trace." : " flagged traces.");
        return "Seen in the case's flagged rows.";
    }
}
