package com.commerceos.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** GWP 판정 규칙(FR-CAT-07, docs/13 §3) — T13 채널 사은품 중복 방지 포함. */
class GwpEvaluatorTest {
    final UUID be = UUID.randomUUID();
    final UUID account = UUID.randomUUID();
    final UUID amp = UUID.randomUUID();
    final UUID clf = UUID.randomUUID();
    final UUID pouch = UUID.randomUUID();
    final UUID sample = UUID.randomUUID();
    final UUID brand = UUID.randomUUID();
    final Instant now = Instant.now();

    GwpEvaluator.Campaign campaign(String name, int priority, String group, String policy, BigDecimal min, Map<UUID, Integer> req,
                                   List<GwpEvaluator.Reward> rewards, String overlap, String ref) {
        return new GwpEvaluator.Campaign(UUID.randomUUID(), name, be, Set.of(), now.minus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS),
                "ACTIVE", priority, group, policy, min, null, "ALL", req, Set.of(), rewards, 1, null, "SUBSTITUTE", overlap, ref);
    }

    GwpEvaluator.Order order(List<GwpEvaluator.ChannelGift> gifts) {
        return new GwpEvaluator.Order(be, account, now, List.of(new GwpEvaluator.Line(amp, 2, new BigDecimal("66000"), brand)), gifts);
    }

    List<GwpEvaluator.Reward> pouchReward() {
        return List.of(new GwpEvaluator.Reward(pouch, 1, 0));
    }

    @Test
    void T13_channelGiftWithSameGiftOrCampaignRef_skipsInternalByDefault() {
        var c = campaign("앰플 2개 파우치", 100, null, "ONE_PER_GROUP", null, Map.of(amp, 2), pouchReward(), "SKIP_INTERNAL", "NAVER-PROMO-77");
        var bySku = GwpEvaluator.evaluate(order(List.of(new GwpEvaluator.ChannelGift(null, pouch))), List.of(c), null);
        assertThat(bySku.awards()).isEmpty();
        assertThat(bySku.skipped().get(0).reason()).isEqualTo("CHANNEL_GIFT_DUPLICATE");
        var byRef = GwpEvaluator.evaluate(order(List.of(new GwpEvaluator.ChannelGift("NAVER-PROMO-77", null))), List.of(c), null);
        assertThat(byRef.awards()).isEmpty();
        // 다른 채널 사은품이면 지급
        assertThat(GwpEvaluator.evaluate(order(List.of(new GwpEvaluator.ChannelGift(null, sample))), List.of(c), null).awards()).hasSize(1);
        // 명시적으로 함께 지급을 허용한 캠페인만 중복 지급
        var both = campaign("함께 지급", 100, null, "ONE_PER_GROUP", null, Map.of(), pouchReward(), "ALLOW_BOTH", "NAVER-PROMO-77");
        assertThat(GwpEvaluator.evaluate(order(List.of(new GwpEvaluator.ChannelGift("NAVER-PROMO-77", pouch))), List.of(both), null).awards()).hasSize(1);
    }

    @Test
    void stackGroups_onePerGroup_cumulative_andHighestOnly() {
        var high = campaign("A", 200, "WINTER", "ONE_PER_GROUP", null, Map.of(), pouchReward(), "SKIP_INTERNAL", null);
        var low = campaign("B", 100, "WINTER", "ONE_PER_GROUP", null, Map.of(), List.of(new GwpEvaluator.Reward(sample, 1, 0)), "SKIP_INTERNAL", null);
        var r = GwpEvaluator.evaluate(order(List.of()), List.of(low, high), null);
        assertThat(r.awards()).extracting(GwpEvaluator.Award::campaignName).containsExactly("A");
        assertThat(r.skipped()).extracting(GwpEvaluator.Skipped::reason).containsExactly("STACK_GROUP");

        var c1 = campaign("C1", 200, "G", "CUMULATIVE", null, Map.of(), pouchReward(), "SKIP_INTERNAL", null);
        var c2 = campaign("C2", 100, "G", "CUMULATIVE", null, Map.of(), List.of(new GwpEvaluator.Reward(sample, 1, 0)), "SKIP_INTERNAL", null);
        assertThat(GwpEvaluator.evaluate(order(List.of()), List.of(c1, c2), null).awards()).hasSize(2);

        var only = campaign("ONLY", 300, null, "HIGHEST_PRIORITY_ONLY", null, Map.of(), pouchReward(), "SKIP_INTERNAL", null);
        var other = campaign("OTHER", 100, null, "ONE_PER_GROUP", null, Map.of(), List.of(new GwpEvaluator.Reward(sample, 1, 0)), "SKIP_INTERNAL", null);
        var ex = GwpEvaluator.evaluate(order(List.of()), List.of(other, only), null);
        assertThat(ex.awards()).extracting(GwpEvaluator.Award::campaignName).containsExactly("ONLY");
        assertThat(ex.skipped()).extracting(GwpEvaluator.Skipped::reason).containsExactly("STACK_POLICY");
    }

    @Test
    void conditions_periodAccountAmountAndExcludedSkus() {
        var minAmount = campaign("5만원 이상", 100, null, "ONE_PER_GROUP", new BigDecimal("50000"), Map.of(), pouchReward(), "SKIP_INTERNAL", null);
        assertThat(GwpEvaluator.evaluate(order(List.of()), List.of(minAmount), null).awards()).hasSize(1);
        // 제외 SKU 금액은 조건 금액에서 빠진다
        var excluding = new GwpEvaluator.Campaign(UUID.randomUUID(), "제외", be, Set.of(), now.minusSeconds(60), now.plusSeconds(60), "ACTIVE", 100,
                null, "ONE_PER_GROUP", new BigDecimal("50000"), null, "ALL", Map.of(), Set.of(amp), pouchReward(), 1, null, "SUBSTITUTE", "SKIP_INTERNAL", null);
        assertThat(GwpEvaluator.evaluate(order(List.of()), List.of(excluding), null).skipped().get(0).reason()).isEqualTo("CONDITION_NOT_MET");
        // AND: 금액은 넘지만 조건 SKU(CLF) 없음
        var both = campaign("금액+SKU", 100, null, "ONE_PER_GROUP", new BigDecimal("10000"), Map.of(clf, 1), pouchReward(), "SKIP_INTERNAL", null);
        assertThat(GwpEvaluator.evaluate(order(List.of()), List.of(both), null).awards()).isEmpty();
        var any = new GwpEvaluator.Campaign(both.id(), "ANY", be, Set.of(), both.startsAt(), both.endsAt(), "ACTIVE", 100, null, "ONE_PER_GROUP",
                new BigDecimal("10000"), null, "ANY", Map.of(clf, 1), Set.of(), pouchReward(), 1, null, "SUBSTITUTE", "SKIP_INTERNAL", null);
        assertThat(GwpEvaluator.evaluate(order(List.of()), List.of(any), null).awards()).hasSize(1);
        // 기간 밖·다른 판매계정·다른 사업자
        var future = new GwpEvaluator.Campaign(UUID.randomUUID(), "미래", be, Set.of(), now.plusSeconds(3600), now.plusSeconds(7200), "ACTIVE", 100, null,
                "ONE_PER_GROUP", null, null, "ALL", Map.of(), Set.of(), pouchReward(), 1, null, "SUBSTITUTE", "SKIP_INTERNAL", null);
        var otherAccount = new GwpEvaluator.Campaign(UUID.randomUUID(), "다른 계정", be, Set.of(UUID.randomUUID()), now.minusSeconds(60), now.plusSeconds(60),
                "ACTIVE", 100, null, "ONE_PER_GROUP", null, null, "ALL", Map.of(), Set.of(), pouchReward(), 1, null, "SUBSTITUTE", "SKIP_INTERNAL", null);
        var r = GwpEvaluator.evaluate(order(List.of()), List.of(future, otherAccount), null);
        assertThat(r.skipped()).extracting(GwpEvaluator.Skipped::reason).containsExactlyInAnyOrder("OUT_OF_PERIOD", "CHANNEL_ACCOUNT_NOT_TARGETED");
    }

    @Test
    void outOfStock_substitutesHoldsOrProceeds_andSharedStockIsNotDoubleUsed() {
        var sub = campaign("대체", 200, null, "ONE_PER_GROUP", null, Map.of(),
                List.of(new GwpEvaluator.Reward(pouch, 1, 0), new GwpEvaluator.Reward(sample, 2, 1)), "SKIP_INTERNAL", null);
        Map<UUID, Long> stock = new HashMap<>(Map.of(pouch, 0L, sample, 5L));
        var r = GwpEvaluator.evaluate(order(List.of()), List.of(sub), stock);
        assertThat(r.awards().get(0).giftSkuId()).isEqualTo(sample);
        assertThat(r.awards().get(0).substitute()).isTrue();
        assertThat(stock.get(sample)).isEqualTo(3L);

        var hold = new GwpEvaluator.Campaign(UUID.randomUUID(), "보류", be, Set.of(), now.minusSeconds(60), now.plusSeconds(60), "ACTIVE", 100, null,
                "ONE_PER_GROUP", null, null, "ALL", Map.of(), Set.of(), pouchReward(), 1, null, "HOLD_ORDER", "SKIP_INTERNAL", null);
        var held = GwpEvaluator.evaluate(order(List.of()), List.of(hold), new HashMap<>(Map.of(pouch, 0L)));
        assertThat(held.holdOrder()).isTrue();
        assertThat(held.awards()).isEmpty();

        // 두 캠페인(누적)이 같은 사은품 재고 1개를 두 번 쓰지 않는다
        var a = campaign("A", 200, null, "CUMULATIVE", null, Map.of(), pouchReward(), "SKIP_INTERNAL", null);
        var b = campaign("B", 100, null, "CUMULATIVE", null, Map.of(), pouchReward(), "SKIP_INTERNAL", null);
        var shared = GwpEvaluator.evaluate(order(List.of()), List.of(a, b), new HashMap<>(Map.of(pouch, 1L)));
        assertThat(shared.awards()).hasSize(1);
        assertThat(shared.skipped().get(0).reason()).isEqualTo("GIFT_OUT_OF_STOCK");

        var exhausted = new GwpEvaluator.Campaign(UUID.randomUUID(), "선착순 끝", be, Set.of(), now.minusSeconds(60), now.plusSeconds(60), "ACTIVE", 100,
                null, "ONE_PER_GROUP", null, null, "ALL", Map.of(), Set.of(), pouchReward(), 1, 0, "SUBSTITUTE", "SKIP_INTERNAL", null);
        assertThat(GwpEvaluator.evaluate(order(List.of()), List.of(exhausted), null).skipped().get(0).reason()).isEqualTo("LIMIT_EXHAUSTED");
    }
}
