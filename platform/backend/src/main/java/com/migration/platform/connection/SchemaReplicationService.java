package com.migration.platform.connection;

import com.migration.platform.common.CryptoService;
import com.migration.platform.common.NotFoundException;
import com.migration.platform.connection.dto.ConstraintDtos.ConstraintApplyResult;
import com.migration.platform.connector.MigrationConfig;
import com.migration.platform.project.MigrationProject;
import com.migration.platform.project.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Replicates source indexes and foreign keys onto the target after the initial load (issue #33).
 * Defaults/check constraints are out of scope (cross-engine expression translation) and tracked
 * separately. Idempotent: indexes use IF NOT EXISTS; an already-existing FK is treated as applied.
 */
@Service
public class SchemaReplicationService {

    private static final Logger log = LoggerFactory.getLogger(SchemaReplicationService.class);

    private final ProjectRepository projects;
    private final ConnectionRepository connections;
    private final CryptoService crypto;
    private final JdbcSupport jdbc;
    private final SchemaDiscoveryService discovery;

    public SchemaReplicationService(ProjectRepository projects, ConnectionRepository connections,
                                    CryptoService crypto, JdbcSupport jdbc, SchemaDiscoveryService discovery) {
        this.projects = projects;
        this.connections = connections;
        this.crypto = crypto;
        this.jdbc = jdbc;
        this.discovery = discovery;
    }

    /** DDL that would be applied (indexes first, then foreign keys). */
    @Transactional(readOnly = true)
    public List<String> previewDdl(UUID projectId) {
        Generated g = generate(projectId);
        List<String> all = new ArrayList<>(g.indexes);
        all.addAll(g.foreignKeys);
        return all;
    }

    /** Generate + apply on the target; idempotent and per-statement fault tolerant. */
    @Transactional
    public ConstraintApplyResult apply(UUID projectId) {
        MigrationProject project = requireProject(projectId);
        DbConnection tgt = requireConnection(project.getTargetConnectionId(), "target");
        Generated g = generate(projectId);

        List<String> statements = new ArrayList<>(g.indexes);
        statements.addAll(g.foreignKeys);
        List<String> errors = new ArrayList<>();
        int okIdx = 0, okFk = 0;

        try (Connection tc = jdbc.open(tgt, crypto.decrypt(tgt.getPasswordEnc()))) {
            for (int i = 0; i < statements.size(); i++) {
                String sql = statements.get(i);
                boolean isIndex = i < g.indexes.size();
                try (Statement st = tc.createStatement()) {
                    st.execute(sql);
                    if (isIndex) okIdx++; else okFk++;
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
                    if (msg.contains("already exists")) {
                        if (isIndex) okIdx++; else okFk++;   // idempotent
                    } else {
                        errors.add(sql + " -> " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Constraint replication failed for project {}: {}", projectId, e.getMessage());
            errors.add("Connection/apply error: " + e.getMessage());
        }
        return new ConstraintApplyResult(okIdx, okFk, statements, errors);
    }

    private Generated generate(UUID projectId) {
        MigrationProject project = requireProject(projectId);
        DbConnection src = requireConnection(project.getSourceConnectionId(), "source");
        MigrationConfig mc = MigrationConfig.from(project.getConfig(), project.getName());

        List<String> indexes = new ArrayList<>();
        List<String> fks = new ArrayList<>();
        for (String fq : selectedTables(project)) {
            String[] parts = fq.split("\\.", 2);
            String schema = parts.length == 2 ? parts[0] : "dbo";
            String table = parts.length == 2 ? parts[1] : parts[0];
            discovery.listIndexes(src.getId(), schema, table)
                    .forEach(ix -> indexes.add(ConstraintDdl.indexDdl(mc.targetSchema(), table, ix)));
            discovery.listForeignKeys(src.getId(), schema, table)
                    .forEach(fk -> fks.add(ConstraintDdl.foreignKeyDdl(mc.targetSchema(), table, fk)));
        }
        return new Generated(indexes, fks);
    }

    @SuppressWarnings("unchecked")
    private List<String> selectedTables(MigrationProject p) {
        Object v = p.getConfig() == null ? null : p.getConfig().get("selectedTables");
        if (v instanceof List<?> list) return list.stream().map(Object::toString).toList();
        if (v instanceof String s && !s.isBlank()) return List.of(s.split("\\s*,\\s*"));
        return List.of();
    }

    private MigrationProject requireProject(UUID id) {
        return projects.findById(id).orElseThrow(() -> new NotFoundException("Project " + id + " not found"));
    }

    private DbConnection requireConnection(UUID id, String role) {
        if (id == null) throw new IllegalArgumentException("Project has no " + role + " connection configured");
        return connections.findById(id)
                .orElseThrow(() -> new NotFoundException(role + " connection " + id + " not found"));
    }

    private record Generated(List<String> indexes, List<String> foreignKeys) {}
}
