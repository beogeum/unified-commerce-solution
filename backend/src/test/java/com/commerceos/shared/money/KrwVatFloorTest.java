package com.commerceos.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** DLG-030: 부가세 = 포함금액 ÷ 11 원 미만 절사, 공급가 = 포함금액 − 부가세. */
class KrwVatFloorTest {
    @ParameterizedTest
    @CsvSource({"33000,30000,3000", "31000,28182,2818", "28900,26273,2627", "105,96,9", "10,10,0", "0,0,0", "11,10,1"})
    void splitsWholeWon(String gross, String supply, String vat) {
        TaxedAmount t = TaxedAmount.fromGrossKrwVatFloor(Money.of(gross, "KRW"), TaxType.TAXABLE);
        assertThat(t.supply().toPlainString()).isEqualTo(supply);
        assertThat(t.vat().toPlainString()).isEqualTo(vat);
        assertThat(t.supply().plus(t.vat()).isEqualTo(t.gross())).isTrue();
    }

    @Test
    void exemptHasNoVat_andNonKrwOrFractionRejected() {
        assertThat(TaxedAmount.fromGrossKrwVatFloor(Money.of("5500", "KRW"), TaxType.EXEMPT).vat().isZero()).isTrue();
        assertThatThrownBy(() -> TaxedAmount.fromGrossKrwVatFloor(Money.of("10.5", "USD"), TaxType.TAXABLE)).isInstanceOf(IllegalArgumentException.class);
    }
}
