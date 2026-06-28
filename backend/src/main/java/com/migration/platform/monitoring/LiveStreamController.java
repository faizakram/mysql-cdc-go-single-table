package com.migration.platform.monitoring;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Live sync monitor delivery (#168): an SSE stream that pushes a per-table throughput + lag snapshot
 * once a second to every subscriber (fan-out from a single snapshot), plus a REST snapshot fallback.
 */
@RestController
@RequestMapping("/api/v1/monitoring/live")
public class LiveStreamController {

    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;   // 30 min; client auto-reconnects

    private final LiveStreamMonitor monitor;
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public LiveStreamController(LiveStreamMonitor monitor) {
        this.monitor = monitor;
    }

    /** REST snapshot — current per-table throughput + lag. */
    @GetMapping
    public LiveStreamDtos.LiveSnapshot snapshot() {
        return build();
    }

    /** SSE stream — pushes a snapshot every second. EventSource passes the JWT via the token query-param. */
    @GetMapping("/stream")
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> { emitter.complete(); emitters.remove(emitter); });
        emitter.onError(e -> emitters.remove(emitter));
        emitters.add(emitter);
        try { emitter.send(SseEmitter.event().name("live").data(build())); }   // immediate first frame
        catch (IOException e) { emitters.remove(emitter); }
        return emitter;
    }

    @Scheduled(fixedRate = 1000)
    void broadcast() {
        if (emitters.isEmpty()) return;
        LiveStreamDtos.LiveSnapshot snap = build();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("live").data(snap));
            } catch (Exception e) {
                emitter.complete();
                emitters.remove(emitter);
            }
        }
    }

    private LiveStreamDtos.LiveSnapshot build() {
        List<LiveStreamDtos.TableThroughput> tables = monitor.snapshot();
        double total = tables.stream().mapToDouble(LiveStreamDtos.TableThroughput::eventsPerSec).sum();
        return new LiveStreamDtos.LiveSnapshot(System.currentTimeMillis(), Math.round(total * 10.0) / 10.0, tables);
    }
}
