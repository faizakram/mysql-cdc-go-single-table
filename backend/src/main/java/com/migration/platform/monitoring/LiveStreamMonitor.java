package com.migration.platform.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.migration.platform.config.PlatformProperties;
import com.migration.platform.connector.MigrationConfig;
import com.migration.platform.project.MigrationProject;
import com.migration.platform.project.ProjectRepository;
import com.migration.platform.project.ProjectStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Live sync monitor (#168): tails the Debezium CDC topics as an independent Kafka consumer
 * ({@code auto.offset.reset=latest}, unique group — a live tail with no history replay and no
 * impact on the JDBC sink's consumer group) and maintains rolling per-table throughput + lag.
 *
 * <p>The source topics carry the full Debezium envelope, so each record yields the operation
 * ({@code op}: c/u/d/r), the source table ({@code source.table}) and the commit time
 * ({@code ts_ms}) used to derive replication lag.
 */
@Component
public class LiveStreamMonitor {

    private static final Logger log = LoggerFactory.getLogger(LiveStreamMonitor.class);
    /** Drop per-table stats after this long with no events, so the in-memory map stays bounded (#214). */
    private static final long STAT_TTL_MS = 3_600_000L;   // 1 hour

    private final PlatformProperties platform;
    private final LiveStreamProperties props;
    private final ProjectRepository projects;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, TableStat> stats = new ConcurrentHashMap<>();

    // topic-prefix → project, rebuilt periodically so new/renamed projects are picked up.
    private volatile Map<String, ProjectRef> prefixes = Map.of();
    private volatile long prefixesAt;

    private volatile boolean running;
    private Thread worker;
    private KafkaConsumer<byte[], byte[]> consumer;

    public LiveStreamMonitor(PlatformProperties platform, LiveStreamProperties props, ProjectRepository projects) {
        this.platform = platform;
        this.props = props;
        this.projects = projects;
    }

    private record ProjectRef(String id, String name) {}

    @PostConstruct
    void start() {
        if (!props.enabled()) {
            log.info("Live sync monitor disabled (platform.monitoring.live.enabled=false)");
            return;
        }
        running = true;
        worker = new Thread(this::run, "live-sync-monitor");
        worker.setDaemon(true);
        worker.start();
        log.info("Live sync monitor started (pattern={}, window={}s)", props.topicPattern(), props.windowSeconds());
    }

    private void run() {
        Properties cfg = new Properties();
        cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, platform.connect().kafkaBootstrap());
        cfg.put(ConsumerConfig.GROUP_ID_CONFIG, "live-sync-monitor-" + UUID.randomUUID());
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        cfg.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");   // live tail only — never replay history
        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");   // we never commit; offsets don't matter

        try (KafkaConsumer<byte[], byte[]> c = new KafkaConsumer<>(cfg)) {
            this.consumer = c;
            c.subscribe(Pattern.compile(props.topicPattern()));
            while (running) {
                ConsumerRecords<byte[], byte[]> records = c.poll(Duration.ofMillis(500));
                long now = System.currentTimeMillis();
                for (ConsumerRecord<byte[], byte[]> rec : records) {
                    if (rec.value() == null) continue;   // tombstone — the delete event itself carries op=d
                    record(rec.topic(), rec.value(), now);
                }
            }
        } catch (org.apache.kafka.common.errors.WakeupException ignored) {
            // expected on shutdown
        } catch (Exception e) {
            if (running) log.warn("Live sync monitor stopped on error: {}", e.getMessage());
        }
    }

    private void record(String topic, byte[] value, long now) {
        try {
            JsonNode root = mapper.readTree(value);
            JsonNode p = root.has("payload") ? root.get("payload") : root;   // schemas.enable on/off
            String op = p.path("op").asText(null);
            if (op == null) return;
            String table = p.path("source").path("table").asText("");
            long tsMs = p.path("ts_ms").asLong(0);
            stats.computeIfAbsent(topic, t -> new TableStat(friendlyTable(t, table)))
                    .accept(op, now, tsMs, props.windowSeconds());
        } catch (Exception ignored) {
            // non-Debezium / unparseable record — skip
        }
    }

    private static String friendlyTable(String topic, String sourceTable) {
        if (sourceTable != null && !sourceTable.isBlank()) return sourceTable;
        int dot = topic.lastIndexOf('.');
        return dot >= 0 ? topic.substring(dot + 1) : topic;
    }

    /**
     * Per-table throughput + lag snapshot, busiest first. When {@code projectId} is non-null, only
     * tables whose topic belongs to that project are returned (project-scoped live stream, #168).
     */
    public List<LiveStreamDtos.TableThroughput> snapshot(String projectId) {
        long now = System.currentTimeMillis();
        // Evict tables with no events for a while so the map can't grow unbounded as topics/projects
        // come and go (memory leak, #214). They re-appear on the next event if the topic is live again.
        stats.entrySet().removeIf(e -> e.getValue().lastEventMs > 0 && now - e.getValue().lastEventMs > STAT_TTL_MS);
        Map<String, ProjectRef> map = prefixMap(now);
        List<LiveStreamDtos.TableThroughput> out = new ArrayList<>();
        for (Map.Entry<String, TableStat> e : stats.entrySet()) {
            ProjectRef ref = resolveProject(e.getKey(), map);
            if (projectId != null && (ref == null || !projectId.equals(ref.id()))) continue;
            out.add(e.getValue().view(now, props.windowSeconds(),
                    ref == null ? null : ref.id(), ref == null ? null : ref.name()));
        }
        out.sort(Comparator.comparingDouble(LiveStreamDtos.TableThroughput::eventsPerSec).reversed());
        return out;
    }

    /** Match a topic to its owning project by longest topic-prefix ({@code prefix.<...>}). */
    private ProjectRef resolveProject(String topic, Map<String, ProjectRef> map) {
        ProjectRef best = null;
        int bestLen = -1;
        for (Map.Entry<String, ProjectRef> e : map.entrySet()) {
            String prefix = e.getKey();
            if (topic.startsWith(prefix + ".") && prefix.length() > bestLen) {
                best = e.getValue();
                bestLen = prefix.length();
            }
        }
        return best;
    }

    /** Cached topic-prefix → project map, rebuilt at most every 30s so renames/new projects appear. */
    private Map<String, ProjectRef> prefixMap(long now) {
        if (now - prefixesAt < 30_000 && !prefixes.isEmpty()) return prefixes;
        Map<String, ProjectRef> map = new java.util.HashMap<>();
        try {
            // Only ACTIVE projects have live topics — avoid loading every project here, hit on the SSE
            // broadcast path (#214).
            for (MigrationProject p : projects.findByStatus(ProjectStatus.ACTIVE)) {
                String prefix = MigrationConfig.from(p.getConfig(), p.getName()).topicPrefix();
                if (prefix != null && !prefix.isBlank()) map.put(prefix, new ProjectRef(p.getId().toString(), p.getName()));
            }
        } catch (Exception e) {
            log.debug("Could not refresh project prefixes: {}", e.getMessage());
        }
        prefixes = map;
        prefixesAt = now;
        return map;
    }

    @PreDestroy
    void stop() {
        running = false;
        if (consumer != null) {
            try { consumer.wakeup(); } catch (Exception ignored) { }
        }
    }

    /** Per-table rolling counters. Cumulative totals + a windowed deque of event times for the rate. */
    private static final class TableStat {
        private final String table;
        private long inserts, updates, deletes, reads;
        private volatile long lastEventMs;
        private volatile long lastLagMs;
        private final Deque<Long> recent = new ArrayDeque<>();   // event epoch ms within the window

        TableStat(String table) { this.table = table; }

        synchronized void accept(String op, long now, long tsMs, int windowSeconds) {
            switch (op) {
                case "c" -> inserts++;
                case "u" -> updates++;
                case "d" -> deletes++;
                case "r" -> reads++;
                default -> { return; }
            }
            lastEventMs = now;
            if (tsMs > 0) lastLagMs = Math.max(0, now - tsMs);
            recent.addLast(now);
            prune(now, windowSeconds);
        }

        private void prune(long now, int windowSeconds) {
            long cutoff = now - windowSeconds * 1000L;
            while (!recent.isEmpty() && recent.peekFirst() < cutoff) recent.pollFirst();
        }

        synchronized LiveStreamDtos.TableThroughput view(long now, int windowSeconds, String projectId, String project) {
            prune(now, windowSeconds);
            double perSec = recent.size() / (double) windowSeconds;
            long ageMs = lastEventMs == 0 ? -1 : now - lastEventMs;
            return new LiveStreamDtos.TableThroughput(projectId, project, table, inserts, updates, deletes, reads,
                    inserts + updates + deletes + reads, round1(perSec), lastLagMs, ageMs);
        }

        private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
    }
}
