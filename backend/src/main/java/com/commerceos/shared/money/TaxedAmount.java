package com.commerceos.shared.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * docs/05 §2 Money 표준: 총액·공급가·부가세·과세구분·부가세 포함 여부를 함께 보관한다.
 * 공급가+세액=총액 을 생성 시 검증한다.
 */
public record TaxedAmount(Money gross, Money supply, Money vat, TaxType taxType, VatInclusion originalInclusion) {

    public TaxedAmount {
        Objects.requireNonNull(taxType);
        Objects.requireNonNull(originalInclusion);
        if (!supply.plus(vat).isEqualTo(gross)) {
            throw new IllegalArgumentException("supply + vat must equal gross");
        }
        if (taxType != TaxType.TAXABLE && !vat.isZero()) {
            throw new IllegalArgumentException("non-taxable amount must have zero vat");
        }
    }

    /**
     * 부가세 포함 총액을 공급가/세액으로 분리. 세액 = 총액 - 공급가, 공급가 = 총액 / (1 + 세율) 을 지정한 모드로 반올림.
     * 세율과 반올림 모드는 정책(OQ-021)에서 결정되므로 호출자가 반드시 지정한다.
     */
    public static TaxedAmount fromGross(Money gross, TaxType taxType, BigDecimal vatRate, RoundingMode mode) {
        if (taxType != TaxType.TAXABLE) {
            return new TaxedAmount(gross, gross, Money.zero(gross.currency()), taxType, VatInclusion.INCLUDED);
        }
        Objects.requireNonNull(vatRate, "vatRate");
        Objects.requireNonNull(mode, "rounding mode must be explicit");
        int scale = gross.currency().getDefaultFractionDigits();
        BigDecimal supply = gross.amount().divide(BigDecimal.ONE.add(vatRate), scale, mode);
        Money supplyMoney = new Money(supply, gross.currency());
        return new TaxedAmount(gross, supplyMoney, gross.minus(supplyMoney), taxType, VatInclusion.INCLUDED);
    }

    public static TaxedAmount fromSupply(Money supply, TaxType taxType, BigDecimal vatRate, RoundingMode mode) {
        if (taxType != TaxType.TAXABLE) {
            return new TaxedAmount(supply, supply, Money.zero(supply.currency()), taxType, VatInclusion.EXCLUDED);
        }
        Objects.requireNonNull(mode, "rounding mode must be explicit");
        int scale = supply.currency().getDefaultFractionDigits();
        Money vat = new Money(supply.amount().multiply(vatRate).setScale(scale, mode), supply.currency());
        return new TaxedAmount(supply.plus(vat), supply, vat, taxType, VatInclusion.EXCLUDED);
    }
}
