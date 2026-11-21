package com.commerceos.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.support.Api;
import com.commerceos.support.Fixtures;
import com.commerceos.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SmokeTest extends IntegrationTest {
    @Test
    void bootsAndServesFoundationApi() {
        Fixtures.Ws ws = fx.workspace();
        UUID a = fx.businessEntity(ws.owner(), "사업자A");
        Api.Res list = ws.owner().get("/api/v1/business-entities");
        assertThat(list.status()).as(list.body().toString()).isEqualTo(200);
        assertThat(list.data()).hasSize(1);
        assertThat(list.data().get(0).get("id").asString()).isEqualTo(a.toString());
        Api.Res me = ws.owner().get("/api/v1/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.data().get("role_codes").get(0).asString()).isEqualTo("SUPER_ADMIN");
    }
}
