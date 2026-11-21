package com.commerceos.shared.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * 통화가 있는 정확한 십진 금액. float/double 금지(CLAUDE.md).
 * 통화가 다른 금액끼리 연산하지 않는다(OQ-022). 반올림 모드는 기본값 없이 호출자가 명시한다(OQ-021).
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.scale() > currency.getDefaultFractionDigits() + 4) {
            throw new IllegalArgumentException("scale too large for " + currency);
        }
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    public static Money krw(long amount) {
        return new Money(BigDecimal.valueOf(amount), Currency.getInstance("KRW"));
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money times(long quantity) {
        return new Money(amount.multiply(BigDecimal.valueOf(quantity)), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    /** 통화 기본 소수 자릿수로 반올림. 모드는 정책에 따라 호출자가 지정. */
    public Money rounded(RoundingMode mode) {
        Objects.requireNonNull(mode, "rounding mode must be explicit");
        return new Money(amount.setScale(currency.getDefaultFractionDigits(), mode), currency);
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    /** API 응답용 decimal 문자열(docs/07 §1). 지수 표기 금지. */
    public String toPlainString() {
        return amount.stripTrailingZeros().scale() < 0 ? amount.setScale(0).toPlainString() : amount.stripTrailingZeros().toPlainString();
    }

    @Override
    public int compareTo(Money o) {
        requireSameCurrency(o);
        return amount.compareTo(o.amount);
    }

    /** 값 비교는 scale 과 무관해야 한다(30000 == 30000.00). */
    public boolean isEqualTo(Money o) {
        return compareTo(o) == 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    public static class CurrencyMismatchException extends IllegalArgumentException {
        public CurrencyMismatchException(Currency a, Currency b) {
            super("currency mismatch: " + a + " vs " + b);
        }
    }
}
