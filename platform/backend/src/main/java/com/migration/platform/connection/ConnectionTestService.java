package com.migration.platform.connection;

import com.migration.platform.connection.dto.TestResult;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.Map;

/** Validates source/target connectivity using JDBC (issue #22 / UI #35 "Test connection"). */
@Service
public class ConnectionTestService {

    private final JdbcSupport jdbc;

    public ConnectionTestService(JdbcSupport jdbc) {
        this.jdbc = jdbc;
    }

    public TestResult test(DbType type, String host, int port, String database,
                           String username, String password, Map<String, Object> options) {
        long start = System.currentTimeMillis();
        try (Connection conn = jdbc.open(type, host, port, database, username, password, options)) {
            boolean valid = conn.isValid(8);
            long latency = System.currentTimeMillis() - start;
            return valid
                    ? new TestResult(true, "Connection successful", latency)
                    : new TestResult(false, "Connection opened but failed validation", latency);
        } catch (Exception e) {
            return new TestResult(false, e.getMessage(), System.currentTimeMillis() - start);
        }
    }
}
