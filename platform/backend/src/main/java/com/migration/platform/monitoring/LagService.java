package com.migration.platform.monitoring;

import com.migration.platform.config.PlatformProperties;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Measures CDC lag as the sink connector's Kafka consumer-group lag — total records the target is
 * behind the source stream (issue #50). Returns null when the group is unknown/unreachable.
 */
@Service
public class LagService {

    private static final Logger log = LoggerFactory.getLogger(LagService.class);
    private final String bootstrap;

    public LagService(PlatformProperties props) {
        this.bootstrap = props.connect().kafkaBootstrap();
    }

    public Long consumerGroupLag(String group) {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 8000);
        p.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 8000);
        try (Admin admin = Admin.create(p)) {
            Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> committed =
                    admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get();
            if (committed == null || committed.isEmpty()) return null;

            Map<TopicPartition, OffsetSpec> specs = committed.keySet().stream()
                    .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> end =
                    admin.listOffsets(specs).all().get();

            Map<String, Long> committedByTp = new HashMap<>();
            Map<String, Long> endByTp = new HashMap<>();
            committed.forEach((tp, off) -> committedByTp.put(tp.toString(), off.offset()));
            end.forEach((tp, info) -> endByTp.put(tp.toString(), info.offset()));
            return MonitoringLag.totalLag(committedByTp, endByTp);
        } catch (Exception e) {
            log.debug("Lag lookup failed for group {}: {}", group, e.getMessage());
            return null;
        }
    }
}
