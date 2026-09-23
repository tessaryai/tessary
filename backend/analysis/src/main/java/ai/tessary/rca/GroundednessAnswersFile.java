// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.classifier.detector.groundedness.GroundednessEvidence.FlaggedAnswerPage;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence.FlaggedAnswerView;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence.FlaggedSentenceView;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence.RetrievedDocumentView;
import java.util.List;
import java.util.Locale;

/**
 * {@code dossier/detections.md} for a groundedness RCA: the flagged answers the finding cites, newest first, each
 * as the model was sent it (the question, the answer, every flagged sentence with its score, and the documents).
 * A groundedness cause is found by reading a flagged sentence against its documents, and handing both over saves
 * the agent a {@code get_span} per answer before it can start.
 *
 * <p>The selection is declared, never an undeclared sample: the newest {@link #CAP} answers, the same page the
 * finding page opens on, cut to whole answers inside {@link #CHAR_BUDGET}, with the counts shown and the rest one
 * {@code get_finding_evidence} page away. The name matches the triage dossier's file of the same content, which
 * the groundedness method card points at.
 */
final class GroundednessAnswersFile {

    static final String NAME = "detections.md";

    /** Answers read for the file: the finding page's first page. */
    static final int CAP = 50;

    /** The same ~30k-token budget as {@code evidence.json}. Documents are long, so this binds before {@link #CAP}
     *  does on most call sites. */
    static final int CHAR_BUDGET = 30_000 * 4;

    private GroundednessAnswersFile() {}

    static String render(FlaggedAnswerPage page) {
        StringBuilder sb = new StringBuilder("# Flagged answers\n\n")
                .append("The answers this finding cites, newest first. For each: the trace and span ids (the")
                .append(" `get_trace` / `get_span` arguments), the score (P(unsupported) of the strongest")
                .append(" sentence), each flagged sentence with its score, the question, the answer as it was")
                .append(" scored, and the documents it was checked against. A flagged sentence is where the model")
                .append(" saw no support in the documents, not proof that the sentence is wrong.\n");
        List<FlaggedAnswerView> rows = page.rows();
        int shown = 0;
        for (FlaggedAnswerView a : rows) {
            String one = answer(shown + 1, a);
            if (shown > 0 && sb.length() + one.length() > CHAR_BUDGET) break;
            sb.append(one);
            shown++;
        }
        sb.append('\n');
        if (shown == page.total()) {
            sb.append(String.format(Locale.ROOT, "All %d flagged answer(s) are shown.%n", shown));
        } else {
            sb.append(String.format(
                    Locale.ROOT,
                    "The newest %d of %d flagged answer(s) are shown. The rest are the `witness` span refs of"
                            + " `get_finding_evidence`; read them with `get_span`.%n",
                    shown,
                    page.total()));
        }
        String out = sb.toString();
        // Only a single answer can pass the budget on its own; cut it where the budget ends and say so.
        if (out.length() <= CHAR_BUDGET) return out;
        return out.substring(0, CHAR_BUDGET)
                + "\n\n[cut at " + CHAR_BUDGET + " characters: read the rest of this answer with `get_span`.]\n";
    }

    private static String answer(int n, FlaggedAnswerView a) {
        StringBuilder sb = new StringBuilder("\n## ")
                .append(n)
                .append(". trace `")
                .append(a.traceId())
                .append("` span `")
                .append(a.spanId())
                .append("`\n\n");
        sb.append("- flagged at ").append(a.flaggedAt() == null ? "an unknown time" : a.flaggedAt());
        if (a.score() != null) sb.append(String.format(Locale.ROOT, ", score %.3f", a.score()));
        if (a.cleared()) sb.append(", since cleared as a false alarm");
        sb.append('\n');
        String answer = a.answer();
        for (FlaggedSentenceView s : a.flaggedSentences()) {
            sb.append(String.format(Locale.ROOT, "- flagged sentence, score %.3f", s.score()));
            if (answer != null && s.start() >= 0 && s.end() <= answer.length() && s.start() < s.end()) {
                sb.append(": \"").append(oneLine(answer.substring(s.start(), s.end()))).append('"');
            } else {
                sb.append(String.format(Locale.ROOT, ": [%d, %d)", s.start(), s.end()));
            }
            sb.append('\n');
        }
        if (!a.stored() || answer == null) {
            sb.append("- no longer stored: its trace aged out, so only the ids and scores remain\n");
            return sb.toString();
        }
        String question = a.question();
        if (question != null) {
            sb.append("\n### Question\n\n").append(fenced(question));
        }
        sb.append("\n### Answer\n\n").append(fenced(answer));
        List<RetrievedDocumentView> retrieved = a.documents();
        List<RetrievedDocumentView> documents = retrieved == null ? List.of() : retrieved;
        if (a.premiseHadEvidence()) {
            sb.append("\n### Documents (").append(documents.size()).append(")\n");
            for (int i = 0; i < documents.size(); i++) {
                RetrievedDocumentView d = documents.get(i);
                sb.append("\n#### ")
                        .append(d.title() == null ? "Document " + (i + 1) : d.title())
                        .append("\n\n")
                        .append(fenced(d.text()));
            }
        } else {
            sb.append("\n### No documents were retrieved; the answer was checked against its prompt\n\n");
            for (RetrievedDocumentView d : documents) sb.append(fenced(d.text()));
        }
        return sb.toString();
    }

    /** A block the text cannot close early: the fence is one backtick longer than the longest run inside. */
    private static String fenced(String text) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            run = text.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        String fence = "`".repeat(Math.max(3, longest + 1));
        return fence + "text\n" + text + (text.endsWith("\n") ? "" : "\n") + fence + "\n";
    }

    private static String oneLine(String s) {
        return s.strip().replaceAll("\\s+", " ");
    }
}
