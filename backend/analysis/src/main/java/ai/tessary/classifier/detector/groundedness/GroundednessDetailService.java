// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import ai.tessary.classifier.detector.GroundingEvidenceReads.SpanRef;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence.FlaggedAnswerPage;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence.FlaggedAnswerView;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence.FlaggedSentenceText;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence.FlaggedSentenceView;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence.GroundednessDetail;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence.RetrievedDocumentView;
import ai.tessary.classifier.detector.groundedness.GroundednessRateRepository.AnswerPage;
import ai.tessary.classifier.detector.groundedness.GroundednessRateRepository.CauseRef;
import ai.tessary.classifier.detector.groundedness.GroundednessRateRepository.CitedAnswer;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.substrate.SubstrateObservation;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The finding page's groundedness block. Answers come from the finding's witness rows, so the page lists exactly what
 * it cites. Question, answer and documents are rebuilt through {@link GroundednessInputs}, the detector's own read, so
 * sentence offsets index into the same string.
 */
@Service
public class GroundednessDetailService {

    public static final int PAGE_SIZE = 50;

    private final GroundednessRateRepository rates;
    private final SubstrateReadRepository substrate;
    private final GroundednessInputs inputs;
    private final ObjectMapper mapper;

    public GroundednessDetailService(
            GroundednessRateRepository rates, SubstrateReadRepository substrate, ObjectMapper mapper) {
        this.rates = rates;
        this.substrate = substrate;
        this.inputs = new GroundednessInputs(substrate, substrate);
        this.mapper = mapper;
    }

    /** Null for any other finding. */
    public @Nullable GroundednessDetail detail(FindingRow finding) {
        if (!FindingRow.Cause.GROUNDEDNESS_RATE.equals(finding.causeKind())) return null;
        FlaggedAnswerPage first = page(finding, null, PAGE_SIZE, null);
        return GroundednessEvidence.detail(finding, first.rows(), first.nextCursor());
    }

    /** With {@code cause} set, only answers in the traces that RCA cause names. */
    public FlaggedAnswerPage page(FindingRow finding, @Nullable CauseRef cause, int limit, @Nullable String cursor) {
        return page(finding.projectId(), finding.subjectId(), finding.id(), cause, limit, cursor);
    }

    /** For RCA, which holds the claim, not the triage columns. */
    public FlaggedAnswerPage page(
            String projectId,
            String classifierId,
            String findingId,
            @Nullable CauseRef cause,
            int limit,
            @Nullable String cursor) {
        int offset = decode(cursor);
        AnswerPage cited = rates.answerPage(projectId, classifierId, findingId, cause, limit, offset);
        Map<SpanRef, GroundednessInputs.Inputs> scored = new HashMap<>();
        Map<SpanRef, SubstrateObservation> spans = new HashMap<>();
        if (!cited.rows().isEmpty()) {
            List<SpanRef> refs = cited.rows().stream()
                    .map(a -> new SpanRef(a.traceId(), a.spanId()))
                    .toList();
            List<SubstrateObservation> read = substrate.observationsByIds(projectId, refs);
            List<GroundednessInputs.@Nullable Inputs> in = read.isEmpty() ? List.of() : inputs.read(read);
            for (int i = 0; i < read.size(); i++) {
                SubstrateObservation o = read.get(i);
                SpanRef ref = new SpanRef(o.traceId(), o.observationId());
                spans.put(ref, o);
                GroundednessInputs.Inputs one = in.get(i);
                if (one != null) scored.put(ref, one);
            }
        }
        List<FlaggedAnswerView> rows = new ArrayList<>(cited.rows().size());
        for (CitedAnswer a : cited.rows()) {
            SpanRef ref = new SpanRef(a.traceId(), a.spanId());
            rows.add(view(a, spans.get(ref), scored.get(ref)));
        }
        int next = offset + cited.rows().size();
        return new FlaggedAnswerPage(rows, cited.total(), next < cited.total() ? Integer.toString(next) : null);
    }

    /** Every ref is a key; one with no detection row maps to no sentences. */
    public Map<SpanRef, List<FlaggedSentenceText>> flaggedSentences(FindingRow finding, Collection<SpanRef> refs) {
        if (refs.isEmpty()) return Map.of();
        Map<SpanRef, String> evidence = rates.detectionEvidence(finding.projectId(), finding.subjectId(), refs);
        Map<SpanRef, String> answers = new HashMap<>();
        if (!evidence.isEmpty()) {
            for (SubstrateObservation o : substrate.observationsByIds(finding.projectId(), evidence.keySet())) {
                String answer = o.outputText();
                if (answer != null && !answer.isBlank()) {
                    answers.put(new SpanRef(o.traceId(), o.observationId()), answer);
                }
            }
        }
        Map<SpanRef, List<FlaggedSentenceText>> out = new LinkedHashMap<>();
        for (SpanRef ref : refs) {
            String answer = answers.get(ref);
            out.put(
                    ref,
                    sentences(parse(evidence.get(ref))).stream()
                            .map(s -> new FlaggedSentenceText(cut(answer, s), s.score()))
                            .toList());
        }
        return out;
    }

    private static @Nullable String cut(@Nullable String answer, FlaggedSentenceView s) {
        if (answer == null || s.start() < 0 || s.end() > answer.length() || s.start() >= s.end()) return null;
        return answer.substring(s.start(), s.end());
    }

    private static List<FlaggedSentenceView> sentences(JsonNode evidence) {
        List<FlaggedSentenceView> sentences = new ArrayList<>();
        for (JsonNode s : evidence.path("flagged_sentences")) {
            sentences.add(new FlaggedSentenceView(
                    s.path("start").asInt(0),
                    s.path("end").asInt(0),
                    s.path("unsupported").asDouble(0)));
        }
        return sentences;
    }

    /**
     * An answer the inputs no longer score (its call site's shape moved) is shown without its question and documents.
     */
    private FlaggedAnswerView view(
            CitedAnswer a, @Nullable SubstrateObservation span, GroundednessInputs.@Nullable Inputs in) {
        JsonNode evidence = parse(a.evidenceJson());
        List<FlaggedSentenceView> sentences = sentences(evidence);
        Double score = null;
        for (FlaggedSentenceView sentence : sentences) {
            if (score == null || sentence.score() > score) score = sentence.score();
        }
        if (score == null && evidence.path("unsupported").isNumber())
            score = evidence.path("unsupported").asDouble();
        String answer = span == null ? null : span.outputText();
        boolean stored = answer != null && !answer.isBlank();
        List<RetrievedDocumentView> documents = null;
        if (stored) {
            documents = new ArrayList<>();
            if (in != null) {
                for (String passage : in.passages()) documents.add(new RetrievedDocumentView(null, passage));
            }
        }
        return new FlaggedAnswerView(
                a.traceId(),
                a.spanId(),
                a.sessionId() != null ? a.sessionId() : span == null ? null : span.sessionId(),
                a.flaggedAt(),
                score,
                stored && in != null ? in.question() : null,
                stored ? answer : null,
                sentences,
                documents,
                stored && in != null
                        ? in.premiseHadEvidence()
                        : evidence.path("premise_had_evidence").asBoolean(false),
                stored,
                a.cleared());
    }

    private JsonNode parse(@Nullable String json) {
        if (json == null || json.isBlank()) return mapper.createObjectNode();
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            return mapper.createObjectNode();
        }
    }

    /** Anything unreadable starts over. */
    private static int decode(@Nullable String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            return Math.max(0, Integer.parseInt(cursor));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
