package com.commerceos.cs;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.operations.WorkItemService;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.shared.crypto.SecretCipher;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.db.TenantContext;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import com.commerceos.shared.json.Json;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CS 문의 통합함(FR-CS-01·05, docs/01 §CS, docs/13 §8, DLG-116·201~206).
 * <ul>
 *   <li>채널 문의 API 는 미연동 — 담당자가 채널 문의를 옮겨 적거나(원본 채널 문의번호로 중복 방지) 전화·메일·채팅 문의를 기록한다.</li>
 *   <li>답변은 기록 후 담당자가 채널에 직접 등록하고 '채널 등록 완료'를 표시한다(자동 고객 전송 없음, FR-CS-05). 메시지는 추가 전용.</li>
 *   <li>응답기한(유형별 기본 시간) 지나면 업무함 `CS_RESPONSE_OVERDUE`. 고객 재문의는 다시 열고 표시한다.</li>
 *   <li>주문 문의는 주문의 사업자·판매계정을 따르고 구매자 정보는 주문 쪽(마스킹)을 쓴다. 주문 없는 문의의 고객 정보는 암호화·마스킹.</li>
 *   <li>권한: 조회 `order.read` + 사업자·판매계정 범위, 처리 `order.update`.</li>
 * </ul>
 */
@Service
public class CsService {
    static final Set<String> TYPES = Set.of("DELIVERY", "PRODUCT", "CANCEL", "RETURN", "EXCHANGE", "PAYMENT", "ADDRESS_CHANGE", "OTHER");
    static final Set<String> SOURCES = Set.of("CHANNEL", "PHONE", "EMAIL", "CHAT", "OTHER");
    static final Set<String> STATUSES = Set.of("OPEN", "IN_PROGRESS", "WAITING_CUSTOMER", "ANSWERED", "CLOSED");
    static final Set<String> PRIORITIES = Set.of("URGENT", "HIGH", "NORMAL", "LOW");
    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final SecretCipher cipher;
    private final WorkItemService workItems;
    private final AuditService audit;
    private final Json json;
    private final int defaultSlaHours;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public CsService(JdbcClient jdbc, AccessGuard guard, SecretCipher cipher, WorkItemService workItems, AuditService audit, Json json,
                     @Value("${commerce.cs.response-sla-hours:24}") int defaultSlaHours, org.springframework.transaction.PlatformTransactionManager tm) {
        this.tx = new org.springframework.transaction.support.TransactionTemplate(tm);
        this.jdbc = jdbc;
        this.guard = guard;
        this.cipher = cipher;
        this.workItems = workItems;
        this.audit = audit;
        this.json = json;
        this.defaultSlaHours = defaultSlaHours;
    }

    public record NewInquiry(UUID channelAccountId, UUID orderId, UUID orderItemId, String source, String externalInquiryKey, String inquiryType, String title,
                             String message, String customerName, String customerPhone, String priority, UUID assigneeMemberId) {
    }

    public record MessageView(UUID id, String direction, String body, UUID templateId, String channelStatus, String createdByName, Instant createdAt,
                              Instant channelMarkedAt) {
    }

    public record InquiryView(UUID id, String inquiryNo, UUID businessEntityId, String businessEntityName, UUID channelAccountId, String channelAccountName,
                              UUID orderId, String orderNo, String externalOrderKey, UUID claimId, String source, String externalInquiryKey, String inquiryType,
                              String title, String status, String priority, UUID assigneeMemberId, String assigneeName, Instant dueAt, boolean overdue,
                              boolean customerReply, String customerNameMasked, Instant firstResponseAt, Instant answeredAt, Instant closedAt, Instant createdAt,
                              String lastMessage, int pendingChannelAnswers, List<MessageView> messages, Map<String, Object> orderSummary, int version) {
    }

    public record Kpi(int open, int dueToday, int overdue, int inProgress, int customerReplied, int waitingChannelPost) {
    }

    public record TemplateInput(UUID businessEntityId, String name, String inquiryType, String body, String status) {
    }

    public record TemplateView(UUID id, UUID businessEntityId, String name, String inquiryType, String body, String status, int version) {
    }

    // ---------------------------------------------------------------- 문의

    @Transactional
    public InquiryView create(NewInquiry in) {
        guard.requirePermission(Perm.ORDER_UPDATE);
        CurrentActor a = guard.actor();
        if (in == null) {
            throw Errors.validation("title", "문의 내용을 입력하세요.");
        }
        UUID ca = in.channelAccountId();
        UUID orderId = in.orderId();
        UUID be;
        String buyerMasked = null;
        if (orderId != null) {
            Map<String, Object> o = jdbc.sql("select business_entity_id, channel_account_id, buyer_name_masked from sales_order where workspace_id = :ws and id = :id")
                    .param("ws", a.workspaceId()).param("id", orderId).query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("SALES_ORDER"));
            if (ca != null && !ca.equals(o.get("channel_account_id"))) {
                throw Errors.validation("channel_account_id", "주문의 판매계정과 다릅니다.");
            }
            ca = (UUID) o.get("channel_account_id");
            be = (UUID) o.get("business_entity_id");
            buyerMasked = (String) o.get("buyer_name_masked");
            if (in.orderItemId() != null && jdbc.sql("select count(*) from order_item where order_id = :o and id = :i").param("o", orderId).param("i", in.orderItemId())
                    .query(Integer.class).single() == 0) {
                throw Errors.validation("order_item_id", "이 주문의 상품이 아닙니다.");
            }
        } else {
            if (ca == null) {
                throw Errors.validation("channel_account_id", "판매계정 또는 주문을 지정하세요.");
            }
            UUID finalCa = ca;
            be = jdbc.sql("select business_entity_id from channel_account where workspace_id = :ws and id = :id").param("ws", a.workspaceId()).param("id", finalCa)
                    .query(UUID.class).optional().orElseThrow(() -> Errors.notFound("CHANNEL_ACCOUNT"));
        }
        guard.checkWrite("CHANNEL_ACCOUNT", ca, be, ca);
        String source = in.source() == null ? "CHANNEL" : in.source();
        String type = in.inquiryType() == null ? "OTHER" : in.inquiryType();
        String priority = in.priority() == null ? "NORMAL" : in.priority();
        if (!SOURCES.contains(source) || !TYPES.contains(type) || !PRIORITIES.contains(priority)) {
            throw Errors.validation("inquiry_type", "접수 경로·문의 유형·중요도를 확인하세요.");
        }
        String title = trim(in.title(), 200, "title");
        String message = trim(in.message(), 5000, "message");
        if (title == null || message == null) {
            throw Errors.validation("title", "제목과 문의 내용을 입력하세요.");
        }
        String ext = trim(in.externalInquiryKey(), 200, "external_inquiry_key");
        if (ext != null) {
            UUID dup = jdbc.sql("select id from cs_inquiry where channel_account_id = :ca and external_inquiry_key = :k").param("ca", ca).param("k", ext).query(UUID.class)
                    .optional().orElse(null);
            if (dup != null) {
                throw Errors.conflict("CS_INQUIRY_DUPLICATE", "같은 채널 문의번호가 이미 등록되어 있습니다.", Map.of("inquiry_id", dup.toString()));
            }
        }
        if (in.assigneeMemberId() != null) {
            requireMember(a.workspaceId(), in.assigneeMemberId());
        }
        SecretCipher.Encrypted enc = null;
        String masked = buyerMasked;
        if (orderId == null && (in.customerName() != null || in.customerPhone() != null)) {
            Map<String, Object> pii = new LinkedHashMap<>();
            pii.put("name", in.customerName());
            pii.put("phone", in.customerPhone());
            enc = cipher.encrypt(json.write(pii));
            masked = maskName(in.customerName());
        }
        UUID id = Ids.newId();
        Instant now = Instant.now();
        jdbc.sql("""
                insert into cs_inquiry (id, workspace_id, inquiry_no, business_entity_id, channel_account_id, order_id, order_item_id, source, external_inquiry_key,
                                        inquiry_type, title, status, priority, assignee_member_id, due_at, customer_cipher, customer_key_id, customer_name_masked, created_by)
                values (:id, :ws, :no, :be, :ca, cast(:o as uuid), cast(:oi as uuid), :src, cast(:ext as text), :t, :title, :st, :p, cast(:as as uuid), :due,
                        cast(:cc as bytea), cast(:ck as text), cast(:cm as text), :by)
                """).param("id", id).param("ws", a.workspaceId()).param("no", nextNo(a.workspaceId())).param("be", be).param("ca", ca).param("o", orderId)
                .param("oi", in.orderItemId()).param("src", source).param("ext", ext).param("t", type).param("title", title)
                .param("st", in.assigneeMemberId() == null ? "OPEN" : "IN_PROGRESS").param("p", priority).param("as", in.assigneeMemberId())
                .param("due", Db.ts(now.plus(Duration.ofHours(slaHours(priority))))).param("cc", enc == null ? null : enc.cipherText())
                .param("ck", enc == null ? null : enc.keyId()).param("cm", masked).param("by", a.memberId()).update();
        message(a, id, "INBOUND", message, null);
        audit.record(AuditEntry.builder(a.workspaceId(), "CS_INQUIRY_CREATED", "CS_INQUIRY", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(be, ca).after(Map.of("type", type, "source", source, "order_id", String.valueOf(orderId))).build());
        return get(id);
    }

    /** 메시지 추가: INBOUND(고객 재문의 → 다시 열림), OUTBOUND(답변 → 채널 직접 등록 필요), NOTE(내부 메모). */
    @Transactional
    public InquiryView addMessage(UUID id, String direction, String body, UUID templateId) {
        guard.requirePermission(Perm.ORDER_UPDATE);
        CurrentActor a = guard.actor();
        Map<String, Object> q = lock(a.workspaceId(), id);
        guard.checkWrite("CS_INQUIRY", id, (UUID) q.get("business_entity_id"), (UUID) q.get("channel_account_id"));
        if (!Set.of("INBOUND", "OUTBOUND", "NOTE").contains(direction)) {
            throw Errors.validation("direction", "메시지 종류는 고객 문의(INBOUND)·답변(OUTBOUND)·내부 메모(NOTE) 중 하나입니다.");
        }
        String text = trim(body, 5000, "body");
        if (text == null) {
            throw Errors.validation("body", "내용을 입력하세요.");
        }
        String st = (String) q.get("status");
        if ("CLOSED".equals(st) && !"INBOUND".equals(direction)) {
            throw Errors.conflict("CS_INQUIRY_CLOSED", "종료된 문의입니다. 고객 재문의를 기록하면 다시 열립니다.");
        }
        message(a, id, direction, text, templateId);
        Instant now = Instant.now();
        switch (direction) {
            case "OUTBOUND" -> jdbc.sql("""
                    update cs_inquiry set status = 'ANSWERED', customer_reply = false, answered_at = now(), first_response_at = coalesce(first_response_at, now()),
                           assignee_member_id = coalesce(assignee_member_id, :m), updated_at = now(), version = version + 1 where id = :id
                    """).param("m", a.memberId()).param("id", id).update();
            case "INBOUND" -> jdbc.sql("""
                    update cs_inquiry set status = case when status in ('ANSWERED', 'WAITING_CUSTOMER', 'CLOSED') then 'OPEN' else status end,
                           customer_reply = status in ('ANSWERED', 'WAITING_CUSTOMER', 'CLOSED'), closed_at = null,
                           due_at = case when status in ('ANSWERED', 'WAITING_CUSTOMER', 'CLOSED') then :due else due_at end, updated_at = now(), version = version + 1 where id = :id
                    """).param("due", Db.ts(now.plus(Duration.ofHours(slaHours((String) q.get("priority")))))).param("id", id).update();
            default -> jdbc.sql("update cs_inquiry set updated_at = now(), version = version + 1 where id = :id").param("id", id).update();
        }
        if ("OUTBOUND".equals(direction)) {
            workItems.resolveByKey(a.workspaceId(), "cs-overdue:" + id);
        }
        return get(id);
    }

    @Transactional
    public InquiryView markChannelPosted(UUID inquiryId, UUID messageId) {
        guard.requirePermission(Perm.ORDER_UPDATE);
        CurrentActor a = guard.actor();
        Map<String, Object> q = lock(a.workspaceId(), inquiryId);
        guard.checkWrite("CS_INQUIRY", inquiryId, (UUID) q.get("business_entity_id"), (UUID) q.get("channel_account_id"));
        int n = jdbc.sql("""
                update cs_message set channel_status = 'MANUAL_DONE', channel_marked_by = :by, channel_marked_at = now()
                 where id = :m and inquiry_id = :q and direction = 'OUTBOUND' and channel_status = 'MANUAL_REQUIRED'
                """).param("by", a.memberId()).param("m", messageId).param("q", inquiryId).update();
        if (n == 0) {
            throw Errors.conflict("CS_MESSAGE_NOT_PENDING", "채널 등록을 기다리는 답변이 아닙니다.");
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "CS_ANSWER_POSTED_TO_CHANNEL", "CS_INQUIRY", inquiryId).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope((UUID) q.get("business_entity_id"), (UUID) q.get("channel_account_id")).after(Map.of("message_id", messageId.toString())).build());
        return get(inquiryId);
    }

    /** 담당자·상태·중요도 변경(If-Match 선택). */
    @Transactional
    public InquiryView update(UUID id, String status, UUID assignee, String priority, Integer version) {
        guard.requirePermission(Perm.ORDER_UPDATE);
        CurrentActor a = guard.actor();
        Map<String, Object> q = lock(a.workspaceId(), id);
        guard.checkWrite("CS_INQUIRY", id, (UUID) q.get("business_entity_id"), (UUID) q.get("channel_account_id"));
        if (version != null && version != ((Number) q.get("version")).intValue()) {
            throw Errors.conflict("VERSION_CONFLICT", "다른 담당자가 먼저 바꿨습니다. 새로 고친 뒤 다시 시도하세요.");
        }
        String st = status == null ? (String) q.get("status") : status;
        String pr = priority == null ? (String) q.get("priority") : priority;
        if (!STATUSES.contains(st) || !PRIORITIES.contains(pr)) {
            throw Errors.validation("status", "상태·중요도를 확인하세요.");
        }
        if ("ANSWERED".equals(st) && !"ANSWERED".equals(q.get("status"))) {
            throw Errors.validation("status", "답변 완료는 답변을 기록하면 자동으로 바뀝니다.");
        }
        if (assignee != null) {
            requireMember(a.workspaceId(), assignee);
        }
        UUID as = assignee == null ? (UUID) q.get("assignee_member_id") : assignee;
        if ("OPEN".equals(st) && as != null && assignee != null) {
            st = "IN_PROGRESS";
        }
        jdbc.sql("""
                update cs_inquiry set status = :st, priority = :p, assignee_member_id = cast(:as as uuid), closed_at = case when :st = 'CLOSED' then now() else null end,
                       customer_reply = case when :st = 'CLOSED' then false else customer_reply end, updated_at = now(), version = version + 1 where id = :id
                """).param("st", st).param("p", pr).param("as", as).param("id", id).update();
        if ("CLOSED".equals(st)) {
            workItems.resolveByKey(a.workspaceId(), "cs-overdue:" + id);
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "CS_INQUIRY_UPDATED", "CS_INQUIRY", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope((UUID) q.get("business_entity_id"), (UUID) q.get("channel_account_id"))
                .before(Map.of("status", q.get("status"), "assignee", String.valueOf(q.get("assignee_member_id"))))
                .after(Map.of("status", st, "assignee", String.valueOf(as), "priority", pr)).build());
        return get(id);
    }

    @Transactional
    public InquiryView linkClaim(UUID id, UUID claimId) {
        guard.requirePermission(Perm.ORDER_UPDATE);
        CurrentActor a = guard.actor();
        Map<String, Object> q = lock(a.workspaceId(), id);
        guard.checkWrite("CS_INQUIRY", id, (UUID) q.get("business_entity_id"), (UUID) q.get("channel_account_id"));
        if (claimId == null || q.get("order_id") == null || jdbc.sql("select count(*) from claim where id = :c and order_id = :o").param("c", claimId)
                .param("o", q.get("order_id")).query(Integer.class).single() == 0) {
            throw Errors.validation("claim_id", "이 문의 주문의 클레임을 선택하세요.");
        }
        jdbc.sql("update cs_inquiry set claim_id = :c, updated_at = now(), version = version + 1 where id = :id").param("c", claimId).param("id", id).update();
        message(a, id, "SYSTEM", "클레임 연결", null);
        return get(id);
    }

    // ---------------------------------------------------------------- 조회

    public List<InquiryView> list(List<String> businessEntityIds, String status, String type, Boolean mine, Boolean overdueOnly, UUID orderId, String q) {
        guard.requirePermission(Perm.ORDER_READ);
        CurrentActor a = guard.actor();
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        Set<UUID> cas = guard.scope().channelAccountIds(AccessKind.READ);
        if (bes.isEmpty() || cas.isEmpty()) {
            return List.of();
        }
        Map<String, Object> p = new LinkedHashMap<>(Map.of("ws", a.workspaceId(), "bes", bes, "cas", cas));
        StringBuilder w = new StringBuilder(" where c.workspace_id = :ws and c.business_entity_id in (:bes) and c.channel_account_id in (:cas)");
        if (status != null && !status.isBlank()) {
            if ("ACTIVE".equals(status)) {
                w.append(" and c.status <> 'CLOSED'");
            } else {
                w.append(" and c.status = :st");
                p.put("st", status);
            }
        }
        if (type != null && !type.isBlank()) {
            w.append(" and c.inquiry_type = :t");
            p.put("t", type);
        }
        if (Boolean.TRUE.equals(mine)) {
            w.append(" and c.assignee_member_id = :me");
            p.put("me", a.memberId());
        }
        if (Boolean.TRUE.equals(overdueOnly)) {
            w.append(" and c.status in ('OPEN', 'IN_PROGRESS') and c.due_at < now()");
        }
        if (orderId != null) {
            w.append(" and c.order_id = :o");
            p.put("o", orderId);
        }
        if (q != null && !q.isBlank()) {
            w.append(" and (c.inquiry_no ilike :q or c.title ilike :q or c.external_inquiry_key ilike :q or so.order_no ilike :q or so.external_order_key ilike :q)");
            p.put("q", "%" + q.trim() + "%");
        }
        return jdbc.sql(SELECT + w + """
                 order by case when c.status in ('OPEN', 'IN_PROGRESS') then 0 else 1 end,
                          case c.priority when 'URGENT' then 0 when 'HIGH' then 1 when 'NORMAL' then 2 else 3 end, c.due_at limit 300
                """).params(p).query(this::map).list();
    }

    public InquiryView get(UUID id) {
        guard.requirePermission(Perm.ORDER_READ);
        CurrentActor a = guard.actor();
        InquiryView v = jdbc.sql(SELECT + " where c.workspace_id = :ws and c.id = :id").param("ws", a.workspaceId()).param("id", id).query(this::map).optional()
                .orElseThrow(() -> Errors.notFound("CS_INQUIRY"));
        guard.checkRead("CS_INQUIRY", id, v.businessEntityId(), v.channelAccountId());
        List<MessageView> messages = jdbc.sql("""
                select m.*, (select u.display_name from workspace_member x join user_account u on u.id = x.user_id where x.id = m.created_by) as by_name
                  from cs_message m where m.inquiry_id = :id order by m.created_at, m.id
                """).param("id", id).query((rs, i) -> new MessageView(Db.uuid(rs, "id"), rs.getString("direction"), rs.getString("body"), Db.uuid(rs, "template_id"),
                rs.getString("channel_status"), rs.getString("by_name"), Db.instant(rs, "created_at"), Db.instant(rs, "channel_marked_at"))).list();
        Map<String, Object> summary = v.orderId() == null ? Map.of() : orderSummary(v.orderId());
        return new InquiryView(v.id(), v.inquiryNo(), v.businessEntityId(), v.businessEntityName(), v.channelAccountId(), v.channelAccountName(), v.orderId(), v.orderNo(),
                v.externalOrderKey(), v.claimId(), v.source(), v.externalInquiryKey(), v.inquiryType(), v.title(), v.status(), v.priority(), v.assigneeMemberId(),
                v.assigneeName(), v.dueAt(), v.overdue(), v.customerReply(), v.customerNameMasked(), v.firstResponseAt(), v.answeredAt(), v.closedAt(), v.createdAt(),
                v.lastMessage(), v.pendingChannelAnswers(), messages, summary, v.version());
    }

    public Kpi kpi(List<String> businessEntityIds) {
        guard.requirePermission(Perm.ORDER_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        Set<UUID> cas = guard.scope().channelAccountIds(AccessKind.READ);
        if (bes.isEmpty() || cas.isEmpty()) {
            return new Kpi(0, 0, 0, 0, 0, 0);
        }
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Map<String, Object> r = jdbc.sql("""
                select count(*) filter (where status = 'OPEN') as open,
                       count(*) filter (where status in ('OPEN', 'IN_PROGRESS') and due_at < (cast(:d as date) + 1)::timestamp at time zone 'Asia/Seoul' and due_at >= now()) as due_today,
                       count(*) filter (where status in ('OPEN', 'IN_PROGRESS') and due_at < now()) as overdue,
                       count(*) filter (where status = 'IN_PROGRESS') as in_progress,
                       count(*) filter (where customer_reply and status <> 'CLOSED') as replied,
                       (select count(*) from cs_message m join cs_inquiry x on x.id = m.inquiry_id where x.workspace_id = :ws and x.business_entity_id in (:bes)
                           and x.channel_account_id in (:cas) and m.channel_status = 'MANUAL_REQUIRED') as pending_post
                  from cs_inquiry where workspace_id = :ws and business_entity_id in (:bes) and channel_account_id in (:cas)
                """).param("ws", guard.actor().workspaceId()).param("bes", bes).param("cas", cas).param("d", today).query().listOfRows().get(0);
        return new Kpi(i(r.get("open")), i(r.get("due_today")), i(r.get("overdue")), i(r.get("in_progress")), i(r.get("replied")), i(r.get("pending_post")));
    }

    // ---------------------------------------------------------------- 템플릿

    public List<TemplateView> templates(UUID businessEntityId) {
        guard.requirePermission(Perm.ORDER_READ);
        return jdbc.sql("""
                select * from cs_template where workspace_id = :ws and (business_entity_id is null or cast(:be as uuid) is null or business_entity_id = :be)
                 order by status, name
                """).param("ws", guard.actor().workspaceId()).param("be", businessEntityId).query((rs, i) -> new TemplateView(Db.uuid(rs, "id"),
                Db.uuid(rs, "business_entity_id"), rs.getString("name"), rs.getString("inquiry_type"), rs.getString("body"), rs.getString("status"), rs.getInt("version")))
                .list();
    }

    @Transactional
    public TemplateView saveTemplate(UUID id, TemplateInput in) {
        guard.requirePermission(Perm.ORDER_UPDATE);
        CurrentActor a = guard.actor();
        if (in == null) {
            throw Errors.validation("name", "템플릿 내용을 입력하세요.");
        }
        if (in.businessEntityId() != null) {
            guard.checkWrite("BUSINESS_ENTITY", in.businessEntityId(), in.businessEntityId(), null);
        }
        String name = trim(in.name(), 100, "name");
        String body = trim(in.body(), 5000, "body");
        if (name == null || body == null) {
            throw Errors.validation("name", "템플릿 이름과 내용을 입력하세요.");
        }
        if (in.inquiryType() != null && !TYPES.contains(in.inquiryType())) {
            throw Errors.validation("inquiry_type", "문의 유형을 확인하세요.");
        }
        String status = in.status() == null ? "ACTIVE" : in.status();
        UUID tid = id == null ? Ids.newId() : id;
        if (id == null) {
            jdbc.sql("""
                    insert into cs_template (id, workspace_id, business_entity_id, name, inquiry_type, body, status, created_by)
                    values (:id, :ws, cast(:be as uuid), :n, cast(:t as text), :b, :s, :by)
                    """).param("id", tid).param("ws", a.workspaceId()).param("be", in.businessEntityId()).param("n", name).param("t", in.inquiryType()).param("b", body)
                    .param("s", status).param("by", a.memberId()).update();
        } else if (jdbc.sql("""
                update cs_template set name = :n, inquiry_type = cast(:t as text), body = :b, status = :s, updated_at = now(), version = version + 1
                 where workspace_id = :ws and id = :id
                """).param("n", name).param("t", in.inquiryType()).param("b", body).param("s", status).param("ws", a.workspaceId()).param("id", id).update() == 0) {
            throw Errors.notFound("CS_TEMPLATE");
        }
        return templates(null).stream().filter(t -> t.id().equals(tid)).findFirst().orElseThrow();
    }

    /** 템플릿 변수 채우기: {고객명}(마스킹 — 원문은 개인정보 권한으로 채널에서 확인) {주문번호} {상품} {송장번호}. */
    public Map<String, Object> render(UUID inquiryId, UUID templateId) {
        InquiryView v = get(inquiryId);
        String body = jdbc.sql("select body from cs_template where workspace_id = :ws and id = :id and status = 'ACTIVE'").param("ws", guard.actor().workspaceId())
                .param("id", templateId).query(String.class).optional().orElseThrow(() -> Errors.notFound("CS_TEMPLATE"));
        Map<String, Object> s = v.orderSummary();
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("{고객명}", v.customerNameMasked() == null ? "고객" : v.customerNameMasked());
        vars.put("{주문번호}", v.externalOrderKey() == null ? "" : v.externalOrderKey());
        vars.put("{상품}", String.valueOf(s.getOrDefault("first_item", "")));
        vars.put("{송장번호}", String.valueOf(s.getOrDefault("tracking_no", "")));
        String out = body;
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (var e : vars.entrySet()) {
            if (out.contains(e.getKey()) && e.getValue().isEmpty()) {
                missing.add(e.getKey());
            }
            out = out.replace(e.getKey(), e.getValue());
        }
        return Map.of("body", out, "missing_variables", missing);
    }

    // ---------------------------------------------------------------- 응답기한 감시

    @Scheduled(fixedDelayString = "${commerce.cs.watch-interval:PT30M}", initialDelayString = "${commerce.cs.watch-initial-delay:PT5M}")
    public void scheduled() {
        scanOverdue(Instant.now());
    }

    /** @return 새로 만든 응답기한 초과 업무 수 */
    public int scanOverdue(Instant now) {
        return TenantContext.asSystem(() -> tx.execute(status -> {
            int n = 0;
            for (Map<String, Object> q : jdbc.sql("""
                    select c.id, c.workspace_id, c.business_entity_id, c.channel_account_id, c.inquiry_no, c.title, c.due_at from cs_inquiry c
                     where c.status in ('OPEN', 'IN_PROGRESS') and c.due_at < :now
                       and not exists (select 1 from work_item w where w.workspace_id = c.workspace_id and w.dedupe_key = 'cs-overdue:' || c.id
                                         and w.status in ('OPEN', 'IN_PROGRESS', 'ON_HOLD'))
                    """).param("now", Db.ts(now)).query().listOfRows()) {
                workItems.raise(new WorkItemService.Raise((UUID) q.get("workspace_id"), (UUID) q.get("business_entity_id"), (UUID) q.get("channel_account_id"), null,
                        "CS_RESPONSE_OVERDUE", "HIGH", "CS 응답기한 초과: " + q.get("inquiry_no"), "문의 '" + q.get("title") + "' 의 응답기한이 지났습니다.",
                        "CS 통합함에서 답변을 기록하고 채널에 등록하세요.", "cs-overdue:" + q.get("id"), Map.of("inquiry_id", q.get("id").toString())));
                n++;
            }
            return n;
        }));
    }

    // ---------------------------------------------------------------- 내부

    static final String SELECT = """
            select c.*, be.display_name as be_name, ca.display_name as ca_name, so.order_no, so.external_order_key,
                   (select u.display_name from workspace_member x join user_account u on u.id = x.user_id where x.id = c.assignee_member_id) as assignee_name,
                   (select m.body from cs_message m where m.inquiry_id = c.id and m.direction <> 'SYSTEM' order by m.created_at desc, m.id desc limit 1) as last_message,
                   (select count(*) from cs_message m where m.inquiry_id = c.id and m.channel_status = 'MANUAL_REQUIRED') as pending_post
              from cs_inquiry c join business_entity be on be.id = c.business_entity_id join channel_account ca on ca.id = c.channel_account_id
              left join sales_order so on so.id = c.order_id
            """;

    private InquiryView map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Instant due = Db.instant(rs, "due_at");
        String st = rs.getString("status");
        String last = rs.getString("last_message");
        return new InquiryView(Db.uuid(rs, "id"), rs.getString("inquiry_no"), Db.uuid(rs, "business_entity_id"), rs.getString("be_name"), Db.uuid(rs, "channel_account_id"),
                rs.getString("ca_name"), Db.uuid(rs, "order_id"), rs.getString("order_no"), rs.getString("external_order_key"), Db.uuid(rs, "claim_id"),
                rs.getString("source"), rs.getString("external_inquiry_key"), rs.getString("inquiry_type"), rs.getString("title"), st, rs.getString("priority"),
                Db.uuid(rs, "assignee_member_id"), rs.getString("assignee_name"), due, Set.of("OPEN", "IN_PROGRESS").contains(st) && due.isBefore(Instant.now()),
                rs.getBoolean("customer_reply"), rs.getString("customer_name_masked"), Db.instant(rs, "first_response_at"), Db.instant(rs, "answered_at"),
                Db.instant(rs, "closed_at"), Db.instant(rs, "created_at"), last == null ? null : last.length() > 80 ? last.substring(0, 80) + "…" : last,
                rs.getInt("pending_post"), List.of(), Map.of(), rs.getInt("version"));
    }

    private Map<String, Object> orderSummary(UUID orderId) {
        Map<String, Object> o = jdbc.sql("""
                select so.order_no, so.status, so.shipping_status, so.payment_status, so.total_amount,
                       (select i.product_name from order_item i where i.order_id = so.id order by i.line_no limit 1) as first_item,
                       (select s.tracking_no from shipment s where s.order_id = so.id order by s.shipped_at desc limit 1) as tracking_no,
                       (select s.carrier_code from shipment s where s.order_id = so.id order by s.shipped_at desc limit 1) as carrier_code,
                       (select count(*) from claim c where c.order_id = so.id) as claims
                  from sales_order so where so.id = :o
                """).param("o", orderId).query().listOfRows().get(0);
        Map<String, Object> m = new LinkedHashMap<>();
        o.forEach((k, v) -> m.put(k, v == null ? "" : v instanceof java.math.BigDecimal b ? b.toPlainString() : v));
        return m;
    }

    private void message(CurrentActor a, UUID inquiryId, String direction, String body, UUID templateId) {
        jdbc.sql("""
                insert into cs_message (id, workspace_id, inquiry_id, direction, body, template_id, channel_status, created_by)
                values (:id, :ws, :q, :d, :b, cast(:t as uuid), cast(:cs as text), :by)
                """).param("id", Ids.newId()).param("ws", a.workspaceId()).param("q", inquiryId).param("d", direction).param("b", body).param("t", templateId)
                .param("cs", "OUTBOUND".equals(direction) ? "MANUAL_REQUIRED" : null).param("by", a.memberId()).update();
    }

    private Map<String, Object> lock(UUID ws, UUID id) {
        return jdbc.sql("select * from cs_inquiry where workspace_id = :ws and id = :id for update").param("ws", ws).param("id", id).query().listOfRows().stream()
                .findFirst().orElseThrow(() -> Errors.notFound("CS_INQUIRY"));
    }

    private void requireMember(UUID ws, UUID member) {
        if (jdbc.sql("select count(*) from workspace_member where workspace_id = :ws and id = :id and status = 'ACTIVE'").param("ws", ws).param("id", member)
                .query(Integer.class).single() == 0) {
            throw Errors.validation("assignee_member_id", "이 운영그룹의 활성 사용자가 아닙니다.");
        }
    }

    private int slaHours(String priority) {
        return switch (priority) {
            case "URGENT" -> Math.max(1, defaultSlaHours / 6);
            case "HIGH" -> Math.max(1, defaultSlaHours / 2);
            case "LOW" -> defaultSlaHours * 2;
            default -> defaultSlaHours;
        };
    }

    private String nextNo(UUID ws) {
        LocalDate d = LocalDate.now(ZoneId.of("Asia/Seoul"));
        int n = jdbc.sql("""
                insert into claim_number_counter (workspace_id, kind, day, last_value) values (:ws, 'CS_INQUIRY', :d, 1)
                on conflict (workspace_id, kind, day) do update set last_value = claim_number_counter.last_value + 1 returning last_value
                """).param("ws", ws).param("d", d).query(Integer.class).single();
        return "CS-" + d.toString().replace("-", "") + "-" + String.format("%04d", n);
    }

    static String maskName(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String s = v.trim();
        return s.length() == 1 ? "*" : s.length() == 2 ? s.charAt(0) + "*" : s.charAt(0) + "*".repeat(s.length() - 2) + s.charAt(s.length() - 1);
    }

    private static int i(Object o) {
        return ((Number) o).intValue();
    }

    private static String trim(String s, int max, String field) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        if (t.length() > max) {
            throw Errors.validation(field, max + "자 이내로 입력하세요.");
        }
        return t;
    }
}
