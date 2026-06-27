package com.migration.platform.audit;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * An immutable record of a control or configuration action (#57): who did what, to what, when.
 * Maps the {@code audit_log} table created in V1.
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    private UUID id;

    /** Username of the actor, or "system" for scheduled/background actions. */
    @Column(nullable = false)
    private String actor;

    /** e.g. JOB_START, JOB_STOP, PROJECT_CREATE, CONNECTION_DELETE, SCHEDULE_RUN_NOW, LOGIN. */
    @Column(nullable = false)
    private String action;

    /** What the action targeted (project/job/connection id or name). */
    private String target;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> details = new HashMap<>();

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public Map<String, Object> getDetails() { return details; }
    public void setDetails(Map<String, Object> details) { this.details = details; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
