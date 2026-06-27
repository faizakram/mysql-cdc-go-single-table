package com.migration.platform.scheduling.dto;

import com.migration.platform.scheduling.JobSchedule;
import com.migration.platform.scheduling.ScheduleKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class ScheduleDtos {

    private ScheduleDtos() {}

    /** Create/update payload. {@code cron} is a Spring 6-field expression (sec min hour dom mon dow). */
    public record ScheduleRequest(
            @NotNull ScheduleKind kind,
            @NotBlank String cron,
            Boolean enabled
    ) {}

    public record ScheduleResponse(
            UUID id,
            UUID projectId,
            ScheduleKind kind,
            String cron,
            boolean enabled,
            OffsetDateTime lastRunAt,
            String lastStatus,
            OffsetDateTime nextRunAt,
            OffsetDateTime createdAt
    ) {
        public static ScheduleResponse from(JobSchedule s) {
            return new ScheduleResponse(s.getId(), s.getProjectId(), s.getKind(), s.getCron(),
                    s.isEnabled(), s.getLastRunAt(), s.getLastStatus(), s.getNextRunAt(), s.getCreatedAt());
        }
    }
}
