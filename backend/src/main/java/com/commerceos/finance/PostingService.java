package com.commerceos.finance;

import com.commerceos.operations.WorkItemService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영 사건 → 관리 손익 원장 자동 기록(M5 N1·N2·N5, docs/05, DLG-035·051·153~160). 모두 멱등(source_key).
 * <ul>
 *   <li>출고 확정: 출고 단위만큼 매출(상품 금액·판매자 할인·채널 할인 메모), 첫 출고에 고객 배송비 매출, 구성품 원가(이동평균, 사은품은 판촉비 GWP), 계약요율 예상 비용.</li>
 *   <li>반품 검수: 정상·재포장 판정분만 원가 환입(출고 당시 단가), 불량·파손·만료·폐기대기는 장부가 0 기록(원가는 매출원가에 남김 → 폐기 때 다시 손실로 잡지 않음), 미확정은 보류.
 *       반품 클레임 회수 검수가 끝나면 반품 매출 차감·환급 수수료 반영, 회수비 예상, 고객 부담 반품 배송비 수익.</li>
 *   <li>교환 완료: 차액(±)을 교환 차액 매출로. 교환품 원가는 그 출고에서.</li>
 *   <li>폐기 승인: 장부가 남은 수량만 폐기손실.</li>
 * </ul>
 */
@Service
public class PostingService {
    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final UnitCosts costs;
    private final CostRuleService rules;
    private final WorkItemService workItems;

    public PostingService(JdbcClient jdbc, Ledger ledger, UnitCosts costs, CostRuleService rules, WorkItemService workItems) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.costs = costs;
        this.rules = rules;
        this.workItems = workItems;
    }

    // ---------------------------------------------------------------- 출고

    @Transactional(propagation = Propagation.MANDATORY)
    public void onShipment(UUID ws, UUID foId) {
        Map<String, Object> f = jdbc.sql("""
                select f.*, o.order_no, o.shipping_fee, o.source_type, o.channel_account_id as order_ca from fulfillment_order f join sales_order o on o.id = f.order_id
                 where f.workspace_id = :ws and f.id = :id and f.status = 'SHIPPED'
                """).param("ws", ws).param("id", foId).query().listOfRows().stream().findFirst().orElse(null);
        if (f == null) {
            return;
        }
        UUID be = (UUID) f.get("business_entity_id");
        UUID ca = (UUID) f.get("channel_account_id");
        UUID orderId = (UUID) f.get("order_id");
        UUID node = (UUID) f.get("node_id");
        Instant at = instant(f.get("shipped_at"));
        LocalDate period = Ledger.period(at);
        String foRef = (String) f.get("fo_no");
        List<Map<String, Object>> lines = jdbc.sql("""
                select l.order_item_id, l.sku_id, l.quantity, c.quantity_per_unit, i.original_qty, i.line_amount, i.seller_discount, i.channel_discount, i.tax_type,
                       (i.is_gift or exists (select 1 from sales_order so where so.id = i.order_id and so.order_kind = 'SAMPLE')) as is_gift, i.item_kind,
                       i.claim_id, i.sku_id as item_sku
                  from fulfillment_order_line l join order_item i on i.id = l.order_item_id
                  join order_item_component c on c.order_item_id = l.order_item_id and c.component_sku_id = l.sku_id
                 where l.fo_id = :f order by l.line_no
                """).param("f", foId).query().listOfRows();
        // 원가(구성품 단위)
        for (Map<String, Object> l : lines) {
            UUID item = (UUID) l.get("order_item_id");
            UUID sku = (UUID) l.get("sku_id");
            int qty = ((Number) l.get("quantity")).intValue();
            Optional<BigDecimal> unit = costs.current(be, sku);
            boolean gift = Boolean.TRUE.equals(l.get("is_gift"));
            BigDecimal amount = unit.map(u -> Ledger.won(u.multiply(BigDecimal.valueOf(qty)))).orElse(BigDecimal.ZERO);
            ledger.post(new Ledger.Entry(ws, be, ca, orderId, item, (UUID) l.get("claim_id"), foId, null, sku, node, "COST", gift ? "GWP" : "PRODUCT_COGS",
                    unit.isPresent() ? "ACTUAL" : "ESTIMATED", amount, BigDecimal.ZERO, "OUT_OF_SCOPE", qty, unit.orElse(null), period, "SHIPMENT", foRef,
                    "SHIP:" + foId + ":COGS:" + item + ":" + sku, null, null, unit.isPresent() ? null : "원가 미등록 — 원가 등록 후 조정 필요", null));
            if (unit.isEmpty()) {
                workItems.raise(new WorkItemService.Raise(ws, be, null, null, "FINANCE_COST_MISSING", "MEDIUM", "출고 원가 미등록 SKU",
                        "원가가 등록되지 않은 SKU 가 출고되어 매출원가를 0 원(예상)으로 기록했습니다.", "SKU 원가 또는 입고 단가를 등록하고 원가 조정을 기록하세요.",
                        "cost-missing:" + be + ":" + sku, Map.of("sku_id", sku.toString())));
            }
        }
        // 매출(주문상품 단위: 구성품이 모두 출고된 단위)
        Map<UUID, Integer> units = new java.util.LinkedHashMap<>();
        Map<UUID, Map<String, Object>> items = new java.util.LinkedHashMap<>();
        for (Map<String, Object> l : lines) {
            UUID item = (UUID) l.get("order_item_id");
            int u = ((Number) l.get("quantity")).intValue() / ((Number) l.get("quantity_per_unit")).intValue();
            units.merge(item, u, Math::min);
            items.putIfAbsent(item, l);
        }
        BigDecimal netSupplyTotal = BigDecimal.ZERO;
        int unitTotal = 0;
        for (var e : units.entrySet()) {
            UUID item = e.getKey();
            int u = e.getValue();
            Map<String, Object> l = items.get(item);
            if (u <= 0 || Boolean.TRUE.equals(l.get("is_gift")) || "EXCHANGE_REPLACEMENT".equals(l.get("item_kind"))) {
                continue;
            }
            int original = ((Number) l.get("original_qty")).intValue();
            String tax = l.get("tax_type") == null ? "TAXABLE" : (String) l.get("tax_type");
            UUID sku = (UUID) l.get("item_sku");
            BigDecimal sales = shareOfOriginal(item, "SALES", (BigDecimal) l.get("line_amount"), u, original);
            BigDecimal disc = shareOfOriginal(item, "SELLER_DISCOUNT", (BigDecimal) l.get("seller_discount"), u, original);
            BigDecimal chDisc = shareOfOriginal(item, "CHANNEL_DISCOUNT", (BigDecimal) l.get("channel_discount"), u, original);
            BigDecimal[] s = Ledger.split(sales, tax);
            BigDecimal[] d = Ledger.split(disc.negate(), tax);
            String key = "SHIP:" + foId + ":" + item;
            ledger.post(new Ledger.Entry(ws, be, ca, orderId, item, null, foId, null, sku, node, "REVENUE", "SALES", "ACTUAL", s[0], s[1], tax, u, null, period, "SHIPMENT",
                    foRef, key + ":SALES", null, null, null, null));
            if (disc.signum() != 0) {
                ledger.post(new Ledger.Entry(ws, be, ca, orderId, item, null, foId, null, sku, node, "REVENUE", "SELLER_DISCOUNT", "ACTUAL", d[0], d[1], tax, u, null,
                        period, "SHIPMENT", foRef, key + ":SELLER_DISCOUNT", null, null, null, null));
            }
            if (chDisc.signum() != 0) {
                BigDecimal[] cd = Ledger.split(chDisc, tax);
                ledger.post(new Ledger.Entry(ws, be, ca, orderId, item, null, foId, null, sku, node, "MEMO", "CHANNEL_DISCOUNT", "ACTUAL", cd[0], cd[1], tax, u, null,
                        period, "SHIPMENT", foRef, key + ":CHANNEL_DISCOUNT", null, null, "채널 부담 할인(채널 보전 대상, 판매자 매출 감액 아님)", null));
            }
            BigDecimal net = s[0].add(d[0]);
            netSupplyTotal = netSupplyTotal.add(net);
            unitTotal += u;
            // 판매·결제 수수료(주문상품별: 반품 환급 계산에 쓴다)
            for (String cat : List.of("SELLING_FEE", "PAYMENT_FEE")) {
                Optional<CostRuleService.Rule> r = rules.find(ws, be, ca, null, cat, at);
                if (r.isEmpty()) {
                    continue;
                }
                BigDecimal fee = switch (r.get().method()) {
                    case "RATE_OF_NET_SALES" -> Ledger.won(net.multiply(BigDecimal.valueOf(r.get().rateBps())).divide(BigDecimal.valueOf(10000), 4, RoundingMode.HALF_UP));
                    case "PER_UNIT" -> r.get().amount().multiply(BigDecimal.valueOf(u));
                    default -> null;
                };
                if (fee != null && fee.signum() != 0) {
                    ledger.post(new Ledger.Entry(ws, be, ca, orderId, item, null, foId, null, sku, node, "COST", cat, "ESTIMATED", fee, BigDecimal.ZERO, "OUT_OF_SCOPE", u,
                            null, period, "SHIPMENT", foRef, key + ":" + cat, null, cat + ":ITEM:" + item, "계약요율 예상", null));
                }
            }
        }
        // 고객 배송비 매출(주문 첫 출고 때 한 번)
        BigDecimal shipFee = (BigDecimal) f.get("shipping_fee");
        if (shipFee != null && shipFee.signum() > 0) {
            BigDecimal[] sf = Ledger.split(shipFee, "TAXABLE");
            ledger.post(new Ledger.Entry(ws, be, ca, orderId, null, null, foId, null, null, node, "REVENUE", "SHIPPING_FEE", "ACTUAL", sf[0], sf[1], "TAXABLE", null, null,
                    period, "SHIPMENT", foRef, "SHIPFEE:" + orderId, null, null, "고객 부담 배송비", null));
        }
        // 출고 물류 예상 비용(출고지시 범위 — 3PL·택배 청구가 오면 같은 범위를 대체, T26)
        int parcels = jdbc.sql("select count(*) from shipment where fo_id = :f").param("f", foId).query(Integer.class).single();
        int componentUnits = lines.stream().mapToInt(l -> ((Number) l.get("quantity")).intValue()).sum();
        // 포장 부자재를 이 출고에서 차감했으면 부자재 원가는 차감 기록(고정 단가)에서만 — MATERIAL 규칙과 중복하지 않는다(CHG-011, DLG-248)
        List<Map<String, Object>> mats = jdbc.sql("select sku_id, required_qty, shortfall_qty, unit_cost from fo_material_consumption where fo_id = :f order by sku_id")
                .param("f", foId).query().listOfRows();
        for (Map<String, Object> m : mats) {
            BigDecimal unit = (BigDecimal) m.get("unit_cost");
            int q = ((Number) m.get("required_qty")).intValue();
            boolean actual = unit != null && ((Number) m.get("shortfall_qty")).intValue() == 0;
            BigDecimal amt = unit == null ? BigDecimal.ZERO : Ledger.won(unit.multiply(BigDecimal.valueOf(q)));
            ledger.post(new Ledger.Entry(ws, be, ca, orderId, null, null, foId, null, (UUID) m.get("sku_id"), node, "COST", "MATERIAL", actual ? "ACTUAL" : "ESTIMATED", amt,
                    BigDecimal.ZERO, "OUT_OF_SCOPE", q, unit, period, "SHIPMENT", foRef, "SHIP:" + foId + ":MATERIAL:" + m.get("sku_id"), null, null,
                    unit == null ? "부자재 원가 미등록" : actual ? "포장 부자재 차감" : "부자재 재고 부족분 포함(필요 수량 기준)", null));
        }
        for (String cat : List.of("OUTBOUND_SHIPPING", "THIRD_PARTY_PICK", "PACK", "MATERIAL")) {
            if ("MATERIAL".equals(cat) && !mats.isEmpty()) {
                continue;
            }
            Optional<CostRuleService.Rule> r = rules.find(ws, be, ca, node, cat, at);
            if (r.isEmpty()) {
                continue;
            }
            BigDecimal amt = switch (r.get().method()) {
                case "PER_SHIPMENT" -> r.get().amount().multiply(BigDecimal.valueOf(Math.max(1, parcels)));
                case "PER_ORDER" -> r.get().amount();
                case "PER_UNIT" -> r.get().amount().multiply(BigDecimal.valueOf(componentUnits));
                case "RATE_OF_NET_SALES" -> Ledger.won(netSupplyTotal.multiply(BigDecimal.valueOf(r.get().rateBps())).divide(BigDecimal.valueOf(10000), 4, RoundingMode.HALF_UP));
                default -> null;
            };
            if (amt != null && amt.signum() != 0) {
                ledger.post(new Ledger.Entry(ws, be, ca, orderId, null, null, foId, null, null, node, "COST", cat, "ESTIMATED", amt, BigDecimal.ZERO, "OUT_OF_SCOPE",
                        null, null, period, "SHIPMENT", foRef, "SHIP:" + foId + ":" + cat, null, cat + ":FO:" + foId, "계약요율 예상", null));
            }
        }
    }

    // ---------------------------------------------------------------- 반품 검수

    @Transactional(propagation = Propagation.MANDATORY)
    public void onReturnInspected(UUID ws, UUID receiptId) {
        Map<String, Object> rc = jdbc.sql("select * from return_receipt where workspace_id = :ws and id = :id").param("ws", ws).param("id", receiptId).query().listOfRows()
                .stream().findFirst().orElse(null);
        if (rc == null || rc.get("claim_id") == null) {
            return; // 원주문 모르는 반품은 원가·매출 연결 없음(확인 후 재고 조정으로)
        }
        UUID claimId = (UUID) rc.get("claim_id");
        Map<String, Object> c = jdbc.sql("select * from claim where id = :c").param("c", claimId).query().listOfRows().get(0);
        UUID be = (UUID) c.get("business_entity_id");
        UUID ca = (UUID) c.get("channel_account_id");
        UUID orderId = (UUID) c.get("order_id");
        String claimNo = (String) c.get("claim_no");
        for (Map<String, Object> in : jdbc.sql("""
                select ri.*, l.sku_id, l.claim_item_id, ci.order_item_id,
                       (i.is_gift or exists (select 1 from sales_order so where so.id = i.order_id and so.order_kind = 'SAMPLE')) as is_gift from return_inspection ri join return_receipt_line l on l.id = ri.receipt_line_id
                  join claim_item ci on ci.id = l.claim_item_id join order_item i on i.id = ci.order_item_id
                 where l.receipt_id = :r order by ri.inspected_at
                """).param("r", receiptId).query().listOfRows()) {
            String cond = (String) in.get("condition");
            UUID sku = (UUID) in.get("sku_id");
            UUID item = (UUID) in.get("order_item_id");
            int qty = ((Number) in.get("quantity")).intValue();
            BigDecimal unit = shippedUnitCost(item, sku).or(() -> costs.current(be, sku)).orElse(BigDecimal.ZERO);
            LocalDate period = Ledger.period(instant(in.get("inspected_at")));
            UUID inspectionId = (UUID) in.get("id");
            boolean gift = Boolean.TRUE.equals(in.get("is_gift"));
            switch (cond) {
                case "SELLABLE", "REPACK" -> ledger.post(new Ledger.Entry(ws, be, ca, orderId, item, claimId, null, null, sku, (UUID) rc.get("node_id"), "COST",
                        gift ? "GWP" : "PRODUCT_COGS", "ACTUAL", Ledger.won(unit.multiply(BigDecimal.valueOf(qty))).negate(), BigDecimal.ZERO, "OUT_OF_SCOPE", -qty, unit,
                        period, "RETURN_INSPECTION", claimNo, "RETCOGS:" + inspectionId, null, null, "반품 정상 회수 원가 환입", null));
                case "UNDETERMINED" -> {
                    // 판정 확정 전: 보류
                }
                default -> {
                    if (in.get("to_stock_id") != null) {
                        jdbc.sql("""
                                insert into inventory_valuation_entry (id, workspace_id, business_entity_id, sku_id, node_id, stock_id, entry_type, quantity, unit_cost, loss_amount,
                                                                       reason, source_key)
                                values (gen_random_uuid(), :ws, :be, :s, :n, :st, 'ZERO_BOOK_RETURN', :q, :u, 0, :r, :k) on conflict (workspace_id, source_key) do nothing
                                """).param("ws", ws).param("be", be).param("s", sku).param("n", rc.get("node_id")).param("st", in.get("to_stock_id")).param("q", qty)
                                .param("u", unit).param("r", "반품 " + cond + " — 원가 환입 없음(매출원가에 유지), 장부가 0").param("k", "ZB:" + inspectionId).update();
                    }
                }
            }
        }
        if ("INSPECTED".equals(c.get("return_status"))) {
            onReturnCompleted(ws, c);
        }
    }

    /** 반품 확정(회수 검수 완료): 반품 매출 차감·판매자 할인 환원·환급 수수료, 회수비 예상, 고객 부담 반품 배송비 수익. 교환은 원매출 유지. */
    private void onReturnCompleted(UUID ws, Map<String, Object> c) {
        UUID claimId = (UUID) c.get("id");
        UUID be = (UUID) c.get("business_entity_id");
        UUID ca = (UUID) c.get("channel_account_id");
        UUID orderId = (UUID) c.get("order_id");
        String claimNo = (String) c.get("claim_no");
        LocalDate period = Ledger.period(Instant.now());
        if ("RETURN".equals(c.get("claim_type"))) {
            for (Map<String, Object> ci : jdbc.sql("""
                    select ci.*, i.tax_type, i.sku_id as item_sku,
                           (i.is_gift or exists (select 1 from sales_order so where so.id = i.order_id and so.order_kind = 'SAMPLE')) as is_gift from claim_item ci join order_item i on i.id = ci.order_item_id where ci.claim_id = :c order by ci.line_no
                    """).param("c", claimId).query().listOfRows()) {
                UUID item = (UUID) ci.get("order_item_id");
                int q = ((Number) ci.get("quantity")).intValue();
                String tax = ci.get("tax_type") == null ? "TAXABLE" : (String) ci.get("tax_type");
                String key = "SRET:" + ci.get("id");
                for (String cat : List.of("SALES", "SELLER_DISCOUNT", "CHANNEL_DISCOUNT")) {
                    BigDecimal[] part = reverseShare(item, cat, q);
                    if (part == null || (part[0].signum() == 0 && part[1].signum() == 0)) {
                        continue;
                    }
                    String outCat = "SALES".equals(cat) ? "SALES_RETURN" : cat;
                    String cls = "CHANNEL_DISCOUNT".equals(cat) ? "MEMO" : "REVENUE";
                    ledger.post(new Ledger.Entry(ws, be, ca, orderId, item, claimId, null, null, (UUID) ci.get("item_sku"), null, cls, outCat, "ACTUAL", part[0].negate(),
                            part[1].negate(), tax, -q, null, period, "CLAIM", claimNo, key + ":" + cat, null, null, "반품 확정", null));
                }
                for (String cat : List.of("SELLING_FEE", "PAYMENT_FEE")) {
                    Optional<CostRuleService.Rule> r = rules.find(ws, be, ca, null, cat, Instant.now());
                    if (r.isPresent() && !r.get().refundableOnReturn()) {
                        continue;
                    }
                    BigDecimal[] fee = reverseShare(item, cat, q);
                    if (fee == null || fee[0].signum() == 0) {
                        continue;
                    }
                    ledger.post(new Ledger.Entry(ws, be, ca, orderId, item, claimId, null, null, (UUID) ci.get("item_sku"), null, "COST", cat, "ESTIMATED", fee[0].negate(),
                            BigDecimal.ZERO, "OUT_OF_SCOPE", -q, null, period, "CLAIM", claimNo, key + ":" + cat, null, cat + ":ITEM:" + item, "반품분 수수료 환급(예상)", null));
                }
            }
        }
        Optional<CostRuleService.Rule> r = rules.find(ws, be, ca, null, "RETURN_SHIPPING", Instant.now());
        if (r.isPresent() && r.get().amount() != null && r.get().amount().signum() > 0) {
            ledger.post(new Ledger.Entry(ws, be, ca, orderId, null, claimId, null, null, null, null, "COST", "RETURN_SHIPPING", "ESTIMATED", r.get().amount(),
                    BigDecimal.ZERO, "OUT_OF_SCOPE", null, null, period, "CLAIM", claimNo, "RETSHIP:" + claimId, null, "RETURN_SHIPPING:CLAIM:" + claimId, "회수비 예상", null));
        }
        BigDecimal fee = (BigDecimal) c.get("return_shipping_fee");
        if (fee != null && fee.signum() > 0) {
            BigDecimal[] sf = Ledger.split(fee, "TAXABLE");
            ledger.post(new Ledger.Entry(ws, be, ca, orderId, null, claimId, null, null, null, null, "REVENUE", "CUSTOMER_RETURN_FEE", "ACTUAL", sf[0], sf[1], "TAXABLE",
                    null, null, period, "CLAIM", claimNo, "RETFEE:" + claimId, null, null, "고객 부담 반품 배송비", null));
        }
    }

    // ---------------------------------------------------------------- 교환 완료

    @Transactional(propagation = Propagation.MANDATORY)
    public void onClaimCompleted(UUID ws, UUID claimId) {
        Map<String, Object> c = jdbc.sql("select * from claim where workspace_id = :ws and id = :id").param("ws", ws).param("id", claimId).query().listOfRows().stream()
                .findFirst().orElse(null);
        if (c == null || !"EXCHANGE".equals(c.get("claim_type"))) {
            return;
        }
        BigDecimal diff = (BigDecimal) c.get("price_difference");
        if (diff == null || diff.signum() == 0) {
            return;
        }
        BigDecimal[] s = Ledger.split(diff, "TAXABLE");
        ledger.post(new Ledger.Entry(ws, (UUID) c.get("business_entity_id"), (UUID) c.get("channel_account_id"), (UUID) c.get("order_id"), null, claimId, null, null,
                null, null, "REVENUE", "EXCHANGE_PRICE_DIFFERENCE", "ACTUAL", s[0], s[1], "TAXABLE", null, null, Ledger.period(instant(c.get("completed_at"))), "CLAIM",
                (String) c.get("claim_no"), "EXDIFF:" + claimId, null, null, diff.signum() > 0 ? "교환 차액 고객 추가 결제" : "교환 차액 환불", null));
    }

    // ---------------------------------------------------------------- 폐기

    @Transactional(propagation = Propagation.MANDATORY)
    public void onDisposed(UUID ws, UUID approvalId, UUID stockId, int qty) {
        Map<String, Object> s = jdbc.sql("select owner_business_entity_id, sku_id, node_id from inventory_stock where workspace_id = :ws and id = :id").param("ws", ws)
                .param("id", stockId).query().listOfRows().stream().findFirst().orElse(null);
        if (s == null) {
            return;
        }
        UUID be = (UUID) s.get("owner_business_entity_id");
        UUID sku = (UUID) s.get("sku_id");
        int pool = impairedPool(stockId);
        int pre = Math.min(qty, pool);
        BigDecimal unit = costs.current(be, sku).orElse(BigDecimal.ZERO);
        BigDecimal loss = Ledger.won(unit.multiply(BigDecimal.valueOf(qty - pre)));
        boolean inserted = jdbc.sql("""
                insert into inventory_valuation_entry (id, workspace_id, business_entity_id, sku_id, node_id, stock_id, entry_type, quantity, unit_cost, loss_amount,
                                                       pre_impaired_qty, reason, source_key)
                values (gen_random_uuid(), :ws, :be, :s, :n, :st, 'DISPOSAL', :q, :u, :l, :p, :r, :k) on conflict (workspace_id, source_key) do nothing
                """).param("ws", ws).param("be", be).param("s", sku).param("n", s.get("node_id")).param("st", stockId).param("q", qty).param("u", unit).param("l", loss)
                .param("p", pre).param("r", "폐기(이미 평가손실·장부가 0 수량 " + pre + "개는 다시 손실로 잡지 않음)").param("k", "DISP:" + approvalId).update() > 0;
        if (inserted && loss.signum() > 0) {
            ledger.post(new Ledger.Entry(ws, be, null, null, null, null, null, null, sku, (UUID) s.get("node_id"), "COST", "INVENTORY_IMPAIRMENT", "ACTUAL", loss,
                    BigDecimal.ZERO, "OUT_OF_SCOPE", qty - pre, unit, Ledger.period(Instant.now()), "INVENTORY_DISPOSAL", approvalId.toString(), "DISP:" + approvalId, null,
                    null, "폐기손실(장부가 기준)", null));
        }
    }

    // ---------------------------------------------------------------- 사업자 간 재고 이전(T35)

    /**
     * 보내는 사업자에 내부 매출(공급가·부가세)과 내부 매출원가(승인 시점 단가)를 내부거래로 기록(DLG-033·034·174).
     * 받는 사업자는 손익이 아니라 재고 자산(이전 단가로 이동평균)이므로 원장 행이 없다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onIntercompanyTransfer(UUID ws, UUID transferId) {
        Map<String, Object> t = jdbc.sql("select * from intercompany_transfer where workspace_id = :ws and id = :id and status = 'COMPLETED'").param("ws", ws)
                .param("id", transferId).query().listOfRows().stream().findFirst().orElse(null);
        if (t == null) {
            return;
        }
        UUID from = (UUID) t.get("from_business_entity_id");
        UUID to = (UUID) t.get("to_business_entity_id");
        UUID sku = (UUID) t.get("sku_id");
        UUID node = (UUID) t.get("node_id");
        int qty = ((Number) t.get("quantity")).intValue();
        LocalDate period = Ledger.period(instant(t.get("completed_at")));
        String ref = transferId.toString();
        String tax = (String) t.get("tax_type");
        ledger.post(new Ledger.Entry(ws, from, null, null, null, null, null, null, sku, node, "REVENUE", "SALES", "ACTUAL", (BigDecimal) t.get("supply_amount"),
                (BigDecimal) t.get("vat_amount"), tax, qty, null, period, "INTERCOMPANY_TRANSFER", ref, "ICT:" + transferId + ":SALES", null, null,
                "사업자 간 재고 이전(내부 매출, 세금계산서 발행 대상)", null, true, to));
        BigDecimal unit = (BigDecimal) t.get("unit_cost");
        ledger.post(new Ledger.Entry(ws, from, null, null, null, null, null, null, sku, node, "COST", "PRODUCT_COGS", unit == null ? "ESTIMATED" : "ACTUAL",
                unit == null ? BigDecimal.ZERO : Ledger.won(unit.multiply(BigDecimal.valueOf(qty))), BigDecimal.ZERO, "OUT_OF_SCOPE", qty, unit, period,
                "INTERCOMPANY_TRANSFER", ref, "ICT:" + transferId + ":COGS", null, null, "사업자 간 재고 이전(내부 매출원가)", null, true, to));
        if (unit == null) {
            workItems.raise(new WorkItemService.Raise(ws, from, null, null, "FINANCE_COST_MISSING", "MEDIUM", "사업자 간 이전 원가 미등록 SKU",
                    "원가가 없는 SKU 를 다른 사업자로 이전해 내부 매출원가를 0 으로 기록했습니다.", "원가를 등록한 뒤 비용 등록으로 보정하세요.", "cost-missing:" + from + ":" + sku,
                    Map.of("sku_id", sku.toString())));
        }
    }

    /** 이 버킷에서 장부가가 이미 0 인(평가손실·불량 반품) 아직 폐기되지 않은 수량. */
    int impairedPool(UUID stockId) {
        return jdbc.sql("""
                select coalesce(sum(case when entry_type in ('ZERO_BOOK_RETURN', 'IMPAIRMENT') then quantity else 0 end), 0)
                     - coalesce(sum(case when entry_type = 'DISPOSAL' then pre_impaired_qty else 0 end), 0)
                  from inventory_valuation_entry where stock_id = :s
                """).param("s", stockId).query(Integer.class).single();
    }

    // ---------------------------------------------------------------- 내부

    /**
     * 주문상품 금액 중 이번 출고 단위 몫(DLG-030: 원 미만 반올림, 마지막 수량이 나머지를 가져 합 = 원금액).
     */
    private BigDecimal shareOfOriginal(UUID item, String category, BigDecimal amount, int units, int original) {
        if (amount == null || amount.signum() == 0) {
            return BigDecimal.ZERO;
        }
        Map<String, Object> done = jdbc.sql("""
                select coalesce(sum(quantity), 0) as q, coalesce(sum(supply_amount + vat_amount), 0) as a from financial_entry
                 where order_item_id = :i and category = :c and source_type = 'SHIPMENT'
                """).param("i", item).param("c", category).query().listOfRows().get(0);
        int doneQ = ((Number) done.get("q")).intValue();
        BigDecimal doneA = ((BigDecimal) done.get("a")).abs();
        if (doneQ + units >= original) {
            return amount.subtract(doneA);
        }
        return Ledger.won(amount.multiply(BigDecimal.valueOf(units)).divide(BigDecimal.valueOf(original), 4, RoundingMode.HALF_UP));
    }

    /** 출고로 인식한 금액 중 반품 수량 몫 [공급가, 부가세](양수 기준). 마지막 반품 수량이 나머지. */
    private BigDecimal[] reverseShare(UUID item, String category, int q) {
        Map<String, Object> rec = jdbc.sql("""
                select coalesce(sum(quantity) filter (where source_type = 'SHIPMENT'), 0) as sq,
                       coalesce(sum(supply_amount) filter (where source_type = 'SHIPMENT'), 0) as ss, coalesce(sum(vat_amount) filter (where source_type = 'SHIPMENT'), 0) as sv,
                       coalesce(-sum(quantity) filter (where source_type = 'CLAIM'), 0) as rq,
                       coalesce(-sum(supply_amount) filter (where source_type = 'CLAIM'), 0) as rs, coalesce(-sum(vat_amount) filter (where source_type = 'CLAIM'), 0) as rv
                  from financial_entry where order_item_id = :i and category in (:c, :c2) and reverses_id is null
                """).param("i", item).param("c", category).param("c2", "SALES".equals(category) ? "SALES_RETURN" : category).query().listOfRows().get(0);
        int sq = ((Number) rec.get("sq")).intValue();
        if (sq <= 0) {
            return null;
        }
        BigDecimal ss = (BigDecimal) rec.get("ss");
        BigDecimal sv = (BigDecimal) rec.get("sv");
        int rq = ((Number) rec.get("rq")).intValue();
        if (rq + q >= sq) {
            return new BigDecimal[]{ss.subtract((BigDecimal) rec.get("rs")), sv.subtract((BigDecimal) rec.get("rv"))};
        }
        BigDecimal ratio = BigDecimal.valueOf(q).divide(BigDecimal.valueOf(sq), 10, RoundingMode.HALF_UP);
        BigDecimal gross = Ledger.won(ss.add(sv).multiply(ratio));
        BigDecimal vat = sv.signum() == 0 ? BigDecimal.ZERO : Ledger.split(gross, "TAXABLE")[1];
        return new BigDecimal[]{gross.subtract(vat), vat};
    }

    /** 출고 당시 원가 단가(원가 스냅샷). */
    private Optional<BigDecimal> shippedUnitCost(UUID item, UUID sku) {
        return jdbc.sql("""
                select sum(supply_amount) / nullif(sum(quantity), 0) from financial_entry
                 where order_item_id = :i and sku_id = :s and category in ('PRODUCT_COGS', 'GWP') and source_type = 'SHIPMENT' and quantity > 0
                """).param("i", item).param("s", sku).query(BigDecimal.class).optional().filter(x -> x != null && x.signum() > 0);
    }

    static Instant instant(Object v) {
        if (v == null) {
            return Instant.now();
        }
        if (v instanceof java.sql.Timestamp t) {
            return t.toInstant();
        }
        if (v instanceof java.time.OffsetDateTime o) {
            return o.toInstant();
        }
        return (Instant) v;
    }
}
