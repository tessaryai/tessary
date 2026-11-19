// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.PromptsError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.Principal;
import ai.tessary.tenant.PrincipalRepository;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prompts, their immutable numbered versions, and deployment labels. A version number is the prompt's
 * {@code last_version + 1}, taken under a row lock on the prompt, and is never reused after a delete; {@code latest} is
 * never stored and is added to a version's labels when it is the highest. Every read is keyed on the project, so another project's prompt id is {@code NOT_FOUND}.
 */
@Service
public class PromptService {

    private static final Logger log = LoggerFactory.getLogger(PromptService.class);

    static final int DEFAULT_VERSION_PAGE = 100;

    public enum LabelMode {
        /** The UI's PUT: the version ends with exactly the listed labels. */
        REPLACE,
        /** The public PATCH: the listed labels move here, the version's other labels stay. */
        ADD
    }

    public enum CreateVia {
        UI("ui"),
        API("api");

        private final String wire;

        CreateVia(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }

        /** The stored {@code created_via}, which the CHECK limits to these two. */
        public static CreateVia fromWire(String value) {
            return "api".equals(value) ? API : UI;
        }
    }

    public record PromptListQuery(
            @Nullable String name,
            @Nullable String folder,
            @Nullable String q,
            @Nullable String label,
            @Nullable String tag,
            @Nullable PromptType type,
            @Nullable Instant fromUpdatedAt,
            @Nullable Instant toUpdatedAt,
            Order order,
            int page,
            int limit,
            boolean direct,
            boolean versionNumbers) {
        public enum Order {
            UPDATED,
            NAME
        }

        public PromptListQuery(
                @Nullable String name,
                @Nullable String folder,
                @Nullable String q,
                @Nullable String label,
                @Nullable String tag,
                @Nullable PromptType type,
                @Nullable Instant fromUpdatedAt,
                @Nullable Instant toUpdatedAt,
                Order order,
                int page,
                int limit) {
            this(name, folder, q, label, tag, type, fromUpdatedAt, toUpdatedAt, order, page, limit, false, false);
        }

        /** Only the prompts directly in {@code folder}, or at the root when it is null, not those in its subfolders. */
        public PromptListQuery directOnly() {
            return new PromptListQuery(
                    name,
                    folder,
                    q,
                    label,
                    tag,
                    type,
                    fromUpdatedAt,
                    toUpdatedAt,
                    order,
                    page,
                    limit,
                    true,
                    versionNumbers);
        }

        /**
         * Each listed prompt's version numbers too, which only the public list returns. Without it a row carries the
         * count alone.
         */
        public PromptListQuery withVersionNumbers() {
            return new PromptListQuery(
                    name, folder, q, label, tag, type, fromUpdatedAt, toUpdatedAt, order, page, limit, direct, true);
        }

        /** MCP: page 1, limit 100, order UPDATED ("when each last changed"). */
        public static PromptListQuery forMcp(@Nullable String folder, @Nullable String label) {
            return new PromptListQuery(null, folder, null, label, null, null, null, null, Order.UPDATED, 1, 100);
        }
    }

    public record CreatePromptCommand(
            String name,
            PromptType type,
            @Nullable Object body,
            @Nullable Map<String, Object> config,
            List<String> labels,
            List<String> tags,
            @Nullable String commitMessage) {}

    public record CreateVersionCommand(
            @Nullable Object body,
            @Nullable Map<String, Object> config,
            List<String> labels,
            @Nullable String commitMessage,
            @Nullable List<String> tags) {}

    /** {@code labels} is sorted, with {@code latest} appended when {@code isLatest}. */
    public record ResolvedVersion(
            PromptRow prompt,
            PromptVersionRow version,
            PromptBody body,
            Map<String, Object> config,
            List<String> labels,
            boolean isLatest) {}

    /** {@code lastConfigByPromptId} is parsed here from each row's stored config, so DTO mappers need no codec. */
    public record PromptPage(
            List<PromptListRow> items,
            Map<String, List<PromptLabelRow>> labelsByPromptId,
            Map<String, Map<String, Object>> lastConfigByPromptId,
            int page,
            int limit,
            long total) {}

    /**
     * {@code versions} is one page, newest first; {@code labels} ordered by label. {@code latestVersion} is the highest
     * number the prompt has, read on its own because a page of older versions does not hold it.
     */
    public record PromptDetail(
            PromptRow prompt,
            List<PromptVersionSummaryRow> versions,
            List<PromptLabelRow> labels,
            int latestVersion,
            boolean hasOlderVersions) {}

    /** A display label per author principal id; one this lookup cannot name reads as {@link #UNKNOWN}. */
    public record Authors(Map<String, String> labels) {
        public static final String UNKNOWN = "Unknown";

        public String of(String principalId) {
            return labels.getOrDefault(principalId, UNKNOWN);
        }
    }

    private final PromptRepository repo;
    private final PrincipalRepository principals;
    private final PromptFetchCache fetchCache;
    private final Clock clock;

    @Autowired
    public PromptService(PromptRepository repo, PrincipalRepository principals, PromptFetchCache fetchCache) {
        this(repo, principals, fetchCache, Clock.systemUTC());
    }

    PromptService(PromptRepository repo, PrincipalRepository principals, PromptFetchCache fetchCache, Clock clock) {
        this.repo = repo;
        this.principals = principals;
        this.fetchCache = fetchCache;
        this.clock = clock;
    }

    /** Stores the trimmed name. */
    @Transactional
    public PromptDetail create(String projectId, String principalId, CreateVia via, CreatePromptCommand cmd) {
        String name = PromptNames.requireName(cmd.name());
        PromptBody body = PromptBody.parse(cmd.type(), cmd.body());
        String configJson = configJson(cmd.config());
        requireCommitMessage(cmd.commitMessage());
        cmd.labels().forEach(PromptNames::requireLabel);
        List<String> tags = PromptNames.normalizeTags(cmd.tags());
        Instant now = now();
        PromptRow row = new PromptRow(Ids.ulid(), projectId, name, cmd.type(), tags, 1, principalId, now, now);
        try {
            repo.insertPrompt(row);
        } catch (DuplicateKeyException e) {
            throw new TessaryException(PromptsError.NAME_EXISTS, name);
        } catch (DataIntegrityViolationException e) {
            throw new TessaryException(PromptsError.INVALID_NAME, name);
        }
        writeVersion(
                row, row.lastVersion(), principalId, via, body, configJson, cmd.labels(), cmd.commitMessage(), now);
        fetchCache.invalidate(projectId);
        return get(projectId, row.id());
    }

    /** Non-null {@code tags} replace the prompt's tags in the same transaction; null keeps them. */
    @Transactional
    public ResolvedVersion createVersion(
            String projectId, String promptId, String principalId, CreateVia via, CreateVersionCommand cmd) {
        PromptRow row = lock(projectId, promptId);
        PromptBody body = PromptBody.parse(row.type(), cmd.body());
        String configJson = configJson(cmd.config());
        requireCommitMessage(cmd.commitMessage());
        cmd.labels().forEach(PromptNames::requireLabel);
        List<String> tags = cmd.tags() == null ? null : PromptNames.normalizeTags(cmd.tags());
        int version = repo.claimVersion(projectId, promptId);
        Instant now = now();
        PromptVersionRow written =
                writeVersion(row, version, principalId, via, body, configJson, cmd.labels(), cmd.commitMessage(), now);
        if (tags != null) writeTags(row, tags, via, now);
        row = row.writtenAt(now, tags);
        fetchCache.invalidate(projectId);
        List<String> labels = new ArrayList<>(new TreeSet<>(cmd.labels()));
        labels.add(PromptNames.LATEST);
        return new ResolvedVersion(row, written, body, PromptJson.readObject(configJson), List.copyOf(labels), true);
    }

    @Transactional
    public ResolvedVersion setLabels(
            String projectId, String promptId, int version, String principalId, Set<String> labels, LabelMode mode) {
        return setLabels(projectId, promptId, version, principalId, labels, Set.of(), mode);
    }

    /**
     * The public PATCH. {@code labels} move to the version first, then each of {@code removeLabels} that is on this
     * version is deleted; one on another version stays. A label in both lists is refused, and {@code latest} in either
     * is {@code LATEST_RESERVED}. Locks the prompt row before any label row, the order every label write takes, so two
     * label writes on one prompt queue instead of deadlocking.
     */
    @Transactional
    public ResolvedVersion setLabels(
            String projectId,
            String promptId,
            int version,
            String principalId,
            Set<String> labels,
            Set<String> removeLabels,
            LabelMode mode) {
        labels.forEach(PromptNames::requireLabel);
        removeLabels.forEach(PromptNames::requireLabel);
        Set<String> both = new TreeSet<>(labels);
        both.retainAll(removeLabels);
        if (!both.isEmpty()) {
            throw new TessaryException(
                    CommonError.INVALID_PARAMETER, "new_labels and remove_labels both name " + String.join(", ", both));
        }
        PromptRow row = lock(projectId, promptId);
        PromptVersionRow target = requireVersion(row, version);
        Set<String> onVersion = new TreeSet<>();
        for (PromptLabelRow l : repo.labelsOf(projectId, List.of(promptId))) {
            if (l.versionId().equals(target.id())) onVersion.add(l.label());
        }
        Instant now = now();
        for (String label : new TreeSet<>(labels)) moveLabel(row, label, target, principalId, now);
        Set<String> result = new TreeSet<>(labels);
        if (mode == LabelMode.REPLACE) {
            for (String stale : onVersion) {
                if (!labels.contains(stale)) repo.deleteLabel(projectId, promptId, stale);
            }
        } else {
            result.addAll(onVersion);
        }
        for (String gone : new TreeSet<>(removeLabels)) {
            if (onVersion.contains(gone)) {
                repo.deleteLabel(projectId, promptId, gone);
                result.remove(gone);
            }
        }
        repo.touchPrompt(projectId, promptId, now);
        fetchCache.invalidate(projectId);
        return resolved(row.writtenAt(now, null), target, result);
    }

    /**
     * Deletes the version {@code label} points at, read under the prompt lock so a concurrent move cannot send the
     * delete to another version. An unknown label is {@code LABEL_NOT_FOUND}; {@code latest} is never stored, so it is
     * {@code LATEST_RESERVED}.
     */
    @Transactional
    public boolean deleteVersionByLabel(String projectId, String promptId, String label) {
        PromptNames.requireLabel(label);
        PromptRow row = lock(projectId, promptId);
        int version = repo.labelVersion(projectId, promptId, label)
                .orElseThrow(() -> new TessaryException(PromptsError.LABEL_NOT_FOUND, row.name(), label));
        return deleteLocked(row, version);
    }

    /**
     * Trimmed like a stored name. A name holding U+0000 can never be stored, and the driver refuses it as a parameter,
     * so it names no prompt.
     */
    public Optional<PromptRow> findByName(String projectId, String name) {
        if (PromptBody.holdsNul(name)) return Optional.empty();
        return repo.findByName(projectId, PromptNames.lookupName(name));
    }

    /**
     * Throws NOT_FOUND, VERSION_NOT_FOUND, LABEL_NOT_FOUND, BOTH_VERSION_AND_LABEL. With neither, reads production. One
     * statement reads the version, its labels and the highest number, so they come from one snapshot. A found version
     * is served from {@link PromptFetchCache} until a prompt write in the project or the TTL drops it.
     */
    public ResolvedVersion resolve(String projectId, String name, @Nullable Integer version, @Nullable String label) {
        if (version != null && label != null) throw new TessaryException(PromptsError.BOTH_VERSION_AND_LABEL);
        if (PromptBody.holdsNul(name)) throw new TessaryException(PromptsError.NOT_FOUND, name);
        String wanted = label == null ? PromptNames.PRODUCTION : label;
        PromptSelector selector = version != null
                ? new PromptSelector.Version(version)
                : PromptNames.LATEST.equals(wanted) ? new PromptSelector.Latest() : new PromptSelector.Label(wanted);
        String lookupName = PromptNames.lookupName(name);
        return fetchCache.fetch(
                projectId, lookupName, selector, () -> load(projectId, name, lookupName, selector, version, wanted));
    }

    private ResolvedVersion load(
            String projectId,
            String name,
            String lookupName,
            PromptSelector selector,
            @Nullable Integer version,
            String wanted) {
        PromptResolveRow found = repo.resolve(projectId, lookupName, selector)
                .orElseThrow(() -> new TessaryException(PromptsError.NOT_FOUND, name));
        PromptVersionRow v = found.version();
        if (v == null) {
            throw version != null
                    ? new TessaryException(
                            PromptsError.VERSION_NOT_FOUND, found.prompt().name(), version)
                    : new TessaryException(PromptsError.LABEL_NOT_FOUND, name, wanted);
        }
        return resolved(found.prompt(), v, new TreeSet<>(found.labels()), found.maxVersion());
    }

    /** The first page of versions. */
    public PromptDetail get(String projectId, String promptId) {
        return get(projectId, promptId, DEFAULT_VERSION_PAGE, null);
    }

    /** {@code limit} versions newest first, below {@code beforeVersion} when it is given. */
    public PromptDetail get(String projectId, String promptId, int limit, @Nullable Integer beforeVersion) {
        PromptRow row = requirePrompt(projectId, promptId);
        List<PromptVersionSummaryRow> page = repo.versionsOf(projectId, promptId, limit + 1, beforeVersion);
        boolean older = page.size() > limit;
        return new PromptDetail(
                row,
                older ? List.copyOf(page.subList(0, limit)) : page,
                repo.labelsOf(projectId, List.of(promptId)),
                repo.maxVersion(projectId, promptId),
                older);
    }

    public ResolvedVersion getVersion(String projectId, String promptId, int version) {
        PromptRow row = requirePrompt(projectId, promptId);
        return resolved(row, requireVersion(row, version));
    }

    public PromptPage list(String projectId, PromptListQuery q) {
        int page = Math.max(1, q.page());
        long offset = (page - 1L) * q.limit();
        if (offset > Integer.MAX_VALUE) throw new TessaryException(CommonError.INVALID_PARAMETER, "page");
        long total = repo.count(projectId, q);
        List<PromptListRow> items = repo.list(projectId, q, (int) offset, q.limit());
        List<String> ids = items.stream().map(i -> i.prompt().id()).toList();
        Map<String, List<PromptLabelRow>> labels = new HashMap<>();
        for (PromptLabelRow l : repo.labelsOf(projectId, ids)) {
            labels.computeIfAbsent(l.promptId(), k -> new ArrayList<>()).add(l);
        }
        Map<String, Map<String, Object>> configs = new HashMap<>();
        for (PromptListRow i : items) configs.put(i.prompt().id(), PromptJson.readObject(i.lastConfigJson()));
        return new PromptPage(items, labels, configs, page, q.limit(), total);
    }

    /** The child folders of {@code q.folder()}, or of the root, under the same filters as the list. */
    public List<PromptFolderRow> folders(String projectId, PromptListQuery q) {
        return repo.childFolders(projectId, q);
    }

    /** Every deployment label in the project, sorted, each once. */
    public List<String> labelNames(String projectId) {
        return repo.labelNames(projectId);
    }

    /** Every tag in the project, sorted, each once. */
    public List<String> tagNames(String projectId) {
        return repo.tagNames(projectId);
    }

    /**
     * One lookup for every id. The ids come from this project's own rows, so the principals named are ones that wrote
     * here.
     */
    public Authors authors(Collection<String> principalIds) {
        Map<String, String> labels = new HashMap<>();
        for (Principal p : principals.findByIds(Set.copyOf(principalIds))) {
            String label = blankToNull(p.displayName());
            if (label == null) label = blankToNull(p.email());
            if (label != null) labels.put(p.id(), label);
        }
        return new Authors(Map.copyOf(labels));
    }

    public PromptDetail updateTags(String projectId, String promptId, List<String> tags) {
        List<String> normalized = PromptNames.normalizeTags(tags);
        PromptRow row = requirePrompt(projectId, promptId);
        writeTags(row, normalized, CreateVia.UI, now());
        fetchCache.invalidate(projectId);
        return get(projectId, promptId);
    }

    private void writeTags(PromptRow row, List<String> tags, CreateVia via, Instant now) {
        try {
            repo.updateTags(row.projectId(), row.id(), tags, now);
        } catch (DataIntegrityViolationException e) {
            throw new TessaryException(CommonError.INVALID_BODY, "the tags hold a value that cannot be stored");
        }
        StructuredLog.info(log, Markers.OPS, "prompt.tags.updated")
                .message("set %d tags on prompt %s via %s", tags.size(), row.id(), via.wire())
                .field("projectId", row.projectId())
                .field("promptId", row.id())
                .field("tagCount", tags.size())
                .field("via", via.wire())
                .log();
    }

    /** False when the id names no prompt of this project. */
    @Transactional
    public boolean delete(String projectId, String promptId) {
        Optional<PromptRow> row = repo.findById(projectId, promptId);
        if (row.isEmpty()) return false;
        int versions = repo.countVersions(projectId, promptId);
        boolean deleted = repo.deletePrompt(projectId, promptId);
        fetchCache.invalidate(projectId);
        StructuredLog.info(log, Markers.OPS, "prompt.deleted")
                .message("deleted prompt %s with %d versions", promptId, versions)
                .field("projectId", projectId)
                .field("promptId", promptId)
                .field("versions", versions)
                .log();
        return deleted;
    }

    /** Deletes every prompt whose name starts with {@code folderPath + "/"}; the count deleted. */
    public int deleteFolder(String projectId, String folderPath) {
        String path = PromptNames.requireFolder(folderPath);
        int count = repo.deleteFolder(projectId, path + "/");
        fetchCache.invalidate(projectId);
        StructuredLog.info(log, Markers.OPS, "prompt.folder.deleted")
                .message("deleted %d prompts in a folder", count)
                .field("projectId", projectId)
                .field("count", count)
                .log();
        return count;
    }

    /**
     * A labeled version is deleted with its labels (the FK cascades). Deleting the last version deletes the prompt in
     * the same transaction, so no prompt is left without a version.
     */
    @Transactional
    public boolean deleteVersion(String projectId, String promptId, int version) {
        return deleteLocked(lock(projectId, promptId), version);
    }

    private boolean deleteLocked(PromptRow row, int version) {
        String projectId = row.projectId();
        String promptId = row.id();
        PromptVersionRow target = requireVersion(row, version);
        long labelCount = repo.labelsOf(projectId, List.of(promptId)).stream()
                .filter(l -> l.versionId().equals(target.id()))
                .count();
        boolean deleted = repo.deleteVersion(projectId, promptId, version);
        boolean promptDeleted = repo.maxVersion(projectId, promptId) == 0;
        if (promptDeleted) {
            repo.deletePrompt(projectId, promptId);
        } else {
            repo.touchPrompt(projectId, promptId, now());
        }
        fetchCache.invalidate(projectId);
        StructuredLog.info(log, Markers.OPS, "prompt.version.deleted")
                .message("deleted version %d of prompt %s with %d labels", version, promptId, labelCount)
                .field("projectId", projectId)
                .field("promptId", promptId)
                .field("version", version)
                .field("labelCount", labelCount)
                .field("promptDeleted", promptDeleted)
                .log();
        return deleted;
    }

    private PromptVersionRow writeVersion(
            PromptRow row,
            int version,
            String principalId,
            CreateVia via,
            PromptBody body,
            String configJson,
            List<String> labels,
            @Nullable String commitMessage,
            Instant now) {
        String bodyJson = body.toJson();
        PromptVersionRow written = new PromptVersionRow(
                Ids.ulid(),
                row.projectId(),
                row.id(),
                version,
                bodyJson,
                configJson,
                commitMessage,
                principalId,
                via,
                now);
        try {
            repo.insertVersion(written);
        } catch (DuplicateKeyException e) {
            throw new TessaryException(PromptsError.CONCURRENT_WRITE, row.name());
        } catch (DataIntegrityViolationException e) {
            // No cause attached: the SQL error context quotes the stored JSON, and a prompt body is never logged.
            throw new TessaryException(
                    CommonError.INVALID_BODY, "the prompt version holds a value that cannot be stored");
        }
        for (String label : new TreeSet<>(labels)) moveLabel(row, label, written, principalId, now);
        repo.touchPrompt(row.projectId(), row.id(), now);
        StructuredLog.info(log, Markers.OPS, "prompt.version.created")
                .message("created version %d of prompt %s via %s", version, row.id(), via.wire())
                .field("projectId", row.projectId())
                .field("promptId", row.id())
                .field("version", version)
                .field("createdVia", via.wire())
                .field("labelCount", labels.size())
                .field("bodyChars", bodyJson.length())
                .log();
        return written;
    }

    private void moveLabel(PromptRow row, String label, PromptVersionRow target, String by, Instant now) {
        Optional<Integer> from = repo.labelVersion(row.projectId(), row.id(), label);
        repo.upsertLabel(row.projectId(), row.id(), label, target.id(), by, now);
        StructuredLog.info(log, Markers.OPS, "prompt.label.moved")
                .message("moved label %s of prompt %s to version %d", label, row.id(), target.version())
                .field("projectId", row.projectId())
                .field("promptId", row.id())
                .field("label", label)
                .field("fromVersion", from.orElse(null))
                .field("toVersion", target.version())
                .log();
    }

    private PromptRow lock(String projectId, String promptId) {
        if (repo.lockPrompt(projectId, promptId).isEmpty()) {
            throw new TessaryException(PromptsError.NOT_FOUND, promptId);
        }
        return requirePrompt(projectId, promptId);
    }

    private PromptRow requirePrompt(String projectId, String promptId) {
        return repo.findById(projectId, promptId)
                .orElseThrow(() -> new TessaryException(PromptsError.NOT_FOUND, promptId));
    }

    private PromptVersionRow requireVersion(PromptRow row, int version) {
        return repo.findVersion(row.projectId(), row.id(), version)
                .orElseThrow(() -> new TessaryException(PromptsError.VERSION_NOT_FOUND, row.name(), version));
    }

    private ResolvedVersion resolved(PromptRow row, PromptVersionRow v) {
        return resolved(row, v, repo.labelsOf(row.projectId(), List.of(row.id())));
    }

    private ResolvedVersion resolved(PromptRow row, PromptVersionRow v, List<PromptLabelRow> all) {
        Set<String> labels = new TreeSet<>();
        for (PromptLabelRow l : all) {
            if (l.versionId().equals(v.id())) labels.add(l.label());
        }
        return resolved(row, v, labels);
    }

    private ResolvedVersion resolved(PromptRow row, PromptVersionRow v, Set<String> sortedLabels) {
        return resolved(row, v, sortedLabels, repo.maxVersion(row.projectId(), row.id()));
    }

    private ResolvedVersion resolved(PromptRow row, PromptVersionRow v, Set<String> sortedLabels, int maxVersion) {
        boolean isLatest = v.version() == maxVersion;
        List<String> labels = new ArrayList<>(sortedLabels);
        if (isLatest) labels.add(PromptNames.LATEST);
        return new ResolvedVersion(
                row,
                v,
                PromptBody.fromJson(row.type(), v.bodyJson()),
                PromptJson.readObject(v.configJson()),
                List.copyOf(labels),
                isLatest);
    }

    /** Null is {@code {}}; U+0000 anywhere is refused. No size cap of its own: the 1 MiB request cap bounds it. */
    private static String configJson(@Nullable Map<String, Object> config) {
        if (holdsNul(config)) {
            throw new TessaryException(CommonError.INVALID_PARAMETER, "config " + PromptBody.NUL);
        }
        return PromptJson.write(config == null ? Map.of() : config);
    }

    /** Walks the parsed JSON (keys too), not its serialized text, where an escaped backslash would look alike. */
    private static boolean holdsNul(@Nullable Object value) {
        return switch (value) {
            case String s -> PromptBody.holdsNul(s);
            case Map<?, ?> m -> m.entrySet().stream().anyMatch(e -> holdsNul(e.getKey()) || holdsNul(e.getValue()));
            case List<?> l -> l.stream().anyMatch(PromptService::holdsNul);
            case null, default -> false;
        };
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static void requireCommitMessage(@Nullable String message) {
        if (message != null && PromptBody.holdsNul(message)) {
            throw new TessaryException(CommonError.INVALID_PARAMETER, "commit message " + PromptBody.NUL);
        }
    }

    /** Microseconds, the precision timestamptz stores, so a returned row equals the one read back later. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
