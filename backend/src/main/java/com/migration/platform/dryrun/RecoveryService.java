package com.migration.platform.dryrun;

import com.migration.platform.common.CryptoService;
import com.migration.platform.common.NotFoundException;
import com.migration.platform.connection.ConnectionRepository;
import com.migration.platform.connection.DbConnection;
import com.migration.platform.connection.JdbcSupport;
import com.migration.platform.connector.MigrationConfig;
import com.migration.platform.connector.TargetNaming;
import com.migration.platform.project.MigrationProject;
import com.migration.platform.project.ProjectRepository;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Checkpoint + rollback (#106) and resumability support (#107) for migrations.
 *
 * <p><b>Checkpoint</b> records the target row counts for the project's selected tables (a marker for
 * before/after comparison). <b>Rollback</b> restores the target to its pre-migration (empty) state
 * by truncating those tables — an explicit, destructive operation invoked only on request.
 * <b>Resumability</b> is inherent: the sink upserts by primary key (idempotent) and connectors
 * resume from committed Kafka offsets, so a restarted job continues without duplication.
 */
@Service
public class RecoveryService {

    private final ProjectRepository projects;
    private final ConnectionRepository connections;
    private final JdbcSupport jdbc;
    private final CryptoService crypto;

    public RecoveryService(ProjectRepository projects, ConnectionRepository connections,
                           JdbcSupport jdbc, CryptoService crypto) {
        this.projects = projects;
        this.connections = connections;
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    public record Checkpoint(Map<String, Long> targetRowCounts) {}
    public record RollbackResult(List<String> truncated, List<String> errors) {}

    public Checkpoint checkpoint(UUID projectId) {
        Ctx c = ctx(projectId);
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection tc = jdbc.open(c.target, crypto.decrypt(c.target.getPasswordEnc()))) {
            for (String t : c.targetTables) {
                try (Statement st = tc.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + t)) {
                    counts.put(t, rs.next() ? rs.getLong(1) : 0L);
                } catch (Exception e) { counts.put(t, -1L); }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Checkpoint failed: " + e.getMessage());
        }
        return new Checkpoint(counts);
    }

    /** Destructive: truncate the project's target tables to restore the pre-migration state. */
    public RollbackResult rollback(UUID projectId) {
        Ctx c = ctx(projectId);
        List<String> done = new ArrayList<>();
        List<String> errs = new ArrayList<>();
        try (Connection tc = jdbc.open(c.target, crypto.decrypt(c.target.getPasswordEnc()))) {
            for (String t : c.targetTables) {
                try (Statement st = tc.createStatement()) {
                    st.executeUpdate("TRUNCATE TABLE " + t);
                    done.add(t);
                } catch (Exception e) { errs.add(t + ": " + e.getMessage()); }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Rollback failed: " + e.getMessage());
        }
        return new RollbackResult(done, errs);
    }

    private record Ctx(DbConnection target, List<String> targetTables) {}

    @SuppressWarnings("unchecked")
    private Ctx ctx(UUID projectId) {
        MigrationProject p = projects.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project " + projectId + " not found"));
        MigrationConfig mc = MigrationConfig.from(p.getConfig(), p.getName());
        DbConnection tgt = connections.findById(p.getTargetConnectionId())
                .orElseThrow(() -> new IllegalArgumentException("No target connection"));
        Object v = p.getConfig() == null ? null : p.getConfig().get("selectedTables");
        List<String> selected = (v instanceof List<?> l) ? l.stream().map(Object::toString).toList() : List.of();
        if (selected.isEmpty()) throw new IllegalArgumentException("No tables selected for this project");
        List<String> targetTables = new ArrayList<>();
        for (String fq : selected) {
            String table = fq.contains(".") ? fq.substring(fq.indexOf('.') + 1) : fq;
            targetTables.add(mc.targetSchema() + "." + TargetNaming.apply(table, mc.namingStrategy()));
        }
        return new Ctx(tgt, targetTables);
    }
}
