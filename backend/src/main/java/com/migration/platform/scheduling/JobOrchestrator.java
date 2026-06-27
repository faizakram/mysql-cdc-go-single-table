package com.migration.platform.scheduling;

import com.migration.platform.scheduling.dto.OrchestratorDtos.OrchestratorStatus;
import com.migration.platform.scheduling.dto.OrchestratorDtos.TaskView;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Central job queue with concurrency control (#54).
 *
 * <p>Tasks (full-load / validation runs) are submitted from the scheduler (#53) or manual run-now.
 * At most {@code maxConcurrent} run at once; the rest wait in a FIFO queue (backpressure). For
 * fairness, at most one task per project runs concurrently — a heavy snapshot for project A never
 * blocks project B, and two runs of the same project never overlap. The current running/queued
 * state is observable via {@link #status()}.
 *
 * <p>The orchestrator is deliberately decoupled from what a task *does*: callers pass a
 * {@link Runnable} action, so it has no dependency on JobService/ReconciliationService.
 */
@Component
public class JobOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(JobOrchestrator.class);

    public enum State { QUEUED, RUNNING }

    /** A unit of work tracked by the orchestrator. */
    public static final class Task {
        final UUID id = UUID.randomUUID();
        final UUID projectId;
        final String projectName;
        final ScheduleKind kind;
        final String source;          // SCHEDULED | MANUAL
        final Runnable action;
        final Consumer<Boolean> onComplete;   // receives success flag; may be null
        final OffsetDateTime enqueuedAt = OffsetDateTime.now();
        volatile OffsetDateTime startedAt;
        volatile State state = State.QUEUED;

        Task(UUID projectId, String projectName, ScheduleKind kind, String source,
             Runnable action, Consumer<Boolean> onComplete) {
            this.projectId = projectId;
            this.projectName = projectName;
            this.kind = kind;
            this.source = source;
            this.action = action;
            this.onComplete = onComplete;
        }

        TaskView view() {
            return new TaskView(id, projectId, projectName, kind.name(), source, state.name(),
                    enqueuedAt, startedAt);
        }
    }

    private final int maxConcurrent;
    private final ExecutorService pool;
    private final Object lock = new Object();
    private final Deque<Task> pending = new ArrayDeque<>();
    private final Map<UUID, Task> running = new LinkedHashMap<>();
    private final Set<UUID> runningProjects = new HashSet<>();

    public JobOrchestrator(OrchestratorProperties props) {
        this.maxConcurrent = props.maxConcurrent();
        this.pool = Executors.newFixedThreadPool(this.maxConcurrent, r -> {
            Thread t = new Thread(r, "job-orchestrator");
            t.setDaemon(true);
            return t;
        });
        log.info("Job orchestrator started (maxConcurrent={})", maxConcurrent);
    }

    /** Submit a task. Returns immediately; the task runs now if a slot is free, else it queues. */
    public Task submit(UUID projectId, String projectName, ScheduleKind kind, String source,
                       Runnable action, Consumer<Boolean> onComplete) {
        Task t = new Task(projectId, projectName, kind, source, action, onComplete);
        synchronized (lock) {
            pending.addLast(t);
        }
        dispatch();
        return t;
    }

    /** Is a task for this project currently running or queued? Used to avoid duplicate scheduling. */
    public boolean hasPendingOrRunning(UUID projectId) {
        synchronized (lock) {
            if (runningProjects.contains(projectId)) return true;
            return pending.stream().anyMatch(t -> t.projectId.equals(projectId));
        }
    }

    public OrchestratorStatus status() {
        synchronized (lock) {
            List<TaskView> run = new ArrayList<>();
            for (Task t : running.values()) run.add(t.view());
            List<TaskView> wait = new ArrayList<>();
            for (Task t : pending) wait.add(t.view());
            return new OrchestratorStatus(maxConcurrent, running.size(), pending.size(), run, wait);
        }
    }

    /** Promote queued tasks into free slots, skipping any whose project is already running. */
    private void dispatch() {
        List<Task> toRun = new ArrayList<>();
        synchronized (lock) {
            Iterator<Task> it = pending.iterator();
            while (running.size() + toRun.size() < maxConcurrent && it.hasNext()) {
                Task t = it.next();
                if (runningProjects.contains(t.projectId)) continue;  // fairness: one per project
                it.remove();
                running.put(t.id, t);
                runningProjects.add(t.projectId);
                t.state = State.RUNNING;
                t.startedAt = OffsetDateTime.now();
                toRun.add(t);
            }
        }
        for (Task t : toRun) pool.submit(() -> runTask(t));
    }

    private void runTask(Task t) {
        boolean ok = false;
        try {
            t.action.run();
            ok = true;
        } catch (Exception e) {
            log.warn("Orchestrated {} task for project {} failed: {}", t.kind, t.projectId, e.getMessage());
        } finally {
            synchronized (lock) {
                running.remove(t.id);
                runningProjects.remove(t.projectId);
            }
            if (t.onComplete != null) {
                try { t.onComplete.accept(ok); } catch (Exception e) {
                    log.warn("Task completion callback failed: {}", e.getMessage());
                }
            }
            dispatch();   // a slot just freed; pull the next eligible task
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
