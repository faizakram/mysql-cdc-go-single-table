package com.migration.platform.planning;

import com.migration.platform.planning.dto.PlanDtos.MigrationPlan;
import com.migration.platform.planning.dto.PlanDtos.PlanTable;
import com.migration.platform.planning.dto.PlanDtos.TableInput;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure migration-planning logic (#88): FK-topological ordering with parallel levels (#89), and
 * risk assembly (#91). No DB access — fully unit-testable. Cycles are detected and their members
 * flagged (they migrate last with deferred constraints).
 */
public final class MigrationPlanLogic {

    private MigrationPlanLogic() {}

    public static final long HUGE_ROWS = 50_000_000L;

    /**
     * @param tables       selected tables (fq "schema.table")
     * @param deps         child -> set of parent tables it references (FK); only edges within {@code tables} count
     * @param rowCount/bytes/hasPk/unmappable per table
     * @param rowsPerSec   throughput assumption for the duration estimate
     */
    public static MigrationPlan plan(List<TableInput> tables,
                                     Map<String, Set<String>> deps,
                                     long rowsPerSec) {
        Set<String> all = new HashSet<>();
        for (TableInput t : tables) all.add(t.fqName());

        // Kahn's algorithm by levels: a table is ready once all its in-set parents are placed.
        Map<String, Set<String>> pending = new HashMap<>();
        for (TableInput t : tables) {
            Set<String> ps = new HashSet<>(deps.getOrDefault(t.fqName(), Set.of()));
            ps.retainAll(all);
            ps.remove(t.fqName());   // ignore self-reference for ordering
            pending.put(t.fqName(), ps);
        }

        Map<String, Integer> level = new LinkedHashMap<>();
        int lvl = 0;
        boolean progress = true;
        while (pending.size() > level.size() && progress) {
            progress = false;
            List<String> ready = new ArrayList<>();
            for (var e : pending.entrySet()) {
                if (level.containsKey(e.getKey())) continue;
                boolean allPlaced = level.keySet().containsAll(e.getValue());
                if (allPlaced) ready.add(e.getKey());
            }
            for (String r : ready) { level.put(r, lvl); progress = true; }
            lvl++;
        }
        // Whatever remains is part of a cycle.
        Set<String> cyclic = new HashSet<>();
        for (String t : pending.keySet()) if (!level.containsKey(t)) cyclic.add(t);

        long totalRows = 0, totalBytes = 0;
        List<PlanTable> out = new ArrayList<>();
        for (TableInput t : tables) {
            List<String> risks = new ArrayList<>();
            if (!t.hasPk()) risks.add("NO_PRIMARY_KEY");
            if (t.unmappableColumns() > 0) risks.add("UNSUPPORTED_TYPES(" + t.unmappableColumns() + ")");
            if (t.rowCount() > HUGE_ROWS) risks.add("HUGE_TABLE");
            if (cyclic.contains(t.fqName())) risks.add("CIRCULAR_FK");
            totalRows += Math.max(0, t.rowCount());
            totalBytes += Math.max(0, t.bytes());
            out.add(new PlanTable(t.fqName(), t.rowCount(), t.bytes(),
                    cyclic.contains(t.fqName()) ? -1 : level.getOrDefault(t.fqName(), 0), t.hasPk(), risks));
        }
        // Order: by level (cyclic last), then by fq name.
        out.sort((a, b) -> {
            int la = a.level() < 0 ? Integer.MAX_VALUE : a.level();
            int lb = b.level() < 0 ? Integer.MAX_VALUE : b.level();
            return la != lb ? Integer.compare(la, lb) : a.fqName().compareToIgnoreCase(b.fqName());
        });

        int maxLevel = level.values().stream().mapToInt(i -> i).max().orElse(0);
        long estSeconds = rowsPerSec <= 0 ? 0 : (totalRows + rowsPerSec - 1) / rowsPerSec;
        List<String> planRisks = new ArrayList<>();
        if (!cyclic.isEmpty()) planRisks.add("Circular FK dependencies among " + cyclic.size() + " table(s)");
        long noPk = out.stream().filter(t -> !t.hasPk()).count();
        if (noPk > 0) planRisks.add(noPk + " table(s) without a primary key (CDC/upsert needs one)");
        long huge = out.stream().filter(t -> t.rowCount() > HUGE_ROWS).count();
        if (huge > 0) planRisks.add(huge + " very large table(s) — consider parallel snapshot tuning");

        return new MigrationPlan(out, maxLevel + 1, !cyclic.isEmpty(),
                totalRows, totalBytes, estSeconds, planRisks);
    }
}
