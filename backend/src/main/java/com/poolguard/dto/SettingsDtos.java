package com.poolguard.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

public final class SettingsDtos {
    private SettingsDtos() {}

    public record SettingsResponse(
        int scheduleValue,
        String scheduleUnit,
        int restorePasses,
        OffsetDateTime nextRunAt
    ) {}

    public record SettingsRequest(
        @NotNull @Min(1) @Max(999) Integer scheduleValue,
        @NotBlank String scheduleUnit,
        @NotNull @Min(1) @Max(20) Integer restorePasses
    ) {}
}
