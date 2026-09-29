package com.poolguard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;
import com.poolguard.model.MatchMode;

public final class PolicyDtos {
    private PolicyDtos() {}

    public record PolicyResponse(UUID id, String title, String answer, String model, int sortOrder, boolean active, int version, MatchMode matchMode) {}

    public record PolicySave(UUID id, @jakarta.validation.Valid @jakarta.validation.constraints.NotNull PolicyRequest policy) {}

    public record PolicyRequest(
        @Size(max=500) @NotBlank(message = "检测题目不能为空") String title,
        @Size(max=10000) @NotBlank(message = "标准答案或评分要点不能为空") String answer,
        @Size(max=128) @NotBlank(message = "能力标签不能为空") String model,
        Integer sortOrder,
        Boolean active,
        MatchMode matchMode
    ) {}
}
