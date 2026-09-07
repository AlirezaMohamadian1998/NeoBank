package com.neobank.neobank.transaction;

import com.neobank.neobank.shared.money.CurrencyCode;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.*;

import java.math.BigDecimal;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class FxRate {

    @EqualsAndHashCode.Include
    @Enumerated(EnumType.STRING)
    @Column(name = "currency_context", nullable = false, updatable = false)
    private CurrencyContext currencyContext;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency_code", nullable = false, updatable = false)
    private CurrencyCode currency;

    @Column(
            name = "rate",
            nullable = false,
            updatable = false,
            precision = 19,
            scale = 8
    )
    private BigDecimal rate;

    public static FxRate createNew(
            CurrencyContext currencyContext,
            CurrencyCode currency,
            BigDecimal rate
    ) {

        if(currencyContext == null) {
            throw new IllegalArgumentException("Currency context must not be null.");
        }

        if(currency == null) {
            throw new IllegalArgumentException("Currency must not be null.");
        }

        if(rate == null) {
            throw new IllegalArgumentException("Rate must not be null.");
        }

        if(rate.signum() <= 0) {
            throw new IllegalArgumentException("Rate must be positive.");
        }

        BigDecimal normalizedRate = rate.stripTrailingZeros();

        int fractionalDigits = Math.max(normalizedRate.scale(), 0);
        int integerDigits = Math.max(normalizedRate.precision() - normalizedRate.scale(), 0);

        if(fractionalDigits > 8 || integerDigits > 11) {
            throw new IllegalArgumentException("Rate must fit DECIMAL(19,8)");
        }

        return new FxRate(currencyContext, currency, rate);
    }
}
