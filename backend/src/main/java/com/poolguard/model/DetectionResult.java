package com.poolguard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "detection_results")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DetectionResult {
    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "policy_id")
    private UUID policyId;

    @Column(name = "model", length = 200)
    private String model;

    @Column(nullable = false)
    private Boolean passed;

    @Column(nullable = false)
    @Builder.Default
    private String outcome = "PASS";

    @Column(columnDefinition = "TEXT")
    private String question;

    @Column(name = "expected_answer", columnDefinition = "TEXT")
    private String expectedAnswer;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "match_mode", length = 16)
    private MatchMode matchMode;

    @Column(nullable = false)
    private Integer score;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "answer_excerpt", columnDefinition = "TEXT")
    private String answerExcerpt;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
