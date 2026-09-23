// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import ai.tessary.classifier.detector.GroundingEvidenceReads.SpanRef;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence.FlaggedAnswerPage;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence.FlaggedAnswerView;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The finding page's groundedness block: the rate off the finding's payload, and the flagged answers it cites,
 * each with its question, the answer as it was scored, the sentences the model flagged in it and the documents
 * it was compared against.
 *
 * <p>The answers come from the finding's span-grain witness rows, not a fresh query, so the page lists exactly
 * what the finding cites, newest flag first, a page at a time: the block carries the first {@link #PAGE_SIZE},
 * and {@link #page} the rest. The flagged sentences are the detection's own {@code flagged_sentences}. The
 * question, answer and documents are rebuilt at request time through {@link GroundednessInputs}, the same read
 * the detector scored from, so the sentence offsets index into the same answer string the page shows.
 */
@Service
public class GroundednessDetailService {

    /** Answers the block carries, and a page's default size. */
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

    /** The block for a {@code groundedness_rate} finding, or null for any other finding. */
    public @Nullable GroundednessDetail detail(FindingRow finding) {
        if (!FindingRow.Cause.GROUNDEDNESS_RATE.equals(finding.causeKind())) return null;
        FlaggedAnswerPage first = page(finding, null, PAGE_SIZE, null);
        return GroundednessEvidence.detail(finding, first.rows(), first.nextCursor());
    }

    /**
     * One page of the flagged answers {@code finding} cites, newest flag first. With {@code cause} set, only the
     * answers in the traces that RCA cause names.
     *
     * @param cursor the {@code nextCursor} of the page before, or null for the first
     */
    public FlaggedAnswerPage page(FindingRow finding, @Nullable CauseRef cause, int limit, @Nullable String cursor) {
        return page(finding.projectId(), finding.subjectId(), finding.id(), cause, limit, cursor);
    }

    /**
     * {@link #page(FindingRow, CauseRef, int, String)} by the finding's ids, for a reader that holds the claim
     * rather than the finding row: RCA, which never reads the triage columns a {@link FindingRow} carries.
     *
     * @param classifierId the finding's subject, the classifier that filed it
     */
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

    /**
     * One answer. It is stored while its span's output is: an answer the inputs no longer score (its call site's
     * shape moved) is still shown, with the question and documents it can no longer be matched to left out.
     */
    private FlaggedAnswerView view(
            CitedAnswer a, @Nullable SubstrateObservation span, GroundednessInputs.@Nullable Inputs in) {
        JsonNode evidence = parse(a.evidenceJson());
        List<FlaggedSentenceView> sentences = new ArrayList<>();
        Double score = null;
        for (JsonNode s : evidence.path("flagged_sentences")) {
            FlaggedSentenceView sentence = new FlaggedSentenceView(
                    s.path("start").asInt(0), s.path("end").asInt(0), s.path("unsupported").asDouble(0));
            sentences.add(sentence);
            if (score == null || sentence.score() > score) score = sentence.score();
        }
        if (score == null && evidence.path("unsupported").isNumber()) score = evidence.path("unsupported").asDouble();
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

    /** The cursor is the next row's offset; anything unreadable starts over. */
    private static int decode(@Nullable String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            return Math.max(0, Integer.parseInt(cursor));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
