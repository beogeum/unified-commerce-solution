package com.commerceos.catalog;

import com.commerceos.identity.access.AccessGuard;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BOM(M2 C4, FR-CAT-05·06, docs/03 §3, docs/13 §2).
 * <ul>
 *   <li>초안(DRAFT)에서만 구성을 바꾸고, 게시(ACTIVE)하면 고정된다. 새 버전 게시는 이전 게시 버전의 적용기간을 그 시각에 닫는다.</li>
 *   <li>유형별 상위·구성 SKU 규칙을 지키고, 자기참조·순환을 금지한다(운영그룹 BOM 그래프 잠금 하에 검사).</li>
 *   <li>과거 주문은 그 시각의 버전으로 해석한다({@link #effective}).</li>
 * </ul>
 */
@Service
public class BomService {
    static final Set<String> TYPES = Set.of("SALES_BUNDLE", "ASSEMBLY", "PACKAGING", "PROMOTION", "MANUFACTURING");
    /** 유형별 허용 상위 SKU 유형 */
    static final Map<String, Set<String>> PARENT_TYPES = Map.of(
            "SALES_BUNDLE", Set.of("BUNDLE"),
            "PROMOTION", Set.of("BUNDLE"),
            "ASSEMBLY", Set.of("KIT"),
            "MANUFACTURING", Set.of("NORMAL", "KIT"));
    /** 유형별 허용 구성 SKU 유형 */
    static final Map<String, Set<String>> COMPONENT_TYPES = Map.of(
            "SALES_BUNDLE", Set.of("NORMAL", "KIT", "GIFT", "SAMPLE", "BUNDLE", "SERVICE", "DIGITAL"),
            "PROMOTION", Set.of("NORMAL", "KIT", "GIFT", "SAMPLE", "BUNDLE"),
            "ASSEMBLY", Set.of("NORMAL", "GIFT", "SAMPLE", "MATERIAL", "KIT"),
            "MANUFACTURING", Set.of("NORMAL", "MATERIAL"),
            "PACKAGING", Set.of("MATERIAL"));

    private final JdbcClient jdbc;
    private final AccessGuard guard;
    private final AuditService audit;
    private final OutboxPublisher outbox;
    private final Clock clock;

    public BomService(JdbcClient jdbc, AccessGuard guard, AuditService audit, OutboxPublisher outbox, Clock clock) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.outbox = outbox;
        this.clock = clock;
    }

    public record ComponentView(UUID skuId, String skuCode, String skuName, String skuType, int quantity) {
    }

    public record BomView(UUID id, String bomType, UUID parentSkuId, String parentSkuCode, String packagingProfileCode, int version,
                          String status, Instant validFrom, Instant validTo, boolean splitShipmentAllowed, String note,
                          List<ComponentView> components, Instant createdAt, Instant publishedAt, String publishedByName, int versionLock) {
    }

    public record ComponentInput(UUID skuId, Integer quantity) {
    }

    public record Create(String bomType, UUID parentSkuId, String packagingProfileCode, List<ComponentInput> components,
                         Boolean splitShipmentAllowed, String note) {
    }

    public record Publish(Instant validFrom) {
    }

    public record CostLine(String skuCode, int quantity, BigDecimal unitCost, BigDecimal lineCost) {
    }

    public record CostView(UUID businessEntityId, List<CostLine> lines, BigDecimal totalCost, List<String> missingCostSkuCodes) {
    }

    // ---------------- 조회 ----------------

    public List<BomView> list(UUID parentSkuId, String bomType, String status, String packagingProfileCode) {
        guard.requirePermission(Perm.CATALOG_READ);
        return jdbc.sql(SELECT + """
                 where b.workspace_id = :ws and (cast(:p as uuid) is null or b.parent_sku_id = :p)
                   and (cast(:t as text) is null or b.bom_type = :t) and (cast(:st as text) is null or b.status = :st)
                   and (cast(:pk as text) is null or b.packaging_profile_code = :pk)
                 order by coalesce(ps.sku_code, b.packaging_profile_code), b.bom_type, b.version desc limit 500
                """).param("ws", ws()).param("p", parentSkuId).param("t", bomType).param("st", status).param("pk", packagingProfileCode)
                .query(this::map).list().stream().map(this::withComponents).toList();
    }

    public BomView get(UUID id) {
        guard.requirePermission(Perm.CATALOG_READ);
        return find(id).orElseThrow(() -> Errors.notFound("BOM"));
    }

    /** 그 시각에 유효한 게시 버전(M4 주문 스냅샷용). */
    public Optional<BomView> effective(UUID parentSkuId, String bomType, Instant at) {
        guard.requirePermission(Perm.CATALOG_READ);
        return jdbc.sql(SELECT + """
                 where b.workspace_id = :ws and b.parent_sku_id = :p and b.bom_type = :t and b.status <> 'DRAFT'
                   and b.valid_from <= :at and (b.valid_to is null or b.valid_to > :at)
                """).param("ws", ws()).param("p", parentSkuId).param("t", bomType).param("at", Db.ts(at))
                .query(this::map).optional().map(this::withComponents);
    }

    public Optional<BomView> effectivePackaging(String profileCode, Instant at) {
        guard.requirePermission(Perm.CATALOG_READ);
        return jdbc.sql(SELECT + """
                 where b.workspace_id = :ws and b.packaging_profile_code = :pk and b.bom_type = 'PACKAGING' and b.status <> 'DRAFT'
                   and b.valid_from <= :at and (b.valid_to is null or b.valid_to > :at)
                """).param("ws", ws()).param("pk", profileCode).param("at", Db.ts(at)).query(this::map).optional().map(this::withComponents);
    }

    /** 구성품 원가 합(사업자별 현재 원가, cost.read 필요). 원가 없는 구성품은 0원으로 더하지 않고 목록으로 알린다. */
    public CostView cost(UUID bomId, UUID businessEntityId) {
        guard.requirePermission(Perm.COST_READ);
        BomView b = get(bomId);
        if (jdbc.sql("select count(*) from business_entity where workspace_id = :ws and id = :id").param("ws", ws()).param("id", businessEntityId)
                .query(Integer.class).single() == 0) {
            throw Errors.notFound("BUSINESS_ENTITY");
        }
        guard.checkRead("BUSINESS_ENTITY", businessEntityId, businessEntityId, null);
        List<CostLine> lines = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (ComponentView c : b.components()) {
            BigDecimal unit = jdbc.sql("""
                    select unit_cost from sku_cost where workspace_id = :ws and business_entity_id = :be and sku_id = :s and effective_from <= :now
                     order by effective_from desc limit 1
                    """).param("ws", ws()).param("be", businessEntityId).param("s", c.skuId()).param("now", Db.ts(clock.instant()))
                    .query(BigDecimal.class).optional().orElse(null);
            if (unit == null) {
                missing.add(c.skuCode());
                lines.add(new CostLine(c.skuCode(), c.quantity(), null, null));
            } else {
                BigDecimal line = unit.multiply(BigDecimal.valueOf(c.quantity()));
                total = total.add(line);
                lines.add(new CostLine(c.skuCode(), c.quantity(), unit, line));
            }
        }
        return new CostView(businessEntityId, lines, missing.isEmpty() ? total : null, missing);
    }

    // ---------------- 변경 ----------------

    @Transactional
    public BomView create(Create c) {
        guard.requirePermission(Perm.CATALOG_MANAGE);
        CurrentActor a = guard.actor();
        CatalogRules.requireEnum("bom_type", c.bomType(), TYPES);
        String profile = null;
        if ("PACKAGING".equals(c.bomType())) {
            if (c.parentSkuId() != null) {
                throw Errors.unprocessable("BOM_PARENT_INVALID", "포장 BOM 은 SKU 가 아니라 포장 프로필(예: DEFAULT)에 연결합니다.");
            }
            if (c.packagingProfileCode() == null || !c.packagingProfileCode().trim().matches("^[A-Z0-9_]{1,40}$")) {
                throw Errors.validation("packaging_profile_code", "포장 프로필 코드는 영문 대문자·숫자·밑줄 40자 이내로 입력하세요.");
            }
            profile = c.packagingProfileCode().trim();
        } else {
            if (c.parentSkuId() == null) {
                throw Errors.validation("parent_sku_id", "BOM 을 적용할 상위 SKU 를 선택하세요.");
            }
            Map<String, Object> parent = sku(c.parentSkuId());
            String type = (String) parent.get("sku_type");
            if (!PARENT_TYPES.get(c.bomType()).contains(type)) {
                throw Errors.unprocessable("BOM_PARENT_INVALID", bomTypeKo(c.bomType()) + " BOM 의 상위 SKU 는 "
                        + String.join("·", PARENT_TYPES.get(c.bomType()).stream().map(BomService::skuTypeKo).sorted().toList()) + " 유형이어야 합니다.",
                        Map.of("parent_sku_type", type));
            }
        }
        lockGraph(a);
        int version = jdbc.sql("""
                select coalesce(max(version), 0) + 1 from bom where workspace_id = :ws and bom_type = :t
                   and coalesce(parent_sku_id::text, packaging_profile_code) = :key
                """).param("ws", a.workspaceId()).param("t", c.bomType())
                .param("key", c.parentSkuId() == null ? profile : c.parentSkuId().toString()).query(Integer.class).single();
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into bom (id, workspace_id, bom_type, parent_sku_id, packaging_profile_code, version, split_shipment_allowed, note, created_by)
                values (:id, :ws, :t, :p, :pk, :v, :split, :note, :by)
                """).param("id", id).param("ws", a.workspaceId()).param("t", c.bomType()).param("p", c.parentSkuId()).param("pk", profile)
                .param("v", version).param("split", Boolean.TRUE.equals(c.splitShipmentAllowed()))
                .param("note", CatalogRules.optionalText("note", c.note(), 500)).param("by", a.memberId()).update();
        writeComponents(a, id, c.bomType(), c.parentSkuId(), c.components());
        BomView v = find(id).orElseThrow();
        audit.record(AuditEntry.builder(a.workspaceId(), "BOM_DRAFT_CREATED", "BOM", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .after(snapshot(v)).build());
        return v;
    }

    @Transactional
    public BomView replaceComponents(UUID id, int expectedVersion, List<ComponentInput> components, Boolean splitShipmentAllowed) {
        guard.requirePermission(Perm.CATALOG_MANAGE);
        CurrentActor a = guard.actor();
        lockGraph(a);
        BomView b = lock(id);
        CatalogRules.checkVersion(expectedVersion, b.versionLock());
        if (!"DRAFT".equals(b.status())) {
            throw Errors.conflict("BOM_PUBLISHED_IMMUTABLE", "게시된 BOM 은 바꿀 수 없습니다. 새 버전을 만들어 게시하세요.");
        }
        jdbc.sql("delete from bom_component where bom_id = :id").param("id", id).update();
        writeComponents(a, id, b.bomType(), b.parentSkuId(), components);
        jdbc.sql("update bom set split_shipment_allowed = coalesce(:split, split_shipment_allowed), version_lock = version_lock + 1 where id = :id")
                .param("split", splitShipmentAllowed).param("id", id).update();
        BomView after = find(id).orElseThrow();
        audit.record(AuditEntry.builder(a.workspaceId(), "BOM_DRAFT_UPDATED", "BOM", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .before(snapshot(b)).after(snapshot(after)).build());
        return after;
    }

    /** 새 버전으로 기존 게시 버전을 대체하려면 초안을 만들어(create) 게시한다. 기존 게시 버전은 새 버전 시작 시각에 닫힌다. */
    @Transactional
    public BomView publish(UUID id, int expectedVersion, Publish p) {
        guard.requirePermission(Perm.CATALOG_MANAGE);
        CurrentActor a = guard.actor();
        lockGraph(a);
        BomView b = lock(id);
        CatalogRules.checkVersion(expectedVersion, b.versionLock());
        if (!"DRAFT".equals(b.status())) {
            throw Errors.conflict("BOM_ALREADY_PUBLISHED", "이미 게시된 BOM 입니다.");
        }
        if (b.components().isEmpty()) {
            throw Errors.unprocessable("BOM_EMPTY", "구성품이 없는 BOM 은 게시할 수 없습니다.");
        }
        Instant now = clock.instant();
        Instant from = p == null || p.validFrom() == null ? now : p.validFrom();
        if (from.isBefore(now.minus(BusinessSkuPolicyService.BACKDATE_TOLERANCE))) {
            throw Errors.unprocessable("BOM_BACKDATE_FORBIDDEN", "이미 지난 시점부터 적용하는 BOM 은 게시할 수 없습니다(과거 주문 스냅샷 보호).");
        }
        // 구성 SKU 가 게시 시점에도 유효한지(초안 작성 후 중지·보관됐을 수 있음)
        for (ComponentView c : b.components()) {
            Map<String, Object> s = sku(c.skuId());
            if (!"ACTIVE".equals(s.get("status"))) {
                throw Errors.unprocessable("BOM_COMPONENT_INACTIVE", "운영 중이 아닌 구성품이 있습니다: " + c.skuCode());
            }
        }
        if (b.parentSkuId() != null) {
            checkNoCycle(a, b.parentSkuId());
        }
        // 기존 게시 버전 닫기: 열린(또는 새 시작 이후까지 이어지는) 이전 버전의 종료를 새 시작 시각으로
        List<UUID> previous = jdbc.sql("""
                select id from bom where workspace_id = :ws and bom_type = :t and coalesce(parent_sku_id::text, packaging_profile_code) = :key
                   and status = 'ACTIVE' and id <> :id and valid_from < :from and valid_to is null
                """).param("ws", a.workspaceId()).param("t", b.bomType()).param("key", key(b)).param("id", id).param("from", Db.ts(from))
                .query(UUID.class).list();
        for (UUID prev : previous) {
            jdbc.sql("update bom set valid_to = :to, version_lock = version_lock + 1 where id = :id").param("to", Db.ts(from)).param("id", prev).update();
        }
        try {
            jdbc.sql("update bom set status = 'ACTIVE', valid_from = :from, published_by = :by, published_at = now(), version_lock = version_lock + 1 where id = :id")
                    .param("from", Db.ts(from)).param("by", a.memberId()).param("id", id).update();
        } catch (DataIntegrityViolationException e) {
            throw Errors.conflict("BOM_PERIOD_OVERLAP", "같은 SKU·유형의 다른 게시 버전과 적용기간이 겹칩니다. 시작 시각을 확인하세요.");
        }
        BomView after = find(id).orElseThrow();
        audit.record(AuditEntry.builder(a.workspaceId(), "BOM_PUBLISHED", "BOM", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .after(snapshot(after)).metadata(Map.of("closed_previous", previous.stream().map(UUID::toString).toList())).build());
        outbox.publish(a.workspaceId(), null, "BOM", id, "BomPublished", Map.of("bom_id", id.toString(), "bom_type", b.bomType(),
                "parent", key(b), "version", b.version()));
        return after;
    }

    @Transactional
    public BomView retire(UUID id, int expectedVersion, String reason) {
        guard.requirePermission(Perm.CATALOG_MANAGE);
        CurrentActor a = guard.actor();
        BomView b = lock(id);
        CatalogRules.checkVersion(expectedVersion, b.versionLock());
        String why = CatalogRules.requireText("reason", reason, 500);
        if (!"ACTIVE".equals(b.status())) {
            throw Errors.conflict("BOM_NOT_ACTIVE", "게시된 BOM 만 종료할 수 있습니다.");
        }
        Instant now = clock.instant();
        Instant to = b.validTo() != null && b.validTo().isBefore(now) ? b.validTo() : (b.validFrom().isAfter(now) ? null : now);
        if (to == null) {
            throw Errors.unprocessable("BOM_NOT_STARTED", "아직 시작하지 않은 BOM 은 종료 대신 새 초안으로 바꾸세요.");
        }
        jdbc.sql("update bom set status = 'RETIRED', valid_to = coalesce(valid_to, :to), version_lock = version_lock + 1 where id = :id")
                .param("to", Db.ts(to)).param("id", id).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "BOM_RETIRED", "BOM", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .metadata(Map.of("reason", why)).build());
        return find(id).orElseThrow();
    }

    @Transactional
    public void deleteDraft(UUID id) {
        guard.requirePermission(Perm.CATALOG_MANAGE);
        CurrentActor a = guard.actor();
        BomView b = lock(id);
        if (!"DRAFT".equals(b.status())) {
            throw Errors.conflict("BOM_PUBLISHED_IMMUTABLE", "게시된 BOM 은 삭제할 수 없습니다. 종료하세요.");
        }
        jdbc.sql("delete from bom_component where bom_id = :id").param("id", id).update();
        jdbc.sql("delete from bom where id = :id").param("id", id).update();
        audit.record(AuditEntry.builder(a.workspaceId(), "BOM_DRAFT_DELETED", "BOM", id).actor(ActorType.USER, a.memberId(), a.displayName())
                .before(snapshot(b)).build());
    }

    // ---------------- 규칙 ----------------

    private void writeComponents(CurrentActor a, UUID bomId, String bomType, UUID parentSkuId, List<ComponentInput> components) {
        List<ComponentInput> list = components == null ? List.of() : components;
        if (list.size() > 200) {
            throw Errors.validation("components", "구성품은 200개까지 넣을 수 있습니다.");
        }
        Set<UUID> seen = new HashSet<>();
        int sort = 0;
        for (ComponentInput c : list) {
            if (c == null || c.skuId() == null) {
                throw Errors.validation("components", "구성품 SKU 를 선택하세요.");
            }
            if (c.quantity() == null || c.quantity() < 1 || c.quantity() > 10000) {
                throw Errors.validation("quantity", "구성 수량은 1 이상의 정수여야 합니다.");
            }
            if (c.skuId().equals(parentSkuId)) {
                throw Errors.unprocessable("BOM_SELF_REFERENCE", "자기 자신을 구성품으로 넣을 수 없습니다.");
            }
            if (!seen.add(c.skuId())) {
                throw Errors.unprocessable("BOM_COMPONENT_DUPLICATE", "같은 구성품을 두 번 넣지 말고 수량을 늘리세요.");
            }
            Map<String, Object> s = sku(c.skuId());
            String type = (String) s.get("sku_type");
            if (!COMPONENT_TYPES.get(bomType).contains(type)) {
                throw Errors.unprocessable("BOM_COMPONENT_TYPE_INVALID", bomTypeKo(bomType) + " BOM 에 " + skuTypeKo(type) + " SKU(" + s.get("sku_code")
                        + ")는 넣을 수 없습니다.", Map.of("sku_code", s.get("sku_code"), "sku_type", type));
            }
            if ("ARCHIVED".equals(s.get("status"))) {
                throw Errors.unprocessable("BOM_COMPONENT_INACTIVE", "보관된 SKU 는 구성품으로 쓸 수 없습니다: " + s.get("sku_code"));
            }
            jdbc.sql("insert into bom_component (workspace_id, bom_id, component_sku_id, quantity, sort_order) values (:ws, :b, :s, :q, :o)")
                    .param("ws", a.workspaceId()).param("b", bomId).param("s", c.skuId()).param("q", c.quantity()).param("o", sort++).update();
        }
        if (parentSkuId != null) {
            checkNoCycle(a, parentSkuId);
        }
    }

    /**
     * 상위 SKU 에서 출발해 구성 관계(초안·게시·종료 모든 버전, 포장 제외)를 따라가 자기 자신으로 돌아오면 순환.
     * 미래·과거 버전까지 포함하는 이유: 어느 시점에든 순환이 생기면 재고 계산·주문 전개가 끝나지 않는다.
     */
    private void checkNoCycle(CurrentActor a, UUID start) {
        Map<UUID, List<UUID>> edges = new HashMap<>();
        jdbc.sql("""
                select b.parent_sku_id, c.component_sku_id from bom b join bom_component c on c.bom_id = b.id
                 where b.workspace_id = :ws and b.parent_sku_id is not null and b.bom_type <> 'PACKAGING'
                """).param("ws", a.workspaceId()).query((rs, i) -> {
                    edges.computeIfAbsent(Db.uuid(rs, "parent_sku_id"), k -> new ArrayList<>()).add(Db.uuid(rs, "component_sku_id"));
                    return null;
                }).list();
        Deque<List<UUID>> stack = new ArrayDeque<>();
        stack.push(List.of(start));
        Set<UUID> visited = new HashSet<>();
        while (!stack.isEmpty()) {
            List<UUID> path = stack.pop();
            UUID node = path.get(path.size() - 1);
            for (UUID next : edges.getOrDefault(node, List.of())) {
                if (next.equals(start)) {
                    List<UUID> cycle = new ArrayList<>(path);
                    cycle.add(next);
                    List<String> codes = cycle.stream().map(x -> (String) sku(x).get("sku_code")).toList();
                    throw Errors.unprocessable("BOM_CYCLE", "구성 관계가 순환합니다: " + String.join(" → ", codes), Map.of("cycle", codes));
                }
                if (visited.add(next)) {
                    List<UUID> np = new ArrayList<>(path);
                    np.add(next);
                    stack.push(np);
                }
            }
        }
    }

    /** 같은 운영그룹의 BOM 그래프 변경을 직렬화(동시 게시로 순환이 생기는 것 방지). */
    private void lockGraph(CurrentActor a) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended('bom-graph:' || cast(:ws as text), 0))").param("ws", a.workspaceId())
                .query().listOfRows();
    }

    private Map<String, Object> sku(UUID id) {
        return jdbc.sql("select id, sku_code, sku_type, status from sku where workspace_id = :ws and id = :id").param("ws", ws()).param("id", id)
                .query().listOfRows().stream().findFirst().orElseThrow(() -> Errors.notFound("SKU"));
    }

    static String bomTypeKo(String t) {
        return switch (t) {
            case "SALES_BUNDLE" -> "판매묶음";
            case "ASSEMBLY" -> "조립";
            case "PACKAGING" -> "포장";
            case "PROMOTION" -> "프로모션";
            default -> "제조";
        };
    }

    static String skuTypeKo(String t) {
        return switch (t) {
            case "NORMAL" -> "일반";
            case "BUNDLE" -> "가상묶음";
            case "KIT" -> "실물세트";
            case "MATERIAL" -> "부자재";
            case "GIFT" -> "증정품";
            case "SAMPLE" -> "샘플";
            case "SERVICE" -> "서비스";
            default -> "디지털";
        };
    }

    private static String key(BomView b) {
        return b.parentSkuId() == null ? b.packagingProfileCode() : b.parentSkuId().toString();
    }

    private static final String SELECT = """
            select b.*, ps.sku_code as parent_sku_code, u.display_name as published_by_name from bom b
              left join sku ps on ps.workspace_id = b.workspace_id and ps.id = b.parent_sku_id
              left join workspace_member m on m.workspace_id = b.workspace_id and m.id = b.published_by
              left join user_account u on u.id = m.user_id
            """;

    private BomView lock(UUID id) {
        jdbc.sql("select id from bom where workspace_id = :ws and id = :id for update").param("ws", ws()).param("id", id).query(UUID.class).optional()
                .orElseThrow(() -> Errors.notFound("BOM"));
        return find(id).orElseThrow();
    }

    private Optional<BomView> find(UUID id) {
        return jdbc.sql(SELECT + " where b.workspace_id = :ws and b.id = :id").param("ws", ws()).param("id", id).query(this::map).optional()
                .map(this::withComponents);
    }

    private BomView withComponents(BomView b) {
        List<ComponentView> cs = jdbc.sql("""
                select c.component_sku_id, s.sku_code, s.name, s.sku_type, c.quantity from bom_component c
                  join sku s on s.workspace_id = c.workspace_id and s.id = c.component_sku_id
                 where c.workspace_id = :ws and c.bom_id = :b order by c.sort_order
                """).param("ws", ws()).param("b", b.id())
                .query((rs, i) -> new ComponentView(Db.uuid(rs, "component_sku_id"), rs.getString("sku_code"), rs.getString("name"),
                        rs.getString("sku_type"), rs.getInt("quantity"))).list();
        return new BomView(b.id(), b.bomType(), b.parentSkuId(), b.parentSkuCode(), b.packagingProfileCode(), b.version(), b.status(), b.validFrom(),
                b.validTo(), b.splitShipmentAllowed(), b.note(), cs, b.createdAt(), b.publishedAt(), b.publishedByName(), b.versionLock());
    }

    private BomView map(ResultSet rs, int i) throws SQLException {
        return new BomView(Db.uuid(rs, "id"), rs.getString("bom_type"), Db.uuid(rs, "parent_sku_id"), rs.getString("parent_sku_code"),
                rs.getString("packaging_profile_code"), rs.getInt("version"), rs.getString("status"), Db.instant(rs, "valid_from"),
                Db.instant(rs, "valid_to"), rs.getBoolean("split_shipment_allowed"), rs.getString("note"), List.of(), Db.instant(rs, "created_at"),
                Db.instant(rs, "published_at"), rs.getString("published_by_name"), rs.getInt("version_lock"));
    }

    private static Map<String, Object> snapshot(BomView b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bom_type", b.bomType());
        m.put("parent", b.parentSkuCode() == null ? b.packagingProfileCode() : b.parentSkuCode());
        m.put("version", b.version());
        m.put("status", b.status());
        m.put("valid_from", String.valueOf(b.validFrom()));
        m.put("components", b.components().stream().map(c -> c.skuCode() + "×" + c.quantity()).toList());
        return m;
    }

    private UUID ws() {
        return guard.actor().workspaceId();
    }
}
