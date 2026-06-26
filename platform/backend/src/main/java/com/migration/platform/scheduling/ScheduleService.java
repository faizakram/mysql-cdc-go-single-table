package com.migration.platform.scheduling;

import com.migration.platform.common.NotFoundException;
import com.migration.platform.job.JobService;
import com.migration.platform.job.dto.JobResponse;
import com.migration.platform.project.MigrationProject;
import com.migration.platform.project.ProjectRepository;
import com.migration.platform.reconciliation.ReconciliationService;
import com.migration.platform.scheduling.dto.ScheduleDtos.ScheduleRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Manages per-project schedules (#53) and submits their work to the {@link JobOrchestrator} (#54).
 * A schedule's action is either a full-load (deploy connectors + snapshot/CDC job) or a validation
 * (reconciliation) run.
 */
@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    private final JobScheduleRepository repo;
    private final ProjectRepository projects;
    private final JobOrchestrator orchestrator;
    private final JobService jobs;
    private final ReconciliationService reconciliation;
    private final com.migration.platform.audit.AuditService audit;

    public ScheduleService(JobScheduleRepository repo, ProjectRepository projects,
                           JobOrchestrator orchestrator, JobService jobs,
                           ReconciliationService reconciliation,
                           com.migration.platform.audit.AuditService audit) {
        this.repo = repo;
        this.projects = projects;
        this.orchestrator = orchestrator;
        this.jobs = jobs;
        this.reconciliation = reconciliation;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<JobSchedule> list(UUID projectId) {
        return repo.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    @Transactional
    public JobSchedule create(UUID projectId, ScheduleRequest req) {
        if (!projects.existsById(projectId)) throw new NotFoundException("Project " + projectId + " not found");
        CronExpression cron = parseCron(req.cron());
        JobSchedule s = new JobSchedule();
        s.setProjectId(projectId);
        s.setKind(req.kind());
        s.setCron(req.cron());
        s.setEnabled(req.enabled() == null || req.enabled());
        s.setNextRunAt(s.isEnabled() ? cron.next(OffsetDateTime.now()) : null);
        s = repo.save(s);
        audit.record("SCHEDULE_CREATE", projectId.toString(),
                java.util.Map.of("kind", req.kind().name(), "cron", req.cron()));
        return s;
    }

    @Transactional
    public JobSchedule update(UUID id, ScheduleRequest req) {
        JobSchedule s = find(id);
        CronExpression cron = parseCron(req.cron());
        s.setKind(req.kind());
        s.setCron(req.cron());
        s.setEnabled(req.enabled() == null || req.enabled());
        s.setNextRunAt(s.isEnabled() ? cron.next(OffsetDateTime.now()) : null);
        return repo.save(s);
    }

    @Transactional
    public void delete(UUID id) {
        repo.delete(find(id));
    }

    /** Manual "run now" (#53) — submits immediately, bypassing the cron, but still queued/limited. */
    @Transactional
    public JobSchedule runNow(UUID id) {
        JobSchedule s = find(id);
        enqueue(s, "MANUAL");
        audit.record("SCHEDULE_RUN_NOW", s.getProjectId().toString(), java.util.Map.of("kind", s.getKind().name()));
        return repo.save(s);
    }

    /** Called by the sweeper: enqueue every enabled schedule whose next run is due. */
    @Transactional
    public int fireDue() {
        OffsetDateTime now = OffsetDateTime.now();
        List<JobSchedule> due = repo.findByEnabledTrueAndNextRunAtLessThanEqual(now);
        int fired = 0;
        for (JobSchedule s : due) {
            enqueue(s, "SCHEDULED");
            // Advance the cron cadence regardless, so a slow/queued run never causes a fire storm.
            s.setNextRunAt(parseCron(s.getCron()).next(now));
            repo.save(s);
            fired++;
        }
        return fired;
    }

    /** Submit the schedule's action to the orchestrator (deduped per project) and mark RUNNING. */
    private void enqueue(JobSchedule s, String source) {
        MigrationProject p = projects.findById(s.getProjectId())
                .orElseThrow(() -> new NotFoundException("Project " + s.getProjectId() + " not found"));

        // Per-project fairness is enforced by the orchestrator; skip a duplicate submission so the
        // queue doesn't pile up runs for a project that's already busy.
        if (orchestrator.hasPendingOrRunning(s.getProjectId())) {
            log.info("Skipping {} {} for project '{}' — already running/queued", source, s.getKind(), p.getName());
            return;
        }

        UUID scheduleId = s.getId();
        ScheduleKind kind = s.getKind();
        Runnable action = () -> runAction(p.getId(), kind);
        orchestrator.submit(p.getId(), p.getName(), kind, source, action,
                success -> recordOutcome(scheduleId, success));

        s.setLastRunAt(OffsetDateTime.now());
        s.setLastStatus("RUNNING");
    }

    /** The actual work, executed on an orchestrator worker thread. */
    private void runAction(UUID projectId, ScheduleKind kind) {
        switch (kind) {
            case FULL_LOAD -> {
                JobResponse job = jobs.create(projectId);
                jobs.start(job.id());
            }
            case VALIDATION -> reconciliation.run(projectId, "COUNT", 0);
        }
    }

    /** Persist the run outcome. Runs on a worker thread, so it uses its own transaction via the repo. */
    @Transactional
    public void recordOutcome(UUID scheduleId, boolean success) {
        repo.findById(scheduleId).ifPresent(s -> {
            s.setLastStatus(success ? "SUCCESS" : "FAILED");
            repo.save(s);
        });
    }

    public JobSchedule find(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Schedule " + id + " not found"));
    }

    private CronExpression parseCron(String cron) {
        try {
            return CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid cron expression: " + e.getMessage());
        }
    }
}
