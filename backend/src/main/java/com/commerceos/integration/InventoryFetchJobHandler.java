package com.commerceos.integration;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.ActorFactory;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.inventory.ExternalSnapshotService;
import com.commerceos.integration.spi.Connector;
import com.commerceos.integration.spi.ConnectorContext;
import com.commerceos.integration.spi.ConnectorEnvelope;
import com.commerceos.integration.spi.ConnectorException;
import com.commerceos.integration.spi.LogisticsConnector;
import com.commerceos.operations.WorkItemService;
import com.commerceos.platform.job.Job;
import com.commerceos.platform.job.JobHandler;
import com.commerceos.platform.job.JobOutcome;
import com.commerceos.shared.error.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 3PL 재고 조회 작업(M3 I4). 외부 호출(쪽 단위 커서)은 트랜잭션 밖에서, 반영은 한 트랜잭션에서 한다.
 * 실행 시점에도 요청자 권한으로 지원 기능을 다시 확인한다(그 사이 기능 확인이 취소됐으면 중단).
 * 같은 작업이 다시 실행되어도 원천 키(job:{id})로 한 번만 반영된다.
 */
@Component
public class InventoryFetchJobHandler implements JobHandler {
    public static final String TYPE = "INVENTORY_FETCH";
    private static final int MAX_PAGES = 1000;

    private final JdbcClient jdbc;
    private final ConnectionService connections;
    private final ConnectorRegistry registry;
    private final CapabilityService capabilities;
    private final ExternalSnapshotService snapshots;
    private final WorkItemService workItems;
    private final AccessGuard guard;
    private final ActorFactory actors;
    private final TransactionTemplate tx;

    public InventoryFetchJobHandler(JdbcClient jdbc, ConnectionService connections, ConnectorRegistry registry, CapabilityService capabilities,
                                    ExternalSnapshotService snapshots, WorkItemService workItems, AccessGuard guard, ActorFactory actors,
                                    PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.connections = connections;
        this.registry = registry;
        this.capabilities = capabilities;
        this.snapshots = snapshots;
        this.workItems = workItems;
        this.guard = guard;
        this.actors = actors;
        this.tx = new TransactionTemplate(tm);
    }

    @Override
    public String jobType() {
        return TYPE;
    }

    @Override
    public JobOutcome handle(Job job) {
        Optional<ConnectionService.ConnectionView> found = connections.findSystem(job.workspaceId(), job.connectionId());
        if (found.isEmpty()) {
            return JobOutcome.failed("CONNECTION_NOT_FOUND", "연동 연결을 찾을 수 없습니다.");
        }
        ConnectionService.ConnectionView c = found.get();
        if ("PAUSED".equals(c.status())) {
            return JobOutcome.failed("CONNECTION_PAUSED", "일시중지된 연결입니다. 재개한 뒤 다시 실행하세요.");
        }
        if (!"SHIPPER_ACCOUNT".equals(c.targetType()) || c.shipperAccountId() == null) {
            return JobOutcome.failed("CONNECTION_TARGET_INVALID", "재고 조회는 화주계정 연결에서만 실행할 수 있습니다.");
        }
        Optional<Connector> connector = registry.find(c.connectorCode()).filter(x -> x instanceof LogisticsConnector);
        if (connector.isEmpty()) {
            return JobOutcome.reviewRequired("CONNECTOR_NOT_AVAILABLE", "물류 연동 모듈을 사용할 수 없습니다. 관리자 확인이 필요합니다.");
        }
        Optional<CurrentActor> actor = job.createdByMemberId() == null ? Optional.empty() : actors.forMember(job.workspaceId(), job.createdByMemberId());
        if (actor.isEmpty()) {
            return JobOutcome.failed("REQUESTER_INACTIVE", "작업을 요청한 사용자가 더 이상 활성 상태가 아닙니다. 다시 요청하세요.");
        }
        try {
            guard.runAs(actor.get(), () -> {
                capabilities.require(c.id(), "INVENTORY_QUERY", "READ");
                return null;
            });
        } catch (ApiException e) {
            return JobOutcome.failed(e.code(), e.getMessage());
        }
        Optional<Map<String, String>> credential = connections.activeCredential(c.id());
        if (credential.isEmpty()) {
            return JobOutcome.failed("CREDENTIAL_MISSING", "등록된 인증정보가 없습니다.");
        }
        String externalKey = jdbc.sql("select external_shipper_key from shipper_account where id = :id").param("id", c.shipperAccountId())
                .query(String.class).optional().orElse(null);
        ConnectorContext ctx = new ConnectorContext(job.workspaceId(), c.id(), c.targetType(), c.shipperAccountId(), externalKey, c.apiVersion(), credential.get());

        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> rawRefs = new ArrayList<>();
        Instant asOf = null;
        boolean asOfConsistent = true;
        String mappingVersion = null;
        Integer capabilityVersion = null;
        Instant fetchedAt = Instant.now();
        try {
            String cursor = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                ConnectorEnvelope<Map<String, Object>> env = ((LogisticsConnector) connector.get()).fetchInventory(ctx, cursor);
                if (page == 0) {
                    asOf = env.sourceTimestamp();
                    mappingVersion = env.mappingVersion();
                    capabilityVersion = env.capabilityVersion();
                } else if (!java.util.Objects.equals(asOf, env.sourceTimestamp())) {
                    asOfConsistent = false; // 쪽마다 기준시각이 다르면 한 시점의 재고로 볼 수 없다
                }
                rows.addAll(env.mappedObjects());
                if (env.rawPayloadRef() != null) {
                    rawRefs.add(env.rawPayloadRef());
                }
                cursor = env.nextCursor();
                if (cursor == null) {
                    break;
                }
            }
        } catch (ConnectorException e) {
            return switch (e.kind()) {
                case AUTH_EXPIRED -> JobOutcome.failed("AUTH_EXPIRED", "물류사 인증이 만료되었습니다. 인증정보를 갱신한 뒤 다시 실행하세요.");
                case PERMISSION_DENIED -> JobOutcome.failed("PERMISSION_DENIED", "물류사 재고 조회 권한이 없습니다. 물류사 API 권한을 확인하세요.");
                case RATE_LIMITED -> JobOutcome.retry("RATE_LIMITED", "호출 제한으로 잠시 후 다시 시도합니다.", e.retryAfterSeconds() == null ? 60 : e.retryAfterSeconds());
                case UNSUPPORTED -> JobOutcome.reviewRequired("UNSUPPORTED", "연동 모듈이 재고 조회를 지원하지 않습니다.");
                default -> JobOutcome.retry("CONNECTOR_TRANSIENT", "물류사 응답이 지연되어 다시 시도합니다.", 30L);
            };
        }
        Instant watermark = asOfConsistent ? asOf : null;
        String mv = mappingVersion == null ? "unknown" : mappingVersion;
        Integer cv = capabilityVersion;
        String rawRef = rawRefs.isEmpty() ? null : String.join(",", rawRefs.size() > 20 ? rawRefs.subList(0, 20) : rawRefs);
        try {
            return tx.execute(s -> apply(job, c, rows, watermark, fetchedAt, mv, cv, rawRef));
        } catch (ApiException e) {
            return JobOutcome.failed(e.code(), e.getMessage());
        }
    }

    private JobOutcome apply(Job job, ConnectionService.ConnectionView c, List<Map<String, Object>> rows, Instant asOf, Instant fetchedAt, String mappingVersion,
                             Integer capabilityVersion, String rawRef) {
        UUID ws = job.workspaceId();
        List<Map<String, Object>> nodes = jdbc.sql("""
                select n.id, n.code from fulfillment_node_shipper l join fulfillment_node n on n.id = l.node_id
                 where l.shipper_account_id = :s and n.inventory_source = 'EXTERNAL' and n.status <> 'CLOSED' order by n.code
                """).param("s", c.shipperAccountId()).query().listOfRows();
        if (nodes.isEmpty()) {
            return JobOutcome.failed("NODE_NOT_LINKED", "이 화주계정이 연결된 물류사 재고 기준 거점이 없습니다. 출고거점에 화주계정을 먼저 연결하세요.");
        }
        Map<UUID, List<ExternalSnapshotService.Line>> byNode = new LinkedHashMap<>();
        Map<UUID, List<Map<String, Object>>> rawByNode = new LinkedHashMap<>();
        List<String> unresolved = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Object nodeCode = r.get("node_code");
            UUID node = null;
            if (nodeCode != null) {
                node = nodes.stream().filter(n -> nodeCode.toString().equalsIgnoreCase((String) n.get("code"))).map(n -> (UUID) n.get("id")).findFirst().orElse(null);
            } else if (nodes.size() == 1) {
                node = (UUID) nodes.get(0).get("id");
            }
            if (node == null) {
                unresolved.add(String.valueOf(nodeCode));
                continue;
            }
            byNode.computeIfAbsent(node, k -> new ArrayList<>()).add(line(r));
            rawByNode.computeIfAbsent(node, k -> new ArrayList<>()).add(r);
        }
        if (!unresolved.isEmpty()) {
            workItems.raise(new WorkItemService.Raise(ws, c.businessEntityId(), null, c.id(), "EXTERNAL_NODE_UNRESOLVED", "MEDIUM",
                    "물류사 재고의 센터를 찾지 못한 행 " + unresolved.size() + "건 · " + c.displayName(),
                    "물류사 자료의 센터 코드가 이 화주계정에 연결된 출고거점 코드와 맞지 않습니다. 해당 행은 반영하지 않았습니다.",
                    "출고거점 코드를 물류사 센터 코드와 맞추거나 화주계정을 해당 거점에 연결하세요.", "integration:" + c.id() + ":EXTERNAL_NODE_UNRESOLVED",
                    Map.of("job_id", job.id().toString(), "node_codes", unresolved.stream().distinct().limit(20).toList())));
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (var e : byNode.entrySet()) {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("job_id", job.id().toString());
            raw.put("rows", rawByNode.get(e.getKey()));
            // 물류사 API 가 전체 SKU 를 돌려주는지는 계정별로 확인되지 않았다 → 목록에 없는 SKU 를 0 으로 보지 않는다(DLG-099)
            ExternalSnapshotService.SnapshotDetail d = snapshots.applySystem(new ExternalSnapshotService.Incoming(ws, e.getKey(), c.businessEntityId(),
                    c.shipperAccountId(), c.id(), "API", "job:" + job.id(), asOf, fetchedAt, false, mappingVersion, capabilityVersion, rawRef, raw, null,
                    e.getValue(), null, c.connectorCode()));
            results.add(Map.of("node_id", e.getKey().toString(), "snapshot_id", d.snapshot().id().toString(), "status", d.snapshot().status(),
                    "issue_count", d.snapshot().issueCount()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connection_id", c.id().toString());
        out.put("row_count", rows.size());
        out.put("unresolved_rows", unresolved.size());
        out.put("watermark", asOf == null ? null : asOf.toString());
        out.put("snapshots", results);
        return JobOutcome.succeeded(out);
    }

    private static ExternalSnapshotService.Line line(Map<String, Object> r) {
        Object q = r.get("quantity");
        Object sellable = r.get("sellable");
        Object exp = r.get("expires_on");
        return new ExternalSnapshotService.Line(r.get("sku_code") == null ? null : r.get("sku_code").toString(), str(r.get("lot_code")),
                exp == null ? null : LocalDate.parse(exp.toString()), str(r.get("location_code")), str(r.get("status")),
                sellable instanceof Boolean b ? b : sellable == null ? null : Boolean.valueOf(sellable.toString()),
                q instanceof Number n ? Integer.valueOf(n.intValue()) : q == null ? null : Integer.valueOf(q.toString()));
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
