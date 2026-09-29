package com.poolguard.dto;

import com.poolguard.model.RunStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class RunDtos {
    private RunDtos() {}

    public record RunResponse(
        UUID id,
        String triggerType,
        RunStatus status,
        int total,
        int passed,
        int failed,
        int errors,
        String errorMessage,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        long durationMs,
        Long plannedChecks,
        Long completedChecks
    ) {}

    public record RunPage(java.util.List<RunResponse> items, long total, int page, int pageSize, int totalPages) {}
}
