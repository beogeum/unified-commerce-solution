package com.commerceos.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commerceos.shared.money.Money;
import com.commerceos.shared.money.TaxType;
import com.commerceos.shared.money.TaxedAmount;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

/** 금액은 정확한 십진수·통화·부가세 구분(CLAUDE.md, docs/05 §2). 사례 A 의 단가 분해를 고정값으로 검증. */
class MoneyTest {
    static final BigDecimal VAT = new BigDecimal("0.10");

    @Test
    void caseA_unitPriceSplit_supply27000_vat2700() {
        Money paid = Money.krw(33_000).minus(Money.krw(3_300));
        TaxedAmount t = TaxedAmount.fromGross(paid, TaxType.TAXABLE, VAT, RoundingMode.HALF_UP);
        assertThat(t.supply().isEqualTo(Money.krw(27_000))).isTrue();
        assertThat(t.vat().isEqualTo(Money.krw(2_700))).isTrue();
        assertThat(t.supply().times(2).isEqualTo(Money.krw(54_000))).isTrue();
    }

    @Test
    void currencyMismatch_rejected() {
        assertThatThrownBy(() -> Money.krw(1).plus(Money.of("1", "USD"))).isInstanceOf(Money.CurrencyMismatchException.class);
    }

    @Test
    void roundingModeMustBeExplicit() {
        assertThatThrownBy(() -> TaxedAmount.fromGross(Money.krw(1001), TaxType.TAXABLE, VAT, null)).isInstanceOf(NullPointerException.class);
        TaxedAmount down = TaxedAmount.fromGross(Money.krw(1001), TaxType.TAXABLE, VAT, RoundingMode.DOWN);
        assertThat(down.supply().plus(down.vat()).isEqualTo(Money.krw(1001))).isTrue();
    }

    @Test
    void exemptHasNoVat_andInvariantEnforced() {
        TaxedAmount ex = TaxedAmount.fromGross(Money.krw(5000), TaxType.EXEMPT, VAT, RoundingMode.HALF_UP);
        assertThat(ex.vat().isZero()).isTrue();
        assertThatThrownBy(() -> new TaxedAmount(Money.krw(100), Money.krw(90), Money.krw(5), TaxType.TAXABLE,
                com.commerceos.shared.money.VatInclusion.INCLUDED)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void plainStringHasNoExponent() {
        assertThat(Money.of("30000.00", "KRW").toPlainString()).isEqualTo("30000");
        assertThat(Money.of("1500.50", "USD").toPlainString()).isEqualTo("1500.5");
    }
}
