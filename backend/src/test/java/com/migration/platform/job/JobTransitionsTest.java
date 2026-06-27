package com.migration.platform.job;

import org.junit.jupiter.api.Test;

import static com.migration.platform.job.JobTransitions.Action.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobTransitionsTest {

    @Test
    void allowsTheValidLifecycleTransitions() {
        assertThat(JobTransitions.allowed(START, JobStatus.CREATED)).isTrue();
        assertThat(JobTransitions.allowed(START, JobStatus.STOPPED)).isTrue();   // re-run
        assertThat(JobTransitions.allowed(START, JobStatus.FAILED)).isTrue();    // retry
        assertThat(JobTransitions.allowed(PAUSE, JobStatus.RUNNING)).isTrue();
        assertThat(JobTransitions.allowed(PAUSE, JobStatus.SNAPSHOT)).isTrue();
        assertThat(JobTransitions.allowed(RESUME, JobStatus.PAUSED)).isTrue();
        assertThat(JobTransitions.allowed(STOP, JobStatus.RUNNING)).isTrue();
    }

    @Test
    void rejectsInvalidTransitions() {
        assertThat(JobTransitions.allowed(START, JobStatus.RUNNING)).isFalse();  // already running
        assertThat(JobTransitions.allowed(PAUSE, JobStatus.STOPPED)).isFalse();
        assertThat(JobTransitions.allowed(RESUME, JobStatus.RUNNING)).isFalse();
        assertThat(JobTransitions.allowed(STOP, JobStatus.COMPLETED)).isFalse();
    }

    @Test
    void assertAllowedThrowsWithAClearMessage() {
        assertThatThrownBy(() -> JobTransitions.assertAllowed(PAUSE, JobStatus.STOPPED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot pause a job in state STOPPED");
    }
}
