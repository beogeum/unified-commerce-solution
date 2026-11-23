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

    /** @return 새로 기록했으면 true(같은 source_key 가 이미 있으면 false) */
    public boolean post(Entry e) {
        return jdbc.sql("""
                insert into financial_entry (id, workspace_id, business_entity_id, channel_account_id, order_id, order_item_id, claim_id, fo_id, shipment_id, sku_id, node_id,
                    entry_class, category, basis, supply_amount, vat_amount, tax_type, quantity, unit_cost, period_date, source_type, source_ref, source_key, reverses_id,
                    scope_key, memo, created_by)
                values (:id, :ws, :be, cast(:ca as uuid), cast(:o as uuid), cast(:oi as uuid), cast(:c as uuid), cast(:f as uuid), cast(:s as uuid), cast(:k as uuid),
                    cast(:n as uuid), :cls, :cat, :b, :sup, :vat, :tt, cast(:q as integer), cast(:uc as numeric), :pd, :st, cast(:sr as text), :sk, cast(:rv as uuid),
                    cast(:sc as text), cast(:m as text), cast(:by as uuid))
                on conflict (workspace_id, source_key) do nothing
                """).param("id", Ids.newId()).param("ws", e.workspaceId()).param("be", e.businessEntityId()).param("ca", e.channelAccountId()).param("o", e.orderId())
                .param("oi", e.orderItemId()).param("c", e.claimId()).param("f", e.foId()).param("s", e.shipmentId()).param("k", e.skuId()).param("n", e.nodeId())
                .param("cls", e.entryClass()).param("cat", e.category()).param("b", e.basis()).param("sup", e.supply()).param("vat", e.vat() == null ? BigDecimal.ZERO : e.vat())
                .param("tt", e.taxType() == null ? "TAXABLE" : e.taxType()).param("q", e.quantity()).param("uc", e.unitCost()).param("pd", e.periodDate())
                .param("st", e.sourceType()).param("sr", e.sourceRef()).param("sk", e.sourceKey()).param("rv", e.reversesId()).param("sc", e.scopeKey()).param("m", e.memo())
                .param("by", e.createdBy()).update() > 0;
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
