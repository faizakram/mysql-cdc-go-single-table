package com.migration.platform.audit;

import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only audit log (#57). Admin-only — enforced in SecurityConfig. */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditService service;

    public AuditController(AuditService service) {
        this.service = service;
    }

    public record AuditEntry(UUID id, String actor, String action, String target,
                             Map<String, Object> details, OffsetDateTime createdAt) {
        static AuditEntry from(AuditLog a) {
            return new AuditEntry(a.getId(), a.getActor(), a.getAction(), a.getTarget(),
                    a.getDetails(), a.getCreatedAt());
        }
    }

    public record AuditPage(List<AuditEntry> content, int page, int size, long total) {}

    @GetMapping
    public AuditPage list(@RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "50") int size) {
        Page<AuditLog> p = service.list(page, size);
        return new AuditPage(p.getContent().stream().map(AuditEntry::from).toList(),
                p.getNumber(), p.getSize(), p.getTotalElements());
    }
}
