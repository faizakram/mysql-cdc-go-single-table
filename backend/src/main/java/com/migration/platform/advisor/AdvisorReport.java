package com.migration.platform.advisor;

import java.util.List;
import java.util.UUID;

/** Advisor response for a project: the observed signals plus the recommendations derived from them (#217). */
public record AdvisorReport(
        UUID projectId,
        String jobStatus,
        long eventsPerSec,
        long maxLagMs,
        Long sinkLagRecords,
        List<PerformanceAdvisor.Recommendation> recommendations
) {}
