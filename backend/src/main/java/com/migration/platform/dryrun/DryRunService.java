package com.migration.platform.dryrun;

import com.migration.platform.connection.ConnectionRepository;
import com.migration.platform.connection.ConnectionService;
import com.migration.platform.connection.DbConnection;
import com.migration.platform.connection.TargetSchemaService;
import com.migration.platform.connection.dto.TestResult;
import com.migration.platform.connector.MigrationConfig;
import com.migration.platform.planning.MigrationPlanService;
import com.migration.platform.planning.dto.PlanDtos.MigrationPlan;
import com.migration.platform.planning.dto.PlanDtos.PlanTable;
import com.migration.platform.project.MigrationProject;
import com.migration.platform.project.ProjectRepository;
import com.migration.platform.common.NotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Migration simulation / dry-run (#105): validates a project end-to-end WITHOUT deploying any
 * connector or writing to the target — connectivity, a generated plan, type-mappability and risks.
 * Surfaces blockers so problems are caught before a real run.
 */
@Service
public class DryRunService {

    private final ProjectRepository projects;
    private final ConnectionService connections;
    private final ConnectionRepository connectionRepo;
    private final TargetSchemaService targetSchema;
    private final MigrationPlanService planner;

    public DryRunService(ProjectRepository projects, ConnectionService connections,
                         ConnectionRepository connectionRepo, TargetSchemaService targetSchema,
                         MigrationPlanService planner) {
        this.projects = projects;
        this.connections = connections;
        this.connectionRepo = connectionRepo;
        this.targetSchema = targetSchema;
        this.planner = planner;
    }

    public record DryRunReport(
            boolean ok,
            TestResult source,
            TestResult target,
            MigrationPlan plan,
            List<String> blockers,
            List<String> warnings
    ) {}

    public DryRunReport run(UUID projectId) {
        MigrationProject p = projects.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project " + projectId + " not found"));

        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        TestResult src = safeTest(p.getSourceConnectionId(), "source", blockers);
        TestResult tgt = safeTest(p.getTargetConnectionId(), "target", blockers);

        // Target schema must exist for the JDBC sink to create tables; the job auto-creates it on
        // start, so this is an informational heads-up rather than a blocker.
        if (tgt != null && tgt.success() && p.getTargetConnectionId() != null) {
            DbConnection tgtConn = connectionRepo.findById(p.getTargetConnectionId()).orElse(null);
            String schema = MigrationConfig.from(p.getConfig(), p.getName()).targetSchema();
            if (tgtConn != null && !targetSchema.exists(tgtConn, schema)) {
                warnings.add("Target schema '" + schema + "' does not exist — it will be created automatically when the job starts.");
            }
        }

        MigrationPlan plan = null;
        if (src != null && src.success() && tgt != null && tgt.success()) {
            try {
                plan = planner.plan(projectId);
                if (plan.hasCycles()) warnings.add("Circular FK dependencies — tables will migrate with deferred constraints");
                for (PlanTable t : plan.tables()) {
                    if (!t.hasPk()) warnings.add(t.fqName() + ": no primary key (CDC/upsert needs one)");
                    if (t.risks().stream().anyMatch(r -> r.startsWith("UNSUPPORTED_TYPES")))
                        warnings.add(t.fqName() + ": contains types with no clean target mapping — review");
                }
            } catch (Exception e) {
                blockers.add("Plan generation failed: " + e.getMessage());
            }
        }
        boolean ok = blockers.isEmpty();
        return new DryRunReport(ok, src, tgt, plan, blockers, warnings);
    }

    private TestResult safeTest(UUID connId, String role, List<String> blockers) {
        if (connId == null) {
            blockers.add("No " + role + " connection assigned");
            return null;
        }
        try {
            TestResult r = connections.test(connId);
            if (!r.success()) blockers.add(role + " connection failed: " + r.message());
            return r;
        } catch (Exception e) {
            blockers.add(role + " connection error: " + e.getMessage());
            return null;
        }
    }
}
