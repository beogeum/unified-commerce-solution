package com.commerceos.integration.spi;

import java.util.Map;
import java.util.UUID;

/** 커넥터 호출 문맥. credentials 는 복호화된 값이며 로그·응답으로 내보내지 않는다. */
public record ConnectorContext(UUID workspaceId, UUID connectionId, String targetType, UUID targetId, String externalAccountKey,
                               String apiVersion, Map<String, String> credentials) {
    @Override
    public String toString() {
        return "ConnectorContext[connectionId=" + connectionId + ", targetType=" + targetType + ", credentials=***]";
    }
}
