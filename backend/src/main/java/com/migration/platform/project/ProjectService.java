package com.migration.platform.project;

import com.migration.platform.audit.AuditService;
import com.migration.platform.common.NotFoundException;
import com.migration.platform.common.PageResponse;
import com.migration.platform.connection.ConnectionRepository;
import com.migration.platform.connection.DbConnection;
import com.migration.platform.connection.EngineCatalog;
import com.migration.platform.job.JobRepository;
import com.migration.platform.job.JobStatus;
import com.migration.platform.project.dto.ProjectRequest;
import com.migration.platform.project.dto.ProjectResponse;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ProjectService {

    /** A job in one of these states has connectors deployed — editing wiring/config would orphan them (#179). */
    static final EnumSet<JobStatus> ACTIVE_JOB = EnumSet.of(JobStatus.RUNNING, JobStatus.SNAPSHOT, JobStatus.PAUSED);

    private final ProjectRepository repo;
    private final AuditService audit;
    private final ConnectionRepository connections;
    private final JobRepository jobs;

    public ProjectService(ProjectRepository repo, AuditService audit, ConnectionRepository connections,
                          JobRepository jobs) {
        this.repo = repo;
        this.audit = audit;
        this.connections = connections;
        this.jobs = jobs;
    }

    /** Reject unsupported source→target engine pairings server-side (#76/#82). */
    private void validatePair(MigrationProject p) {
        if (p.getSourceConnectionId() == null || p.getTargetConnectionId() == null) return;
        DbConnection src = connections.findById(p.getSourceConnectionId()).orElse(null);
        DbConnection tgt = connections.findById(p.getTargetConnectionId()).orElse(null);
        if (src != null && tgt != null) {
            EngineCatalog.validatePair(src.getDbType(), tgt.getDbType());
        }
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> list() {
        return repo.findAll().stream().map(ProjectResponse::from).toList();
    }

    /** Paged + filterable project list (#127). {@code q} matches name/description, {@code status} is exact. */
    @Transactional(readOnly = true)
    public PageResponse<ProjectResponse> listPage(int page, int size, String q, ProjectStatus status) {
        Specification<MigrationProject> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + q.toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("description"), "")), like)));
            }
            if (status != null) ps.add(cb.equal(root.get("status"), status));
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.of(repo.findAll(spec, pageable), ProjectResponse::from);
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(UUID id) {
        return ProjectResponse.from(find(id));
    }

    @Transactional
    public ProjectResponse create(ProjectRequest req) {
        if (repo.existsByName(req.name())) {
            throw new IllegalArgumentException("A project named '" + req.name() + "' already exists");
        }
        MigrationProject p = new MigrationProject();
        apply(p, req);
        validatePair(p);
        p = repo.save(p);
        audit.record("PROJECT_CREATE", p.getId().toString(), Map.of("name", req.name()));
        return ProjectResponse.from(p);
    }

    @Transactional
    public ProjectResponse update(UUID id, ProjectRequest req) {
        MigrationProject p = find(id);
        // Changing the source/target connections or the migration config while a job is running would
        // leave the deployed connectors pointing at stale wiring (#179). Allow name/description edits;
        // block wiring/config changes until the job is stopped.
        boolean wiringChanged = !Objects.equals(p.getSourceConnectionId(), req.sourceConnectionId())
                || !Objects.equals(p.getTargetConnectionId(), req.targetConnectionId())
                || !Objects.equals(p.getConfig(), req.config());
        if (wiringChanged && jobs.existsByProjectIdAndStatusIn(id, ACTIVE_JOB)) {
            throw new IllegalArgumentException(
                    "Stop the running job before changing this project's connections or configuration.");
        }
        apply(p, req);
        validatePair(p);
        p = repo.save(p);
        audit.record("PROJECT_UPDATE", id.toString(), Map.of("name", req.name()));
        return ProjectResponse.from(p);
    }

    @Transactional
    public void delete(UUID id) {
        if (!repo.existsById(id)) throw new NotFoundException("Project " + id + " not found");
        if (jobs.existsByProjectIdAndStatusIn(id, ACTIVE_JOB)) {
            throw new IllegalArgumentException("Stop the running job before deleting this project.");
        }
        repo.deleteById(id);
        audit.record("PROJECT_DELETE", id.toString(), Map.of());
    }

    private void apply(MigrationProject p, ProjectRequest req) {
        p.setName(req.name());
        p.setDescription(req.description());
        p.setSourceConnectionId(req.sourceConnectionId());
        p.setTargetConnectionId(req.targetConnectionId());
        p.setConfig(req.config() != null ? req.config() : new HashMap<>());
        // A project is READY once both endpoints are chosen.
        if (p.getSourceConnectionId() != null && p.getTargetConnectionId() != null
                && p.getStatus() == ProjectStatus.DRAFT) {
            p.setStatus(ProjectStatus.READY);
        }
    }

    private MigrationProject find(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Project " + id + " not found"));
    }
}
