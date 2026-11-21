package com.commerceos.operations;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.AccessKind;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.identity.access.Perm;
import com.commerceos.identity.access.ResolvedScope;
import com.commerceos.shared.db.Db;
import com.commerceos.shared.error.Errors;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 통합검색 기본(docs/01 §2). Foundation 범위: 사업자·판매계정·판매단위·화주계정·연동 연결.
 * 모든 결과는 READ 범위와 객체별 조회 권한으로 거른 뒤 사업자/판매계정 문맥을 붙인다.
 * 주문·상품·송장 등은 각 모듈이 같은 규칙으로 검색 공급자를 추가한다.
 */
@Service
public class SearchService {
    private final JdbcClient jdbc;
    private final AccessGuard guard;

    public SearchService(JdbcClient jdbc, AccessGuard guard) {
        this.jdbc = jdbc;
        this.guard = guard;
    }

    public record Hit(String type, UUID id, String title, String subtitle, UUID businessEntityId, String businessEntityName,
                      UUID channelAccountId, String channelAccountName) {
    }

    public List<Hit> search(String q, int limit) {
        CurrentActor a = guard.actor();
        if (q == null || q.trim().length() < 2) {
            throw Errors.validation("q", "검색어를 2자 이상 입력하세요.");
        }
        String term = q.trim();
        ResolvedScope s = guard.scope();
        Object bes = Db.uuids(s.businessEntityIds(AccessKind.READ));
        Object cas = Db.uuids(s.channelAccountIds(AccessKind.READ));
        List<Hit> hits = new ArrayList<>();
        if (a.has(Perm.BUSINESS_ENTITY_READ)) {
            hits.addAll(jdbc.sql("""
                    select id, display_name, legal_name from business_entity
                     where workspace_id = :ws and id = any(:bes)
                       and (display_name ilike '%' || :q || '%' or legal_name ilike '%' || :q || '%'
                            or business_registration_no = regexp_replace(:q, '-', '', 'g'))
                     order by display_name limit :limit
                    """).param("ws", a.workspaceId()).param("bes", bes).param("q", term).param("limit", limit)
                    .query((rs, i) -> new Hit("BUSINESS_ENTITY", Db.uuid(rs, "id"), rs.getString("display_name"), rs.getString("legal_name"),
                            Db.uuid(rs, "id"), rs.getString("display_name"), null, null)).list());
        }
        if (a.has(Perm.CHANNEL_ACCOUNT_READ)) {
            hits.addAll(jdbc.sql("""
                    select ca.id, ca.display_name, ca.sales_channel_code, ca.external_account_key, be.id as be_id, be.display_name as be_name
                      from channel_account ca join business_entity be on be.id = ca.business_entity_id
                     where ca.workspace_id = :ws and ca.business_entity_id = any(:bes) and ca.id = any(:cas)
                       and (ca.display_name ilike '%' || :q || '%' or ca.external_account_key = :q)
                     order by ca.display_name limit :limit
                    """).param("ws", a.workspaceId()).param("bes", bes).param("cas", cas).param("q", term).param("limit", limit)
                    .query((rs, i) -> new Hit("CHANNEL_ACCOUNT", Db.uuid(rs, "id"), rs.getString("display_name"),
                            rs.getString("sales_channel_code"), Db.uuid(rs, "be_id"), rs.getString("be_name"), Db.uuid(rs, "id"),
                            rs.getString("display_name"))).list());
            hits.addAll(jdbc.sql("""
                    select su.id, su.display_name, su.external_unit_key, ca.id as ca_id, ca.display_name as ca_name,
                           be.id as be_id, be.display_name as be_name
                      from sales_unit su join channel_account ca on ca.id = su.channel_account_id
                      join business_entity be on be.id = ca.business_entity_id
                     where su.workspace_id = :ws and ca.business_entity_id = any(:bes) and ca.id = any(:cas)
                       and (su.display_name ilike '%' || :q || '%' or su.external_unit_key = :q)
                     order by su.display_name limit :limit
                    """).param("ws", a.workspaceId()).param("bes", bes).param("cas", cas).param("q", term).param("limit", limit)
                    .query((rs, i) -> new Hit("SALES_UNIT", Db.uuid(rs, "id"), rs.getString("display_name"), rs.getString("external_unit_key"),
                            Db.uuid(rs, "be_id"), rs.getString("be_name"), Db.uuid(rs, "ca_id"), rs.getString("ca_name"))).list());
        }
        if (a.has(Perm.LOGISTICS_ACCOUNT_READ)) {
            hits.addAll(jdbc.sql("""
                    select s.id, s.display_name, s.logistics_provider_code, be.id as be_id, be.display_name as be_name
                      from shipper_account s join business_entity be on be.id = s.business_entity_id
                     where s.workspace_id = :ws and s.business_entity_id = any(:bes)
                       and (s.display_name ilike '%' || :q || '%' or s.external_shipper_key = :q)
                     order by s.display_name limit :limit
                    """).param("ws", a.workspaceId()).param("bes", bes).param("q", term).param("limit", limit)
                    .query((rs, i) -> new Hit("SHIPPER_ACCOUNT", Db.uuid(rs, "id"), rs.getString("display_name"),
                            rs.getString("logistics_provider_code"), Db.uuid(rs, "be_id"), rs.getString("be_name"), null, null)).list());
        }
        if (a.has(Perm.INTEGRATION_READ)) {
            hits.addAll(jdbc.sql("""
                    select c.id, c.display_name, c.connector_code, c.channel_account_id,
                           coalesce(ca.business_entity_id, sa.business_entity_id) as be_id, be.display_name as be_name
                      from integration_connection c
                      left join channel_account ca on ca.id = c.channel_account_id
                      left join shipper_account sa on sa.id = c.shipper_account_id
                      join business_entity be on be.id = coalesce(ca.business_entity_id, sa.business_entity_id)
                     where c.workspace_id = :ws and be.id = any(:bes) and (c.channel_account_id is null or c.channel_account_id = any(:cas))
                       and c.display_name ilike '%' || :q || '%'
                     order by c.display_name limit :limit
                    """).param("ws", a.workspaceId()).param("bes", bes).param("cas", cas).param("q", term).param("limit", limit)
                    .query((rs, i) -> new Hit("INTEGRATION_CONNECTION", Db.uuid(rs, "id"), rs.getString("display_name"),
                            rs.getString("connector_code"), Db.uuid(rs, "be_id"), rs.getString("be_name"),
                            Db.uuid(rs, "channel_account_id"), null)).list());
        }
        if (a.has(Perm.CATALOG_READ)) {
            // 상품·SKU 는 운영그룹 공통 마스터(CONF-004) — 사업자 범위와 무관하게 조회 권한만 본다
            hits.addAll(jdbc.sql("""
                    select s.id, s.sku_code, p.name from sku s join product p on p.workspace_id = s.workspace_id and p.id = s.product_id
                     where s.workspace_id = :ws and s.status <> 'ARCHIVED'
                       and (lower(s.sku_code) = lower(:q) or s.barcode = :q or s.sku_code ilike :q || '%' or p.name ilike '%' || :q || '%')
                     order by s.sku_code limit :limit
                    """).param("ws", a.workspaceId()).param("q", term).param("limit", limit)
                    .query((rs, i) -> new Hit("SKU", Db.uuid(rs, "id"), rs.getString("sku_code"), rs.getString("name"), null, null, null, null))
                    .list());
            hits.addAll(jdbc.sql("""
                    select id, name, product_code from product
                     where workspace_id = :ws and status <> 'ARCHIVED' and (name ilike '%' || :q || '%' or lower(product_code) = lower(:q))
                     order by name limit :limit
                    """).param("ws", a.workspaceId()).param("q", term).param("limit", limit)
                    .query((rs, i) -> new Hit("PRODUCT", Db.uuid(rs, "id"), rs.getString("name"), rs.getString("product_code"), null, null, null, null))
                    .list());
        }
        return hits;
    }
}
