package com.migration.platform.monitoring;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Live sync monitor delivery (#168): SSE streams of per-table throughput + lag, pushed once a second.
 * Available globally (all projects) and scoped per project, so N projects each get their own live
 * stream. EventSource passes the JWT via the {@code token} query-param.
 */
@RestController
@RequestMapping("/api/v1")
public class LiveStreamController {

    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;   // 30 min; client auto-reconnects

    private final LiveStreamMonitor monitor;
    /** Each subscriber with the project it's scoped to (null = global, all projects). */
    private final CopyOnWriteArrayList<Sub> subs = new CopyOnWriteArrayList<>();

    public LiveStreamController(LiveStreamMonitor monitor) {
        this.monitor = monitor;
    }

    private record Sub(SseEmitter emitter, String projectId) {}

    // ---- Global (all projects) ----
    @GetMapping("/monitoring/live")
    public LiveStreamDtos.LiveSnapshot snapshot() {
        return build(null);
    }

    @GetMapping("/monitoring/live/stream")
    public SseEmitter stream() {
        return subscribe(null);
    }

    // ---- Project-scoped ----
    @GetMapping("/projects/{projectId}/monitoring/live")
    public LiveStreamDtos.LiveSnapshot projectSnapshot(@PathVariable String projectId) {
        return build(projectId);
    }

    @GetMapping("/projects/{projectId}/monitoring/live/stream")
    public SseEmitter projectStream(@PathVariable String projectId) {
        return subscribe(projectId);
    }

    private SseEmitter subscribe(String projectId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Sub sub = new Sub(emitter, projectId);
        emitter.onCompletion(() -> subs.remove(sub));
        emitter.onTimeout(() -> { emitter.complete(); subs.remove(sub); });
        emitter.onError(e -> subs.remove(sub));
        subs.add(sub);
        try { emitter.send(SseEmitter.event().name("live").data(build(projectId))); }   // immediate first frame
        catch (IOException e) { subs.remove(sub); }
        return emitter;
    }

    @Scheduled(fixedRate = 1000)
    void broadcast() {
        if (subs.isEmpty()) return;
        Map<String, LiveStreamDtos.LiveSnapshot> cache = new HashMap<>();   // one snapshot per distinct scope per tick
        for (Sub sub : subs) {
            String key = sub.projectId() == null ? "*" : sub.projectId();
            LiveStreamDtos.LiveSnapshot snap = cache.computeIfAbsent(key, k -> build(sub.projectId()));
            try {
                sub.emitter().send(SseEmitter.event().name("live").data(snap));
            } catch (Exception e) {
                sub.emitter().complete();
                subs.remove(sub);
            }
        }
    }

    private LiveStreamDtos.LiveSnapshot build(String projectId) {
        List<LiveStreamDtos.TableThroughput> tables = monitor.snapshot(projectId);
        double total = tables.stream().mapToDouble(LiveStreamDtos.TableThroughput::eventsPerSec).sum();
        return new LiveStreamDtos.LiveSnapshot(System.currentTimeMillis(), Math.round(total * 10.0) / 10.0, tables);
    }
}
