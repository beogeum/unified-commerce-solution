package com.commerceos.order;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.error.Errors;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수동·B2B·샘플 주문(FR-ORD-04, DLG-197~200). 화면에서 직접 만든 주문도 채널 주문과 같은 경로(표준 주문 → 원본 보존 → 검증 → 구성 → 확보 → 출고 배정)를 탄다.
 * <ul>
 *   <li>원천유형 MANUAL, 외부 주문키는 시스템 번호(M-날짜-순번). 판매계정·판매단위는 사용자가 고른다(사업자 = 판매계정 귀속).</li>
 *   <li>SKU 를 직접 지정(채널상품 매핑 없음). 수령인·연락처는 채널 주문과 같이 암호화·마스킹.</li>
 *   <li>B2B: 거래처명 기록. 샘플: 금액 0 만 허용, 출고 원가는 판촉비(GWP)로.</li>
 *   <li>채널로 발송처리하지 않는다(채널 반영 대상 아님).</li>
 * </ul>
 */
@Service
public class ManualOrderService {
    public static final String SKU_PREFIX = "SKU:";
    static final Set<String> KINDS = Set.of("MANUAL", "B2B", "SAMPLE");
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final OrderIngestService ingest;
    private final AuditService audit;

    public ManualOrderService(JdbcClient jdbc, AccessGuard guard, OrderIngestService ingest, AuditService audit) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.ingest = ingest;
        this.audit = audit;
    }

    public record Line(UUID skuId, Integer quantity, BigDecimal unitPrice, BigDecimal discount) {
    }

    public record Recipient(String name, String phone, String postalCode, String address1, String address2, String memo) {
    }

    public record NewManualOrder(UUID channelAccountId, UUID salesUnitId, String kind, String customerName, Recipient recipient, BigDecimal shippingFee,
                                 List<Line> lines, String memo) {
    }

    public record Created(UUID orderId, String orderNo, String externalOrderKey, String status, String kind) {
    }

    @Transactional
    public Created create(NewManualOrder in) {
        guard.requirePermission(Perm.ORDER_UPDATE);
        CurrentActor a = guard.actor();
        if (in == null || in.channelAccountId() == null) {
            throw Errors.validation("channel_account_id", "주문을 귀속할 판매계정을 선택하세요.");
        }
        String kind = in.kind() == null ? "MANUAL" : in.kind();
        if (!KINDS.contains(kind)) {
            throw Errors.validation("kind", "주문 종류는 MANUAL(일반 수동)·B2B·SAMPLE(샘플) 중 하나입니다.");
        }
        UUID be = jdbc.sql("select business_entity_id from channel_account where workspace_id = :ws and id = :id and status = 'ACTIVE'").param("ws", a.workspaceId())
                .param("id", in.channelAccountId()).query(UUID.class).optional().orElseThrow(() -> Errors.notFound("CHANNEL_ACCOUNT"));
        guard.checkWrite("CHANNEL_ACCOUNT", in.channelAccountId(), be, in.channelAccountId());
        Map<String, Object> unit = (in.salesUnitId() == null
                ? jdbc.sql("select id, external_unit_key from sales_unit where channel_account_id = :ca and status = 'ACTIVE' order by created_at limit 1").param("ca", in.channelAccountId())
                : jdbc.sql("select id, external_unit_key from sales_unit where channel_account_id = :ca and id = :id").param("ca", in.channelAccountId()).param("id", in.salesUnitId()))
                .query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.validation("sales_unit_id", "이 판매계정의 판매단위를 선택하세요."));
        String customer = in.customerName() == null || in.customerName().isBlank() ? null : in.customerName().trim();
        if ("B2B".equals(kind) && customer == null) {
            throw Errors.validation("customer_name", "B2B 주문은 거래처명을 입력하세요.");
        }
        if (customer != null && customer.length() > 100) {
            throw Errors.validation("customer_name", "거래처명은 100자 이내입니다.");
        }
        Recipient r = in.recipient();
        if (r == null || blank(r.name()) || blank(r.phone()) || blank(r.address1())) {
            throw Errors.validation("recipient", "받는 분 이름·연락처·주소를 입력하세요.");
        }
        List<Line> lines = in.lines() == null ? List.of() : in.lines();
        if (lines.isEmpty() || lines.size() > 100) {
            throw Errors.validation("lines", "주문 상품을 1~100개 입력하세요.");
        }
        BigDecimal ship = in.shippingFee() == null ? BigDecimal.ZERO : in.shippingFee();
        if (ship.signum() < 0 || ship.stripTrailingZeros().scale() > 0) {
            throw Errors.validation("shipping_fee", "배송비를 원 단위로 입력하세요.");
        }
        if ("SAMPLE".equals(kind) && ship.signum() != 0) {
            throw Errors.validation("shipping_fee", "샘플 주문은 금액 0 입니다(배송비는 비용으로 기록).");
        }
        List<OrderInput.Item> items = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        BigDecimal total = ship;
        int no = 0;
        for (Line l : lines) {
            no++;
            if (l == null || l.skuId() == null || !seen.add(l.skuId())) {
                throw Errors.validation("lines", no + "번째 상품의 SKU 가 비었거나 중복입니다.");
            }
            Map<String, Object> sku = jdbc.sql("""
                    select s.sku_code, coalesce(s.name, p.name) as name, s.sellable, s.status from sku s join product p on p.id = s.product_id
                     where s.workspace_id = :ws and s.id = :id
                    """).param("ws", a.workspaceId()).param("id", l.skuId()).query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("SKU"));
            if (!"ACTIVE".equals(sku.get("status")) || !Boolean.TRUE.equals(sku.get("sellable"))) {
                throw Errors.unprocessable("SKU_NOT_SELLABLE", no + "번째 상품(" + sku.get("sku_code") + ")은 판매 가능한 활성 SKU 가 아닙니다.");
            }
            int q = l.quantity() == null ? 0 : l.quantity();
            if (q < 1) {
                throw Errors.validation("lines", no + "번째 상품의 수량은 1 이상입니다.");
            }
            BigDecimal price = l.unitPrice() == null ? BigDecimal.ZERO : l.unitPrice();
            BigDecimal disc = l.discount() == null ? BigDecimal.ZERO : l.discount();
            if (price.signum() < 0 || disc.signum() < 0 || price.stripTrailingZeros().scale() > 0 || disc.stripTrailingZeros().scale() > 0) {
                throw Errors.validation("lines", no + "번째 상품의 단가·할인을 원 단위로 입력하세요.");
            }
            BigDecimal amount = price.multiply(BigDecimal.valueOf(q));
            if (disc.compareTo(amount) > 0) {
                throw Errors.validation("lines", no + "번째 상품의 할인이 금액보다 큽니다.");
            }
            if ("SAMPLE".equals(kind) && amount.signum() != 0) {
                throw Errors.validation("lines", "샘플 주문은 단가 0 입니다(원가는 판촉비로 기록).");
            }
            items.add(new OrderInput.Item(String.valueOf(no), SKU_PREFIX + sku.get("sku_code"), "", (String) sku.get("name"), null, q, 0, price, amount, disc,
                    BigDecimal.ZERO, null, false, null));
            total = total.add(amount).subtract(disc);
        }
        String key = nextKey(a.workspaceId());
        Instant now = Instant.now();
        OrderInput order = new OrderInput(key, "MANUAL", (String) unit.get("external_unit_key"), now, now, "MANUAL_" + kind, now, ship, total,
                new OrderInput.Party(customer, null, null), new OrderInput.Recipient(r.name().trim(), r.phone().trim(), r.postalCode(), r.address1().trim(), r.address2(),
                r.memo()), items);
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("external_order_key", key);
        raw.put("kind", kind);
        raw.put("customer_name", customer);
        raw.put("memo", in.memo());
        raw.put("lines", lines.stream().map(x -> Map.of("sku_id", x.skuId().toString(), "quantity", x.quantity(), "unit_price", String.valueOf(x.unitPrice()))).toList());
        raw.put("recipient", Map.of("name", r.name(), "phone", r.phone(), "address1", r.address1()));
        OrderIngestService.Result res = ingest.ingest(new OrderIngestService.Incoming(a.workspaceId(), in.channelAccountId(), null, null, "MANUAL", "manual-v1", raw,
                order, a.memberId()));
        if (res.orderId() == null) {
            throw Errors.unprocessable("MANUAL_ORDER_REJECTED", "주문을 만들지 못했습니다: " + res.detail());
        }
        jdbc.sql("update sales_order set order_kind = :k, customer_name = cast(:c as text), created_by = :by where id = :id").param("k", kind).param("c", customer)
                .param("by", a.memberId()).param("id", res.orderId()).update();
        Map<String, Object> o = jdbc.sql("select order_no, status from sales_order where id = :id").param("id", res.orderId()).query().listOfRows().get(0);
        audit.record(AuditEntry.builder(a.workspaceId(), "MANUAL_ORDER_CREATED", "SALES_ORDER", res.orderId()).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(be, in.channelAccountId()).reason(in.memo()).after(Map.of("kind", kind, "lines", lines.size(), "total", total.toPlainString())).build());
        return new Created(res.orderId(), (String) o.get("order_no"), key, (String) o.get("status"), kind);
    }

    private String nextKey(UUID ws) {
        LocalDate d = LocalDate.now(ZoneId.of("Asia/Seoul"));
        int n = jdbc.sql("""
                insert into claim_number_counter (workspace_id, kind, day, last_value) values (:ws, 'MANUAL_ORDER', :d, 1)
                on conflict (workspace_id, kind, day) do update set last_value = claim_number_counter.last_value + 1 returning last_value
                """).param("ws", ws).param("d", d).query(Integer.class).single();
        return "M-" + d.toString().replace("-", "") + "-" + String.format("%04d", n);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
