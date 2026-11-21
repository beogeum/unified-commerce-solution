package com.commerceos.platform.job;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record Job(
        UUID id,
        UUID workspaceId,
        UUID businessEntityId,
        UUID channelAccountId,
        UUID connectionId,
        String jobType,
        JobStatus status,
        JsonNode payload,
        JsonNode result,
        String errorCode,
        String errorMessageKo,
        int attempts,
        int maxAttempts,
        Instant availableAt,
        Integer progressTotal,
        Integer progressDone,
        UUID createdByMemberId,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {
}
