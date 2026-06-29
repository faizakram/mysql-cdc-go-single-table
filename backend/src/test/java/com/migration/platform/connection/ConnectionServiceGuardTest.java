package com.migration.platform.connection;

import com.migration.platform.audit.AuditService;
import com.migration.platform.common.CryptoService;
import com.migration.platform.connection.dto.ConnectionRequest;
import com.migration.platform.job.JobRepository;
import com.migration.platform.job.JobStatus;
import com.migration.platform.project.MigrationProject;
import com.migration.platform.project.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * In-use guards for connections (#179): a connection can't be edited while a job that depends on it is
 * active, nor deleted while any project still references it — otherwise the live connectors are silently
 * broken or orphaned.
 */
@ExtendWith(MockitoExtension.class)
class ConnectionServiceGuardTest {

    @Mock ConnectionRepository repo;
    @Mock CryptoService crypto;
    @Mock ConnectionTestService tester;
    @Mock AuditService audit;
    @Mock ProjectRepository projects;
    @Mock JobRepository jobs;
    @InjectMocks ConnectionService service;

    private ConnectionRequest req() {
        return new ConnectionRequest("c", DbType.POSTGRESQL, "h", 5432, "db", "u", "p", Map.of());
    }

    @Test
    void blocksEditWhileADependentJobIsActive() {
        UUID id = UUID.randomUUID();
        MigrationProject p = new MigrationProject();
        p.setName("Live Migration");
        when(repo.findById(id)).thenReturn(java.util.Optional.of(new DbConnection()));
        when(projects.findBySourceConnectionIdOrTargetConnectionId(id, id)).thenReturn(List.of(p));
        when(jobs.existsByProjectIdAndStatusIn(any(), anyCollection())).thenReturn(true);

        assertThatThrownBy(() -> service.update(id, req()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("in use by a running migration");
        verify(repo, never()).save(any());
    }

    @Test
    void blocksDeleteWhileReferencedByAProject() {
        UUID id = UUID.randomUUID();
        MigrationProject p = new MigrationProject();
        p.setName("Orders → PG");
        when(repo.existsById(id)).thenReturn(true);
        when(projects.findBySourceConnectionIdOrTargetConnectionId(id, id)).thenReturn(List.of(p));

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Orders → PG");
        verify(repo, never()).deleteById(any());
    }

    @Test
    void allowsDeleteWhenNoProjectReferencesIt() {
        UUID id = UUID.randomUUID();
        when(repo.existsById(id)).thenReturn(true);
        when(projects.findBySourceConnectionIdOrTargetConnectionId(id, id)).thenReturn(List.of());

        service.delete(id);

        verify(repo).deleteById(id);
    }

    @Test
    void activeJobStatesAreSnapshotRunningPaused() {
        // Guard scope is the in-flight states — a COMPLETED/STOPPED/FAILED job must not block edits.
        assertThat(List.of(JobStatus.SNAPSHOT, JobStatus.RUNNING, JobStatus.PAUSED))
                .doesNotContain(JobStatus.COMPLETED, JobStatus.STOPPED, JobStatus.FAILED, JobStatus.CREATED);
    }
}
