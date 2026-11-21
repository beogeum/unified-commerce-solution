package com.commerceos.operations;

import com.commerceos.platform.outbox.OutboxEvent;
import com.commerceos.platform.outbox.OutboxSubscriber;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 연동 장애 이벤트 → 업무함(FR-OPS-02). 같은 연결의 같은 오류는 하나의 항목으로 묶인다. */
@Component
public class IntegrationIncidentSubscriber implements OutboxSubscriber {
    private final WorkItemService workItems;

    public IntegrationIncidentSubscriber(WorkItemService workItems) {
        this.workItems = workItems;
    }

    @Override
    public String consumerName() {
        return "operations.integration-incident";
    }

    @Override
    public boolean supports(String eventType) {
        return switch (eventType) {
            case "IntegrationConnectionAuthExpired", "IntegrationConnectionErrored", "ChannelAccountReassigned" -> true;
            default -> false;
        };
    }

    @Override
    public void handle(OutboxEvent e) {
        JsonNode p = e.payload();
        Map<String, Object> related = new LinkedHashMap<>();
        related.put("source_event_id", e.eventId().toString());
        switch (e.eventType()) {
            case "IntegrationConnectionAuthExpired" -> {
                related.put("connection_id", text(p, "connection_id"));
                workItems.raise(new WorkItemService.Raise(e.workspaceId(), e.businessEntityId(), uuid(p, "channel_account_id"),
                        uuid(p, "connection_id"), "INTEGRATION_AUTH_EXPIRED", "HIGH",
                        "연동 인증 만료: " + text(p, "connection_name"),
                        "판매채널/물류사 인증정보가 만료되어 이 연결의 수집·전송이 중단되었습니다. 다른 연결은 정상 처리됩니다.",
                        "판매자센터에서 인증정보를 재발급하고 [연동 관리 > 인증정보 교체] 후 상태 점검을 실행하세요.",
                        "integration:" + text(p, "connection_id") + ":AUTH_EXPIRED", related));
            }
            case "IntegrationConnectionErrored" -> {
                related.put("connection_id", text(p, "connection_id"));
                related.put("error_code", text(p, "error_code"));
                workItems.raise(new WorkItemService.Raise(e.workspaceId(), e.businessEntityId(), uuid(p, "channel_account_id"),
                        uuid(p, "connection_id"), "INTEGRATION_ERROR", "HIGH",
                        "연동 오류 반복: " + text(p, "connection_name"),
                        "연동 호출이 연속으로 실패해 연결이 오류 상태가 되었습니다(" + text(p, "error_code") + ").",
                        "연동 이력의 오류 내용을 확인하고 권한·호출 제한·인증 상태를 점검한 뒤 다시 시도하세요.",
                        "integration:" + text(p, "connection_id") + ":" + text(p, "error_code"), related));
            }
            case "ChannelAccountReassigned" -> {
                related.put("channel_account_id", text(p, "channel_account_id"));
                related.put("from_business_entity_id", text(p, "from_business_entity_id"));
                workItems.raise(new WorkItemService.Raise(e.workspaceId(), e.businessEntityId(), uuid(p, "channel_account_id"), null,
                        "CHANNEL_ACCOUNT_REASSIGNED", "MEDIUM", "판매계정 사업자 귀속 변경 — 연동 재확인 필요",
                        "판매계정의 귀속 사업자가 바뀌어 기존 연동 연결을 일시중지했습니다.",
                        "새 사업자 명의의 인증정보·권한을 확인한 뒤 연결을 재개하고 상태 점검을 실행하세요.",
                        "channel-account:" + text(p, "channel_account_id") + ":REASSIGNED:" + e.eventId(), related));
            }
            default -> {
            }
        }
    }

    private static String text(JsonNode p, String field) {
        JsonNode n = p.get(field);
        return n == null || n.isNull() ? null : n.asString();
    }

    private static UUID uuid(JsonNode p, String field) {
        String t = text(p, field);
        return t == null ? null : UUID.fromString(t);
    }
}
