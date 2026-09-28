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
        String failureReason
    ) {}

    public record AccountActionRequest(String action) {}

    public record AccountSummary(
        long total,
        long healthy,
        long recovering,
        long degraded,
        long disabled
    ) {}
}
