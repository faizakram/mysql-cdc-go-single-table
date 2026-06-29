package com.migration.platform.project;

import com.migration.platform.audit.AuditService;
import com.migration.platform.connection.ConnectionRepository;
import com.migration.platform.job.JobRepository;
import com.migration.platform.project.dto.ProjectRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * In-use guards for projects (#179): while a job is active, the project's wiring/config can't be changed
 * (it would leave the deployed connectors pointing at stale wiring) and the project can't be deleted.
 */
@ExtendWith(MockitoExtension.class)
class ProjectServiceGuardTest {

    @Mock ProjectRepository repo;
    @Mock AuditService audit;
    @Mock ConnectionRepository connections;
    @Mock JobRepository jobs;
    @InjectMocks ProjectService service;

    @Test
    void blocksWiringChangeWhileAJobIsActive() {
        UUID id = UUID.randomUUID();
        UUID srcA = UUID.randomUUID(), tgtA = UUID.randomUUID(), srcB = UUID.randomUUID();
        MigrationProject existing = new MigrationProject();
        existing.setName("p");
        existing.setSourceConnectionId(srcA);
        existing.setTargetConnectionId(tgtA);
        when(repo.findById(id)).thenReturn(Optional.of(existing));
        when(jobs.existsByProjectIdAndStatusIn(any(), anyCollection())).thenReturn(true);

        // req points the source at a different connection — a wiring change.
        ProjectRequest req = new ProjectRequest("p", null, srcB, tgtA, Map.of());
        assertThatThrownBy(() -> service.update(id, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Stop the running job");
        verify(repo, never()).save(any());
    }

    @Test
    void blocksDeleteWhileAJobIsActive() {
        UUID id = UUID.randomUUID();
        when(repo.existsById(id)).thenReturn(true);
        when(jobs.existsByProjectIdAndStatusIn(any(), anyCollection())).thenReturn(true);

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Stop the running job");
        verify(repo, never()).deleteById(any());
    }

    @Test
    void allowsDeleteWhenNoJobIsActive() {
        UUID id = UUID.randomUUID();
        when(repo.existsById(id)).thenReturn(true);
        when(jobs.existsByProjectIdAndStatusIn(any(), anyCollection())).thenReturn(false);

        service.delete(id);

        verify(repo).deleteById(id);
    }
}
