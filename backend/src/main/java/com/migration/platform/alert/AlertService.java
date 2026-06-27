package com.migration.platform.alert;

import com.migration.platform.common.NotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Raises/resolves/acknowledges alerts with dedup (one open FIRING alert per dedup key). */
@Service
public class AlertService {

    private final AlertRepository repo;
    private final AlertNotifier notifier;

    public AlertService(AlertRepository repo, AlertNotifier notifier) {
        this.repo = repo;
        this.notifier = notifier;
    }

    /** Raise an alert if one isn't already firing for this dedup key. Idempotent + notifies once. */
    @Transactional
    public void raise(String dedupKey, String severity, String type, String message, UUID projectId) {
        if (repo.findFirstByDedupKeyAndStatus(dedupKey, "FIRING").isPresent()) return;
        Alert a = new Alert();
        a.setDedupKey(dedupKey);
        a.setSeverity(severity);
        a.setType(type);
        a.setMessage(message);
        a.setProjectId(projectId);
        a.setStatus("FIRING");
        try {
            repo.save(a);
        } catch (DataIntegrityViolationException race) {
            return; // another thread opened the same alert concurrently (unique partial index)
        }
        notifier.notify(a);
    }

    /** Resolve the open alert for this dedup key, if any (condition cleared). */
    @Transactional
    public void resolve(String dedupKey) {
        repo.findFirstByDedupKeyAndStatus(dedupKey, "FIRING").ifPresent(a -> {
            a.setStatus("RESOLVED");
            repo.save(a);
        });
    }

    @Transactional(readOnly = true)
    public List<Alert> list(UUID projectId) {
        return projectId != null
                ? repo.findByProjectIdOrderByCreatedAtDesc(projectId)
                : repo.findTop200ByOrderByCreatedAtDesc();
    }

    @Transactional
    public Alert acknowledge(UUID id) {
        Alert a = repo.findById(id).orElseThrow(() -> new NotFoundException("Alert " + id + " not found"));
        a.setStatus("ACKNOWLEDGED");
        return repo.save(a);
    }

    @Transactional(readOnly = true)
    public long firingCount() {
        return repo.countByStatus("FIRING");
    }
}
