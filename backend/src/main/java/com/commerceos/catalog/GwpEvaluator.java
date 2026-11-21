package com.commerceos.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * GWP 판정(FR-CAT-07, docs/04 §GWP, T13). 순수 계산 — 주문(M4)과 미리보기가 같은 규칙을 쓴다.
 * 순서: 기간·상태·판매계정 → 조건(기본 AND) → 채널 사은품 중복 → 중복 그룹·정책 → 선착순 잔여 → 사은품 재고(대체).
 * 지급하지 않은 캠페인도 이유와 함께 돌려준다(업무함·CS 설명용).
 */
public final class GwpEvaluator {
    private GwpEvaluator() {
    }

    public record Line(UUID skuId, int quantity, BigDecimal amountVatIncluded, UUID brandId) {
    }

    /** 주문에 채널이 이미 넣어 준 사은품(채널 행사 번호 또는 사은품 SKU). */
    public record ChannelGift(String channelCampaignRef, UUID giftSkuId) {
    }

    public record Order(UUID businessEntityId, UUID channelAccountId, Instant orderedAt, List<Line> lines, List<ChannelGift> channelGifts) {
    }

    public record Reward(UUID giftSkuId, int quantity, int priority) {
    }

    public record Campaign(UUID id, String name, UUID businessEntityId, Set<UUID> channelAccountIds, Instant startsAt, Instant endsAt,
                           String status, int priority, String stackGroup, String stackPolicy, BigDecimal minOrderAmount, UUID requiredBrandId,
                           String conditionMode, Map<UUID, Integer> requiredSkus, Set<UUID> excludedSkus, List<Reward> rewards, int maxPerOrder,
                           Integer remainingLimit, String outOfStockPolicy, String channelOverlapPolicy, String channelCampaignRef) {
    }

    public record Award(UUID campaignId, String campaignName, UUID giftSkuId, int quantity, boolean substitute) {
    }

    public record Skipped(UUID campaignId, String campaignName, String reason) {
    }

    public record Result(List<Award> awards, List<Skipped> skipped, boolean holdOrder) {
    }

    /**
     * @param giftStock 사은품 SKU 별 지금 줄 수 있는 수량(모르면 null — 재고 검사 생략, M3 이후 연결).
     *                  지급할 때마다 이 맵에서 차감하므로 같은 주문 안의 여러 캠페인이 같은 재고를 두 번 쓰지 않는다(변경 가능한 맵을 넘길 것).
     */
    public static Result evaluate(Order order, List<Campaign> campaigns, Map<UUID, Long> giftStock) {
        List<Award> awards = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        boolean hold = false;
        Set<String> channelRefs = new HashSet<>();
        Set<UUID> channelGiftSkus = new HashSet<>();
        for (ChannelGift g : order.channelGifts() == null ? List.<ChannelGift>of() : order.channelGifts()) {
            if (g.channelCampaignRef() != null) {
                channelRefs.add(g.channelCampaignRef());
            }
            if (g.giftSkuId() != null) {
                channelGiftSkus.add(g.giftSkuId());
            }
        }
        List<Campaign> eligible = new ArrayList<>();
        for (Campaign c : campaigns.stream().sorted(Comparator.comparingInt(Campaign::priority).reversed().thenComparing(x -> x.id().toString())).toList()) {
            String why = basicReason(order, c);
            if (why == null) {
                why = conditionReason(order, c, orderAmount(order, c));
            }
            if (why == null && "SKIP_INTERNAL".equals(c.channelOverlapPolicy())) {
                boolean sameRef = c.channelCampaignRef() != null && channelRefs.contains(c.channelCampaignRef());
                boolean sameGift = c.rewards().stream().anyMatch(r -> channelGiftSkus.contains(r.giftSkuId()));
                if (sameRef || sameGift) {
                    why = "CHANNEL_GIFT_DUPLICATE";
                }
            }
            if (why != null) {
                skipped.add(new Skipped(c.id(), c.name(), why));
            } else {
                eligible.add(c);
            }
        }
        // 중복 그룹·정책(우선순위 높은 순으로 이미 정렬됨)
        Set<String> usedGroups = new HashSet<>();
        boolean exclusiveTaken = false;
        for (Campaign c : eligible) {
            if (exclusiveTaken) {
                skipped.add(new Skipped(c.id(), c.name(), "STACK_POLICY"));
                continue;
            }
            if (c.stackGroup() != null && !"CUMULATIVE".equals(c.stackPolicy()) && usedGroups.contains(c.stackGroup())) {
                skipped.add(new Skipped(c.id(), c.name(), "STACK_GROUP"));
                continue;
            }
            if (c.remainingLimit() != null && c.remainingLimit() <= 0) {
                skipped.add(new Skipped(c.id(), c.name(), "LIMIT_EXHAUSTED"));
                continue;
            }
            List<Reward> ordered = c.rewards().stream().sorted(Comparator.comparingInt(Reward::priority)).toList();
            Reward chosen = null;
            for (Reward r : ordered) {
                long need = (long) r.quantity() * c.maxPerOrder();
                boolean inStock = giftStock == null || giftStock.getOrDefault(r.giftSkuId(), 0L) >= need;
                if (inStock) {
                    chosen = r;
                    break;
                }
                if (!"SUBSTITUTE".equals(c.outOfStockPolicy())) {
                    break; // 대체 정책이 아니면 기본 사은품만 본다
                }
            }
            if (chosen == null) {
                switch (c.outOfStockPolicy()) {
                    case "HOLD_ORDER" -> {
                        hold = true;
                        skipped.add(new Skipped(c.id(), c.name(), "GIFT_OUT_OF_STOCK_HOLD"));
                    }
                    case "PROCEED_WITHOUT", "SUBSTITUTE" -> skipped.add(new Skipped(c.id(), c.name(), "GIFT_OUT_OF_STOCK"));
                    default -> skipped.add(new Skipped(c.id(), c.name(), "GIFT_OUT_OF_STOCK_STOPPED"));
                }
                continue;
            }
            int qty = chosen.quantity() * c.maxPerOrder();
            if (c.remainingLimit() != null) {
                qty = Math.min(qty, c.remainingLimit() * chosen.quantity());
            }
            awards.add(new Award(c.id(), c.name(), chosen.giftSkuId(), qty, chosen.priority() > 0));
            if (giftStock != null) {
                giftStock.merge(chosen.giftSkuId(), -(long) qty, Long::sum);
            }
            if (c.stackGroup() != null) {
                usedGroups.add(c.stackGroup());
            }
            if ("HIGHEST_PRIORITY_ONLY".equals(c.stackPolicy())) {
                exclusiveTaken = true;
            }
        }
        return new Result(awards, skipped, hold);
    }

    private static BigDecimal orderAmount(Order o, Campaign c) {
        return o.lines().stream().filter(l -> !c.excludedSkus().contains(l.skuId())).map(Line::amountVatIncluded)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String basicReason(Order o, Campaign c) {
        if (!"ACTIVE".equals(c.status())) {
            return "NOT_ACTIVE";
        }
        if (!c.businessEntityId().equals(o.businessEntityId())) {
            return "OTHER_BUSINESS";
        }
        if (o.orderedAt().isBefore(c.startsAt()) || !o.orderedAt().isBefore(c.endsAt())) {
            return "OUT_OF_PERIOD";
        }
        if (!c.channelAccountIds().isEmpty() && !c.channelAccountIds().contains(o.channelAccountId())) {
            return "CHANNEL_ACCOUNT_NOT_TARGETED";
        }
        if (c.rewards().isEmpty()) {
            return "NO_REWARD";
        }
        return null;
    }

    private static String conditionReason(Order o, Campaign c, BigDecimal amount) {
        List<Boolean> results = new ArrayList<>();
        if (c.minOrderAmount() != null) {
            results.add(amount.compareTo(c.minOrderAmount()) >= 0);
        }
        if (!c.requiredSkus().isEmpty()) {
            boolean ok = c.requiredSkus().entrySet().stream().anyMatch(e ->
                    o.lines().stream().filter(l -> l.skuId().equals(e.getKey())).mapToInt(Line::quantity).sum() >= e.getValue());
            results.add(ok);
        }
        if (c.requiredBrandId() != null) {
            results.add(o.lines().stream().anyMatch(l -> c.requiredBrandId().equals(l.brandId()) && !c.excludedSkus().contains(l.skuId())));
        }
        if (results.isEmpty()) {
            return null; // 조건 없는 캠페인 = 대상 계정의 모든 주문
        }
        boolean pass = "ANY".equals(c.conditionMode()) ? results.contains(true) : !results.contains(false);
        return pass ? null : "CONDITION_NOT_MET";
    }
}
