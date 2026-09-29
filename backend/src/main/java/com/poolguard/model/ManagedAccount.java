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

    @Column(name = "detection_models", columnDefinition = "TEXT")
    private String detectionModels;

    @Column(name = "upstream_models", columnDefinition = "TEXT")
    private String upstreamModels;

    @Column(name = "models_synced_at")
    private OffsetDateTime modelsSyncedAt;

    public java.util.List<String> modelsFromUpstream() {
        return upstreamModels == null || upstreamModels.isBlank()
            ? java.util.List.of() : java.util.List.of(upstreamModels.split("\n"));
    }

    public boolean canRetestModel(String modelId) {
        return modelsToDetect().contains(modelId) || modelsFromUpstream().contains(modelId);
    }

    @Column(name = "remote_enabled", nullable = false)
    @Builder.Default
    private Boolean remoteEnabled = true;

    public java.util.List<String> modelsToDetect() {
        return detectionModels == null || detectionModels.isBlank()
            ? java.util.List.of(model) : java.util.List.of(detectionModels.split("\n"));
    }

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

    @Column(name = "fail_streak", nullable = false)
    @Builder.Default
    private Integer failStreak = 0;

    @Column(name = "guard_operation_id", length = 36)
    private String guardOperationId;

    @Column(name = "evaluation_fingerprint", length = 64)
    private String evaluationFingerprint;

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
