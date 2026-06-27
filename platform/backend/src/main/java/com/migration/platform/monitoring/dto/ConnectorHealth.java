package com.migration.platform.monitoring.dto;

import java.util.List;

public record ConnectorHealth(
        String name,
        String role,        // source | sink
        String state,       // RUNNING | PAUSED | FAILED | UNASSIGNED | NOT_FOUND | UNKNOWN
        String workerId,
        List<TaskHealth> tasks
) {
    public boolean healthy() {
        return "RUNNING".equals(state) && tasks.stream().allMatch(t -> "RUNNING".equals(t.state()));
    }

    public long runningTasks() {
        return tasks.stream().filter(t -> "RUNNING".equals(t.state())).count();
    }
}
