package com.migration.platform.scheduling;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class JobOrchestratorTest {

    @Test
    void enforcesConcurrencyLimit() throws Exception {
        JobOrchestrator orch = new JobOrchestrator(new OrchestratorProperties(2));
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch done = new CountDownLatch(4);

        Runnable work = () -> {
            int now = active.incrementAndGet();
            maxObserved.accumulateAndGet(now, Math::max);
            started.countDown();
            try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            active.decrementAndGet();
        };

        // Four tasks, four distinct projects, limit 2.
        for (int i = 0; i < 4; i++) {
            orch.submit(UUID.randomUUID(), "p" + i, ScheduleKind.VALIDATION, "MANUAL", work,
                    ok -> done.countDown());
        }

        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        // With 2 running and a limit of 2, the other two must be queued.
        assertThat(orch.status().running()).isEqualTo(2);
        assertThat(orch.status().queued()).isEqualTo(2);

        release.countDown();
        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(maxObserved.get()).isEqualTo(2);   // never exceeded the limit
    }

    @Test
    void neverRunsTwoTasksForTheSameProjectConcurrently() throws Exception {
        JobOrchestrator orch = new JobOrchestrator(new OrchestratorProperties(4));
        UUID project = UUID.randomUUID();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxForProject = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(3);

        Runnable work = () -> {
            maxForProject.accumulateAndGet(active.incrementAndGet(), Math::max);
            try { Thread.sleep(80); } catch (InterruptedException ignored) {}
            active.decrementAndGet();
        };

        // Three tasks for the SAME project; even with 4 slots, they must serialize.
        for (int i = 0; i < 3; i++) {
            orch.submit(project, "same", ScheduleKind.FULL_LOAD, "SCHEDULED", work, ok -> done.countDown());
        }

        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(maxForProject.get()).isEqualTo(1);
    }
}
