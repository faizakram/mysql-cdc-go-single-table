package com.migration.platform.connection;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds JDBC connections to source/target databases (shared by test + discovery + validation +
 * bulk copy). TLS is configurable per connection via {@code options} and is <b>secure by default</b>
 * (#44): SQL Server uses {@code encrypt=true} unless explicitly disabled.
 *
 * <p>Connections are <b>pooled per (url, user, credentials, options)</b> with HikariCP (#212).
 * Previously every call opened a raw {@code DriverManager} connection; at 300-table scale the
 * per-table discovery/validation/bulk-copy work opened hundreds–thousands of short-lived
 * connections (~50–500 ms TCP+auth each). Pooling reuses a small set instead. The {@link #open}
 * contract is unchanged: callers get a {@link Connection} and {@code close()} it — close() returns
 * it to the pool rather than tearing down the socket.
 *
 * Recognised options: {@code encrypt} (bool, SQL Server, default true),
 * {@code trustServerCertificate} (bool, SQL Server, default false),
 * {@code sslmode} (string, PostgreSQL, e.g. require/verify-full).
 */
@Component
public class JdbcSupport {

    private static final Logger log = LoggerFactory.getLogger(JdbcSupport.class);
    private static final int MAX_POOL_SIZE = 8;   // bounded so we never hammer a source/target

    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();

    public Connection open(DbConnection c, String plaintextPassword) throws SQLException {
        return open(c.getDbType(), c.getHost(), c.getPort(), c.getDatabaseName(),
                c.getUsername(), plaintextPassword, c.getOptions());
    }

    public Connection open(DbType type, String host, int port, String database,
                           String username, String password, Map<String, Object> options) throws SQLException {
        String url = buildUrl(type, host, port, database);
        Properties extras = driverProps(type, options);
        return pool(url, username, password, extras).getConnection();
    }

    /** Driver-level connection properties (TLS, timeouts) — NOT user/password (Hikari sets those). */
    private Properties driverProps(DbType type, Map<String, Object> options) {
        Properties props = new Properties();
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
            case ORACLE, DB2, MONGODB -> { /* driver defaults */ }
        }
        return props;
    }

    /** Cached HikariCP pool for this exact connection target + credentials + options. */
    private HikariDataSource pool(String url, String username, String password, Properties extras) {
        // Key includes a password hash + sorted options so credential/option changes mint a fresh pool.
        StringBuilder key = new StringBuilder(url).append('|').append(username)
                .append('|').append(Integer.toHexString((password == null ? "" : password).hashCode()));
        new TreeMap<>(extras).forEach((k, v) -> key.append('|').append(k).append('=').append(v));
        return pools.computeIfAbsent(key.toString(), k -> {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(url);
            cfg.setUsername(username);
            cfg.setPassword(password);
            cfg.setDataSourceProperties(extras);
            cfg.setMaximumPoolSize(MAX_POOL_SIZE);
            cfg.setMinimumIdle(0);                    // release idle connections so unused pools cost nothing
            cfg.setConnectionTimeout(10_000);
            cfg.setIdleTimeout(60_000);
            cfg.setMaxLifetime(1_800_000);            // 30 min
            // Don't validate at construction — preserve the old behaviour where a bad target surfaces as a
            // SQLException on first use, not a startup crash.
            cfg.setInitializationFailTimeout(-1);
            cfg.setPoolName("ds-" + Integer.toHexString(k.hashCode()));
            log.debug("Created JDBC pool {} for {}", cfg.getPoolName(), url);
            return new HikariDataSource(cfg);
        });
    }

    public String buildUrl(DbType type, String host, int port, String database) {
        return EngineCatalog.spec(type).jdbcUrl(host, port, database);
    }

    @PreDestroy
    public void shutdown() {
        pools.values().forEach(ds -> { try { ds.close(); } catch (Exception ignored) { } });
        pools.clear();
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
