package com.migration.platform.intelligence;

import com.migration.platform.common.NotFoundException;
import com.migration.platform.connection.ConnectionRepository;
import com.migration.platform.connection.DbConnection;
import com.migration.platform.connection.DbType;
import com.migration.platform.connection.SchemaDiscoveryService;
import com.migration.platform.connection.TypeMappingMatrix;
import com.migration.platform.connection.dto.ColumnInfo;
import com.migration.platform.intelligence.MigrationIntelligence.CostEstimate;
import com.migration.platform.planning.MigrationPlanService;
import com.migration.platform.planning.dto.PlanDtos.MigrationPlan;
import com.migration.platform.project.MigrationProject;
import com.migration.platform.project.ProjectRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AI-assisted migration intelligence (#108): cost estimation (#110) from the plan, explainable
 * type-mapping recommendations (#109), and error remediation (#111). Recommendations are rule-based
 * today (deterministic + explainable) and never auto-applied — the user confirms or overrides.
 */
@Service
public class IntelligenceService {

    private final ProjectRepository projects;
    private final ConnectionRepository connections;
    private final SchemaDiscoveryService discovery;
    private final MigrationPlanService planner;

    public IntelligenceService(ProjectRepository projects, ConnectionRepository connections,
                               SchemaDiscoveryService discovery, MigrationPlanService planner) {
        this.projects = projects;
        this.connections = connections;
        this.discovery = discovery;
        this.planner = planner;
    }

    public record Recommendation(String table, String column, String sourceType,
                                 String recommended, String rationale, String confidence) {}

    public CostEstimate cost(UUID projectId) {
        MigrationPlan plan = planner.plan(projectId);
        return MigrationIntelligence.cost(plan.totalRows(), plan.totalBytes(), plan.estimatedSeconds());
    }

    public List<Recommendation> recommendations(UUID projectId) {
        MigrationProject p = projects.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project " + projectId + " not found"));
        DbConnection src = connections.findById(p.getSourceConnectionId())
                .orElseThrow(() -> new IllegalArgumentException("No source connection"));
        DbType srcE = src.getDbType();
        DbType tgtE = connections.findById(p.getTargetConnectionId())
                .map(DbConnection::getDbType).orElse(DbType.POSTGRESQL);

        List<Recommendation> out = new ArrayList<>();
        for (String fq : selectedTables(p)) {
            String[] parts = fq.split("\\.", 2);
            String schema = parts.length == 2 ? parts[0] : "dbo";
            String table = parts.length == 2 ? parts[1] : parts[0];
            for (ColumnInfo c : discovery.listColumns(src.getId(), schema, table)) {
                var m = TypeMappingMatrix.map(srcE, tgtE, c.dataType(), c.size());
                String rationale; String confidence;
                if (srcE == tgtE) { rationale = "Homogeneous migration — type preserved as-is."; confidence = "HIGH"; }
                else if (m.note() != null) { rationale = m.note() + " Review or set an override."; confidence = "LOW"; }
                else { rationale = c.dataType() + " maps cleanly to " + m.targetType() + " on " + tgtE + "."; confidence = "HIGH"; }
                out.add(new Recommendation(table, c.name(), c.dataType(), m.targetType(), rationale, confidence));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> selectedTables(MigrationProject p) {
        Object v = p.getConfig() == null ? null : p.getConfig().get("selectedTables");
        return (v instanceof List<?> l) ? l.stream().map(Object::toString).toList() : List.of();
    }
}
