package com.migration.platform.connection;

import com.migration.platform.audit.AuditService;
import com.migration.platform.common.CryptoService;
import com.migration.platform.common.NotFoundException;
import com.migration.platform.common.PageResponse;
import com.migration.platform.connection.dto.ConnectionRequest;
import com.migration.platform.connection.dto.ConnectionResponse;
import com.migration.platform.connection.dto.TestResult;
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
public class ConnectionService {

    private final ConnectionRepository repo;
    private final CryptoService crypto;
    private final ConnectionTestService tester;
    private final AuditService audit;

    public ConnectionService(ConnectionRepository repo, CryptoService crypto, ConnectionTestService tester,
                             AuditService audit) {
        this.repo = repo;
        this.crypto = crypto;
        this.tester = tester;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<ConnectionResponse> list() {
        return repo.findAll().stream().map(ConnectionResponse::from).toList();
    }

    /** Paged + filterable connection list (#127). {@code q} matches name/host/database/user; {@code dbType} exact. */
    @Transactional(readOnly = true)
    public PageResponse<ConnectionResponse> listPage(int page, int size, String q, DbType dbType) {
        Specification<DbConnection> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + q.toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("host")), like),
                        cb.like(cb.lower(root.get("databaseName")), like),
                        cb.like(cb.lower(root.get("username")), like)));
            }
            if (dbType != null) ps.add(cb.equal(root.get("dbType"), dbType));
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200),
                Sort.by(Sort.Direction.ASC, "name"));
        return PageResponse.of(repo.findAll(spec, pageable), ConnectionResponse::from);
    }

    @Transactional(readOnly = true)
    public ConnectionResponse get(UUID id) {
        return ConnectionResponse.from(find(id));
    }

    @Transactional
    public ConnectionResponse create(ConnectionRequest req) {
        if (repo.existsByName(req.name())) {
            throw new IllegalArgumentException("A connection named '" + req.name() + "' already exists");
        }
        DbConnection c = new DbConnection();
        apply(c, req);
        c = repo.save(c);
        audit.record("CONNECTION_CREATE", c.getId().toString(), Map.of("name", req.name(), "type", req.dbType().name()));
        return ConnectionResponse.from(c);
    }

    @Transactional
    public ConnectionResponse update(UUID id, ConnectionRequest req) {
        DbConnection c = find(id);
        apply(c, req);
        c = repo.save(c);
        audit.record("CONNECTION_UPDATE", id.toString(), Map.of("name", req.name()));
        return ConnectionResponse.from(c);
    }

    @Transactional
    public void delete(UUID id) {
        if (!repo.existsById(id)) throw new NotFoundException("Connection " + id + " not found");
        repo.deleteById(id);
        audit.record("CONNECTION_DELETE", id.toString(), Map.of());
    }

    /** Test by stored connection id (decrypts the secret transiently). */
    @Transactional(readOnly = true)
    public TestResult test(UUID id) {
        DbConnection c = find(id);
        return tester.test(c.getDbType(), c.getHost(), c.getPort(), c.getDatabaseName(),
                c.getUsername(), crypto.decrypt(c.getPasswordEnc()), c.getOptions());
    }

    /** Test ad-hoc params before saving (UI "Test connection" on the form). */
    public TestResult testAdhoc(ConnectionRequest req) {
        return tester.test(req.dbType(), req.host(), req.port(), req.databaseName(),
                req.username(), req.password(), req.options());
    }

    private void apply(DbConnection c, ConnectionRequest req) {
        c.setName(req.name());
        c.setDbType(req.dbType());
        c.setHost(req.host());
        c.setPort(req.port());
        c.setDatabaseName(req.databaseName());
        c.setUsername(req.username());
        c.setPasswordEnc(crypto.encrypt(req.password()));
        c.setOptions(req.options() != null ? req.options() : new HashMap<>());
    }

    private DbConnection find(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Connection " + id + " not found"));
    }
}
