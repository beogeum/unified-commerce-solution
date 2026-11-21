package com.commerceos.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import com.commerceos.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** FR-ORG-01~04, T01-F/T02-F/T03-F/T05-F (docs/10 §12.9 Foundation 판정분). */
class OrganizationStructureTest extends IntegrationTest {

    @Test
    void T01F_twoBusinessesEachOnNaver_accountsAndUnitsAttributedSeparately() {
        Fixtures.Ws ws = fx.workspace();
        UUID a = fx.businessEntity(ws.owner(), "사업자A");
        UUID b = fx.businessEntity(ws.owner(), "사업자B");
        UUID naverA = fx.channelAccount(ws.owner(), a, "NAVER_SMARTSTORE", "A 네이버");
        UUID naverB = fx.channelAccount(ws.owner(), b, "NAVER_SMARTSTORE", "B 네이버");

        JsonNode onlyA = ws.owner().get("/api/v1/channel-accounts?business_entity_id=" + a).data();
        assertThat(ids(onlyA)).containsExactly(naverA.toString());
        JsonNode onlyB = ws.owner().get("/api/v1/channel-accounts?business_entity_id=" + b).data();
        assertThat(ids(onlyB)).containsExactly(naverB.toString());

        JsonNode unitsA = ws.owner().get("/api/v1/channel-accounts/" + naverA + "/sales-units").data();
        assertThat(unitsA).hasSize(1);
        assertThat(unitsA.get(0).get("is_default").asBoolean()).isTrue();
        assertThat(unitsA.get(0).get("external_unit_key").asString()).isEqualTo("DEFAULT");
        assertThat(unitsA.get(0).get("business_entity_id").asString()).isEqualTo(a.toString());
    }

    @Test
    void T02F_oneBusinessWithThreeNaverAccounts_distinctAndAggregatable() {
        Fixtures.Ws ws = fx.workspace();
        UUID a = fx.businessEntity(ws.owner(), "사업자A");
        for (int i = 1; i <= 3; i++) {
            fx.channelAccount(ws.owner(), a, "NAVER_SMARTSTORE", "A 네이버 " + i);
        }
        JsonNode list = ws.owner().get("/api/v1/channel-accounts?business_entity_id=" + a + "&sales_channel_code=NAVER_SMARTSTORE").data();
        assertThat(list).hasSize(3);
        assertThat(ids(list)).doesNotHaveDuplicates();
    }

    @Test
    void T03F_cafe24MultiShop_twoSalesUnitsWithUniqueShopNo() {
        Fixtures.Ws ws = fx.workspace();
        UUID a = fx.businessEntity(ws.owner(), "사업자A");
        UUID cafe24 = fx.channelAccount(ws.owner(), a, "CAFE24", "A 카페24", "mallA",
                Map.of("external_unit_key", "1", "display_name", "기본몰"));
        Api.Res second = ws.owner().post("/api/v1/channel-accounts/" + cafe24 + "/sales-units",
                Map.of("external_unit_key", "2", "display_name", "영문몰"));
        assertThat(second.status()).isEqualTo(201);
        Api.Res dup = ws.owner().post("/api/v1/channel-accounts/" + cafe24 + "/sales-units",
                Map.of("external_unit_key", "2", "display_name", "중복"));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.errorCode()).isEqualTo("SALES_UNIT_DUPLICATE");

        JsonNode units = ws.owner().get("/api/v1/channel-accounts/" + cafe24 + "/sales-units").data();
        assertThat(units).hasSize(2);
        assertThat(units.get(0).get("external_unit_key").asString()).isEqualTo("1");
        assertThat(units.get(0).get("is_default").asBoolean()).isTrue();

        // 하위 판매단위를 쓰지 않는 채널에는 추가 불가
        UUID naver = fx.channelAccount(ws.owner(), a, "NAVER_SMARTSTORE", "A 네이버");
        Api.Res notSupported = ws.owner().post("/api/v1/channel-accounts/" + naver + "/sales-units",
                Map.of("external_unit_key", "x"));
        assertThat(notSupported.errorCode()).isEqualTo("SUB_UNITS_NOT_SUPPORTED");
    }

    @Test
    void salesUnitIdentityIsImmutableAtDbLevel() {
        Fixtures.Ws ws = fx.workspace();
        UUID a = fx.businessEntity(ws.owner(), "사업자A");
        UUID ca = fx.channelAccount(ws.owner(), a, "CAFE24", "카페24");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql(
                        "update sales_unit set external_unit_key = 'CHANGED' where channel_account_id = :ca").param("ca", ca).update())
                .hasMessageContaining("immutable");
    }

    @Test
    void T05F_sameLogisticsProvider_separateShipperAccountsPerBusiness() {
        Fixtures.Ws ws = fx.workspace();
        UUID a = fx.businessEntity(ws.owner(), "사업자A");
        UUID b = fx.businessEntity(ws.owner(), "사업자B");
        UUID sa = fx.shipperAccount(ws.owner(), a, "FASSTO", "SHIP-A");
        UUID sb = fx.shipperAccount(ws.owner(), b, "FASSTO", "SHIP-B");
        assertThat(ids(ws.owner().get("/api/v1/shipper-accounts?business_entity_id=" + a).data())).containsExactly(sa.toString());
        assertThat(ids(ws.owner().get("/api/v1/shipper-accounts?business_entity_id=" + b).data())).containsExactly(sb.toString());
        Api.Res dup = ws.owner().post("/api/v1/shipper-accounts", Map.of("business_entity_id", b.toString(),
                "logistics_provider_code", "FASSTO", "display_name", "중복", "external_shipper_key", "SHIP-A"));
        assertThat(dup.status()).isEqualTo(409);
    }

    @Test
    void duplicateExternalAccountKey_rejectedInWorkspace_allowedInOtherWorkspace() {
        Fixtures.Ws ws = fx.workspace();
        UUID a = fx.businessEntity(ws.owner(), "사업자A");
        UUID b = fx.businessEntity(ws.owner(), "사업자B");
        fx.channelAccount(ws.owner(), a, "COUPANG_MARKETPLACE", "쿠팡", "VENDOR-1", null);
        Api.Res dup = ws.owner().post("/api/v1/channel-accounts", Map.of("business_entity_id", b.toString(),
                "sales_channel_code", "COUPANG_MARKETPLACE", "display_name", "쿠팡 중복", "external_account_key", "VENDOR-1"));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.errorCode()).isEqualTo("CHANNEL_ACCOUNT_DUPLICATE");
        // 같은 판매자ID 라도 채널이 다르면(예: 마켓플레이스/로켓그로스) 별도 계정으로 등록 가능 (OQ-018 확정 전)
        fx.channelAccount(ws.owner(), a, "COUPANG_ROCKET_GROWTH", "로켓그로스", "VENDOR-1", null);

        Fixtures.Ws other = fx.workspace();
        UUID x = fx.businessEntity(other.owner(), "다른그룹 사업자");
        fx.channelAccount(other.owner(), x, "COUPANG_MARKETPLACE", "쿠팡", "VENDOR-1", null);
    }

    @Test
    void businessRegistrationNumber_validatedAndUniqueAndImmutable() {
        Fixtures.Ws ws = fx.workspace();
        Api.Res bad = ws.owner().post("/api/v1/business-entities", Map.of("display_name", "x", "legal_name", "x",
                "legal_type", "CORPORATION", "business_registration_no", "1234567890", "tax_type", "GENERAL"));
        // 1234567890 은 검증번호가 1 이어야 하므로 거부된다
        assertThat(bad.status()).isEqualTo(422);
        String brn = Fixtures.validBrn();
        Api.Res ok = ws.owner().post("/api/v1/business-entities", Map.of("display_name", "A", "legal_name", "A",
                "legal_type", "CORPORATION", "business_registration_no", brn.substring(0, 3) + "-" + brn.substring(3, 5) + "-" + brn.substring(5),
                "tax_type", "GENERAL"));
        assertThat(ok.status()).isEqualTo(201);
        assertThat(ok.data().get("business_registration_no").asString()).isEqualTo(brn);
        Api.Res dup = ws.owner().post("/api/v1/business-entities", Map.of("display_name", "A2", "legal_name", "A2",
                "legal_type", "CORPORATION", "business_registration_no", brn, "tax_type", "GENERAL"));
        assertThat(dup.errorCode()).isEqualTo("BUSINESS_REGISTRATION_NO_DUPLICATE");
        Api.Res change = ws.owner().patch("/api/v1/business-entities/" + ok.id(), Map.of("business_registration_no", Fixtures.validBrn()), 1);
        assertThat(change.errorCode()).isEqualTo("BUSINESS_IDENTITY_IMMUTABLE");
    }

    @Test
    void businessDeactivation_blockedWhileAccountsActive_andHistoryRecorded() {
        Fixtures.Ws ws = fx.workspace();
        UUID a = fx.businessEntity(ws.owner(), "사업자A");
        UUID ca = fx.channelAccount(ws.owner(), a, "ELEVENST", "11번가");
        Api.Res blocked = ws.owner().patch("/api/v1/business-entities/" + a, Map.of("status", "INACTIVE"), 1);
        assertThat(blocked.errorCode()).isEqualTo("BUSINESS_ENTITY_HAS_ACTIVE_ACCOUNTS");
        assertThat(ws.owner().patch("/api/v1/channel-accounts/" + ca, Map.of("status", "CLOSED"), 1).status()).isEqualTo(200);
        Api.Res ok = ws.owner().patch("/api/v1/business-entities/" + a, Map.of("status", "INACTIVE", "display_name", "사업자A(폐업)"), 1);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.data().get("version").asInt()).isEqualTo(2);
        JsonNode history = ws.owner().get("/api/v1/business-entities/" + a + "/history").data();
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("action").asString()).isEqualTo("BUSINESS_ENTITY_UPDATED");
        assertThat(history.get(0).get("before").get("status").asString()).isEqualTo("ACTIVE");
        assertThat(history.get(0).get("after").get("status").asString()).isEqualTo("INACTIVE");
        // 비활성 사업자에는 계정 추가 불가
        Api.Res add = ws.owner().post("/api/v1/channel-accounts", Map.of("business_entity_id", a.toString(),
                "sales_channel_code", "GMARKET", "display_name", "G"));
        assertThat(add.errorCode()).isEqualTo("BUSINESS_ENTITY_INACTIVE");
        // 종료된 계정은 재개 불가
        Api.Res reopen = ws.owner().patch("/api/v1/channel-accounts/" + ca, Map.of("status", "ACTIVE"), 2);
        assertThat(reopen.errorCode()).isEqualTo("CHANNEL_ACCOUNT_CLOSED");
    }

    @Test
    void salesChannelCatalog_listsChannelsWithoutClaimingIntegrationSupport() {
        Fixtures.Ws ws = fx.workspace();
        JsonNode channels = ws.owner().get("/api/v1/sales-channels").data();
        assertThat(channels.size()).isGreaterThanOrEqualTo(24);
        channels.forEach(c -> assertThat(c.has("supported")).isFalse());
        assertThat(ws.owner().get("/api/v1/integrations/connectors").data().findValuesAsString("code"))
                .containsExactlyInAnyOrder("mock-3pl", "mock-channel");
    }

    static List<String> ids(JsonNode arr) {
        return arr.findValuesAsString("id").stream().toList();
    }
}
