package com.migration.platform.connection;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;

/**
 * Builds raw JDBC connections to source/target databases (shared by test + discovery).
 * TLS is configurable per connection via {@code options} and is <b>secure by default</b> (#44):
 * SQL Server uses {@code encrypt=true} unless explicitly disabled.
 *
 * Recognised options: {@code encrypt} (bool, SQL Server, default true),
 * {@code trustServerCertificate} (bool, SQL Server, default false),
 * {@code sslmode} (string, PostgreSQL, e.g. require/verify-full).
 */
@Component
public class JdbcSupport {

    public Connection open(DbConnection c, String plaintextPassword) throws SQLException {
        return open(c.getDbType(), c.getHost(), c.getPort(), c.getDatabaseName(),
                c.getUsername(), plaintextPassword, c.getOptions());
    }

    public Connection open(DbType type, String host, int port, String database,
                           String username, String password, Map<String, Object> options) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        switch (type) {
            case SQLSERVER -> {
                props.setProperty("encrypt", String.valueOf(optBool(options, "encrypt", true)));
                if (optBool(options, "trustServerCertificate", false)) {
                    props.setProperty("trustServerCertificate", "true");
                }
                props.setProperty("loginTimeout", "8");
            }
            case POSTGRESQL -> {
                String sslmode = optStr(options, "sslmode");
                if (sslmode != null) props.setProperty("sslmode", sslmode);
                props.setProperty("connectTimeout", "8");
            }
            case MYSQL -> {
                String sslmode = optStr(options, "sslmode");
                if (sslmode != null) props.setProperty("sslMode", sslmode);
                props.setProperty("connectTimeout", "8000");
            }
            case ORACLE, DB2 -> { /* driver defaults; engine-specific tuning via connection options/URL */ }
        }
        return DriverManager.getConnection(buildUrl(type, host, port, database), props);
    }

    public String buildUrl(DbType type, String host, int port, String database) {
        return EngineCatalog.spec(type).jdbcUrl(host, port, database);
    }

    private boolean optBool(Map<String, Object> options, String key, boolean def) {
        if (options == null) return def;
        Object v = options.get(key);
        if (v instanceof Boolean b) return b;
        if (v == null) return def;
        return Boolean.parseBoolean(v.toString());
    }

    private String optStr(Map<String, Object> options, String key) {
        if (options == null) return null;
        Object v = options.get(key);
        return (v == null || v.toString().isBlank()) ? null : v.toString();
    }
}
