package com.poolguard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "managed_accounts", indexes = {
    @Index(name = "idx_managed_accounts_status", columnList = "status"),
    @Index(name = "idx_managed_accounts_next_check", columnList = "next_check_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManagedAccount {
    @Id
    private UUID id;

    @Column(name = "external_account_id", nullable = false)
    private String externalAccountId;

    @Column(name = "email_masked", nullable = false)
    private String emailMasked;

    @Column(name = "project_code", nullable = false)
    private String projectCode;

    @Column(nullable = false)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Column(nullable = false)
    private Integer score;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "check_count", nullable = false)
    private Integer checkCount;

    @Column(name = "last_check_at")
    private OffsetDateTime lastCheckAt;

    @Column(name = "next_check_at")
    private OffsetDateTime nextCheckAt;

    @Column(name = "last_failure_reason", columnDefinition = "TEXT")
    private String lastFailureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean monitoring = true;

    @Column(name = "source_present", nullable = false)
    @Builder.Default
    private Boolean sourcePresent = true;

    @Column(name = "disabled_by_guard", nullable = false)
    @Builder.Default
    private Boolean disabledByGuard = false;

    @Column(name = "pass_streak", nullable = false)
    @Builder.Default
    private Integer passStreak = 0;

    @PrePersist
    void beforeInsert() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (checkCount == null) checkCount = 0;
        if (score == null) score = 0;
    }

    @PreUpdate
    void beforeUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
