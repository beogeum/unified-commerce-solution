package com.commerceos.fulfillment;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.ActorFactory;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.operations.WorkItemService;
import com.commerceos.shared.db.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 배송 운영 감시(M4 O5).
 * <ul>
 *   <li>송장 미수신 SLA(docs/04 §9): 물류사 접수 후 거점의 SLA 시간이 지나도 출고 확정·송장이 없으면 업무함.</li>
 *   <li>주문 완료(DLG-134): 전부 출고·전 송장 배송완료 후 청약철회 기간(기본 8일)이 지나고 진행 중 출고·열린 클레임이 없으면 COMPLETED.
 *       배송완료만으로 주문완료를 만들지 않는다(docs/04 §2).</li>
 * </ul>
 */
@Component
public class ShipmentWatch {
    private final JdbcClient jdbc;
    private final WorkItemService workItems;
    private final AccessGuard guard;
    private final ActorFactory actors;
    private final FulfillmentService fulfillment;
    private final TransactionTemplate tx;
    private final int completeAfterDays;

    public ShipmentWatch(JdbcClient jdbc, WorkItemService workItems, AccessGuard guard, ActorFactory actors, FulfillmentService fulfillment,
                         PlatformTransactionManager tm, @Value("${commerce.order.complete-after-delivery-days:8}") int completeAfterDays) {
        this.jdbc = jdbc;
        this.workItems = workItems;
        this.guard = guard;
        this.actors = actors;
        this.fulfillment = fulfillment;
        this.tx = new TransactionTemplate(tm);
        this.completeAfterDays = completeAfterDays;
    }

    @Scheduled(fixedDelayString = "${commerce.fulfillment.watch-interval:PT30M}", initialDelayString = "${commerce.fulfillment.watch-initial-delay:PT4M}")
    public void scheduled() {
        Instant now = Instant.now();
        scanTrackingSla(now);
        completeDelivered(now);
    }

    /** @return 새로 만든 송장 미수신 업무 수 */
    public int scanTrackingSla(Instant now) {
        return TenantContext.asSystem(() -> {
            List<Map<String, Object>> late = jdbc.sql("""
                    select f.id, f.workspace_id, f.business_entity_id, f.channel_account_id, f.fo_no, f.accepted_at, n.name as node_name, n.tracking_sla_hours
                      from fulfillment_order f join fulfillment_node n on n.id = f.node_id
                     where f.status in ('ACCEPTED', 'PICKING', 'PACKING') and f.accepted_at is not null
                       and f.accepted_at + make_interval(hours => n.tracking_sla_hours) < :now
                       and not exists (select 1 from work_item w where w.workspace_id = f.workspace_id and w.dedupe_key = 'fulfillment:' || f.id || ':TRACKING'
                                          and w.status in ('OPEN', 'IN_PROGRESS'))
                     limit 1000
                    """).param("now", com.commerceos.shared.db.Db.ts(now)).query().listOfRows();
            for (Map<String, Object> f : late) {
                UUID ws = (UUID) f.get("workspace_id");
                tx.executeWithoutResult(s -> workItems.raise(new WorkItemService.Raise(ws, (UUID) f.get("business_entity_id"), (UUID) f.get("channel_account_id"),
                        null, "FULFILLMENT_TRACKING_MISSING", "HIGH", "송장 미수신: " + f.get("fo_no"),
                        f.get("node_name") + " 에서 접수 후 " + f.get("tracking_sla_hours") + "시간이 지나도록 출고 확정·송장이 없습니다.",
                        "물류사에 출고 여부를 확인하고, 출고됐다면 송장을 등록(파일·직접 입력)하세요. 출고가 불가하면 취소하세요.",
                        "fulfillment:" + f.get("id") + ":TRACKING", Map.of("fulfillment_order_id", f.get("id").toString()))));
            }
            return late.size();
        });
    }

    /** @return 완료 처리한 주문 수 */
    public int completeDelivered(Instant now) {
        return TenantContext.asSystem(() -> {
            List<Map<String, Object>> due = jdbc.sql("""
                    select o.id, o.workspace_id from sales_order o
                     where o.status = 'PROCESSING' and o.shipping_status = 'DELIVERED' and o.delivered_at + make_interval(days => :d) < :now
                       and not exists (select 1 from fulfillment_order f where f.order_id = o.id
                                          and f.status in ('ALLOCATED', 'REQUESTED', 'ACCEPTED', 'PICKING', 'PACKING', 'CANCEL_REQUESTED', 'FAILED'))
                       and not exists (select 1 from claim c where c.order_id = o.id and c.status in ('REQUESTED', 'REVIEWING', 'APPROVED', 'PROCESSING'))
                     limit 1000
                    """).param("d", completeAfterDays).param("now", com.commerceos.shared.db.Db.ts(now)).query().listOfRows();
            int n = 0;
            for (Map<String, Object> o : due) {
                UUID ws = (UUID) o.get("workspace_id");
                UUID id = (UUID) o.get("id");
                CurrentActor system = actors.systemAutomation(ws);
                Integer changed = guard.runAs(system, () -> tx.execute(s -> {
                    int u = jdbc.sql("""
                            update sales_order set status = 'COMPLETED', closed_at = coalesce(closed_at, now()), version = version + 1, updated_at = now()
                             where id = :id and status = 'PROCESSING'
                            """).param("id", id).update();
                    if (u > 0) {
                        fulfillment.orderEvent(system, id, "STATUS_CHANGED", "PROCESSING", "COMPLETED", Map.of("reason", "배송완료 후 " + completeAfterDays + "일 경과"));
                    }
                    return u;
                }));
                n += changed == null ? 0 : changed;
            }
            return n;
        });
    }
}
