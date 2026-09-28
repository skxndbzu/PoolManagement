package com.poolguard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "detection_runs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DetectionRun {
    @Id
    private UUID id;

    @Column(name = "trigger_type", nullable = false)
    private String triggerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status;

    @Column(name = "total_accounts", nullable = false)
    private Integer totalAccounts;

    @Column(name = "passed_accounts", nullable = false)
    private Integer passedAccounts;

    @Column(name = "failed_accounts", nullable = false)
    private Integer failedAccounts;

    @Column(name = "error_accounts", nullable = false)
    @Builder.Default
    private Integer errorAccounts = 0;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
}
