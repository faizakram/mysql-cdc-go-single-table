package com.migration.platform.connection;

import com.migration.platform.connection.dto.TestResult;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.Map;

/** Validates source/target connectivity using JDBC (issue #22 / UI #35 "Test connection"). */
@Service
public class ConnectionTestService {

    private final JdbcSupport jdbc;
    private final MongoSupport mongo;

    public ConnectionTestService(JdbcSupport jdbc, MongoSupport mongo) {
        this.jdbc = jdbc;
        this.mongo = mongo;
    }

    public TestResult test(DbType type, String host, int port, String database,
                           String username, String password, Map<String, Object> options) {
        long start = System.currentTimeMillis();
        if (type == DbType.MONGODB) {   // MongoDB isn't reachable over JDBC (#124)
            try {
                DbConnection c = new DbConnection();
                c.setDbType(type); c.setHost(host); c.setPort(port); c.setDatabaseName(database);
                c.setUsername(username); c.setOptions(options);
                return new TestResult(true, "Connection successful", mongo.ping(c, password));
            } catch (Exception e) {
                return new TestResult(false, e.getMessage(), System.currentTimeMillis() - start);
            }
        }
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
