package com.commerceos.platform.outbox;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** docs/04 §10 표준 이벤트 필드. */
public record OutboxEvent(
        UUID eventId,
        UUID workspaceId,
        UUID businessEntityId,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        int schemaVersion,
        JsonNode payload,
        Instant occurredAt,
        String correlationId,
        UUID sourceEventId,
        int attempts) {
}
