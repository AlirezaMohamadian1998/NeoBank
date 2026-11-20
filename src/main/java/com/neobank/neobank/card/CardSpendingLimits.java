package com.neobank.neobank.card;

import com.neobank.neobank.card.exception.InvalidCardLimitException;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Getter
@Embeddable
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CardSpendingLimits {

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal perTransactionLimit;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal dailyLimit;

    private CardSpendingLimits(BigDecimal perTransactionLimit, BigDecimal dailyLimit) {
        this.perTransactionLimit = perTransactionLimit;
        this.dailyLimit = dailyLimit;
    }

    public static CardSpendingLimits createNew(BigDecimal perTransactionLimit, BigDecimal dailyLimit) {
        BigDecimal normalizedPerTransactionLimit = normalize(perTransactionLimit, "Per-transaction limit");
        BigDecimal normalizedDailyLimit = normalize(dailyLimit, "Daily limit");

        if(normalizedPerTransactionLimit.compareTo(normalizedDailyLimit) > 0) {
            throw new InvalidCardLimitException("Per-transaction limit must not exceed the daily limit");
        }

        return new CardSpendingLimits(normalizedPerTransactionLimit, normalizedDailyLimit);
    }

    public void ensureWithin(CardSpendingLimits maximum) {
        if(maximum == null) {
            throw new IllegalArgumentException("Maximum limits must not be null");
        }

        if(perTransactionLimit.compareTo(maximum.perTransactionLimit) > 0) {
            throw new InvalidCardLimitException(
                    "Per-transaction limit must not exceed " + maximum.perTransactionLimit.toPlainString()
            );
        }

        if(dailyLimit.compareTo(maximum.dailyLimit) > 0) {
            throw new InvalidCardLimitException(
                    "Daily limit must not exceed " + maximum.dailyLimit.toPlainString()
            );
        }
    }

    private static BigDecimal normalize(BigDecimal limit, String name) {
        if(limit == null) {
            throw new InvalidCardLimitException(name + " must not be null");
        }

        if(limit.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidCardLimitException(name + " must be greater than zero");
        }

        if(limit.stripTrailingZeros().scale() > 2) {
            throw new InvalidCardLimitException(name + " must have at most two decimal places");
        }

        return limit.setScale(2, RoundingMode.UNNECESSARY);
    }
}
