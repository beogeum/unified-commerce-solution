package com.commerceos.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 가상묶음 판매가능 수량 계산(docs/03 §3, FR-CAT-05, T12 계산부). 재고 값은 M3 재고 모듈이 넣는다.
 * <ul>
 *   <li>분할출고 불가(기본): 허용 거점마다 MIN(floor(부품 가용 ÷ 소요수량))을 구해 합산. 한 주문은 한 거점에서 모두 나가야 한다.</li>
 *   <li>분할출고 허용: 허용 거점 전체 합계로 MIN(floor(부품 합계 ÷ 소요수량)). 비용·SLA 확인은 정책 쪽 책임.</li>
 * </ul>
 * 허용 거점 밖 재고, 음수 가용(차감 오류)은 0으로 본다.
 */
public final class BundleAvailability {
    private BundleAvailability() {
    }

    public record Component(UUID skuId, int quantity) {
    }

    public record Result(long sellable, Map<String, Long> byNode, Map<UUID, String> limitingComponentByNode) {
    }

    public static Result compute(List<Component> components, Map<String, Map<UUID, Long>> availableByNode, Set<String> allowedNodes,
                                 boolean splitShipmentAllowed) {
        if (components.isEmpty()) {
            return new Result(0, Map.of(), Map.of());
        }
        Map<String, Long> byNode = new LinkedHashMap<>();
        Map<UUID, String> limiting = new LinkedHashMap<>();
        long total = 0;
        for (String node : allowedNodes) {
            Map<UUID, Long> stock = availableByNode.getOrDefault(node, Map.of());
            long nodeMin = Long.MAX_VALUE;
            UUID limit = null;
            for (Component c : components) {
                long avail = Math.max(0, stock.getOrDefault(c.skuId(), 0L));
                long sets = avail / c.quantity();
                if (sets < nodeMin) {
                    nodeMin = sets;
                    limit = c.skuId();
                }
            }
            byNode.put(node, nodeMin);
            if (limit != null) {
                limiting.put(limit, node);
            }
            total += nodeMin;
        }
        if (!splitShipmentAllowed) {
            return new Result(total, byNode, limiting);
        }
        long pooled = Long.MAX_VALUE;
        for (Component c : components) {
            long sum = 0;
            for (String node : allowedNodes) {
                sum += Math.max(0, availableByNode.getOrDefault(node, Map.of()).getOrDefault(c.skuId(), 0L));
            }
            pooled = Math.min(pooled, sum / c.quantity());
        }
        return new Result(pooled, byNode, limiting);
    }
}
