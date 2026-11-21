package com.commerceos.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import com.commerceos.support.IntegrationTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** FR-ORG-07: 판매계정 귀속 변경 이력과 과거 귀속 보존, 연동 일시중지(DEC-F06, DEC-F16). */
class AttributionHistoryTest extends IntegrationTest {

    @Test
    void reassignment_preservesHistory_pausesConnections_raisesWorkItem() {
        Fixtures.Ws ws = fx.workspace();
        UUID a = fx.businessEntity(ws.owner(), "A");
        UUID b = fx.businessEntity(ws.owner(), "B");
        UUID acc = fx.channelAccount(ws.owner(), a, "NAVER_SMARTSTORE", "이관 스토어");
        UUID conn = IntegrationIsolationTest.connection(ws.owner(), acc, "OK", "OK");
        ws.owner().post("/api/v1/integrations/" + conn + "/sync-jobs", Map.of());
        drainAsync();
        OffsetDateTime beforeChange = OffsetDateTime.now(ZoneOffset.UTC);

        Api.Res noReason = ws.owner().postIfMatch("/api/v1/channel-accounts/" + acc + "/attribution-changes",
                Map.of("business_entity_id", b.toString()), 1);
        assertThat(noReason.status()).isEqualTo(422);
        Api.Res changed = ws.owner().postIfMatch("/api/v1/channel-accounts/" + acc + "/attribution-changes",
                Map.of("business_entity_id", b.toString(), "reason", "사업 양수도"), 1);
        assertThat(changed.status()).as(changed.body().toString()).isEqualTo(200);
        assertThat(changed.data().get("business_entity_id").asString()).isEqualTo(b.toString());

        JsonNode history = ws.owner().get("/api/v1/channel-accounts/" + acc + "/attributions").data();
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("business_entity_id").asString()).isEqualTo(b.toString());
        assertThat(history.get(0).get("valid_to").isNull()).isTrue();
        assertThat(history.get(1).get("business_entity_id").asString()).isEqualTo(a.toString());
        assertThat(history.get(1).get("valid_to").isNull()).isFalse();

        // 거래 시점 귀속 조회: 변경 전 시각은 A, 현재는 B (과거 주문 재귀속 금지의 기반)
        UUID atBefore = jdbc.sql("select channel_account_business_at(:ca, :t)").param("ca", acc).param("t", beforeChange).query(UUID.class).single();
        UUID atNow = jdbc.sql("select channel_account_business_at(:ca, now())").param("ca", acc).query(UUID.class).single();
        assertThat(atBefore).isEqualTo(a);
        assertThat(atNow).isEqualTo(b);

        drainAsync();
        assertThat(ws.owner().get("/api/v1/integrations/connections/" + conn).data().get("status").asString()).isEqualTo("PAUSED");
        assertThat(ws.owner().get("/api/v1/work-items?item_type=CHANNEL_ACCOUNT_REASSIGNED").data()).hasSize(1);
    }

    @Test
    void overlappingAttributionPeriods_rejectedByDatabase() {
        Fixtures.Ws ws = fx.workspace();
        UUID a = fx.businessEntity(ws.owner(), "A");
        UUID b = fx.businessEntity(ws.owner(), "B");
        UUID acc = fx.channelAccount(ws.owner(), a, "GMARKET", "G");
        assertThatThrownBy(() -> jdbc.sql("""
                insert into channel_account_attribution (id, workspace_id, channel_account_id, business_entity_id, valid_from)
                values (gen_random_uuid(), :ws, :ca, :be, now() - interval '1 day')
                """).param("ws", ws.id()).param("ca", acc).param("be", b).update())
                .hasMessageContaining("exclusion");
    }

    @Test
    void reassignment_requiresPermissionAndWriteScopeOnBothBusinesses() {
        Fixtures.Ws ws = fx.workspace();
        UUID a = fx.businessEntity(ws.owner(), "A");
        UUID b = fx.businessEntity(ws.owner(), "B");
        UUID acc = fx.channelAccount(ws.owner(), a, "AUCTION", "옥션");
        Api ops = fx.member(ws, "OPS_ADMIN");
        assertThat(ops.postIfMatch("/api/v1/channel-accounts/" + acc + "/attribution-changes",
                Map.of("business_entity_id", b.toString(), "reason", "x"), 1).status()).isEqualTo(403);
        Api stale = ws.owner();
        assertThat(stale.postIfMatch("/api/v1/channel-accounts/" + acc + "/attribution-changes",
                Map.of("business_entity_id", b.toString(), "reason", "x"), 99).errorCode()).isEqualTo("VERSION_CONFLICT");
    }
}
