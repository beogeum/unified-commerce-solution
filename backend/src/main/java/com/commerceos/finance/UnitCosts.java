package com.commerceos.finance;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 출고 원가 단가: 이동평균(입고 단가가 있으면) → 없으면 지금 유효한 등록 원가(DLG-035·156). 부가세 제외. */
@Component
public class UnitCosts {
    private final JdbcClient jdbc;

    public UnitCosts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<BigDecimal> current(UUID businessEntityId, UUID skuId) {
        Optional<BigDecimal> avg = jdbc.sql("select avg_unit_cost from sku_moving_cost where business_entity_id = :b and sku_id = :s").param("b", businessEntityId)
                .param("s", skuId).query(BigDecimal.class).optional();
        if (avg.isPresent()) {
            return avg;
        }
        return jdbc.sql("select unit_cost from sku_cost where business_entity_id = :b and sku_id = :s and effective_from <= now() order by effective_from desc limit 1")
                .param("b", businessEntityId).param("s", skuId).query(BigDecimal.class).optional();
    }
}
