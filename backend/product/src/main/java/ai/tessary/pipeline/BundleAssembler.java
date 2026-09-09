// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pipeline;

import ai.tessary.model.CallSite;
import ai.tessary.model.Capability;
import ai.tessary.model.Chain;
import ai.tessary.model.FailureMode;
import ai.tessary.model.ImplicitInvariant;
import ai.tessary.model.InvariantCoverage;
import ai.tessary.model.Pack;
import ai.tessary.model.Pipeline;
import ai.tessary.model.ProductProfile;
import ai.tessary.model.Progress;
import ai.tessary.model.Runtime;
import ai.tessary.model.TaxonomyNode;
import ai.tessary.open.errors.PipelineError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Parses a sharded {@code .tessary/} bundle into a {@link Pipeline}, shared by
 * the multipart upload path ({@code ImportController}) and the observer's
 * auto-pull path ({@code BundleImportService}). Either feeds the same
 * classify/apply logic via {@code (relativePath -> body)} pairs.
 *
 * <p>Expected layout (the leading {@code .tessary/} is optional — browsers may
 * strip it from {@code webkitRelativePath}, and a repo tree carries it):
 * <pre>
 *   .tessary/
 *     pipeline/{meta,packs,product_profile,invariants,chains,taxonomy,capabilities}.yaml
 *     pipeline/call_sites/&lt;id&gt;.yaml, failure_modes/&lt;id&gt;.yaml
 *     graders/, quality_dimensions/, knowledge/, datasets/, report.md,
 *     .synth-lock.yaml — ignored here
 * </pre>
 * Shard filenames nest the canonical {@code ::}-delimited id as folders ({@code ::} → {@code /}).
 * Nesting is transparent here because {@link #classify} matches by substring and the canonical {@code id}
 * is read from the file body, not the path.
 *
 * <p><b>The grader and quality-dimension shards are IGNORED, not rejected.</b> Track A removed grading
 * from the platform, and the vendored plugin contract still publishes both shards — a bundle written by
 * any current plugin carries them. Failing the import on a shard we no longer store would break every
 * existing repo; skipping it lets an unchanged producer keep uploading and simply drops what has no
 * home. See {@link Shard#IGNORE}.
 * <p>{@code pipeline/meta.yaml} is the only required shard. Unknown YAML fields drop
 * silently so a newer plugin field doesn't break import while the model catches up.
 */
@Component
public class BundleAssembler {

    // Derived from the shared YAML bean with a lenient copy: unknown shard fields drop silently
    // so a newer plugin field doesn't break import while the model catches up. copy() keeps the
    // bean's YAMLFactory and registered modules; only this mapper carries the relaxed feature.
    private final ObjectMapper yamlMapper;

    public BundleAssembler(@Qualifier("yamlObjectMapper") ObjectMapper yamlObjectMapper) {
        this.yamlMapper = yamlObjectMapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** A parsed pipeline plus the repo identity and knowledge index the bundle declares (all nullable). */
    public record AssembledBundle(
            Pipeline pipeline,
            @Nullable String commitSha,
            @Nullable String repoOwner,
            @Nullable String repoName,
            @Nullable String knowledgeIndexJson) {}

    private static final String KNOWLEDGE_INDEX = "knowledge/index.json";

    /** A single bundle file: {@code relativePath} (pre-normalisation) and its decoded text body. */
    public record NamedBody(String relativePath, String body) {}

    public AssembledBundle assemble(List<NamedBody> files) {
        ShardCollector sc = new ShardCollector();
        boolean sawAnyShard = false;

        for (NamedBody f : files) {
            if (f == null || f.body() == null) continue;
            String name = f.relativePath();
            if (name == null || name.isBlank()) continue;
            String relPath = normalisePath(name);

            // The knowledge index is the one knowledge/ file we keep — it powers
            // deterministic Tier-1 diff→entity mapping. The prose markdown stays
            // in the repo (read on demand), not the DB.
            if (KNOWLEDGE_INDEX.equalsIgnoreCase(relPath)) {
                sawAnyShard = true;
                sc.knowledgeIndexJson = f.body();
                continue;
            }

            Shard shard = classify(relPath);
            if (shard == Shard.IGNORE) continue;

            sawAnyShard = true;
            try {
                applyShard(sc, shard, relPath, f.body());
            } catch (TessaryException e) {
                throw e; // already a registered code — don't re-wrap as MALFORMED_YAML
            } catch (Exception e) {
                throw new TessaryException(PipelineError.MALFORMED_YAML, e, relPath);
            }
        }

        if (!sawAnyShard) {
            throw new TessaryException(PipelineError.NO_SHARDS);
        }
        if (sc.meta == null) {
            throw new TessaryException(PipelineError.MISSING_META);
        }
        return sc.toAssembled();
    }

    private void applyShard(ShardCollector sc, Shard shard, String relPath, String body) throws IOException {
        switch (shard) {
            case META -> {
                if (sc.meta != null) {
                    throw new TessaryException(PipelineError.DUPLICATE_FILE, "pipeline/meta.yaml");
                }
                sc.meta = yamlMapper.readTree(body);
            }
            case PACKS -> {
                if (sc.packsSeen) {
                    throw new TessaryException(PipelineError.DUPLICATE_FILE, "pipeline/packs.yaml");
                }
                sc.packsSeen = true;
                JsonNode list = orMissing(yamlMapper.readTree(body)).path("packs");
                sc.packs = readList(list, Pack.class);
            }
            case PRODUCT_PROFILE -> {
                if (sc.productProfileSeen) {
                    throw new TessaryException(PipelineError.DUPLICATE_FILE, "pipeline/product_profile.yaml");
                }
                sc.productProfileSeen = true;
                JsonNode node = orMissing(yamlMapper.readTree(body)).path("product_profile");
                if (!node.isMissingNode() && !node.isNull()) {
                    sc.productProfile = yamlMapper.treeToValue(node, ProductProfile.class);
                }
            }
            case INVARIANTS -> {
                if (sc.invariantsSeen) {
                    throw new TessaryException(PipelineError.DUPLICATE_FILE, "pipeline/invariants.yaml");
                }
                sc.invariantsSeen = true;
                JsonNode tree = orMissing(yamlMapper.readTree(body));
                sc.implicitInvariants = readList(tree.path("implicit_invariants"), ImplicitInvariant.class);
                sc.invariantCoverage = readList(tree.path("invariant_coverage"), InvariantCoverage.class);
            }
            case CHAINS -> {
                if (sc.chainsSeen) {
                    throw new TessaryException(PipelineError.DUPLICATE_FILE, "pipeline/chains.yaml");
                }
                sc.chainsSeen = true;
                sc.chains = readList(orMissing(yamlMapper.readTree(body)).path("chains"), Chain.class);
            }
            case TAXONOMY -> {
                if (sc.taxonomySeen) {
                    throw new TessaryException(PipelineError.DUPLICATE_FILE, "pipeline/taxonomy.yaml");
                }
                sc.taxonomySeen = true;
                sc.taxonomy = readList(orMissing(yamlMapper.readTree(body)).path("taxonomy"), TaxonomyNode.class);
            }
            case CAPABILITIES -> {
                if (sc.capabilitiesSeen) {
                    throw new TessaryException(PipelineError.DUPLICATE_FILE, "pipeline/capabilities.yaml");
                }
                sc.capabilitiesSeen = true;
                sc.capabilities = readList(orMissing(yamlMapper.readTree(body)).path("capabilities"), Capability.class);
            }
            case CALL_SITE -> {
                CallSite cs = yamlMapper.readValue(body, CallSite.class);
                if (cs.id() == null || cs.id().isBlank()) {
                    throw new TessaryException(PipelineError.MISSING_ID, relPath);
                }
                sc.callSites.add(cs);
            }
            case FAILURE_MODES -> {
                JsonNode list = orMissing(yamlMapper.readTree(body)).path("failure_modes");
                sc.failureModes.addAll(readList(list, FailureMode.class));
            }
            case IGNORE -> {
                /* unreachable; caller already filtered */
            }
        }
    }

    private <T> List<T> readList(JsonNode node, Class<T> elementType) throws IOException {
        if (node == null || node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray()) {
            throw new TessaryException(PipelineError.EXPECTED_YAML_LIST, node.getNodeType());
        }
        return yamlMapper.readerForListOf(elementType).readValue(node);
    }

    private static JsonNode orMissing(JsonNode tree) {
        return tree == null ? MissingNode.getInstance() : tree;
    }

    // ------------------------------------------------------------------ classifier

    private enum Shard {
        META,
        PACKS,
        PRODUCT_PROFILE,
        INVARIANTS,
        CHAINS,
        TAXONOMY,
        CAPABILITIES,
        CALL_SITE,
        FAILURE_MODES,
        IGNORE
    }

    /** Strip a leading {@code .tessary/} so matching is layout-agnostic. */
    private static String normalisePath(String name) {
        String p = name.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        if (p.startsWith(".tessary/")) return p.substring(".tessary/".length());
        return p;
    }

    private static Shard classify(String relPath) {
        if (relPath.isBlank()) return Shard.IGNORE;
        String lower = relPath.toLowerCase(Locale.ROOT);
        String base = baseName(lower);

        if (base.startsWith(".")) return Shard.IGNORE;
        if (lower.endsWith(".md") || lower.endsWith(".html") || lower.endsWith(".jsonl") || lower.endsWith(".json")) {
            return Shard.IGNORE;
        }
        if (lower.startsWith("datasets/") || lower.contains("/datasets/")) return Shard.IGNORE;
        // Grading left the platform with Track A; the plugin still writes these two shards, so they are
        // skipped here rather than rejected — see the class javadoc.
        if (lower.contains("graders/")) return Shard.IGNORE;
        if (lower.contains("pipeline/quality_dimensions/")) return Shard.IGNORE;
        if (lower.startsWith("knowledge/") || lower.contains("/knowledge/")) return Shard.IGNORE;
        if (lower.startsWith("packs/") || lower.contains("/packs/")) return Shard.IGNORE;
        if (!(lower.endsWith(".yaml") || lower.endsWith(".yml"))) return Shard.IGNORE;

        if (matches(lower, "pipeline/meta")) return Shard.META;
        if (matches(lower, "pipeline/packs")) return Shard.PACKS;
        if (matches(lower, "pipeline/product_profile")) return Shard.PRODUCT_PROFILE;
        if (matches(lower, "pipeline/invariants")) return Shard.INVARIANTS;
        if (matches(lower, "pipeline/chains")) return Shard.CHAINS;
        if (matches(lower, "pipeline/taxonomy")) return Shard.TAXONOMY;
        if (matches(lower, "pipeline/capabilities")) return Shard.CAPABILITIES;
        if (lower.contains("pipeline/call_sites/")) return Shard.CALL_SITE;
        if (lower.contains("pipeline/failure_modes/")) return Shard.FAILURE_MODES;

        return Shard.IGNORE;
    }

    private static boolean matches(String relPath, String stem) {
        return relPath.endsWith(stem + ".yaml") || relPath.endsWith(stem + ".yml");
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    // ------------------------------------------------------------------ collector

    private final class ShardCollector {
        @Nullable
        JsonNode meta;

        List<Pack> packs = List.of();
        boolean packsSeen;

        @Nullable
        ProductProfile productProfile;

        boolean productProfileSeen;
        List<ImplicitInvariant> implicitInvariants = List.of();
        List<InvariantCoverage> invariantCoverage = List.of();
        boolean invariantsSeen;
        List<Chain> chains = List.of();
        boolean chainsSeen;
        List<TaxonomyNode> taxonomy = List.of();
        boolean taxonomySeen;
        List<Capability> capabilities = List.of();
        boolean capabilitiesSeen;

        @Nullable
        String knowledgeIndexJson;

        final List<CallSite> callSites = new ArrayList<>();
        final List<FailureMode> failureModes = new ArrayList<>();

        AssembledBundle toAssembled() {
            // assemble() throws MISSING_META before calling this; requireNonNull keeps that invariant visible here
            JsonNode meta = java.util.Objects.requireNonNull(this.meta, "pipeline/meta.yaml not collected");
            Pipeline pipeline = new Pipeline(
                    textOrNull(meta.path("version")),
                    textOrNull(meta.path("product_hint")),
                    packs,
                    productProfile,
                    implicitInvariants,
                    invariantCoverage,
                    readRuntime(meta.path("runtime")),
                    List.copyOf(callSites),
                    chains,
                    List.copyOf(failureModes),
                    taxonomy,
                    readProgress(meta.path("progress")),
                    capabilities);
            // Repo identity / commit are forward-compatible: the plugin stamps them
            // into meta.yaml so an uploaded or auto-pulled bundle knows which commit
            // it was synthesized against. Accept either commit_sha or source_commit_sha.
            String commitSha =
                    firstNonBlank(textOrNull(meta.path("commit_sha")), textOrNull(meta.path("source_commit_sha")));
            JsonNode repo = meta.path("repo");
            String repoOwner = textOrNull(repo.path("owner"));
            String repoName = textOrNull(repo.path("name"));
            return new AssembledBundle(pipeline, commitSha, repoOwner, repoName, knowledgeIndexJson);
        }

        private @Nullable Runtime readRuntime(JsonNode node) {
            if (node == null || node.isMissingNode() || node.isNull()) return null;
            try {
                return yamlMapper.treeToValue(node, Runtime.class);
            } catch (Exception e) {
                throw new TessaryException(PipelineError.MALFORMED_META_FIELD, e, "runtime");
            }
        }

        private @Nullable Progress readProgress(JsonNode node) {
            if (node == null || node.isMissingNode() || node.isNull()) return null;
            try {
                return yamlMapper.treeToValue(node, Progress.class);
            } catch (Exception e) {
                throw new TessaryException(PipelineError.MALFORMED_META_FIELD, e, "progress");
            }
        }

        private @Nullable String textOrNull(JsonNode node) {
            if (node == null || node.isMissingNode() || node.isNull()) return null;
            return node.asText(null);
        }

        private @Nullable String firstNonBlank(@Nullable String a, @Nullable String b) {
            if (a != null && !a.isBlank()) return a;
            return (b != null && !b.isBlank()) ? b : null;
        }
    }

    /** Convenience for the multipart path: build {@link NamedBody}s from a path→body map. */
    public static List<NamedBody> namedBodies(Map<String, String> pathToBody) {
        List<NamedBody> out = new ArrayList<>(pathToBody.size());
        for (Map.Entry<String, String> e : pathToBody.entrySet()) {
            out.add(new NamedBody(e.getKey(), e.getValue()));
        }
        return List.copyOf(out);
    }
}
