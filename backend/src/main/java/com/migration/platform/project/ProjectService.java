package com.migration.platform.project;

import com.migration.platform.audit.AuditService;
import com.migration.platform.common.NotFoundException;
import com.migration.platform.common.PageResponse;
import com.migration.platform.connection.ConnectionRepository;
import com.migration.platform.connection.DbConnection;
import com.migration.platform.connection.EngineCatalog;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository repo;
    private final AuditService audit;
    private final ConnectionRepository connections;

    public ProjectService(ProjectRepository repo, AuditService audit, ConnectionRepository connections) {
        this.repo = repo;
        this.audit = audit;
        this.connections = connections;
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
        apply(p, req);
        validatePair(p);
        p = repo.save(p);
        audit.record("PROJECT_UPDATE", id.toString(), Map.of("name", req.name()));
        return ProjectResponse.from(p);
    }

    @Transactional
    public void delete(UUID id) {
        if (!repo.existsById(id)) throw new NotFoundException("Project " + id + " not found");
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
