package com.commerceos.b2b;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.shared.db.TenantContext;
import com.commerceos.operations.WorkItemService;
import com.commerceos.operations.approval.ApprovalHandler;
import com.commerceos.operations.approval.ApprovalService;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * B2B 외상(매출채권) 원장(DLG-228~233). append-only, 부호: + 채권 증가 / − 감소.
 * <ul>
 *   <li>청구(CHARGE): 외상 주문이 출고되면 그 출고의 매출 원장(매출 − 판매자 할인 + 배송비, 부가세 포함)과 같은 금액. 기한 = 출고일 + 결제 기한 일수.</li>
 *   <li>반품 차감(CREDIT_NOTE): 클레임 완료 때 매출 원장의 반품·반품비·교환 차액과 같은 금액. 손익 원장과 금액이 어긋나지 않는다.</li>
 *   <li>수금(PAYMENT)·수금 취소(PAYMENT_REVERSAL)·대손(WRITE_OFF, 승인 필요). 수금은 Idempotency-Key 필수.</li>
 *   <li>여신 검사: 외상 주문 생성 때 거래처 행을 잠그고 (잔액 + 미출고 외상 주문 + 이번 주문) ≤ 한도, 기한 지난 미수 없음, 거래 상태 ACTIVE.</li>
 *   <li>연령 분석은 오래된 청구부터 차감(FIFO).</li>
 * </ul>
 */
@Service
public class ReceivableService implements ApprovalHandler {
    public static final String WRITE_OFF = "RECEIVABLE_WRITE_OFF";
    static final Set<String> METHODS = Set.of("BANK_TRANSFER", "CASH", "CARD", "NOTE", "OFFSET", "OTHER");
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final B2bCustomerService customers;
    private final ApprovalService approvals;
    private final WorkItemService workItems;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public ReceivableService(JdbcClient jdbc, AccessGuard guard, B2bCustomerService customers, @Lazy ApprovalService approvals, WorkItemService workItems,
                             AuditService audit, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.customers = customers;
        this.approvals = approvals;
        this.workItems = workItems;
        this.audit = audit;
        this.tx = tx;
    }

    public record PaymentInput(BigDecimal amount, LocalDate receivedOn, String method, String reference, String memo) {
    }

    public record WriteOffInput(BigDecimal amount, String reason) {
    }

    public record EntryView(UUID id, String entryType, BigDecimal amount, LocalDate occurredOn, LocalDate dueOn, UUID orderId, String orderNo, UUID claimId,
                            String method, String reference, String memo, UUID reversesId, boolean reversed, String createdByName, java.time.Instant createdAt) {
    }

    public record Aging(BigDecimal notDue, BigDecimal d1to30, BigDecimal d31to60, BigDecimal d61to90, BigDecimal over90) {
    }

    public record OpenCharge(UUID entryId, String orderNo, LocalDate occurredOn, LocalDate dueOn, BigDecimal amount, BigDecimal outstanding, long daysOverdue) {
    }

    public record Statement(UUID customerId, String customerName, UUID businessEntityId, BigDecimal creditLimit, int paymentTermsDays, String status,
                            BigDecimal balance, BigDecimal overdue, BigDecimal unbilledOrders, BigDecimal exposure, BigDecimal available, Aging aging,
                            List<OpenCharge> openCharges, List<EntryView> entries) {
    }

    public record SummaryRow(UUID customerId, String code, String name, UUID businessEntityId, String businessEntityName, String status, BigDecimal creditLimit,
                             BigDecimal balance, BigDecimal overdue, BigDecimal exposure, BigDecimal available, LocalDate oldestDueOn) {
    }

    // ---------------------------------------------------------------- 여신 검사(주문 생성 트랜잭션 안)

    /** 거래처 행을 잠그고 주문 가능 여부를 확인한다. 외상이면 한도·연체 검사. @return 거래처 행 */
    public Map<String, Object> checkOrder(UUID customerId, UUID businessEntityId, String terms, BigDecimal orderTotal) {
        CurrentActor a = guard.actor();
        Map<String, Object> c = customers.lock(a.workspaceId(), customerId);
        if (!businessEntityId.equals(c.get("business_entity_id"))) {
            throw Errors.validation("customer_id", "판매계정의 사업자와 다른 사업자의 거래처입니다.");
        }
        if (!"ACTIVE".equals(c.get("status"))) {
            throw Errors.unprocessable("CUSTOMER_NOT_ACTIVE", "거래 보류·중지된 거래처입니다" + (c.get("hold_reason") == null ? "." : ": " + c.get("hold_reason")),
                    Map.of("status", c.get("status")));
        }
        if (!"CREDIT".equals(terms)) {
            return c;
        }
        BigDecimal limit = (BigDecimal) c.get("credit_limit");
        if (limit.signum() == 0) {
            throw Errors.unprocessable("CREDIT_NOT_ALLOWED", "외상 한도가 없는 거래처입니다. 선결제로 주문하거나 재무 담당에게 한도 등록을 요청하세요.");
        }
        Snapshot s = snapshot(customerId, LocalDate.now(B2bCustomerService.KST));
        if (s.overdue.signum() > 0) {
            throw Errors.unprocessable("CREDIT_OVERDUE", "기한이 지난 미수금 " + s.overdue.toPlainString() + "원이 있어 외상 주문을 받을 수 없습니다. 수금 후 다시 시도하세요.",
                    Map.of("overdue", s.overdue.toPlainString()));
        }
        BigDecimal after = s.exposure.add(orderTotal);
        if (after.compareTo(limit) > 0) {
            throw Errors.unprocessable("CREDIT_LIMIT_EXCEEDED", "외상 한도를 넘습니다(한도 " + limit.toPlainString() + "원, 사용 중 " + s.exposure.toPlainString() + "원, 이번 주문 "
                    + orderTotal.toPlainString() + "원).", Map.of("credit_limit", limit.toPlainString(), "exposure", s.exposure.toPlainString(),
                    "order_total", orderTotal.toPlainString(), "available", limit.subtract(s.exposure).max(BigDecimal.ZERO).toPlainString()));
        }
        return c;
    }

    // ---------------------------------------------------------------- 출고·반품 연동(손익 원장 기록 직후, 같은 트랜잭션)

    public void onShipment(UUID ws, UUID foId) {
        Map<String, Object> o = jdbc.sql("""
                select o.id, o.b2b_customer_id, o.business_entity_id, f.shipped_at, f.fo_no, c.payment_terms_days from fulfillment_order f join sales_order o on o.id = f.order_id
                  join b2b_customer c on c.id = o.b2b_customer_id
                 where f.workspace_id = :ws and f.id = :f and o.payment_terms = 'CREDIT'
                """).param("ws", ws).param("f", foId).query().listOfRows().stream().findFirst().orElse(null);
        if (o == null) {
            return;
        }
        BigDecimal amount = jdbc.sql("""
                select coalesce(sum(supply_amount + vat_amount), 0) from financial_entry where workspace_id = :ws and fo_id = :f and entry_class = 'REVENUE'
                   and source_type = 'SHIPMENT'
                """).param("ws", ws).param("f", foId).query(BigDecimal.class).single();
        if (amount.signum() <= 0) {
            return;
        }
        LocalDate day = ((java.sql.Timestamp) o.get("shipped_at")).toInstant().atZone(B2bCustomerService.KST).toLocalDate();
        insert(ws, (UUID) o.get("business_entity_id"), (UUID) o.get("b2b_customer_id"), "CHARGE", amount, (UUID) o.get("id"), foId, null, day,
                day.plusDays(((Number) o.get("payment_terms_days")).longValue()), null, (String) o.get("fo_no"), "출고 청구", null, null, "AR:SHIP:" + foId, null);
    }

    public void onClaimCompleted(UUID ws, UUID claimId) {
        Map<String, Object> o = jdbc.sql("""
                select o.id, o.b2b_customer_id, o.business_entity_id, cl.claim_no, cl.completed_at from claim cl join sales_order o on o.id = cl.order_id
                 where cl.workspace_id = :ws and cl.id = :c and o.payment_terms = 'CREDIT'
                """).param("ws", ws).param("c", claimId).query().listOfRows().stream().findFirst().orElse(null);
        if (o == null) {
            return;
        }
        BigDecimal amount = jdbc.sql("""
                select coalesce(sum(supply_amount + vat_amount), 0) from financial_entry where workspace_id = :ws and claim_id = :c and entry_class = 'REVENUE'
                   and source_type = 'CLAIM'
                """).param("ws", ws).param("c", claimId).query(BigDecimal.class).single();
        if (amount.signum() == 0) {
            return;
        }
        LocalDate day = o.get("completed_at") == null ? LocalDate.now(B2bCustomerService.KST)
                : ((java.sql.Timestamp) o.get("completed_at")).toInstant().atZone(B2bCustomerService.KST).toLocalDate();
        // 반품 차감은 보통 음수(매출 반품), 반품비·교환 차액만 있으면 양수(추가 청구)
        insert(ws, (UUID) o.get("business_entity_id"), (UUID) o.get("b2b_customer_id"), amount.signum() > 0 ? "CHARGE" : "CREDIT_NOTE", amount, (UUID) o.get("id"),
                null, claimId, day, amount.signum() > 0 ? day : null, null, (String) o.get("claim_no"), "클레임 정산", null, null, "AR:CLAIM:" + claimId, null);
    }

    // ---------------------------------------------------------------- 수금·수금 취소

    @Transactional
    public EntryView recordPayment(UUID customerId, String idempotencyKey, PaymentInput in) {
        guard.requirePermission(Perm.RECEIVABLE_MANAGE);
        CurrentActor a = guard.actor();
        if (idempotencyKey == null || idempotencyKey.isBlank() || !idempotencyKey.matches("^[A-Za-z0-9._:-]{8,200}$")) {
            throw Errors.idempotencyKeyRequired();
        }
        Map<String, Object> c = customers.lock(a.workspaceId(), customerId);
        UUID be = (UUID) c.get("business_entity_id");
        guard.checkWrite("B2B_CUSTOMER", customerId, be, null);
        String key = "AR:PAY:" + idempotencyKey;
        UUID existing = jdbc.sql("select id from receivable_entry where workspace_id = :ws and source_key = :k").param("ws", a.workspaceId()).param("k", key)
                .query(UUID.class).optional().orElse(null);
        if (existing != null) {
            return entry(existing);
        }
        if (in == null || in.amount() == null || in.amount().signum() <= 0 || in.amount().stripTrailingZeros().scale() > 0) {
            throw Errors.validation("amount", "수금액을 1원 이상 원 단위로 입력하세요.");
        }
        String method = in.method() == null ? "BANK_TRANSFER" : in.method();
        if (!METHODS.contains(method)) {
            throw Errors.validation("method", "수금 방법 값이 올바르지 않습니다.");
        }
        LocalDate day = in.receivedOn() == null ? LocalDate.now(B2bCustomerService.KST) : in.receivedOn();
        if (day.isAfter(LocalDate.now(B2bCustomerService.KST))) {
            throw Errors.validation("received_on", "미래 날짜로 수금을 기록할 수 없습니다.");
        }
        UUID id = insert(a.workspaceId(), be, customerId, "PAYMENT", in.amount().negate(), null, null, null, day, null, method,
                B2bCustomerService.trim(in.reference(), 100, "reference"), B2bCustomerService.trim(in.memo(), 500, "memo"), null, null, key, a.memberId());
        audit.record(AuditEntry.builder(a.workspaceId(), "RECEIVABLE_PAYMENT_RECORDED", "B2B_CUSTOMER", customerId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(be, null).after(Map.of("entry_id", id.toString(), "amount", in.amount().toPlainString(), "method", method, "received_on", day.toString())).build());
        refreshOverdue(a.workspaceId(), customerId);
        return entry(id);
    }

    @Transactional
    public EntryView reversePayment(UUID entryId, String reason) {
        guard.requirePermission(Perm.RECEIVABLE_MANAGE);
        CurrentActor a = guard.actor();
        Map<String, Object> e = jdbc.sql("select * from receivable_entry where workspace_id = :ws and id = :id").param("ws", a.workspaceId()).param("id", entryId)
                .query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("RECEIVABLE_ENTRY"));
        UUID customerId = (UUID) e.get("customer_id");
        Map<String, Object> c = customers.lock(a.workspaceId(), customerId);
        UUID be = (UUID) c.get("business_entity_id");
        guard.checkWrite("B2B_CUSTOMER", customerId, be, null);
        if (!"PAYMENT".equals(e.get("entry_type"))) {
            throw Errors.unprocessable("ENTRY_NOT_REVERSIBLE", "수금 기록만 취소할 수 있습니다(청구·반품 차감은 출고·클레임에서 자동 기록).");
        }
        String why = B2bCustomerService.trim(reason, 500, "reason");
        if (why == null) {
            throw Errors.validation("reason", "수금 취소 사유를 입력하세요.");
        }
        int done = jdbc.sql("select count(*) from receivable_entry where reverses_id = :id").param("id", entryId).query(Integer.class).single();
        if (done > 0) {
            throw Errors.conflict("ENTRY_ALREADY_REVERSED", "이미 취소한 수금입니다.");
        }
        UUID id = insert(a.workspaceId(), be, customerId, "PAYMENT_REVERSAL", ((BigDecimal) e.get("amount")).negate(), null, null, null,
                LocalDate.now(B2bCustomerService.KST), null, (String) e.get("method"), (String) e.get("reference"), why, entryId, null, "AR:REV:" + entryId, a.memberId());
        audit.record(AuditEntry.builder(a.workspaceId(), "RECEIVABLE_PAYMENT_REVERSED", "B2B_CUSTOMER", customerId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(be, null).reason(why).after(Map.of("entry_id", id.toString(), "reverses_id", entryId.toString())).build());
        refreshOverdue(a.workspaceId(), customerId);
        return entry(id);
    }

    // ---------------------------------------------------------------- 대손(승인)

    @Transactional
    public ApprovalService.Request requestWriteOff(UUID customerId, WriteOffInput in) {
        guard.requirePermission(Perm.RECEIVABLE_MANAGE);
        CurrentActor a = guard.actor();
        Map<String, Object> c = customers.lock(a.workspaceId(), customerId);
        UUID be = (UUID) c.get("business_entity_id");
        guard.checkWrite("B2B_CUSTOMER", customerId, be, null);
        if (in == null || in.amount() == null || in.amount().signum() <= 0 || in.amount().stripTrailingZeros().scale() > 0) {
            throw Errors.validation("amount", "대손 금액을 1원 이상 원 단위로 입력하세요.");
        }
        Snapshot s = snapshot(customerId, LocalDate.now(B2bCustomerService.KST));
        if (in.amount().compareTo(s.balance) > 0) {
            throw Errors.unprocessable("WRITE_OFF_EXCEEDS_BALANCE", "미수 잔액(" + s.balance.toPlainString() + "원)보다 많이 대손 처리할 수 없습니다.");
        }
        if (approvals.pending(WRITE_OFF, customerId.toString()).isPresent()) {
            throw Errors.conflict("APPROVAL_ALREADY_PENDING", "이 거래처에 승인 대기 중인 대손 요청이 있습니다.");
        }
        Map<String, Object> payload = Map.of("customer_id", customerId.toString(), "amount", in.amount().toPlainString());
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("거래처", c.get("name"));
        impact.put("미수 잔액", s.balance.toPlainString());
        impact.put("대손 금액", in.amount().toPlainString());
        impact.put("처리 후 잔액", s.balance.subtract(in.amount()).toPlainString());
        impact.put("손익", "관리 손익 원장에는 반영하지 않음(회계 대손상각은 회계 시스템에서)");
        ApprovalService.Request r = approvals.create(new ApprovalService.NewRequest(WRITE_OFF, "B2B_CUSTOMER", customerId.toString(), be, payload, impact, in.reason()));
        audit.record(AuditEntry.builder(a.workspaceId(), "RECEIVABLE_WRITE_OFF_REQUESTED", "B2B_CUSTOMER", customerId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(be, null).reason(in.reason()).after(Map.of("approval_id", r.id().toString(), "amount", in.amount().toPlainString())).build());
        return r;
    }

    @Override
    public String requestType() {
        return WRITE_OFF;
    }

    @Override
    public boolean isCurrent(ApprovalService.Request r) {
        UUID customerId = UUID.fromString(r.payload().get("customer_id").asString());
        BigDecimal amount = new BigDecimal(r.payload().get("amount").asString());
        return snapshot(customerId, LocalDate.now(B2bCustomerService.KST)).balance.compareTo(amount) >= 0;
    }

    @Override
    public void apply(ApprovalService.Request r) {
        CurrentActor a = guard.actor();
        if (!a.has(Perm.RECEIVABLE_MANAGE)) {
            throw Errors.unprocessable("APPROVER_NEEDS_RECEIVABLE_MANAGE", "대손 승인은 외상 관리 권한이 있는 승인자만 할 수 있습니다.");
        }
        UUID customerId = UUID.fromString(r.payload().get("customer_id").asString());
        BigDecimal amount = new BigDecimal(r.payload().get("amount").asString());
        Map<String, Object> c = customers.lock(a.workspaceId(), customerId);
        if (snapshot(customerId, LocalDate.now(B2bCustomerService.KST)).balance.compareTo(amount) < 0) {
            throw Errors.conflict("WRITE_OFF_EXCEEDS_BALANCE", "요청 후 잔액이 줄어 대손 금액이 잔액보다 큽니다. 다시 요청하세요.");
        }
        UUID id = insert(a.workspaceId(), (UUID) c.get("business_entity_id"), customerId, "WRITE_OFF", amount.negate(), null, null, null,
                LocalDate.now(B2bCustomerService.KST), null, null, null, r.reason(), null, r.id(), "AR:WO:" + r.id(), r.requestedBy());
        audit.record(AuditEntry.builder(a.workspaceId(), "RECEIVABLE_WRITTEN_OFF", "B2B_CUSTOMER", customerId).actor(ActorType.APPROVER, a.memberId(), a.displayName())
                .scope((UUID) c.get("business_entity_id"), null).reason(r.reason()).after(Map.of("entry_id", id.toString(), "amount", amount.toPlainString())).build());
        refreshOverdue(a.workspaceId(), customerId);
    }

    // ---------------------------------------------------------------- 조회

    public Statement statement(UUID customerId) {
        guard.requirePermission(Perm.SETTLEMENT_READ);
        UUID be = customers.beOf(customerId);
        guard.checkRead("B2B_CUSTOMER", customerId, be, null);
        Map<String, Object> c = jdbc.sql("select * from b2b_customer where id = :id").param("id", customerId).query().listOfRows().get(0);
        LocalDate today = LocalDate.now(B2bCustomerService.KST);
        Snapshot s = snapshot(customerId, today);
        BigDecimal limit = (BigDecimal) c.get("credit_limit");
        List<EntryView> entries = jdbc.sql(ENTRY_SELECT + " where e.customer_id = :c order by e.occurred_on desc, e.created_at desc limit 500").param("c", customerId)
                .query(this::mapEntry).list();
        return new Statement(customerId, (String) c.get("name"), be, limit, ((Number) c.get("payment_terms_days")).intValue(), (String) c.get("status"), s.balance,
                s.overdue, s.unbilled, s.exposure, limit.subtract(s.exposure).max(BigDecimal.ZERO), s.aging, s.open, entries);
    }

    public List<SummaryRow> summary(List<String> businessEntityIds, Boolean overdueOnly) {
        guard.requirePermission(Perm.SETTLEMENT_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        if (bes.isEmpty()) {
            return List.of();
        }
        LocalDate today = LocalDate.now(B2bCustomerService.KST);
        List<SummaryRow> out = new ArrayList<>();
        for (Map<String, Object> c : jdbc.sql("""
                select c.*, be.display_name as be_name from b2b_customer c join business_entity be on be.id = c.business_entity_id
                 where c.workspace_id = :ws and c.business_entity_id in (:bes)
                   and (c.credit_limit > 0 or exists (select 1 from receivable_entry e where e.customer_id = c.id)) order by c.name
                """).param("ws", guard.actor().workspaceId()).param("bes", bes).query().listOfRows()) {
            UUID id = (UUID) c.get("id");
            Snapshot s = snapshot(id, today);
            if (Boolean.TRUE.equals(overdueOnly) && s.overdue.signum() == 0) {
                continue;
            }
            BigDecimal limit = (BigDecimal) c.get("credit_limit");
            LocalDate oldest = s.open.stream().map(OpenCharge::dueOn).min(LocalDate::compareTo).orElse(null);
            out.add(new SummaryRow(id, (String) c.get("code"), (String) c.get("name"), (UUID) c.get("business_entity_id"), (String) c.get("be_name"),
                    (String) c.get("status"), limit, s.balance, s.overdue, s.exposure, limit.subtract(s.exposure).max(BigDecimal.ZERO), oldest));
        }
        return out;
    }

    // ---------------------------------------------------------------- 연체 감시

    @Scheduled(cron = "${commerce.receivable.overdue-cron:0 23 7 * * *}", zone = "Asia/Seoul")
    public void scheduled() {
        scanOverdue(LocalDate.now(B2bCustomerService.KST));
    }

    /** 기한 지난 미수가 있는 거래처마다 업무(RECEIVABLE_OVERDUE), 해소되면 닫는다. @return 연체 거래처 수 */
    public int scanOverdue(LocalDate today) {
        return TenantContext.asSystem(() -> tx.execute(status -> {
            int n = 0;
            for (Map<String, Object> c : jdbc.sql("select distinct e.workspace_id, e.customer_id from receivable_entry e").query().listOfRows()) {
                if (refresh((UUID) c.get("workspace_id"), (UUID) c.get("customer_id"), today)) {
                    n++;
                }
            }
            return n;
        }));
    }

    // ---------------------------------------------------------------- 내부

    private void refreshOverdue(UUID ws, UUID customerId) {
        refresh(ws, customerId, LocalDate.now(B2bCustomerService.KST));
    }

    private boolean refresh(UUID ws, UUID customerId, LocalDate today) {
        Snapshot s = snapshot(customerId, today);
        String key = "ar-overdue:" + customerId;
        if (s.overdue.signum() == 0) {
            workItems.resolveByKey(ws, key);
            return false;
        }
        Map<String, Object> c = jdbc.sql("select name, business_entity_id from b2b_customer where id = :id").param("id", customerId).query().listOfRows().get(0);
        long days = s.open.stream().mapToLong(OpenCharge::daysOverdue).max().orElse(0);
        workItems.raise(new WorkItemService.Raise(ws, (UUID) c.get("business_entity_id"), null, null, "RECEIVABLE_OVERDUE", days > 30 ? "HIGH" : "MEDIUM",
                "외상 연체: " + c.get("name"), "기한이 지난 미수금 " + s.overdue.toPlainString() + "원(최장 " + days + "일 경과)이 있습니다. 새 외상 주문은 막힙니다.",
                "거래처에 수금을 요청하고 입금되면 수금을 기록하세요.", key, Map.of("customer_id", customerId.toString())));
        return true;
    }

    private record Snapshot(BigDecimal balance, BigDecimal overdue, BigDecimal unbilled, BigDecimal exposure, Aging aging, List<OpenCharge> open) {
    }

    /** 잔액·연체·연령(FIFO)·미출고 외상 주문. */
    Snapshot snapshot(UUID customerId, LocalDate today) {
        List<Map<String, Object>> rows = jdbc.sql("""
                select e.id, e.entry_type, e.amount, e.occurred_on, e.due_on, o.order_no from receivable_entry e left join sales_order o on o.id = e.order_id
                 where e.customer_id = :c order by e.due_on nulls last, e.occurred_on, e.created_at
                """).param("c", customerId).query().listOfRows();
        BigDecimal balance = BigDecimal.ZERO;
        BigDecimal pool = BigDecimal.ZERO;
        List<Map<String, Object>> charges = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            BigDecimal amt = (BigDecimal) r.get("amount");
            balance = balance.add(amt);
            if ("CHARGE".equals(r.get("entry_type"))) {
                charges.add(r);
            } else {
                pool = pool.subtract(amt); // 감소(−)는 차감 재원 +, 수금 취소(+)는 재원 −
            }
        }
        BigDecimal notDue = BigDecimal.ZERO;
        BigDecimal b30 = BigDecimal.ZERO;
        BigDecimal b60 = BigDecimal.ZERO;
        BigDecimal b90 = BigDecimal.ZERO;
        BigDecimal over = BigDecimal.ZERO;
        BigDecimal overdue = BigDecimal.ZERO;
        List<OpenCharge> open = new ArrayList<>();
        for (Map<String, Object> ch : charges) {
            BigDecimal amt = (BigDecimal) ch.get("amount");
            BigDecimal applied = pool.max(BigDecimal.ZERO).min(amt);
            pool = pool.subtract(applied);
            BigDecimal left = amt.subtract(applied);
            if (left.signum() <= 0) {
                continue;
            }
            LocalDate due = ((java.sql.Date) ch.get("due_on")).toLocalDate();
            long late = Math.max(0, ChronoUnit.DAYS.between(due, today));
            open.add(new OpenCharge((UUID) ch.get("id"), (String) ch.get("order_no"), ((java.sql.Date) ch.get("occurred_on")).toLocalDate(), due, amt, left, late));
            if (late == 0) {
                notDue = notDue.add(left);
            } else {
                overdue = overdue.add(left);
                if (late <= 30) {
                    b30 = b30.add(left);
                } else if (late <= 60) {
                    b60 = b60.add(left);
                } else if (late <= 90) {
                    b90 = b90.add(left);
                } else {
                    over = over.add(left);
                }
            }
        }
        // 미출고 외상 주문(아직 청구 전 금액): 주문 금액 − 출고 청구된 금액, 취소 주문 제외
        BigDecimal unbilled = jdbc.sql("""
                select coalesce(sum(greatest(o.total_amount - coalesce((select sum(e.amount) from receivable_entry e where e.order_id = o.id and e.entry_type = 'CHARGE'
                                                                          and e.fo_id is not null), 0), 0)), 0)
                  from sales_order o where o.b2b_customer_id = :c and o.payment_terms = 'CREDIT' and o.status not in ('CANCELLED', 'COMPLETED')
                """).param("c", customerId).query(BigDecimal.class).single();
        BigDecimal exposure = balance.max(BigDecimal.ZERO).add(unbilled);
        return new Snapshot(balance, overdue, unbilled, exposure, new Aging(notDue, b30, b60, b90, over), open);
    }

    private UUID insert(UUID ws, UUID be, UUID customer, String type, BigDecimal amount, UUID order, UUID fo, UUID claim, LocalDate day, LocalDate due, String method,
                        String reference, String memo, UUID reverses, UUID approval, String sourceKey, UUID by) {
        UUID id = Ids.newId();
        int n = jdbc.sql("""
                insert into receivable_entry (id, workspace_id, business_entity_id, customer_id, entry_type, amount, order_id, fo_id, claim_id, occurred_on, due_on, method,
                                              reference, memo, reverses_id, approval_id, source_key, created_by)
                values (:id, :ws, :be, :c, :t, :a, cast(:o as uuid), cast(:f as uuid), cast(:cl as uuid), :d, cast(:due as date), cast(:m as text), cast(:ref as text),
                        cast(:memo as text), cast(:rev as uuid), cast(:ap as uuid), :k, cast(:by as uuid))
                on conflict (workspace_id, source_key) do nothing
                """).param("id", id).param("ws", ws).param("be", be).param("c", customer).param("t", type).param("a", amount).param("o", order).param("f", fo)
                .param("cl", claim).param("d", day).param("due", due).param("m", method).param("ref", reference).param("memo", memo).param("rev", reverses)
                .param("ap", approval).param("k", sourceKey).param("by", by).update();
        return n == 1 ? id : jdbc.sql("select id from receivable_entry where workspace_id = :ws and source_key = :k").param("ws", ws).param("k", sourceKey)
                .query(UUID.class).single();
    }

    static final String ENTRY_SELECT = """
            select e.*, o.order_no, (select u.display_name from workspace_member m join user_account u on u.id = m.user_id where m.id = e.created_by) as by_name,
                   exists (select 1 from receivable_entry r where r.reverses_id = e.id) as is_reversed
              from receivable_entry e left join sales_order o on o.id = e.order_id
            """;

    private EntryView entry(UUID id) {
        return jdbc.sql(ENTRY_SELECT + " where e.id = :id").param("id", id).query(this::mapEntry).single();
    }

    private EntryView mapEntry(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new EntryView(Db.uuid(rs, "id"), rs.getString("entry_type"), rs.getBigDecimal("amount"), rs.getObject("occurred_on", LocalDate.class),
                rs.getObject("due_on", LocalDate.class), Db.uuid(rs, "order_id"), rs.getString("order_no"), Db.uuid(rs, "claim_id"), rs.getString("method"),
                rs.getString("reference"), rs.getString("memo"), Db.uuid(rs, "reverses_id"), rs.getBoolean("is_reversed"), rs.getString("by_name"),
                rs.getTimestamp("created_at").toInstant());
    }
}
