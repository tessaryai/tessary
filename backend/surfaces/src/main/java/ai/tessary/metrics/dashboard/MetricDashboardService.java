// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics.dashboard;

import ai.tessary.metrics.MetricsQueryValidator;
import ai.tessary.metrics.dashboard.MetricDashboardDtos.MetricDashboard;
import ai.tessary.metrics.dashboard.MetricDashboardDtos.MetricDashboardCreateRequest;
import ai.tessary.metrics.dashboard.MetricDashboardDtos.MetricDashboardList;
import ai.tessary.metrics.dashboard.MetricDashboardDtos.MetricDashboardSummary;
import ai.tessary.metrics.dashboard.MetricDashboardDtos.MetricDashboardUpdateRequest;
import ai.tessary.open.coverage.ExcludeFromJacocoGeneratedReport;
import ai.tessary.open.errors.MetricsError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.tenant.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dashboard reads and writes. The built-in dashboard is answered from {@link DefaultDashboard} and never touches the
 * table; every stored definition passes {@link MetricDashboardValidator} before it is written.
 */
@Service
public class MetricDashboardService {

    private final MetricDashboardRepository repo;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public MetricDashboardService(MetricDashboardRepository repo, ObjectMapper mapper) {
        this(repo, mapper, Clock.systemUTC());
    }

    MetricDashboardService(MetricDashboardRepository repo, ObjectMapper mapper, Clock clock) {
        this.repo = repo;
        this.mapper = mapper;
        this.clock = clock;
    }

    public MetricDashboardList list(String projectId, boolean canManage) {
        List<MetricDashboardSummary> out = new ArrayList<>();
        out.add(DefaultDashboard.summary());
        for (MetricDashboardRow row : repo.list(projectId)) {
            out.add(new MetricDashboardSummary(
                    row.id(),
                    row.name(),
                    row.description(),
                    false,
                    parse(row.definitionJson()).widgets().size(),
                    row.updatedAt()));
        }
        return new MetricDashboardList(List.copyOf(out), canManage);
    }

    public MetricDashboard get(String projectId, String id, boolean canManage) {
        if (DefaultDashboard.ID.equals(id)) return DefaultDashboard.view(canManage);
        return view(stored(projectId, id), canManage);
    }

    /** The project row is locked before the count, so a concurrent create waits and then counts this one's row. */
    @Transactional
    public MetricDashboard create(
            String projectId, @Nullable String userId, MetricDashboardCreateRequest req, boolean canManage) {
        refuseBuiltInName(req.name());
        MetricDashboardDefinition def = sourceOf(projectId, req);
        MetricDashboardValidator.validate(def);
        repo.lockProject(projectId);
        if (repo.count(projectId) >= MetricsQueryValidator.MAX_DASHBOARDS) {
            throw new TessaryException(MetricsError.TOO_MANY_DASHBOARDS, MetricsQueryValidator.MAX_DASHBOARDS);
        }
        String now = clock.instant().toString();
        MetricDashboardRow row = new MetricDashboardRow(
                Ids.ulid(), projectId, req.name(), descriptionOf(req.description()), write(def), userId, now, now);
        try {
            repo.insert(row);
        } catch (DuplicateKeyException e) {
            throw new TessaryException(MetricsError.DUPLICATE_NAME, e, req.name());
        }
        return view(row, def, canManage);
    }

    /** A null definition renames only: the stored definition is left as it is, whatever another tab saved. */
    public MetricDashboard update(String projectId, String id, MetricDashboardUpdateRequest req, boolean canManage) {
        if (DefaultDashboard.ID.equals(id)) throw new TessaryException(MetricsError.BUILT_IN_READ_ONLY);
        MetricDashboardRow existing = stored(projectId, id);
        refuseBuiltInName(req.name());
        MetricDashboardDefinition def = req.definition();
        if (def != null) MetricDashboardValidator.validate(def);
        String description = descriptionOf(req.description());
        String now = clock.instant().toString();
        try {
            if (def == null) {
                MetricDashboardRow renamed = repo.rename(projectId, id, req.name(), description, now)
                        .orElseThrow(() -> new TessaryException(MetricsError.DASHBOARD_NOT_FOUND, id));
                return view(renamed, canManage);
            }
            MetricDashboardRow row = new MetricDashboardRow(
                    existing.id(),
                    projectId,
                    req.name(),
                    description,
                    write(def),
                    existing.createdBy(),
                    existing.createdAt(),
                    now);
            repo.update(row);
            return view(row, def, canManage);
        } catch (DuplicateKeyException e) {
            throw new TessaryException(MetricsError.DUPLICATE_NAME, e, req.name());
        }
    }

    public void delete(String projectId, String id) {
        if (DefaultDashboard.ID.equals(id)) throw new TessaryException(MetricsError.BUILT_IN_READ_ONLY);
        if (!repo.delete(projectId, id)) throw new TessaryException(MetricsError.DASHBOARD_NOT_FOUND, id);
    }

    /** The built-in has no row, so the unique index cannot see that a stored "Default" would sit beside it. */
    private static void refuseBuiltInName(String name) {
        if (DefaultDashboard.NAME.equals(name.strip())) throw new TessaryException(MetricsError.DUPLICATE_NAME, name);
    }

    private MetricDashboardDefinition sourceOf(String projectId, MetricDashboardCreateRequest req) {
        MetricDashboardDefinition given = req.definition();
        if (given != null) return given;
        String cloneOf = req.cloneOf();
        if (cloneOf == null) return MetricDashboardDefinition.empty();
        if (DefaultDashboard.ID.equals(cloneOf)) return DefaultDashboard.DEFINITION;
        return parse(stored(projectId, cloneOf).definitionJson());
    }

    private MetricDashboardRow stored(String projectId, String id) {
        return repo.find(projectId, id).orElseThrow(() -> new TessaryException(MetricsError.DASHBOARD_NOT_FOUND, id));
    }

    private MetricDashboard view(MetricDashboardRow row, boolean canManage) {
        return view(row, parse(row.definitionJson()), canManage);
    }

    private static MetricDashboard view(MetricDashboardRow row, MetricDashboardDefinition def, boolean canManage) {
        return new MetricDashboard(
                row.id(),
                row.name(),
                row.description(),
                false,
                def,
                row.createdBy(),
                row.createdAt(),
                row.updatedAt(),
                canManage);
    }

    private static String descriptionOf(@Nullable String description) {
        return description == null ? "" : description;
    }

    private String write(MetricDashboardDefinition def) {
        return mapper.valueToTree(def).toString();
    }

    @ExcludeFromJacocoGeneratedReport("the strict mapper wrote every stored definition")
    private MetricDashboardDefinition parse(String json) {
        try {
            return mapper.readValue(json, MetricDashboardDefinition.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored dashboard definition does not parse", e);
        }
    }
}
