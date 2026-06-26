package com.migration.platform.connection;

import com.migration.platform.common.CryptoService;
import com.migration.platform.common.NotFoundException;
import com.migration.platform.connection.dto.ConnectionRequest;
import com.migration.platform.connection.dto.ConnectionResponse;
import com.migration.platform.connection.dto.TestResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@Service
public class ConnectionService {

    private final ConnectionRepository repo;
    private final CryptoService crypto;
    private final ConnectionTestService tester;

    public ConnectionService(ConnectionRepository repo, CryptoService crypto, ConnectionTestService tester) {
        this.repo = repo;
        this.crypto = crypto;
        this.tester = tester;
    }

    @Transactional(readOnly = true)
    public List<ConnectionResponse> list() {
        return repo.findAll().stream().map(ConnectionResponse::from).toList();
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
        return ConnectionResponse.from(repo.save(c));
    }

    @Transactional
    public ConnectionResponse update(UUID id, ConnectionRequest req) {
        DbConnection c = find(id);
        apply(c, req);
        return ConnectionResponse.from(repo.save(c));
    }

    @Transactional
    public void delete(UUID id) {
        if (!repo.existsById(id)) throw new NotFoundException("Connection " + id + " not found");
        repo.deleteById(id);
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
