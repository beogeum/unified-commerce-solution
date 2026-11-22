package com.commerceos.catalog;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.platform.outbox.OutboxPublisher;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GWP 캠페인(M2 C5, FR-CAT-07, docs/13 §3). 사업자 범위(READ/WRITE)를 따른다.
 * 초안에서만 조건·사은품을 바꾸고, 운영 중에는 일시중지·종료·종료일 연장·선착순 수량 증가만 허용한다(지급 기준이 도중에 바뀌지 않게).
 */
@Service
public class GwpService {
    static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "DRAFT", Set.of("ACTIVE", "ENDED"),
            "ACTIVE", Set.of("PAUSED", "ENDED"),
            "PAUSED", Set.of("ACTIVE", "ENDED"),
            "ENDED", Set.of());
    static final Set<String> GIFT_TYPES = Set.of("GIFT", "SAMPLE", "NORMAL");

    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AuditService audit;
    private final OutboxPublisher outbox;
    private final Clock clock;

    public GwpService(JdbcClient jdbc, AccessGuard guard, AuditService audit, OutboxPublisher outbox, Clock clock) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.outbox = outbox;
        this.clock = clock;
    }

    public record RequiredSku(UUID skuId, Integer minQuantity) {
    }

    public record RewardInput(UUID giftSkuId, Integer quantity) {
    }

    public record Upsert(UUID businessEntityId, String name, Instant startsAt, Instant endsAt, Integer priority, String stackGroup,
                         String stackPolicy, BigDecimal minOrderAmount, UUID requiredBrandId, String conditionMode, Integer maxPerOrder,
                         Integer totalLimit, String outOfStockPolicy, String returnPolicy, String channelOverlapPolicy, String channelCampaignRef,
                         String note, List<UUID> channelAccountIds, List<RequiredSku> requiredSkus, List<UUID> excludedSkuIds,
                         List<RewardInput> rewards) {
    }

    public record Patch(String status, Instant endsAt, Integer totalLimit) {
    }

    public record SkuRef(UUID skuId, String skuCode, int quantity, int priority) {
    }

    public record CampaignView(UUID id, UUID businessEntityId, String businessEntityName, String name, Instant startsAt, Instant endsAt,
                               String status, int priority, String stackGroup, String stackPolicy, BigDecimal minOrderAmount, UUID requiredBrandId,
                               String requiredBrandName, String conditionMode, int maxPerOrder, Integer totalLimit, String outOfStockPolicy,
                               String returnPolicy, String channelOverlapPolicy, String channelCampaignRef, String note,
                               List<UUID> channelAccountIds, List<SkuRef> requiredSkus, List<SkuRef> excludedSkus, List<SkuRef> rewards,
                               int version, Instant updatedAt) {
    }

    public record PreviewLine(UUID skuId, Integer quantity, BigDecimal amount) {
    }

    public record PreviewGift(String channelCampaignRef, UUID giftSkuId) {
    }

    public record Preview(UUID businessEntityId, UUID channelAccountId, Instant orderedAt, List<PreviewLine> lines, List<PreviewGift> channelGifts) {
    }

    // ---------------- 조회 ----------------

    public List<CampaignView> list(List<String> businessEntityIds, String status) {
        guard.requirePermission(Perm.CATALOG_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        return jdbc.sql(SELECT + " where g.workspace_id = :ws and g.business_entity_id = any(:bes) and (cast(:st as text) is null or g.status = :st) order by g.starts_at desc limit 300")
                .param("ws", ws()).param("bes", Db.uuids(bes)).param("st", status).query(this::map).list().stream().map(this::withParts).toList();
    }

    public CampaignView get(UUID id) {
        guard.requirePermission(Perm.CATALOG_READ);
        CampaignView c = find(id).orElseThrow(() -> Errors.notFound("GWP_CAMPAIGN"));
        guard.checkRead("GWP_CAMPAIGN", id, c.businessEntityId(), null);
        return c;
    }

    // ---------------- 변경 ----------------

    @Transactional
    public CampaignView create(Upsert u) {
        guard.requirePermission(Perm.CATALOG_MANAGE);
        CurrentActor a = guard.actor();
        if (u.businessEntityId() == null) {
            throw Errors.validation("business_entity_id", "행사 사업자를 선택하세요.");
        }
        requireBusiness(u.businessEntityId());
        guard.checkWrite("GWP_CAMPAIGN", null, u.businessEntityId(), null);
        UUID id = Ids.newId();
        jdbc.sql("insert into gwp_campaign (id, workspace_id, business_entity_id, name, starts_at, ends_at, created_by) values (:id, :ws, :be, 'tmp', now(), now() + interval '1 day', :by)")
                .param("id", id).param("ws", a.workspaceId()).param("be", u.businessEntityId()).param("by", a.memberId()).update();
        write(a, id, u);
        CampaignView v = find(id).orElseThrow();
        audit.record(AuditEntry.builder(a.workspaceId(), "GWP_CAMPAIGN_CREATED", "GWP_CAMPAIGN", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(v.businessEntityId(), null).after(snapshot(v)).build());
        return v;
    }

    @Transactional
    public CampaignView update(UUID id, int expectedVersion, Upsert u) {
        guard.requirePermission(Perm.CATALOG_MANAGE);
        CurrentActor a = guard.actor();
        CampaignView before = lock(id);
        guard.checkWrite("GWP_CAMPAIGN", id, before.businessEntityId(), null);
        CatalogRules.checkVersion(expectedVersion, before.version());
        if (!"DRAFT".equals(before.status())) {
            throw Errors.conflict("GWP_NOT_DRAFT", "운영을 시작한 캠페인은 조건·사은품을 바꿀 수 없습니다. 종료 후 새 캠페인을 만드세요(종료일 연장·선착순 수량 증가는 가능).");
        }
        if (u.businessEntityId() != null && !u.businessEntityId().equals(before.businessEntityId())) {
            throw Errors.conflict("GWP_BUSINESS_IMMUTABLE", "행사 사업자는 바꿀 수 없습니다.");
        }
        jdbc.sql("delete from gwp_campaign_account where campaign_id = :id").param("id", id).update();
        jdbc.sql("delete from gwp_campaign_sku where campaign_id = :id").param("id", id).update();
        jdbc.sql("delete from gwp_reward where campaign_id = :id").param("id", id).update();
        write(a, id, new Upsert(before.businessEntityId(), u.name(), u.startsAt(), u.endsAt(), u.priority(), u.stackGroup(), u.stackPolicy(),
                u.minOrderAmount(), u.requiredBrandId(), u.conditionMode(), u.maxPerOrder(), u.totalLimit(), u.outOfStockPolicy(), u.returnPolicy(),
                u.channelOverlapPolicy(), u.channelCampaignRef(), u.note(), u.channelAccountIds(), u.requiredSkus(), u.excludedSkuIds(), u.rewards()));
        jdbc.sql("update gwp_campaign set version = version + 1, updated_at = now() where id = :id").param("id", id).update();
        CampaignView after = find(id).orElseThrow();
        audit.record(AuditEntry.builder(a.workspaceId(), "GWP_CAMPAIGN_UPDATED", "GWP_CAMPAIGN", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .scope(after.businessEntityId(), null).before(snapshot(before)).after(snapshot(after)).build());
        return after;
    }

    @Transactional
    public CampaignView patch(UUID id, int expectedVersion, Patch p) {
        guard.requirePermission(Perm.CATALOG_MANAGE);
        CurrentActor a = guard.actor();
        CampaignView before = lock(id);
        guard.checkWrite("GWP_CAMPAIGN", id, before.businessEntityId(), null);
        CatalogRules.checkVersion(expectedVersion, before.version());
        Instant now = clock.instant();
        String next = p.status() == null ? before.status() : p.status();
        if (!next.equals(before.status())) {
            CatalogRules.requireEnum("status", next, TRANSITIONS.keySet());
            if (!TRANSITIONS.get(before.status()).contains(next)) {
                throw Errors.conflict("INVALID_STATE_TRANSITION", "캠페인 상태를 바꿀 수 없습니다.", Map.of("from", before.status(), "to", next));
            }
            if ("ACTIVE".equals(next)) {
                if (before.rewards().isEmpty()) {
                    throw Errors.unprocessable("GWP_NO_REWARD", "사은품이 없는 캠페인은 시작할 수 없습니다.");
                }
                if (!before.endsAt().isAfter(now)) {
                    throw Errors.unprocessable("GWP_ALREADY_EXPIRED", "종료일이 지난 캠페인은 시작할 수 없습니다.");
                }
            }
        }
        Instant ends = before.endsAt();
        if (p.endsAt() != null && !p.endsAt().equals(before.endsAt())) {
            if (!"DRAFT".equals(before.status()) && p.endsAt().isBefore(before.endsAt())) {
                throw Errors.unprocessable("GWP_END_SHORTEN_FORBIDDEN", "운영 중 캠페인의 종료일은 앞당길 수 없습니다. 바로 끝내려면 '종료'하세요.");
            }
            if (!p.endsAt().isAfter(before.startsAt())) {
                throw Errors.validation("ends_at", "종료일은 시작일보다 뒤여야 합니다.");
            }
            ends = p.endsAt();
        }
        Integer limit = before.totalLimit();
        if (p.totalLimit() != null && !p.totalLimit().equals(before.totalLimit())) {
            if (!"DRAFT".equals(before.status()) && before.totalLimit() != null && p.totalLimit() < before.totalLimit()) {
                throw Errors.unprocessable("GWP_LIMIT_DECREASE_FORBIDDEN", "운영 중 캠페인의 선착순 수량은 줄일 수 없습니다.");
            }
            if (p.totalLimit() < 1) {
                throw Errors.validation("total_limit", "선착순 수량은 1 이상이어야 합니다.");
            }
            limit = p.totalLimit();
        }
        jdbc.sql("update gwp_campaign set status = :st, ends_at = :e, total_limit = :l, version = version + 1, updated_at = now() where id = :id")
                .param("st", next).param("e", Db.ts(ends)).param("l", limit).param("id", id).update();
        CampaignView after = find(id).orElseThrow();
        audit.record(AuditEntry.builder(a.workspaceId(), next.equals(before.status()) ? "GWP_CAMPAIGN_UPDATED" : "GWP_CAMPAIGN_STATUS_CHANGED",
                "GWP_CAMPAIGN", id).actor(ActorType.USER, a.memberId(), a.displayName()).scope(after.businessEntityId(), null)
                .before(snapshot(before)).after(snapshot(after)).build());
        outbox.publish(a.workspaceId(), after.businessEntityId(), "GWP_CAMPAIGN", id, "GwpCampaignChanged",
                Map.of("campaign_id", id.toString(), "status", after.status()));
        return after;
    }

    /** 주문 없이 판정만 해 본다(M4 주문 처리와 같은 엔진). 선착순 잔여는 지급 기록(M4) 전이라 총수량 그대로. 사은품 재고 검사는 M3 이후. */
    public GwpEvaluator.Result preview(Preview p) {
        guard.requirePermission(Perm.CATALOG_READ);
        if (p.businessEntityId() == null || p.channelAccountId() == null) {
            throw Errors.validation("channel_account_id", "사업자와 판매계정을 선택하세요.");
        }
        requireBusiness(p.businessEntityId());
        guard.checkRead("GWP_CAMPAIGN", null, p.businessEntityId(), p.channelAccountId());
        List<GwpEvaluator.Line> lines = new ArrayList<>();
        for (PreviewLine l : p.lines() == null ? List.<PreviewLine>of() : p.lines()) {
            if (l.skuId() == null || l.quantity() == null || l.quantity() < 1) {
                throw Errors.validation("lines", "주문 상품의 SKU 와 수량(1 이상)을 입력하세요.");
            }
            UUID brand = jdbc.sql("select p.brand_id from sku s join product p on p.workspace_id = s.workspace_id and p.id = s.product_id where s.workspace_id = :ws and s.id = :id")
                    .param("ws", ws()).param("id", l.skuId()).query(UUID.class).optional().orElse(null);
            lines.add(new GwpEvaluator.Line(l.skuId(), l.quantity(), BusinessSkuPolicyService.amount("amount", l.amount() == null ? BigDecimal.ZERO : l.amount()), brand));
        }
        List<GwpEvaluator.ChannelGift> gifts = (p.channelGifts() == null ? List.<PreviewGift>of() : p.channelGifts()).stream()
                .map(g -> new GwpEvaluator.ChannelGift(g.channelCampaignRef(), g.giftSkuId())).toList();
        List<GwpEvaluator.Campaign> campaigns = list(List.of(p.businessEntityId().toString()), null).stream()
                .filter(c -> !"DRAFT".equals(c.status()) && !"ENDED".equals(c.status())).map(GwpService::toEngine).toList();
        Instant at = p.orderedAt() == null ? clock.instant() : p.orderedAt();
        return GwpEvaluator.evaluate(new GwpEvaluator.Order(p.businessEntityId(), p.channelAccountId(), at, lines, gifts), campaigns, null);
    }

    /**
     * 주문 처리(M4 O3, 시스템 문맥)용: 사업자의 진행 중·일시중지 캠페인을 판정 엔진 형식으로. 선착순 잔여는 호출자가 지급 기록으로 계산해 넘긴다.
     */
    public List<GwpEvaluator.Campaign> engineCampaigns(UUID ws, UUID businessEntityId, Map<UUID, Integer> awardedSoFar) {
        return jdbc.sql(SELECT + " where g.workspace_id = :ws and g.business_entity_id = :be and g.status in ('ACTIVE', 'PAUSED')")
                .param("ws", ws).param("be", businessEntityId).query(this::map).list().stream().map(this::withParts).map(GwpService::toEngine)
                .map(c -> c.remainingLimit() == null ? c : new GwpEvaluator.Campaign(c.id(), c.name(), c.businessEntityId(), c.channelAccountIds(), c.startsAt(),
                        c.endsAt(), c.status(), c.priority(), c.stackGroup(), c.stackPolicy(), c.minOrderAmount(), c.requiredBrandId(), c.conditionMode(),
                        c.requiredSkus(), c.excludedSkus(), c.rewards(), c.maxPerOrder(), Math.max(0, c.remainingLimit() - awardedSoFar.getOrDefault(c.id(), 0)),
                        c.outOfStockPolicy(), c.channelOverlapPolicy(), c.channelCampaignRef()))
                .toList();
    }

    static GwpEvaluator.Campaign toEngine(CampaignView c) {
        Map<UUID, Integer> required = new LinkedHashMap<>();
        c.requiredSkus().forEach(r -> required.put(r.skuId(), r.quantity()));
        Set<UUID> excluded = new HashSet<>();
        c.excludedSkus().forEach(r -> excluded.add(r.skuId()));
        List<GwpEvaluator.Reward> rewards = c.rewards().stream().map(r -> new GwpEvaluator.Reward(r.skuId(), r.quantity(), r.priority())).toList();
        return new GwpEvaluator.Campaign(c.id(), c.name(), c.businessEntityId(), new HashSet<>(c.channelAccountIds()), c.startsAt(), c.endsAt(),
                c.status(), c.priority(), c.stackGroup(), c.stackPolicy(), c.minOrderAmount(), c.requiredBrandId(), c.conditionMode(), required, excluded,
                rewards, c.maxPerOrder(), c.totalLimit(), c.outOfStockPolicy(), c.channelOverlapPolicy(), c.channelCampaignRef());
    }

    // ---------------- 내부 ----------------

    private void write(CurrentActor a, UUID id, Upsert u) {
        String name = CatalogRules.requireText("name", u.name(), 100);
        if (u.startsAt() == null || u.endsAt() == null || !u.endsAt().isAfter(u.startsAt())) {
            throw Errors.validation("ends_at", "행사 기간(시작·종료)을 확인하세요. 종료는 시작보다 뒤여야 합니다.");
        }
        String stackPolicy = u.stackPolicy() == null ? "ONE_PER_GROUP" : u.stackPolicy();
        CatalogRules.requireEnum("stack_policy", stackPolicy, Set.of("CUMULATIVE", "ONE_PER_GROUP", "HIGHEST_PRIORITY_ONLY"));
        String mode = u.conditionMode() == null ? "ALL" : u.conditionMode();
        CatalogRules.requireEnum("condition_mode", mode, Set.of("ALL", "ANY"));
        String oos = u.outOfStockPolicy() == null ? "SUBSTITUTE" : u.outOfStockPolicy();
        CatalogRules.requireEnum("out_of_stock_policy", oos, Set.of("STOP", "SUBSTITUTE", "PROCEED_WITHOUT", "HOLD_ORDER"));
        String ret = u.returnPolicy() == null ? "REEVALUATE" : u.returnPolicy();
        CatalogRules.requireEnum("return_policy", ret, Set.of("REEVALUATE", "NO_RECOVERY"));
        String overlap = u.channelOverlapPolicy() == null ? "SKIP_INTERNAL" : u.channelOverlapPolicy();
        CatalogRules.requireEnum("channel_overlap_policy", overlap, Set.of("SKIP_INTERNAL", "ALLOW_BOTH"));
        int priority = u.priority() == null ? 100 : u.priority();
        int maxPer = u.maxPerOrder() == null ? 1 : u.maxPerOrder();
        if (priority < 0 || priority > 10000 || maxPer < 1 || maxPer > 100 || (u.totalLimit() != null && u.totalLimit() < 1)) {
            throw Errors.validation("priority", "우선순위(0~10000)·주문당 지급 수(1~100)·선착순 수량(1 이상)을 확인하세요.");
        }
        if (u.stackGroup() != null && !u.stackGroup().isBlank() && !u.stackGroup().matches("^[A-Za-z0-9_-]{1,40}$")) {
            throw Errors.validation("stack_group", "중복 그룹은 영문·숫자·하이픈·밑줄 40자 이내로 입력하세요.");
        }
        if (u.requiredBrandId() != null && jdbc.sql("select count(*) from brand where workspace_id = :ws and id = :id").param("ws", a.workspaceId())
                .param("id", u.requiredBrandId()).query(Integer.class).single() == 0) {
            throw Errors.unprocessable("BRAND_NOT_AVAILABLE", "브랜드를 찾을 수 없습니다.");
        }
        jdbc.sql("""
                update gwp_campaign set name = :n, starts_at = :s, ends_at = :e, priority = :p, stack_group = :g, stack_policy = :sp,
                       min_order_amount = :min, required_brand_id = :brand, condition_mode = :mode, max_per_order = :maxp, total_limit = :lim,
                       out_of_stock_policy = :oos, return_policy = :ret, channel_overlap_policy = :ov, channel_campaign_ref = :ref, note = :note
                 where id = :id
                """).param("n", name).param("s", Db.ts(u.startsAt())).param("e", Db.ts(u.endsAt())).param("p", priority)
                .param("g", u.stackGroup() == null || u.stackGroup().isBlank() ? null : u.stackGroup()).param("sp", stackPolicy)
                .param("min", BusinessSkuPolicyService.amount("min_order_amount", u.minOrderAmount())).param("brand", u.requiredBrandId())
                .param("mode", mode).param("maxp", maxPer).param("lim", u.totalLimit()).param("oos", oos).param("ret", ret).param("ov", overlap)
                .param("ref", CatalogRules.optionalText("channel_campaign_ref", u.channelCampaignRef(), 100))
                .param("note", CatalogRules.optionalText("note", u.note(), 500)).param("id", id).update();
        UUID be = u.businessEntityId();
        for (UUID ca : u.channelAccountIds() == null ? List.<UUID>of() : new HashSet<>(u.channelAccountIds())) {
            if (jdbc.sql("select count(*) from channel_account where workspace_id = :ws and id = :ca and business_entity_id = :be")
                    .param("ws", a.workspaceId()).param("ca", ca).param("be", be).query(Integer.class).single() == 0) {
                throw Errors.unprocessable("CHANNEL_ACCOUNT_NOT_IN_BUSINESS", "행사 사업자의 판매계정만 대상으로 지정할 수 있습니다.");
            }
            jdbc.sql("insert into gwp_campaign_account (workspace_id, campaign_id, channel_account_id) values (:ws, :c, :ca)")
                    .param("ws", a.workspaceId()).param("c", id).param("ca", ca).update();
        }
        Set<UUID> required = new HashSet<>();
        for (RequiredSku r : u.requiredSkus() == null ? List.<RequiredSku>of() : u.requiredSkus()) {
            if (r.skuId() == null || !required.add(r.skuId())) {
                throw Errors.validation("required_skus", "조건 SKU 를 중복 없이 선택하세요.");
            }
            requireSku(r.skuId());
            int min = r.minQuantity() == null ? 1 : r.minQuantity();
            if (min < 1) {
                throw Errors.validation("min_quantity", "최소 수량은 1 이상이어야 합니다.");
            }
            jdbc.sql("insert into gwp_campaign_sku (workspace_id, campaign_id, sku_id, role, min_quantity) values (:ws, :c, :s, 'REQUIRED', :m)")
                    .param("ws", a.workspaceId()).param("c", id).param("s", r.skuId()).param("m", min).update();
        }
        for (UUID ex : u.excludedSkuIds() == null ? List.<UUID>of() : new HashSet<>(u.excludedSkuIds())) {
            if (required.contains(ex)) {
                throw Errors.unprocessable("GWP_SKU_BOTH_REQUIRED_AND_EXCLUDED", "같은 SKU 를 조건과 제외에 함께 넣을 수 없습니다.");
            }
            requireSku(ex);
            jdbc.sql("insert into gwp_campaign_sku (workspace_id, campaign_id, sku_id, role) values (:ws, :c, :s, 'EXCLUDED')")
                    .param("ws", a.workspaceId()).param("c", id).param("s", ex).update();
        }
        int priorityIndex = 0;
        Set<UUID> gifts = new HashSet<>();
        for (RewardInput r : u.rewards() == null ? List.<RewardInput>of() : u.rewards()) {
            if (r.giftSkuId() == null || !gifts.add(r.giftSkuId())) {
                throw Errors.validation("rewards", "사은품 SKU 를 중복 없이 선택하세요.");
            }
            Map<String, Object> s = requireSku(r.giftSkuId());
            if (!GIFT_TYPES.contains(s.get("sku_type")) || !Boolean.TRUE.equals(s.get("inventory_managed")) || !"ACTIVE".equals(s.get("status"))) {
                throw Errors.unprocessable("GWP_GIFT_INVALID", "사은품은 운영 중인 재고관리 SKU(증정품·샘플·일반)여야 합니다: " + s.get("sku_code"));
            }
            int q = r.quantity() == null ? 1 : r.quantity();
            if (q < 1 || q > 100) {
                throw Errors.validation("quantity", "사은품 수량은 1~100 이어야 합니다.");
            }
            jdbc.sql("insert into gwp_reward (workspace_id, campaign_id, gift_sku_id, quantity, priority) values (:ws, :c, :s, :q, :p)")
                    .param("ws", a.workspaceId()).param("c", id).param("s", r.giftSkuId()).param("q", q).param("p", priorityIndex++).update();
        }
        if (priorityIndex > 21) {
            throw Errors.validation("rewards", "사은품·대체 사은품은 21개까지 지정할 수 있습니다.");
        }
    }

    private Map<String, Object> requireSku(UUID id) {
        return jdbc.sql("select id, sku_code, sku_type, inventory_managed, status from sku where workspace_id = :ws and id = :id")
                .param("ws", ws()).param("id", id).query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("SKU"));
    }

    private void requireBusiness(UUID id) {
        if (jdbc.sql("select count(*) from business_entity where workspace_id = :ws and id = :id").param("ws", ws()).param("id", id)
                .query(Integer.class).single() == 0) {
            throw Errors.notFound("BUSINESS_ENTITY");
        }
    }

    private static final String SELECT = """
            select g.*, be.display_name as be_name, b.name as brand_name from gwp_campaign g
              join business_entity be on be.workspace_id = g.workspace_id and be.id = g.business_entity_id
              left join brand b on b.workspace_id = g.workspace_id and b.id = g.required_brand_id
            """;

    private CampaignView lock(UUID id) {
        jdbc.sql("select id from gwp_campaign where workspace_id = :ws and id = :id for update").param("ws", ws()).param("id", id)
                .query(UUID.class).optional().orElseThrow(() -> Errors.notFound("GWP_CAMPAIGN"));
        return find(id).orElseThrow();
    }

    private Optional<CampaignView> find(UUID id) {
        return jdbc.sql(SELECT + " where g.workspace_id = :ws and g.id = :id").param("ws", ws()).param("id", id).query(this::map).optional()
                .map(this::withParts);
    }

    private CampaignView withParts(CampaignView c) {
        List<UUID> accounts = jdbc.sql("select channel_account_id from gwp_campaign_account where campaign_id = :id").param("id", c.id())
                .query(UUID.class).list();
        Map<String, List<SkuRef>> skus = new HashMap<>();
        jdbc.sql("""
                select gs.role, gs.sku_id, s.sku_code, gs.min_quantity from gwp_campaign_sku gs join sku s on s.workspace_id = gs.workspace_id and s.id = gs.sku_id
                 where gs.campaign_id = :id order by s.sku_code
                """).param("id", c.id()).query((rs, i) -> {
                    skus.computeIfAbsent(rs.getString("role"), k -> new ArrayList<>())
                            .add(new SkuRef(Db.uuid(rs, "sku_id"), rs.getString("sku_code"), rs.getInt("min_quantity"), 0));
                    return null;
                }).list();
        List<SkuRef> rewards = jdbc.sql("""
                select r.gift_sku_id, s.sku_code, r.quantity, r.priority from gwp_reward r join sku s on s.workspace_id = r.workspace_id and s.id = r.gift_sku_id
                 where r.campaign_id = :id order by r.priority
                """).param("id", c.id()).query((rs, i) -> new SkuRef(Db.uuid(rs, "gift_sku_id"), rs.getString("sku_code"), rs.getInt("quantity"),
                rs.getInt("priority"))).list();
        return new CampaignView(c.id(), c.businessEntityId(), c.businessEntityName(), c.name(), c.startsAt(), c.endsAt(), c.status(), c.priority(),
                c.stackGroup(), c.stackPolicy(), c.minOrderAmount(), c.requiredBrandId(), c.requiredBrandName(), c.conditionMode(), c.maxPerOrder(),
                c.totalLimit(), c.outOfStockPolicy(), c.returnPolicy(), c.channelOverlapPolicy(), c.channelCampaignRef(), c.note(), accounts,
                skus.getOrDefault("REQUIRED", List.of()), skus.getOrDefault("EXCLUDED", List.of()), rewards, c.version(), c.updatedAt());
    }

    private CampaignView map(ResultSet rs, int i) throws SQLException {
        Object limit = rs.getObject("total_limit");
        return new CampaignView(Db.uuid(rs, "id"), Db.uuid(rs, "business_entity_id"), rs.getString("be_name"), rs.getString("name"),
                Db.instant(rs, "starts_at"), Db.instant(rs, "ends_at"), rs.getString("status"), rs.getInt("priority"), rs.getString("stack_group"),
                rs.getString("stack_policy"), rs.getBigDecimal("min_order_amount"), Db.uuid(rs, "required_brand_id"), rs.getString("brand_name"),
                rs.getString("condition_mode"), rs.getInt("max_per_order"), limit == null ? null : ((Number) limit).intValue(),
                rs.getString("out_of_stock_policy"), rs.getString("return_policy"), rs.getString("channel_overlap_policy"),
                rs.getString("channel_campaign_ref"), rs.getString("note"), List.of(), List.of(), List.of(), List.of(), rs.getInt("version"),
                Db.instant(rs, "updated_at"));
    }

    private static Map<String, Object> snapshot(CampaignView c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", c.name());
        m.put("status", c.status());
        m.put("period", c.startsAt() + " ~ " + c.endsAt());
        m.put("stack", c.stackGroup() + "/" + c.stackPolicy());
        m.put("rewards", c.rewards().stream().map(r -> r.skuCode() + "×" + r.quantity()).toList());
        m.put("total_limit", String.valueOf(c.totalLimit()));
        m.put("channel_overlap_policy", c.channelOverlapPolicy());
        return m;
    }

    private UUID ws() {
        return guard.actor().workspaceId();
    }
}
