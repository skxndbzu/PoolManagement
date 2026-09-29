package com.poolguard.dto;

import com.poolguard.model.AccountStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class AccountDtos {
    private AccountDtos() {}

    public record AccountResponse(
        UUID id,
        String externalId,
        String email,
        String project,
        String model,
        AccountStatus status,
        int score,
        Integer latencyMs,
        int checks,
        OffsetDateTime lastCheck,
        OffsetDateTime nextCheck,
        boolean monitoring,
        boolean sourcePresent,
        boolean disabledByGuard,
        int passStreak,
        String failureReason,
        boolean enabled,
        java.util.List<String> detectionModels,
        java.util.List<String> upstreamModels,
        OffsetDateTime modelsSyncedAt
    ) {}

    public record AccountActionRequest(String action, String model) {
        public AccountActionRequest(String action) { this(action, null); }
    }

    public record DetectionModelsRequest(
        @jakarta.validation.constraints.NotEmpty
        @jakarta.validation.constraints.Size(max = 20)
        java.util.List<@jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(max = 200) String> models
    ) {}

    public record AccountSummary(
        long total,
        long healthy,
        long recovering,
        long degraded,
        long disabled
    ) {}
}
