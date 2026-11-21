package com.commerceos.integration.spi;

import java.time.Instant;
import java.util.List;

/**
 * docs/06 §6 응답 동반 정보. 원본 payload 는 저장소 참조(raw_payload_ref)로 넘기고 매핑 결과와 매핑 규칙 버전을 함께 보관한다.
 */
public record ConnectorEnvelope<T>(String sourceSystem, String accountId, String rawPayloadRef, String externalId,
                                   List<T> mappedObjects, String mappingVersion, Instant sourceTimestamp, Instant fetchedAt,
                                   int capabilityVersion, String nextCursor) {
}
