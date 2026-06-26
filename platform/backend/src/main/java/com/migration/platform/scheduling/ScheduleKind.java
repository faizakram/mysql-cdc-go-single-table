package com.migration.platform.scheduling;

/**
 * What a schedule (or an orchestrated task) runs:
 * <ul>
 *   <li>{@link #FULL_LOAD} — (re)deploy the project's connectors and run a snapshot + CDC job.</li>
 *   <li>{@link #VALIDATION} — run a reconciliation (row-count) pass.</li>
 * </ul>
 */
public enum ScheduleKind {
    FULL_LOAD,
    VALIDATION
}
