package com.migration.platform.connection;

import com.migration.platform.connection.dto.ColumnInfo;
import com.migration.platform.connection.dto.ColumnMapping;
import com.migration.platform.connection.dto.TableInfo;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Live schema discovery + proposed type mapping for a stored connection (issues #30, #37). */
@RestController
@RequestMapping("/api/v1/connections/{id}/schema")
public class SchemaController {

    private final SchemaDiscoveryService discovery;
    private final TypeMappingService typeMapping;

    public SchemaController(SchemaDiscoveryService discovery, TypeMappingService typeMapping) {
        this.discovery = discovery;
        this.typeMapping = typeMapping;
    }

    @GetMapping("/tables")
    public List<TableInfo> tables(@PathVariable UUID id,
                                  @RequestParam(required = false) String schema) {
        return discovery.listTables(id, schema);
    }

    @GetMapping("/columns")
    public List<ColumnInfo> columns(@PathVariable UUID id,
                                    @RequestParam String schema,
                                    @RequestParam String table) {
        return discovery.listColumns(id, schema, table);
    }

    @GetMapping("/type-mapping")
    public List<ColumnMapping> typeMapping(@PathVariable UUID id,
                                           @RequestParam String schema,
                                           @RequestParam String table,
                                           @RequestParam(required = false) UUID projectId) {
        return typeMapping.proposeForTable(id, schema, table, projectId);
    }
}
