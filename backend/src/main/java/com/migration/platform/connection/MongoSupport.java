package com.migration.platform.connection;

import com.migration.platform.connection.dto.ColumnInfo;
import com.migration.platform.connection.dto.TableInfo;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Native MongoDB access for test-connection + schema discovery (#124). MongoDB is not reachable over
 * JDBC, so the JDBC path can't validate or introspect it. Honors the {@code authEnabled} option
 * (auth-less dev instances) the same way the source connector does (#121).
 */
@Component
public class MongoSupport {

    /** Build a Mongo connection string; direct connection avoids replica-set discovery hangs on test. */
    public String connectionString(DbConnection c, String password) {
        boolean auth = optBool(c.getOptions(), "authEnabled", true);
        String creds = auth ? enc(c.getUsername()) + ":" + enc(password) + "@" : "";
        String authSrc = auth ? "&authSource=" + optStr(c.getOptions(), "authSource", "admin") : "";
        return "mongodb://" + creds + c.getHost() + ":" + c.getPort() + "/?directConnection=true" + authSrc;
    }

    /** Ping the deployment; returns latency ms or throws. */
    public long ping(DbConnection c, String password) {
        long start = System.currentTimeMillis();
        try (MongoClient client = MongoClients.create(connectionString(c, password))) {
            client.getDatabase(dbName(c)).runCommand(new Document("ping", 1));
            return System.currentTimeMillis() - start;
        }
    }

    /** Collections in the database, presented as "tables" (PK = _id). */
    public List<TableInfo> listCollections(DbConnection c, String password) {
        List<TableInfo> out = new ArrayList<>();
        try (MongoClient client = MongoClients.create(connectionString(c, password))) {
            MongoDatabase db = client.getDatabase(dbName(c));
            for (String name : db.listCollectionNames()) {
                out.add(new TableInfo(dbName(c), name, true, false));
            }
        }
        out.sort((a, b) -> a.tableName().compareToIgnoreCase(b.tableName()));
        return out;
    }

    /** Infer columns from a sample document (top-level fields; nested → object/array as JSON-ish). */
    public List<ColumnInfo> sampleColumns(DbConnection c, String password, String collection) {
        List<ColumnInfo> out = new ArrayList<>();
        try (MongoClient client = MongoClients.create(connectionString(c, password))) {
            Document doc = client.getDatabase(dbName(c)).getCollection(collection).find().first();
            if (doc != null) {
                for (String key : doc.keySet()) {
                    Object v = doc.get(key);
                    out.add(new ColumnInfo(key, bsonType(v), 0, !"_id".equals(key), "_id".equals(key)));
                }
            }
        }
        return out;
    }

    private String dbName(DbConnection c) {
        return c.getDatabaseName() == null || c.getDatabaseName().isBlank() ? "admin" : c.getDatabaseName();
    }

    private String bsonType(Object v) {
        if (v == null) return "null";
        if (v instanceof org.bson.types.ObjectId) return "objectId";
        if (v instanceof String) return "string";
        if (v instanceof Integer) return "int";
        if (v instanceof Long) return "long";
        if (v instanceof Double || v instanceof org.bson.types.Decimal128) return "double";
        if (v instanceof Boolean) return "bool";
        if (v instanceof java.util.Date) return "date";
        if (v instanceof List) return "array";
        if (v instanceof Document) return "document";
        if (v instanceof byte[]) return "binary";
        return v.getClass().getSimpleName().toLowerCase();
    }

    private static String enc(String s) {
        return s == null ? "" : java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
    private boolean optBool(Map<String, Object> o, String k, boolean def) {
        if (o == null) return def; Object v = o.get(k);
        if (v instanceof Boolean b) return b; return v == null ? def : Boolean.parseBoolean(v.toString());
    }
    private String optStr(Map<String, Object> o, String k, String def) {
        if (o == null) return def; Object v = o.get(k);
        return (v == null || v.toString().isBlank()) ? def : v.toString();
    }
}
