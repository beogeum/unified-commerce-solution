package com.commerceos.finance;

import com.commerceos.shared.id.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 관리 손익 원장 기록(M5, DLG-004). 같은 source_key 는 한 번만(재처리 멱등). 금액은 원 단위 정수.
 * 부가세 포함 금액 → 부가세 = |금액| ÷ 11 원 미만 절사, 공급가 = 금액 − 부가세(DLG-030). 면세·영세·과세 밖은 부가세 0.
 */
@Component
public class Ledger {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final JdbcClient jdbc;

    public Ledger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 원장 한 줄. supply/vat 은 부호 포함. */
    public record Entry(UUID workspaceId, UUID businessEntityId, UUID channelAccountId, UUID orderId, UUID orderItemId, UUID claimId, UUID foId, UUID shipmentId,
                        UUID skuId, UUID nodeId, String entryClass, String category, String basis, BigDecimal supply, BigDecimal vat, String taxType, Integer quantity,
                        BigDecimal unitCost, LocalDate periodDate, String sourceType, String sourceRef, String sourceKey, UUID reversesId, String scopeKey, String memo,
                        UUID createdBy) {
    }

    /**
     * @return 새로 기록했으면 true(같은 source_key 가 이미 있으면 false).
     * 귀속 월이 마감됐으면 다음 열린 달 1일로 귀속하고 원래 귀속일을 남긴다(DLG-036·157, T32). 마감 원장은 바뀌지 않는다.
     */
    public boolean post(Entry e) {
        LocalDate period = e.periodDate();
        LocalDate original = null;
        LocalDate open = openPeriod(e.businessEntityId(), period);
        if (!open.equals(period)) {
            original = period;
            period = open;
        }
        String memo = original == null ? e.memo() : ("마감 후 발생(원 귀속 " + original.toString().substring(0, 7) + ")" + (e.memo() == null ? "" : " · " + e.memo()));
        return jdbc.sql("""
                insert into financial_entry (id, workspace_id, business_entity_id, channel_account_id, order_id, order_item_id, claim_id, fo_id, shipment_id, sku_id, node_id,
                    entry_class, category, basis, supply_amount, vat_amount, tax_type, quantity, unit_cost, period_date, source_type, source_ref, source_key, reverses_id,
                    scope_key, memo, created_by, original_period_date)
                values (:id, :ws, :be, cast(:ca as uuid), cast(:o as uuid), cast(:oi as uuid), cast(:c as uuid), cast(:f as uuid), cast(:s as uuid), cast(:k as uuid),
                    cast(:n as uuid), :cls, :cat, :b, :sup, :vat, :tt, cast(:q as integer), cast(:uc as numeric), :pd, :st, cast(:sr as text), :sk, cast(:rv as uuid),
                    cast(:sc as text), cast(:m as text), cast(:by as uuid), cast(:op as date))
                on conflict (workspace_id, source_key) do nothing
                """).param("id", Ids.newId()).param("ws", e.workspaceId()).param("be", e.businessEntityId()).param("ca", e.channelAccountId()).param("o", e.orderId())
                .param("oi", e.orderItemId()).param("c", e.claimId()).param("f", e.foId()).param("s", e.shipmentId()).param("k", e.skuId()).param("n", e.nodeId())
                .param("cls", e.entryClass()).param("cat", e.category()).param("b", e.basis()).param("sup", e.supply()).param("vat", e.vat() == null ? BigDecimal.ZERO : e.vat())
                .param("tt", e.taxType() == null ? "TAXABLE" : e.taxType()).param("q", e.quantity()).param("uc", e.unitCost()).param("pd", period).param("op", original)
                .param("st", e.sourceType()).param("sr", e.sourceRef()).param("sk", e.sourceKey()).param("rv", e.reversesId()).param("sc", e.scopeKey()).param("m", memo)
                .param("by", e.createdBy()).update() > 0;
    }

    /** 마감되지 않은 가장 이른 날(해당 날짜가 속한 달이 마감이면 다음 달 1일, 연속 마감이면 그 뒤). */
    public LocalDate openPeriod(java.util.UUID be, LocalDate date) {
        LocalDate d = date;
        for (int i = 0; i < 240; i++) {
            Integer closed = jdbc.sql("select count(*) from accounting_period where business_entity_id = :b and month = :m and status = 'CLOSED'").param("b", be)
                    .param("m", d.withDayOfMonth(1)).query(Integer.class).single();
            if (closed == 0) {
                return d;
            }
            d = d.withDayOfMonth(1).plusMonths(1);
        }
        return d;
    }

    /** 부가세 포함 금액을 [공급가, 부가세] 로. */
    static BigDecimal[] split(BigDecimal gross, String taxType) {
        if (taxType != null && !"TAXABLE".equals(taxType)) {
            return new BigDecimal[]{gross, BigDecimal.ZERO};
        }
        BigDecimal vat = gross.abs().divide(BigDecimal.valueOf(11), 0, RoundingMode.DOWN);
        if (gross.signum() < 0) {
            vat = vat.negate();
        }
        return new BigDecimal[]{gross.subtract(vat), vat};
    }

    static LocalDate period(Instant at) {
        return (at == null ? Instant.now() : at).atZone(KST).toLocalDate();
    }

    static BigDecimal won(BigDecimal v) {
        return v.setScale(0, RoundingMode.HALF_UP);
    }
}
