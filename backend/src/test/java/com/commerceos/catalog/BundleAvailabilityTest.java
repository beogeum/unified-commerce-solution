package com.commerceos.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** T12 계산부: 가상묶음 부품 부족·다중거점(docs/03 §3). */
class BundleAvailabilityTest {
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    final List<BundleAvailability.Component> bom = List.of(new BundleAvailability.Component(a, 1), new BundleAvailability.Component(b, 2));
    final Map<String, Map<UUID, Long>> stock = Map.of(
            "HWASEONG", Map.of(a, 10L, b, 8L),   // A 10세트 가능, B 4세트 → 4
            "FASSTO", Map.of(a, 3L, b, 20L),     // A 3세트, B 10세트 → 3
            "PUMGO", Map.of(a, 100L, b, 100L));  // 허용 거점 아님

    @Test
    void noSplit_sumsPerNodeMinimum_onlyAllowedNodes() {
        var r = BundleAvailability.compute(bom, stock, new LinkedHashSet<>(List.of("HWASEONG", "FASSTO")), false);
        assertThat(r.byNode()).containsEntry("HWASEONG", 4L).containsEntry("FASSTO", 3L);
        assertThat(r.sellable()).isEqualTo(7);
        assertThat(r.limitingComponentByNode()).containsEntry(b, "HWASEONG").containsEntry(a, "FASSTO");
    }

    @Test
    void splitAllowed_poolsAcrossAllowedNodes() {
        var r = BundleAvailability.compute(bom, stock, Set.of("HWASEONG", "FASSTO"), true);
        assertThat(r.sellable()).isEqualTo(13); // A 13, B 28/2 = 14 → 13
    }

    @Test
    void missingOrNegativeStock_countsAsZero_andEmptyBomIsZero() {
        var r = BundleAvailability.compute(bom, Map.of("X", Map.of(a, 5L, b, -3L)), Set.of("X", "EMPTY"), false);
        assertThat(r.sellable()).isZero();
        assertThat(BundleAvailability.compute(List.of(), stock, Set.of("HWASEONG"), false).sellable()).isZero();
    }
}
