package com.migration.platform.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Records control/config actions to the audit log (#57). Resolves the actor from the security
 * context, so callers only describe the action. Failures never break the audited action — auditing
 * is best-effort and logged, not propagated.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repo;
    private final AuditProperties props;

    public AuditService(AuditLogRepository repo, AuditProperties props) {
        this.repo = repo;
        this.props = props;
    }

    public void record(String action, String target, Map<String, Object> details) {
        try {
            AuditLog e = new AuditLog();
            e.setActor(currentActor());
            e.setAction(action);
            e.setTarget(target);
            if (details != null) e.setDetails(details);
            repo.save(e);
        } catch (Exception ex) {
            log.warn("Failed to write audit event {} on {}: {}", action, target, ex.getMessage());
        }
    }

    public void record(String action, String target) {
        record(action, target, Map.of());
    }

    /** Record with an explicit actor (e.g. login, before the security context is populated). */
    public void recordAs(String actor, String action, String target, Map<String, Object> details) {
        try {
            AuditLog e = new AuditLog();
            e.setActor(actor == null ? "system" : actor);
            e.setAction(action);
            e.setTarget(target);
            if (details != null) e.setDetails(details);
            repo.save(e);
        } catch (Exception ex) {
            log.warn("Failed to write audit event {}: {}", action, ex.getMessage());
        }
    }

    public Page<AuditLog> list(int page, int size) {
        return repo.findAllByOrderByCreatedAtDesc(PageRequest.of(page, Math.min(size, 200)));
    }

    private String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName())) {
            return "system";
        }
        return auth.getName();
    }

    /** Retention sweep (#57): drop audit entries older than the configured window. */
    @Scheduled(cron = "${platform.audit.retention-cron:0 30 3 * * *}")
    public void enforceRetention() {
        try {
            OffsetDateTime cutoff = OffsetDateTime.now().minusDays(props.retentionDays());
            int deleted = repo.deleteOlderThan(cutoff);
            if (deleted > 0) log.info("Audit retention: removed {} entries older than {} days", deleted, props.retentionDays());
        } catch (Exception e) {
            log.warn("Audit retention sweep failed: {}", e.getMessage());
        }
    }
}
