package com.commerceos.catalog;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.identity.access.ResolvedScope;
import com.commerceos.operations.WorkItemService;
import com.commerceos.platform.audit.ActorType;
import com.commerceos.platform.audit.AuditEntry;
import com.commerceos.platform.audit.AuditService;
import com.commerceos.platform.outbox.OutboxPublisher;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.id.Ids;
import com.commerceos.shared.web.PageQuery;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채널상품과 내부 SKU 매핑(M2 C3, FR-CAT-03).
 * <ul>
 *   <li>채널상품 식별 = 판매단위 + 외부상품ID + 외부옵션ID. 같은 번호라도 판매계정·판매단위가 다르면 다른 상품.</li>
 *   <li>매핑은 유효기간 이력. 재연결은 기존 매핑을 닫고 새로 연다(겹침은 DB 배제 제약). 과거 시점의 SKU 를 다시 찾을 수 있다.</li>
 *   <li>예전에 다른 SKU 에 연결됐던 채널상품을 다시 연결하려면 확인과 사유가 필요하다(충돌 방지).</li>
 *   <li>미연결 채널상품은 판매단위별 업무 1건으로 모으고, 모두 연결되면 자동으로 닫는다.</li>
 * </ul>
 */
@Service
public class ChannelListingService {
    static final Set<String> SALE_STATUS = Set.of("ON_SALE", "SUSPENDED", "SOLD_OUT", "UNKNOWN");
    static final Set<String> MATCH_BASIS = Set.of("MANUAL", "BARCODE", "EXTERNAL_SKU_CODE", "NAME", "FILE");

    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AuditService audit;
    private final OutboxPublisher outbox;
    private final WorkItemService workItems;
    private final Clock clock;

    public ChannelListingService(JdbcClient jdbc, AccessGuard guard, AuditService audit, OutboxPublisher outbox, WorkItemService workItems,
                                 Clock clock) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.outbox = outbox;
        this.workItems = workItems;
        this.clock = clock;
    }

    public record MappingView(UUID id, UUID skuId, String skuCode, String productName, int quantity, Instant validFrom, Instant validTo,
                              String matchBasis, String reason, String createdByName) {
    }

    public record ListingView(UUID id, UUID businessEntityId, String businessEntityName, UUID channelAccountId, String channelAccountName,
                              String salesChannelCode, UUID salesUnitId, String salesUnitName, String externalProductId, String externalOptionId,
                              String externalSkuCode, String title, String optionText, String barcode, BigDecimal channelPrice,
                              Integer channelStock, String saleStatus, String source, Instant lastSyncedAt, String status, UUID replacedById,
                              MappingView currentMapping, int distinctSkuCount, int version, Instant createdAt) {
    }

    public record ListingDetail(ListingView listing, List<MappingView> history, UUID previousListingId) {
    }

    public record Suggestion(UUID skuId, String skuCode, String productName, String basis, String confidence, boolean enabledForBusiness) {
    }

    public record Create(UUID salesUnitId, String externalProductId, String externalOptionId, String externalSkuCode, String title,
                         String optionText, String barcode, BigDecimal channelPrice, Integer channelStock, String saleStatus) {
    }

    public record MapRequest(UUID skuId, Integer quantity, String matchBasis, String reason, Boolean confirmRemap) {
    }

    public record IdChange(String externalProductId, String externalOptionId, String reason) {
    }

    public record Resolved(UUID mappingId, UUID skuId, int quantity) {
    }

    public record MapResult(ListingDetail detail, List<SkuService.Warning> warnings) {
    }

    // ---------------- 조회 ----------------

    public List<ListingView> list(PageQuery page, List<String> businessEntityIds, UUID channelAccountId, UUID salesUnitId, String mapping, String q) {
        guard.requirePermission(Perm.CATALOG_READ);
        Set<UUID> bes = guard.businessEntityFilter(AccessKind.READ, businessEntityIds);
        UUID ca = guard.channelAccountFilter(AccessKind.READ, channelAccountId);
        ResolvedScope s = guard.scope();
        if (mapping != null) {
            CatalogRules.requireEnum("mapping", mapping, Set.of("UNMAPPED", "MAPPED", "REMAPPED"));
        }
        String term = q == null || q.isBlank() ? null : q.trim();
        return jdbc.sql(SELECT + """
                 where l.workspace_id = :ws and ca.business_entity_id = any(:bes) and l.channel_account_id = any(:cas)
                   and (cast(:ca as uuid) is null or l.channel_account_id = :ca)
                   and (cast(:su as uuid) is null or l.sales_unit_id = :su)
                   and (cast(:mapping as text) is null
                        or (:mapping = 'UNMAPPED' and l.status = 'ACTIVE' and m.id is null)
                        or (:mapping = 'MAPPED' and m.id is not null)
                        or (:mapping = 'REMAPPED' and (select count(distinct x.sku_id) from channel_listing_mapping x where x.listing_id = l.id) > 1))
                   and (cast(:q as text) is null or l.title ilike '%' || :q || '%' or l.external_product_id = :q or l.external_option_id = :q
                        or l.external_sku_code = :q or l.barcode = :q)
                   and (cast(:after as uuid) is null or l.id < :after)
                 order by l.id desc limit :limit
                """).param("ws", ws()).param("bes", Db.uuids(bes)).param("cas", Db.uuids(s.channelAccountIds(AccessKind.READ)))
                .param("ca", ca).param("su", salesUnitId).param("mapping", mapping).param("q", term)
                .param("after", page.after()).param("limit", page.fetchSize()).query(this::map).list();
    }

    public ListingDetail get(UUID id) {
        guard.requirePermission(Perm.CATALOG_READ);
        ListingView l = find(id).orElseThrow(() -> Errors.notFound("CHANNEL_LISTING"));
        guard.checkRead("CHANNEL_LISTING", id, l.businessEntityId(), l.channelAccountId());
        UUID previous = jdbc.sql("select id from channel_listing where workspace_id = :ws and replaced_by_id = :id")
                .param("ws", ws()).param("id", id).query(UUID.class).optional().orElse(null);
        return new ListingDetail(l, history(id), previous);
    }

    /** 연결 후보: 바코드·외부 SKU 코드 일치(높음), 상품명 단어 일치(낮음). 판매 가능한 운영 SKU 만. */
    public List<Suggestion> suggestions(UUID id) {
        ListingDetail d = get(id);
        ListingView l = d.listing();
        UUID be = l.businessEntityId();
        List<Suggestion> out = new ArrayList<>();
        Set<UUID> seen = new java.util.HashSet<>();
        if (l.barcode() != null) {
            addSuggestions(out, seen, be, "s.barcode = :v", l.barcode(), "BARCODE", "HIGH");
        }
        if (l.externalSkuCode() != null) {
            addSuggestions(out, seen, be, "lower(s.sku_code) = lower(:v)", l.externalSkuCode(), "EXTERNAL_SKU_CODE", "HIGH");
        }
        for (String word : l.title().split("[\\s/,()\\[\\]]+")) {
            if (word.length() >= 2 && out.size() < 10) {
                addSuggestions(out, seen, be, "(p.name ilike '%' || :v || '%' or s.name ilike '%' || :v || '%')", word, "NAME", "LOW");
            }
        }
        return out.size() > 10 ? out.subList(0, 10) : out;
    }

    private void addSuggestions(List<Suggestion> out, Set<UUID> seen, UUID be, String cond, String value, String basis, String confidence) {
        jdbc.sql("""
                select s.id, s.sku_code, p.name as product_name,
                       coalesce(bp.sell_enabled, false) as enabled
                  from sku s join product p on p.workspace_id = s.workspace_id and p.id = s.product_id
                  left join business_sku_policy bp on bp.workspace_id = s.workspace_id and bp.sku_id = s.id and bp.business_entity_id = :be
                 where s.workspace_id = :ws and s.status = 'ACTIVE' and s.sellable and p.status <> 'ARCHIVED' and
                """ + cond + " order by s.sku_code limit 5")
                .param("ws", ws()).param("be", be).param("v", value)
                .query((rs, i) -> new Suggestion(Db.uuid(rs, "id"), rs.getString("sku_code"), rs.getString("product_name"), basis, confidence,
                        rs.getBoolean("enabled"))).list()
                .forEach(x -> {
                    if (seen.add(x.skuId())) {
                        out.add(x);
                    }
                });
    }

    /** 주문 수집(M4)이 쓰는 해석: 그 시각에 유효했던 매핑. */
    public Optional<Resolved> resolve(UUID salesUnitId, String externalProductId, String externalOptionId, Instant at) {
        return jdbc.sql("""
                select m.id, m.sku_id, m.quantity from channel_listing l
                  join channel_listing_mapping m on m.workspace_id = l.workspace_id and m.listing_id = l.id
                 where l.workspace_id = :ws and l.sales_unit_id = :su and l.external_product_id = :p and l.external_option_id = :o
                   and m.valid_from <= :at and (m.valid_to is null or m.valid_to > :at)
                """).param("ws", ws()).param("su", salesUnitId).param("p", externalProductId)
                .param("o", externalOptionId == null ? "" : externalOptionId).param("at", Db.ts(at))
                .query((rs, i) -> new Resolved(Db.uuid(rs, "id"), Db.uuid(rs, "sku_id"), rs.getInt("quantity"))).optional();
    }

    // ---------------- 변경 ----------------

    @Transactional
    public ListingDetail create(Create c) {
        guard.requirePermission(Perm.CATALOG_MANAGE);
        CurrentActor a = guard.actor();
        if (c.salesUnitId() == null) {
            throw Errors.validation("sales_unit_id", "판매단위를 선택하세요.");
        }
        Map<String, Object> unit = jdbc.sql("""
                select su.id, su.channel_account_id, ca.business_entity_id, su.status from sales_unit su
                  join channel_account ca on ca.workspace_id = su.workspace_id and ca.id = su.channel_account_id
                 where su.workspace_id = :ws and su.id = :id
                """).param("ws", a.workspaceId()).param("id", c.salesUnitId()).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> Errors.notFound("SALES_UNIT"));
        UUID ca = (UUID) unit.get("channel_account_id");
        UUID be = (UUID) unit.get("business_entity_id");
        guard.checkWrite("CHANNEL_LISTING", c.salesUnitId(), be, ca);
        String productId = CatalogRules.requireText("external_product_id", c.externalProductId(), 100);
        String optionId = c.externalOptionId() == null ? "" : c.externalOptionId().trim();
        if (optionId.length() > 100) {
            throw Errors.validation("external_option_id", "외부 옵션ID 는 100자 이하로 입력하세요.");
        }
        String status = c.saleStatus() == null ? "UNKNOWN" : c.saleStatus();
        CatalogRules.requireEnum("sale_status", status, SALE_STATUS);
        if (c.channelStock() != null && c.channelStock() < 0) {
            throw Errors.validation("channel_stock", "채널 재고는 0 이상이어야 합니다.");
        }
        UUID id = Ids.newId();
        // 유일키 위반으로 트랜잭션이 중단되지 않도록 on conflict 로 판정한다(중복이면 기존 ID 를 알려 준다)
        int inserted = jdbc.sql("""
                    insert into channel_listing (id, workspace_id, channel_account_id, sales_unit_id, external_product_id, external_option_id,
                                                 external_sku_code, title, option_text, barcode, channel_price, channel_stock, sale_status, source)
                    values (:id, :ws, :ca, :su, :p, :o, :sku, :title, :opt, :bc, :price, :stock, :st, 'MANUAL')
                    on conflict (sales_unit_id, external_product_id, external_option_id) do nothing
                    """).param("id", id).param("ws", a.workspaceId()).param("ca", ca).param("su", c.salesUnitId()).param("p", productId)
                    .param("o", optionId).param("sku", CatalogRules.optionalText("external_sku_code", c.externalSkuCode(), 100))
                    .param("title", CatalogRules.requireText("title", c.title(), 300))
                    .param("opt", CatalogRules.optionalText("option_text", c.optionText(), 300))
                    .param("bc", CatalogRules.optionalText("barcode", c.barcode(), 40))
                    .param("price", BusinessSkuPolicyService.amount("channel_price", c.channelPrice())).param("stock", c.channelStock())
                    .param("st", status).update();
        if (inserted == 0) {
            UUID existing = jdbc.sql("select id from channel_listing where sales_unit_id = :su and external_product_id = :p and external_option_id = :o")
                    .param("su", c.salesUnitId()).param("p", productId).param("o", optionId).query(UUID.class).single();
            throw Errors.conflict("CHANNEL_LISTING_DUPLICATE", "이 판매단위에 같은 외부 상품·옵션이 이미 등록되어 있습니다.",
                    Map.of("existing_id", existing.toString()));
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "CHANNEL_LISTING_CREATED", "CHANNEL_LISTING", id)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(be, ca)
                .after(Map.of("external_product_id", productId, "external_option_id", optionId)).build());
        raiseUnmapped(a.workspaceId(), be, ca, c.salesUnitId());
        return get(id);
    }

    @Transactional
    public MapResult map(UUID id, MapRequest r) {
        guard.requirePermission(Perm.CATALOG_MANAGE);
        CurrentActor a = guard.actor();
        ListingView l = lock(id);
        guard.checkWrite("CHANNEL_LISTING", id, l.businessEntityId(), l.channelAccountId());
        if (!"ACTIVE".equals(l.status())) {
            throw Errors.conflict("CHANNEL_LISTING_NOT_ACTIVE", "외부 ID 가 바뀌었거나 삭제된 채널상품은 연결할 수 없습니다. 새 채널상품을 연결하세요.");
        }
        if (r.skuId() == null) {
            throw Errors.validation("sku_id", "연결할 SKU 를 선택하세요.");
        }
        int qty = r.quantity() == null ? 1 : r.quantity();
        if (qty < 1 || qty > 1000) {
            throw Errors.validation("quantity", "수량은 1~1000 사이여야 합니다.");
        }
        String basis = r.matchBasis() == null ? "MANUAL" : r.matchBasis();
        CatalogRules.requireEnum("match_basis", basis, MATCH_BASIS);
        Map<String, Object> sku = jdbc.sql("""
                select s.id, s.sku_code, s.status, s.sellable, p.status as product_status from sku s
                  join product p on p.workspace_id = s.workspace_id and p.id = s.product_id where s.workspace_id = :ws and s.id = :id
                """).param("ws", a.workspaceId()).param("id", r.skuId()).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> Errors.notFound("SKU"));
        if (!"ACTIVE".equals(sku.get("status")) || !Boolean.TRUE.equals(sku.get("sellable")) || "ARCHIVED".equals(sku.get("product_status"))) {
            throw Errors.unprocessable("SKU_NOT_MAPPABLE", "운영 중이고 판매 가능한 SKU 에만 연결할 수 있습니다.");
        }
        MappingView current = l.currentMapping();
        if (current != null && current.skuId().equals(r.skuId()) && current.quantity() == qty) {
            return new MapResult(get(id), warnings(l, r.skuId())); // 같은 연결 요청은 그대로(멱등)
        }
        List<String> previousOther = jdbc.sql("""
                select distinct s.sku_code from channel_listing_mapping m join sku s on s.workspace_id = m.workspace_id and s.id = m.sku_id
                 where m.workspace_id = :ws and m.listing_id = :id and m.sku_id <> :sku order by s.sku_code
                """).param("ws", a.workspaceId()).param("id", id).param("sku", r.skuId()).query(String.class).list();
        if (!previousOther.isEmpty() && !Boolean.TRUE.equals(r.confirmRemap())) {
            throw Errors.conflict("MAPPING_REMAP_CONFIRMATION_REQUIRED",
                    "이 채널상품은 다른 SKU 에 연결된 이력이 있습니다. 바꾸는 이유를 적고 확인해야 다시 연결할 수 있습니다.",
                    Map.of("previous_sku_codes", previousOther));
        }
        if (!previousOther.isEmpty() && (r.reason() == null || r.reason().isBlank())) {
            throw Errors.validation("reason", "다른 SKU 로 다시 연결하는 이유를 입력하세요.");
        }
        Instant now = clock.instant();
        if (current != null) {
            closeMapping(current.id(), now, a.memberId());
        }
        insertMapping(a, id, r.skuId(), qty, now, basis, CatalogRules.optionalText("reason", r.reason(), 500));
        audit.record(AuditEntry.builder(a.workspaceId(), current == null ? "CHANNEL_LISTING_MAPPED" : "CHANNEL_LISTING_REMAPPED", "CHANNEL_LISTING", id)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(l.businessEntityId(), l.channelAccountId())
                .before(current == null ? Map.of() : Map.of("sku_id", current.skuId().toString(), "quantity", current.quantity()))
                .after(Map.of("sku_id", r.skuId().toString(), "quantity", qty, "match_basis", basis)).build());
        outbox.publish(a.workspaceId(), l.businessEntityId(), "CHANNEL_LISTING", id, "ChannelListingMapped",
                Map.of("listing_id", id.toString(), "sku_id", r.skuId().toString(), "channel_account_id", l.channelAccountId().toString()));
        settleUnmapped(a.workspaceId(), l.salesUnitId());
        return new MapResult(get(id), warnings(l, r.skuId()));
    }

    @Transactional
    public ListingDetail unmap(UUID id, String reason) {
        guard.requirePermission(Perm.CATALOG_MANAGE);
        CurrentActor a = guard.actor();
        ListingView l = lock(id);
        guard.checkWrite("CHANNEL_LISTING", id, l.businessEntityId(), l.channelAccountId());
        if (l.currentMapping() == null) {
            throw Errors.conflict("CHANNEL_LISTING_NOT_MAPPED", "연결된 SKU 가 없습니다.");
        }
        String why = CatalogRules.requireText("reason", reason, 500);
        closeMapping(l.currentMapping().id(), clock.instant(), a.memberId());
        audit.record(AuditEntry.builder(a.workspaceId(), "CHANNEL_LISTING_UNMAPPED", "CHANNEL_LISTING", id)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(l.businessEntityId(), l.channelAccountId())
                .before(Map.of("sku_id", l.currentMapping().skuId().toString())).metadata(Map.of("reason", why)).build());
        raiseUnmapped(a.workspaceId(), l.businessEntityId(), l.channelAccountId(), l.salesUnitId());
        return get(id);
    }

    /** 채널이 상품·옵션 ID 를 바꾼 경우: 새 채널상품을 만들고 매핑을 그대로 옮긴다. 이전 채널상품과 매핑 이력은 남는다. */
    @Transactional
    public ListingDetail changeExternalId(UUID id, IdChange c) {
        guard.requirePermission(Perm.CATALOG_MANAGE);
        CurrentActor a = guard.actor();
        ListingView l = lock(id);
        guard.checkWrite("CHANNEL_LISTING", id, l.businessEntityId(), l.channelAccountId());
        if (!"ACTIVE".equals(l.status())) {
            throw Errors.conflict("CHANNEL_LISTING_NOT_ACTIVE", "이미 바뀌었거나 삭제된 채널상품입니다.");
        }
        String why = CatalogRules.requireText("reason", c.reason(), 500);
        String productId = CatalogRules.requireText("external_product_id", c.externalProductId(), 100);
        String optionId = c.externalOptionId() == null ? "" : c.externalOptionId().trim();
        if (productId.equals(l.externalProductId()) && optionId.equals(l.externalOptionId())) {
            throw Errors.validation("external_product_id", "바뀐 외부 ID 를 입력하세요.");
        }
        UUID newId = Ids.newId();
        try {
            jdbc.sql("""
                    insert into channel_listing (id, workspace_id, channel_account_id, sales_unit_id, external_product_id, external_option_id,
                                                 external_sku_code, title, option_text, barcode, channel_price, channel_stock, sale_status, source)
                    select :nid, workspace_id, channel_account_id, sales_unit_id, :p, :o, external_sku_code, title, option_text, barcode,
                           channel_price, channel_stock, sale_status, 'MANUAL'
                      from channel_listing where id = :id
                    """).param("nid", newId).param("p", productId).param("o", optionId).param("id", id).update();
        } catch (DuplicateKeyException e) {
            throw Errors.conflict("CHANNEL_LISTING_DUPLICATE", "바뀐 외부 ID 의 채널상품이 이미 등록되어 있습니다. 그 채널상품에 직접 연결하세요.");
        }
        jdbc.sql("update channel_listing set status = 'REPLACED', replaced_by_id = :nid, version = version + 1, updated_at = now() where id = :id")
                .param("nid", newId).param("id", id).update();
        Instant now = clock.instant();
        if (l.currentMapping() != null) {
            closeMapping(l.currentMapping().id(), now, a.memberId());
            insertMapping(a, newId, l.currentMapping().skuId(), l.currentMapping().quantity(), now, "ID_CHANGE", why);
        }
        audit.record(AuditEntry.builder(a.workspaceId(), "CHANNEL_LISTING_EXTERNAL_ID_CHANGED", "CHANNEL_LISTING", id)
                .actor(ActorType.USER, a.memberId(), a.displayName()).scope(l.businessEntityId(), l.channelAccountId())
                .before(Map.of("external_product_id", l.externalProductId(), "external_option_id", l.externalOptionId()))
                .after(Map.of("external_product_id", productId, "external_option_id", optionId, "new_listing_id", newId.toString()))
                .metadata(Map.of("reason", why)).build());
        if (l.currentMapping() == null) {
            raiseUnmapped(a.workspaceId(), l.businessEntityId(), l.channelAccountId(), l.salesUnitId());
        }
        return get(newId);
    }

    // ---------------- 내부 ----------------

    private void insertMapping(CurrentActor a, UUID listingId, UUID skuId, int qty, Instant from, String basis, String reason) {
        try {
            jdbc.sql("""
                    insert into channel_listing_mapping (id, workspace_id, listing_id, sku_id, quantity, valid_from, match_basis, reason, created_by)
                    values (:id, :ws, :l, :s, :q, :f, :b, :r, :by)
                    """).param("id", Ids.newId()).param("ws", a.workspaceId()).param("l", listingId).param("s", skuId).param("q", qty)
                    .param("f", Db.ts(from)).param("b", basis).param("r", reason).param("by", a.memberId()).update();
        } catch (DataIntegrityViolationException e) {
            throw Errors.conflict("MAPPING_CONCURRENT_UPDATE", "다른 사용자가 동시에 이 채널상품을 연결했습니다. 새로고침 후 다시 확인하세요.");
        }
    }

    private void closeMapping(UUID mappingId, Instant at, UUID by) {
        jdbc.sql("update channel_listing_mapping set valid_to = :t, closed_by = :by where id = :id and valid_to is null")
                .param("t", Db.ts(at)).param("by", by).param("id", mappingId).update();
    }

    private void raiseUnmapped(UUID ws, UUID be, UUID ca, UUID salesUnitId) {
        Map<String, Object> unit = jdbc.sql("select display_name from sales_unit where id = :id").param("id", salesUnitId).query().singleRow();
        int count = unmappedCount(ws, salesUnitId);
        if (count == 0) {
            return;
        }
        Map<String, Object> related = new LinkedHashMap<>();
        related.put("sales_unit_id", salesUnitId.toString());
        related.put("unmapped_count", count);
        workItems.raise(new WorkItemService.Raise(ws, be, ca, null, "CHANNEL_LISTING_UNMAPPED", "MEDIUM",
                "미연결 채널상품: " + unit.get("display_name"),
                "내부 SKU 에 연결되지 않은 채널상품이 있습니다. 이 상품의 주문은 '상품 미연결'로 보류되고 재고가 예약되지 않습니다.",
                "[상품 > 채널상품·연결]에서 제안 SKU 를 확인해 연결하세요.", unmappedKey(salesUnitId), related));
    }

    private void settleUnmapped(UUID ws, UUID salesUnitId) {
        if (unmappedCount(ws, salesUnitId) == 0) {
            workItems.resolveByKey(ws, unmappedKey(salesUnitId));
        }
    }

    private int unmappedCount(UUID ws, UUID salesUnitId) {
        return jdbc.sql("""
                select count(*) from channel_listing l where l.workspace_id = :ws and l.sales_unit_id = :su and l.status = 'ACTIVE'
                   and not exists (select 1 from channel_listing_mapping m where m.listing_id = l.id and m.valid_to is null)
                """).param("ws", ws).param("su", salesUnitId).query(Integer.class).single();
    }

    static String unmappedKey(UUID salesUnitId) {
        return "channel-listing-unmapped:" + salesUnitId;
    }

    /** 사업자 판매 설정이 꺼져 있거나 없으면 경고(연결은 허용 — 판매 설정은 C2 정책에서). */
    private List<SkuService.Warning> warnings(ListingView l, UUID skuId) {
        Boolean enabled = jdbc.sql("select sell_enabled from business_sku_policy where workspace_id = :ws and business_entity_id = :be and sku_id = :s")
                .param("ws", ws()).param("be", l.businessEntityId()).param("s", skuId).query(Boolean.class).optional().orElse(null);
        if (Boolean.TRUE.equals(enabled)) {
            return List.of();
        }
        return List.of(new SkuService.Warning("SKU_NOT_ENABLED_FOR_BUSINESS",
                l.businessEntityName() + " 의 이 SKU 판매 설정이 " + (enabled == null ? "없습니다" : "꺼져 있습니다") + ". 사업자별 정책을 확인하세요.",
                Map.of("business_entity_id", l.businessEntityId().toString())));
    }

    private ListingView lock(UUID id) {
        jdbc.sql("select id from channel_listing where workspace_id = :ws and id = :id for update").param("ws", ws()).param("id", id)
                .query(UUID.class).optional().orElseThrow(() -> Errors.notFound("CHANNEL_LISTING"));
        return find(id).orElseThrow();
    }

    private List<MappingView> history(UUID listingId) {
        return jdbc.sql(MAPPING_SELECT + " where m.workspace_id = :ws and m.listing_id = :id order by m.valid_from desc")
                .param("ws", ws()).param("id", listingId).query(ChannelListingService::mapMapping).list();
    }

    private static final String MAPPING_SELECT = """
            select m.*, s.sku_code, p.name as product_name, u.display_name as created_by_name from channel_listing_mapping m
              join sku s on s.workspace_id = m.workspace_id and s.id = m.sku_id
              join product p on p.workspace_id = s.workspace_id and p.id = s.product_id
              left join workspace_member wm on wm.workspace_id = m.workspace_id and wm.id = m.created_by
              left join user_account u on u.id = wm.user_id
            """;

    private static MappingView mapMapping(ResultSet rs, int i) throws SQLException {
        return new MappingView(Db.uuid(rs, "id"), Db.uuid(rs, "sku_id"), rs.getString("sku_code"), rs.getString("product_name"), rs.getInt("quantity"),
                Db.instant(rs, "valid_from"), Db.instant(rs, "valid_to"), rs.getString("match_basis"), rs.getString("reason"),
                rs.getString("created_by_name"));
    }

    private static final String SELECT = """
            select l.*, ca.business_entity_id, be.display_name as be_name, ca.display_name as ca_name, ca.sales_channel_code,
                   su.display_name as su_name,
                   m.id as m_id, m.sku_id as m_sku_id, ms.sku_code as m_sku_code, mp.name as m_product_name, m.quantity as m_quantity,
                   m.valid_from as m_valid_from, m.match_basis as m_basis, m.reason as m_reason,
                   (select count(distinct x.sku_id) from channel_listing_mapping x where x.listing_id = l.id) as distinct_skus
              from channel_listing l
              join channel_account ca on ca.workspace_id = l.workspace_id and ca.id = l.channel_account_id
              join business_entity be on be.workspace_id = ca.workspace_id and be.id = ca.business_entity_id
              join sales_unit su on su.workspace_id = l.workspace_id and su.id = l.sales_unit_id
              left join channel_listing_mapping m on m.workspace_id = l.workspace_id and m.listing_id = l.id and m.valid_to is null
              left join sku ms on ms.workspace_id = m.workspace_id and ms.id = m.sku_id
              left join product mp on mp.workspace_id = ms.workspace_id and mp.id = ms.product_id
            """;

    private Optional<ListingView> find(UUID id) {
        return jdbc.sql(SELECT + " where l.workspace_id = :ws and l.id = :id").param("ws", ws()).param("id", id).query(this::map).optional();
    }

    private ListingView map(ResultSet rs, int i) throws SQLException {
        UUID mid = Db.uuid(rs, "m_id");
        MappingView current = mid == null ? null : new MappingView(mid, Db.uuid(rs, "m_sku_id"), rs.getString("m_sku_code"),
                rs.getString("m_product_name"), rs.getInt("m_quantity"), Db.instant(rs, "m_valid_from"), null, rs.getString("m_basis"),
                rs.getString("m_reason"), null);
        Object stock = rs.getObject("channel_stock");
        return new ListingView(Db.uuid(rs, "id"), Db.uuid(rs, "business_entity_id"), rs.getString("be_name"), Db.uuid(rs, "channel_account_id"),
                rs.getString("ca_name"), rs.getString("sales_channel_code"), Db.uuid(rs, "sales_unit_id"), rs.getString("su_name"),
                rs.getString("external_product_id"), rs.getString("external_option_id"), rs.getString("external_sku_code"), rs.getString("title"),
                rs.getString("option_text"), rs.getString("barcode"), rs.getBigDecimal("channel_price"),
                stock == null ? null : ((Number) stock).intValue(), rs.getString("sale_status"), rs.getString("source"),
                Db.instant(rs, "last_synced_at"), rs.getString("status"), Db.uuid(rs, "replaced_by_id"), current, rs.getInt("distinct_skus"),
                rs.getInt("version"), Db.instant(rs, "created_at"));
    }

    private UUID ws() {
        return guard.actor().workspaceId();
    }
}
