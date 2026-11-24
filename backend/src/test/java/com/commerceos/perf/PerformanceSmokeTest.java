package com.commerceos.perf;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerceos.finance.FinanceTestBase;
import com.commerceos.support.Api;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 대량 데이터 성능 점검(기본 실행 제외). API 로 만든 실제 주문·출고·원장 행을 서버에서 복제해 수십만 건으로 키운 뒤 주요 조회 API 응답 시간을 잰다.
 * 실행: mvn test -Dtest=PerformanceSmokeTest -Dperf.exclude=none (결과: target/perf-report.txt)
 */
@Tag("perf")
class PerformanceSmokeTest extends FinanceTestBase {
    static final int ORDERS = Integer.getInteger("perf.orders", 200_000);
    static final int ENTRIES = Integer.getInteger("perf.entries", 500_000);
    static final int TXNS = Integer.getInteger("perf.txns", 500_000);
    static final int CUSTOMERS = Integer.getInteger("perf.customers", 500);

    /** 같은 표의 한 행을 n 번 복제(지정 열만 식으로 바꿈). 생성 열은 건너뛴다. */
    int clone(String table, String where, Map<String, Object> params, int n, Map<String, String> overrides) {
        List<String> cols = jdbc.sql("""
                select column_name from information_schema.columns where table_name = :t and table_schema = current_schema() and is_generated = 'NEVER'
                 order by ordinal_position
                """).param("t", table).query(String.class).list();
        List<String> exprs = new ArrayList<>();
        for (String c : cols) {
            exprs.add(overrides.getOrDefault(c, "src." + c));
        }
        String sql = "insert into " + table + " (" + String.join(", ", cols) + ") select " + String.join(", ", exprs) + " from " + table
                + " src cross join generate_series(1, " + n + ") g where " + where;
        var q = jdbc.sql(sql);
        for (var e : params.entrySet()) {
            q = q.param(e.getKey(), e.getValue());
        }
        return q.update();
    }

    long time(Api api, String path) {
        api.get(path); // 첫 호출(계획 캐시·JIT) 제외
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            Api.Res r = api.get(path);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertThat(r.status()).as(path + " " + r.body()).isEqualTo(200);
            best = Math.min(best, ms);
        }
        return best;
    }

    @Test
    void mainReadApis_stayFastWithHundredsOfThousandsOfRows() throws Exception {
        Ctx c = ctx("성능상사");
        Api o = c.o();
        String order = order(c, "PERF-1", new Object[]{"P-100", 1, 33000, 1000, 0, false});
        shipOrder(c, order);
        UUID oid = UUID.fromString(order);
        long t0 = System.nanoTime();
        int so = clone("sales_order", "src.id = :id", Map.of("id", oid), ORDERS, Map.of(
                "id", "gen_random_uuid()", "order_no", "src.order_no || '-' || g", "external_order_key", "src.external_order_key || '-' || g",
                "ordered_at", "src.ordered_at - make_interval(mins => g)", "created_at", "src.created_at - make_interval(mins => g)"));
        int fe = clone("financial_entry", "src.order_id = :id", Map.of("id", oid), ENTRIES / Math.max(1, countOf("financial_entry", oid)), Map.of(
                "id", "gen_random_uuid()", "source_key", "src.source_key || ':' || g", "period_date", "src.period_date - (g % 360)",
                "reverses_id", "null", "scope_key", "null"));
        int tx = clone("inventory_transaction", "src.workspace_id = :ws and src.txn_type = 'CONSUME'", Map.of("ws", c.ws().id()), TXNS, Map.of(
                "id", "gen_random_uuid()", "occurred_at", "src.occurred_at - make_interval(mins => g)", "idempotency_key", "null"));
        jdbc.sql("""
                insert into b2b_customer (id, workspace_id, business_entity_id, code, name, credit_limit)
                select gen_random_uuid(), :ws, :be, 'C' || g, '거래처 ' || g, 1000000 from generate_series(1, :n) g
                """).param("ws", c.ws().id()).param("be", c.be()).param("n", CUSTOMERS).update();
        jdbc.sql("""
                insert into receivable_entry (id, workspace_id, business_entity_id, customer_id, entry_type, amount, occurred_on, due_on, source_key)
                select gen_random_uuid(), c.workspace_id, c.business_entity_id, c.id, 'CHARGE', 10000 + g, current_date - g * 7, current_date - g * 7 + 30,
                       'PERF:' || c.id || ':' || g
                  from b2b_customer c cross join generate_series(1, 10) g where c.workspace_id = :ws
                """).param("ws", c.ws().id()).update();
        jdbc.sql("analyze").update();
        long seedMs = (System.nanoTime() - t0) / 1_000_000;

        String no = jdbc.sql("select order_no from sales_order where id = :id").param("id", oid).query(String.class).single();
        Map<String, Long> t = new LinkedHashMap<>();
        t.put("주문 목록(첫 페이지 50)", time(o, "/api/v1/orders?limit=50"));
        t.put("주문 목록(상태 필터)", time(o, "/api/v1/orders?status=PROCESSING&limit=50"));
        t.put("주문 검색(주문번호)", time(o, "/api/v1/orders?q=" + no + "-77777&limit=50"));
        t.put("주문 검색(일부 문자열)", time(o, "/api/v1/orders?q=77777&limit=50"));
        t.put("재고 원장(첫 페이지 50)", time(o, "/api/v1/inventory/transactions?limit=50"));
        t.put("손익(사업자 전체 기간)", time(o, "/api/v1/finance/profit?business_entity_id=" + c.be()));
        t.put("손익 원장(첫 페이지)", time(o, "/api/v1/finance/entries?business_entity_id=" + c.be()));
        t.put("판매 리포트", time(o, "/api/v1/analytics/sales"));
        t.put("재고 리포트", time(o, "/api/v1/analytics/inventory"));
        t.put("외상 현황(거래처 " + CUSTOMERS + ")", time(o, "/api/v1/receivables"));
        t.put("운영 현황", time(o, "/api/v1/ops/status"));

        StringBuilder sb = new StringBuilder();
        sb.append("데이터: 주문 ").append(so + 1).append(" · 손익 원장 +").append(fe).append(" · 재고 원장 +").append(tx).append(" · 외상 거래처 ").append(CUSTOMERS)
                .append(" × 10 (적재 ").append(seedMs / 1000).append("초)\n");
        t.forEach((k, v) -> sb.append(String.format("%-28s %6d ms%n", k, v)));
        Files.writeString(Path.of("target/perf-report.txt"), sb.toString());
        System.out.println(sb);
    }

    int countOf(String table, UUID orderId) {
        return jdbc.sql("select count(*) from " + table + " where order_id = :id").param("id", orderId).query(Integer.class).single();
    }
}
