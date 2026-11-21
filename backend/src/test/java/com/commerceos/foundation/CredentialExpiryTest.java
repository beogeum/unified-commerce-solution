package com.commerceos.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.operations.CredentialExpiryScanner;
import com.commerceos.support.Fixtures;
import com.commerceos.support.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** F9: 인증정보 만료 14일 전 업무함 경고, 같은 만료일은 1건만, 교체로 만료일이 바뀌면 새로 판단. */
class CredentialExpiryTest extends IntegrationTest {
    @Autowired
    CredentialExpiryScanner scanner;

    UUID connection(Fixtures.Ws ws, String name) {
        UUID be = fx.businessEntity(ws.owner(), "A 코스메틱");
        UUID ca = fx.channelAccount(ws.owner(), be, "NAVER_SMARTSTORE", name);
        return ws.owner().post("/api/v1/integrations/connections", Map.of("target_type", "CHANNEL_ACCOUNT",
                "target_id", ca.toString(), "connector_code", "mock-channel", "display_name", name,
                "auth_type", "API_KEY", "credential", Map.of("api_key", "k-" + UUID.randomUUID()))).id();
    }

    @Test
    void expiringCredential_raisesOneWorkItemPerExpiryDate() {
        Fixtures.Ws ws = fx.workspace();
        UUID soon = connection(ws, "네이버 A");
        UUID later = connection(ws, "네이버 B");
        jdbc.sql("update integration_connection set credential_expires_at = now() + interval '5 days' where id = :id").param("id", soon).update();
        jdbc.sql("update integration_connection set credential_expires_at = now() + interval '40 days' where id = :id").param("id", later).update();

        scanner.scan();
        var items = ws.owner().get("/api/v1/work-items?item_type=INTEGRATION_CREDENTIAL_EXPIRING").data();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("connection_id").asString()).isEqualTo(soon.toString());

        // 다시 돌려도, 처리 완료 후에도 같은 만료일은 다시 만들지 않는다
        scanner.scan();
        jdbc.sql("update work_item set status = 'DONE' where workspace_id = :ws").param("ws", ws.id()).update();
        scanner.scan();
        assertThat(jdbc.sql("select count(*) from work_item where workspace_id = :ws and item_type = 'INTEGRATION_CREDENTIAL_EXPIRING'")
                .param("ws", ws.id()).query(Integer.class).single()).isEqualTo(1);

        // 교체 후 새 만료일이 또 임박하면 새 업무
        jdbc.sql("update integration_connection set credential_expires_at = now() + interval '10 days' where id = :id").param("id", soon).update();
        scanner.scan();
        assertThat(jdbc.sql("select count(*) from work_item where workspace_id = :ws and item_type = 'INTEGRATION_CREDENTIAL_EXPIRING'")
                .param("ws", ws.id()).query(Integer.class).single()).isEqualTo(2);
    }
}
