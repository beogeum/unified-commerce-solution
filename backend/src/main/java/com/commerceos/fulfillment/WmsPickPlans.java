package com.commerceos.fulfillment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 자체 WMS 피킹 결과 조회(출고 확정·취소가 쓴다). 피킹 계획은 WmsService 가 만든다(M4 O7). */
@Component
class WmsPickPlans {
    private final JdbcClient jdbc;

    WmsPickPlans(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 확보별 피킹한 버킷·수량(결품 작업의 피킹분 포함). */
    Map<UUID, Map<UUID, Integer>> pickPlan(UUID foId) {
        Map<UUID, Map<UUID, Integer>> plan = new LinkedHashMap<>();
        for (Map<String, Object> t : jdbc.sql("select reservation_id, stock_id, picked_qty from wms_pick_task where fo_id = :f and status in ('PICKED', 'SHORT') and picked_qty > 0")
                .param("f", foId).query().listOfRows()) {
            plan.computeIfAbsent((UUID) t.get("reservation_id"), k -> new LinkedHashMap<>()).merge((UUID) t.get("stock_id"), ((Number) t.get("picked_qty")).intValue(),
                    Integer::sum);
        }
        return plan;
    }

    /** 웨이브의 출고지시가 모두 끝나면(출고·취소) 웨이브 종료. */
    void closeIfDone(UUID waveId) {
        if (waveId == null) {
            return;
        }
        jdbc.sql("""
                update wms_wave set status = 'CLOSED', version = version + 1 where id = :w and status in ('PICKING', 'PICKED')
                   and not exists (select 1 from fulfillment_order f where f.wave_id = :w and f.status not in ('SHIPPED', 'CANCELLED'))
                """).param("w", waveId).update();
    }
}
