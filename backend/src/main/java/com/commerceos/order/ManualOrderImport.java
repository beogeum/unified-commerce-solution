package com.commerceos.order;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.Perm;
import com.commerceos.identity.access.ResolvedScope;
import com.commerceos.operations.imports.ImportTypeHandler;
import com.commerceos.shared.error.Errors;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 수동·B2B·샘플 주문 파일 일괄 등록 양식 v1(DLG-234~238). 한 행 = 주문 상품 1줄, 같은 '주문 묶음 키' 행이 한 주문이 된다.
 * <ul>
 *   <li>주문 정보(판매계정·종류·거래처·결제 조건·수령인·배송비·메모)는 묶음 안에서 같아야 한다. 묶음의 한 행이라도 오류면 묶음 전체 제외.</li>
 *   <li>같은 판매계정 + 묶음 키는 한 번만 등록(파일을 다시 올려도 중복 주문 없음).</li>
 *   <li>실행은 화면 수동 주문과 같은 서비스(검증·여신 검사·표준 주문 경로·감사)를 탄다.</li>
 *   <li>수령인 정보는 민감 필드: 가져오기 행에 평문으로 남기지 않고 원본 파일은 처리 후 본문 삭제.</li>
 * </ul>
 */
@Component
public class ManualOrderImport implements ImportTypeHandler {
    static final String LINE = "_line";
    static final List<String> ORDER_FIELDS = List.of("sales_channel_code", "account_key", "kind", "customer_code", "customer_name", "payment_terms", "recipient_name",
            "recipient_phone", "postal_code", "address1", "address2", "delivery_memo", "shipping_fee", "memo");
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final ManualOrderService orders;

    public ManualOrderImport(JdbcClient jdbc, AccessGuard guard, ManualOrderService orders) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.orders = orders;
    }

    @Override
    public String importType() {
        return "MANUAL_ORDER";
    }

    @Override
    public String templateVersion() {
        return "v1";
    }

    @Override
    public String labelKo() {
        return "수동·B2B 주문 일괄 등록";
    }

    @Override
    public String requiredPermission() {
        return Perm.ORDER_UPDATE;
    }

    @Override
    public List<Field> fields() {
        return List.of(
                new Field("order_ref", "주문 묶음 키", true, List.of("묶음키", "주문키", "order ref", "발주번호"), "PO-2026-001"),
                new Field("sales_channel_code", "판매채널 코드", true, List.of("채널", "channel"), "NAVER_SMARTSTORE"),
                new Field("account_key", "판매계정 외부키", true, List.of("판매계정키", "account key"), "seller-001"),
                new Field("kind", "주문 종류", false, List.of("종류", "kind"), "B2B"),
                new Field("customer_code", "거래처 코드", false, List.of("거래처코드"), "OLIVE"),
                new Field("customer_name", "거래처명", false, List.of("거래처"), ""),
                new Field("payment_terms", "결제 조건", false, List.of("결제조건", "payment terms"), "CREDIT"),
                new Field("recipient_name", "받는 분", true, List.of("수령인", "수취인"), "김납품", true),
                new Field("recipient_phone", "연락처", true, List.of("수령인 연락처", "전화번호"), "010-0000-0000", true),
                new Field("postal_code", "우편번호", false, List.of("우편"), "13529", true),
                new Field("address1", "주소", true, List.of("기본주소", "address"), "경기도 성남시 분당구 판교역로 1", true),
                new Field("address2", "상세 주소", false, List.of("상세주소"), "3층", true),
                new Field("delivery_memo", "배송 메모", false, List.of("배송메모"), "", true),
                new Field("shipping_fee", "배송비", false, List.of("배송료"), "0"),
                new Field("memo", "주문 메모", false, List.of("메모"), ""),
                new Field("sku_code", "SKU 코드", true, List.of("sku", "품번"), "AMP-30"),
                new Field("quantity", "수량", true, List.of("qty"), "10"),
                new Field("unit_price", "단가(부가세 포함)", false, List.of("단가", "price"), "12000"));
    }

    record Account(UUID id, UUID businessEntityId) {
    }

    @Override
    public List<RowCheck> validate(List<Map<String, String>> rows) {
        UUID ws = guard.actor().workspaceId();
        ResolvedScope scope = guard.scope();
        List<List<Message>> msgs = new ArrayList<>();
        List<Map<String, String>> norms = new ArrayList<>();
        Map<String, Integer> firstOf = new HashMap<>();
        Map<String, Set<String>> skusOf = new HashMap<>();
        Map<String, Integer> linesOf = new HashMap<>();
        for (int idx = 0; idx < rows.size(); idx++) {
            Map<String, String> r = rows.get(idx);
            List<Message> m = new ArrayList<>();
            Map<String, String> n = new LinkedHashMap<>();
            n.put(LINE, String.valueOf(idx));
            String ref = v(r, "order_ref");
            if (ref.isEmpty() || ref.length() > 60) {
                m.add(err("order_ref", "주문 묶음 키를 60자 이내로 입력하세요."));
            }
            for (String f : ORDER_FIELDS) {
                n.put(f, v(r, f));
            }
            n.put("order_ref", ref);
            n.put("sales_channel_code", v(r, "sales_channel_code").toUpperCase(Locale.ROOT));
            String kind = v(r, "kind").isEmpty() ? "MANUAL" : v(r, "kind").toUpperCase(Locale.ROOT);
            n.put("kind", kind);
            if (!Set.of("MANUAL", "B2B", "SAMPLE").contains(kind)) {
                m.add(err("kind", "주문 종류는 MANUAL·B2B·SAMPLE 중 하나입니다."));
            }
            Optional<Account> acc = account(ws, n.get("sales_channel_code"), n.get("account_key"));
            if (acc.isEmpty() || !scope.allows(AccessKind.WRITE, acc.get().businessEntityId(), acc.get().id())) {
                m.add(err("account_key", "판매계정을 찾을 수 없거나 주문 등록 권한이 없습니다."));
            } else {
                n.put("_account_id", acc.get().id().toString());
            }
            String terms = n.get("payment_terms").toUpperCase(Locale.ROOT);
            n.put("payment_terms", terms);
            String cust = n.get("customer_code");
            if (!cust.isEmpty()) {
                if (!"B2B".equals(kind)) {
                    m.add(err("customer_code", "거래처 코드는 B2B 주문에서만 씁니다."));
                } else if (acc.isPresent()) {
                    Map<String, Object> c = jdbc.sql("select id, status from b2b_customer where workspace_id = :ws and business_entity_id = :be and code = :c")
                            .param("ws", ws).param("be", acc.get().businessEntityId()).param("c", cust).query().listOfRows().stream().findFirst().orElse(null);
                    if (c == null) {
                        m.add(err("customer_code", "이 판매계정 사업자의 거래처 '" + cust + "' 가 없습니다."));
                    } else {
                        n.put("_customer_id", c.get("id").toString());
                        if (!"ACTIVE".equals(c.get("status"))) {
                            m.add(err("customer_code", "거래 보류·중지된 거래처입니다."));
                        }
                    }
                }
                if (!Set.of("PREPAID", "CREDIT").contains(terms)) {
                    m.add(err("payment_terms", "거래처를 지정하면 결제 조건(PREPAID 선결제 / CREDIT 외상)이 필요합니다."));
                }
            } else {
                if (!terms.isEmpty()) {
                    m.add(err("payment_terms", "결제 조건은 거래처 코드를 지정한 B2B 주문에서만 씁니다."));
                }
                if ("B2B".equals(kind) && n.get("customer_name").isEmpty()) {
                    m.add(err("customer_name", "B2B 주문은 거래처 코드나 거래처명이 필요합니다."));
                }
            }
            if (n.get("recipient_name").isEmpty() || n.get("recipient_phone").isEmpty() || n.get("address1").isEmpty()) {
                m.add(err("recipient_name", "받는 분·연락처·주소는 필수입니다."));
            }
            if (!n.get("recipient_phone").isEmpty() && !n.get("recipient_phone").matches("^[0-9+()\\- ]{7,20}$")) {
                m.add(err("recipient_phone", "연락처 형식을 확인하세요."));
            }
            String fee = n.get("shipping_fee").replace(",", "");
            n.put("shipping_fee", fee.isEmpty() ? "0" : fee);
            if (!n.get("shipping_fee").matches("^\\d{1,9}$")) {
                m.add(err("shipping_fee", "배송비는 0 이상 원 단위 정수입니다."));
            }
            String sku = v(r, "sku_code");
            Map<String, Object> s = sku.isEmpty() ? null : jdbc.sql("""
                    select id, status, sellable from sku where workspace_id = :ws and lower(sku_code) = lower(:c)
                    """).param("ws", ws).param("c", sku).query().listOfRows().stream().findFirst().orElse(null);
            if (s == null) {
                m.add(err("sku_code", "SKU '" + sku + "' 가 없습니다."));
            } else if (!"ACTIVE".equals(s.get("status")) || !Boolean.TRUE.equals(s.get("sellable"))) {
                m.add(err("sku_code", "SKU '" + sku + "' 는 판매 가능한 활성 SKU 가 아닙니다."));
            } else {
                n.put("_sku_id", s.get("id").toString());
            }
            n.put("sku_code", sku);
            String qty = v(r, "quantity").replace(",", "");
            n.put("quantity", qty);
            if (!qty.matches("^\\d{1,6}$") || Integer.parseInt(qty) < 1) {
                m.add(err("quantity", "수량은 1 이상 정수입니다."));
            }
            String price = v(r, "unit_price").replace(",", "");
            n.put("unit_price", price);
            if (!price.isEmpty() && !price.matches("^\\d{1,12}$")) {
                m.add(err("unit_price", "단가는 0 이상 원 단위 정수입니다."));
            } else if (price.isEmpty() && cust.isEmpty() && !"SAMPLE".equals(kind)) {
                m.add(err("unit_price", "단가를 입력하세요(거래처 코드를 쓰면 단가표 단가)."));
            } else if (price.isEmpty() && !cust.isEmpty()) {
                m.add(warn("unit_price", "단가가 비어 있어 거래처 단가표 단가를 씁니다(단가표에 없으면 실행 때 실패)."));
            }
            if ("SAMPLE".equals(kind) && ((!price.isEmpty() && !price.matches("^0+$")) || !n.get("shipping_fee").matches("^0+$"))) {
                m.add(err("unit_price", "샘플 주문은 단가·배송비 0 입니다."));
            }
            // 묶음 규칙: 주문 정보 일치, 같은 SKU 반복 금지, 100줄 이하, 이미 등록한 묶음
            if (!ref.isEmpty() && acc.isPresent()) {
                String gk = acc.get().id() + "|" + ref;
                n.put("_group", gk);
                Integer first = firstOf.putIfAbsent(gk, idx);
                if (first != null) {
                    Map<String, String> head = norms.get(first);
                    for (String f : ORDER_FIELDS) {
                        if (!java.util.Objects.equals(head.get(f), n.get(f))) {
                            m.add(err(f, "같은 주문 묶음(" + ref + ") 안에서 주문 정보가 첫 행과 다릅니다."));
                            break;
                        }
                    }
                } else if (orders.findByImportRef(ws, ManualOrderService.importRef(acc.get().id(), ref)).isPresent()) {
                    m.add(err("order_ref", "이미 등록한 주문 묶음입니다(같은 판매계정 + 묶음 키). 다른 키를 쓰세요."));
                }
                if (!sku.isEmpty() && !skusOf.computeIfAbsent(gk, k -> new HashSet<>()).add(sku.toLowerCase(Locale.ROOT))) {
                    m.add(err("sku_code", "같은 주문 묶음에 같은 SKU 가 반복됩니다(수량을 합치세요)."));
                }
                if (linesOf.merge(gk, 1, Integer::sum) > 100) {
                    m.add(err("order_ref", "한 주문 묶음은 100줄까지입니다."));
                }
            }
            msgs.add(m);
            norms.add(n);
        }
        // 묶음 안 한 행이라도 오류면 묶음 전체 제외
        Set<String> badGroups = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            if (msgs.get(i).stream().anyMatch(x -> "ERROR".equals(x.level())) && norms.get(i).get("_group") != null) {
                badGroups.add(norms.get(i).get("_group"));
            }
        }
        List<RowCheck> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            List<Message> m = msgs.get(i);
            String g = norms.get(i).get("_group");
            if (g != null && badGroups.contains(g) && m.stream().noneMatch(x -> "ERROR".equals(x.level()))) {
                m.add(err("order_ref", "같은 주문 묶음의 다른 행에 오류가 있어 함께 제외합니다."));
            }
            out.add(RowCheck.of(m, norms.get(i)));
        }
        return out;
    }

    @Override
    public String apply(Map<String, String> n, BatchContext context) {
        UUID ws = guard.actor().workspaceId();
        String group = n.get("_group");
        UUID account = UUID.fromString(n.get("_account_id"));
        String ref = ManualOrderService.importRef(account, n.get("order_ref"));
        List<Map<String, String>> lines = context.executableRows().stream().filter(r -> group.equals(r.get("_group")))
                .sorted(Comparator.comparingInt(r -> Integer.parseInt(r.get(LINE)))).toList();
        boolean first = lines.isEmpty() || lines.get(0).get(LINE).equals(n.get(LINE));
        Optional<String> existing = orders.findByImportRef(ws, ref);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (!first) {
            throw Errors.unprocessable("IMPORT_ORDER_HEAD_FAILED", "같은 주문 묶음의 첫 행에서 주문을 만들지 못해 이 행도 반영하지 않았습니다(첫 행 결과 참고).");
        }
        List<ManualOrderService.Line> items = lines.stream().map(r -> new ManualOrderService.Line(UUID.fromString(r.get("_sku_id")), Integer.parseInt(r.get("quantity")),
                r.get("unit_price").isEmpty() ? ("SAMPLE".equals(r.get("kind")) ? BigDecimal.ZERO : null) : new BigDecimal(r.get("unit_price")), null)).toList();
        ManualOrderService.NewManualOrder in = new ManualOrderService.NewManualOrder(account, null, n.get("kind"), blank(n.get("customer_name")),
                new ManualOrderService.Recipient(n.get("recipient_name"), n.get("recipient_phone"), blank(n.get("postal_code")), n.get("address1"),
                        blank(n.get("address2")), blank(n.get("delivery_memo"))), new BigDecimal(n.get("shipping_fee")), items, blank(n.get("memo")),
                n.get("_customer_id") == null ? null : UUID.fromString(n.get("_customer_id")), blank(n.get("payment_terms")));
        return orders.create(in, ref).orderNo();
    }

    private Optional<Account> account(UUID ws, String channel, String key) {
        if (channel.isEmpty() || key.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                select id, business_entity_id from channel_account where workspace_id = :ws and sales_channel_code = :c and external_account_key = :k and status = 'ACTIVE'
                """).param("ws", ws).param("c", channel).param("k", key).query((rs, i) -> new Account((UUID) rs.getObject("id"), (UUID) rs.getObject("business_entity_id")))
                .optional();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    static String v(Map<String, String> r, String k) {
        String s = r.get(k);
        return s == null ? "" : s.trim();
    }

    static Message err(String field, String text) {
        return new Message("ERROR", field, text);
    }

    static Message warn(String field, String text) {
        return new Message("WARNING", field, text);
    }
}
