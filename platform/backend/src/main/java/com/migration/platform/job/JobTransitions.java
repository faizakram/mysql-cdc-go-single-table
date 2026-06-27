package com.migration.platform.job;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Allowed migration-job lifecycle transitions (issue #23). Pure logic, unit-tested, so the service
 * rejects invalid control actions (e.g. pausing a STOPPED job) with a clear error.
 */
public final class JobTransitions {

    public enum Action { START, PAUSE, RESUME, STOP }

    private static final Map<Action, Set<JobStatus>> ALLOWED_FROM = Map.of(
            Action.START,  EnumSet.of(JobStatus.CREATED, JobStatus.STOPPED, JobStatus.FAILED),
            Action.PAUSE,  EnumSet.of(JobStatus.SNAPSHOT, JobStatus.RUNNING),
            Action.RESUME, EnumSet.of(JobStatus.PAUSED),
            Action.STOP,   EnumSet.of(JobStatus.CREATED, JobStatus.SNAPSHOT, JobStatus.RUNNING, JobStatus.PAUSED)
    );

    public static boolean allowed(Action action, JobStatus current) {
        return ALLOWED_FROM.getOrDefault(action, EnumSet.noneOf(JobStatus.class)).contains(current);
    }

    /** @throws IllegalArgumentException (→ HTTP 400) if the action is not valid from {@code current}. */
    public static void assertAllowed(Action action, JobStatus current) {
        if (!allowed(action, current)) {
            throw new IllegalArgumentException(
                    "Cannot " + action.name().toLowerCase() + " a job in state " + current);
        }
    }

    private JobTransitions() {}
}
