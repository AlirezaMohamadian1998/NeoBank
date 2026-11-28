package com.neobank.neobank.card.limit;

import com.neobank.neobank.card.Card;
import com.neobank.neobank.shared.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "card_daily_spend")
public class CardDailySpend extends BaseEntity {

    @Version
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "card_id", nullable = false, updatable = false)
    private Card card;

    @Column(nullable = false, updatable = false)
    private LocalDate spendDate;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amountSpent;

    private CardDailySpend(Card card, LocalDate spendDate) {
        this.card = card;
        this.spendDate = spendDate;
        this.amountSpent = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
    }

    public static CardDailySpend createNew(Card card, LocalDate spendDate) {
        if(card == null) {
            throw new IllegalArgumentException("Card must not be null");
        }

        if(spendDate == null) {
            throw new IllegalArgumentException("Spend date must not be null");
        }

        return new CardDailySpend(card, spendDate);
    }

    public void add(BigDecimal amount, CardSpendingLimits limits) {
        if(amount == null) {
            throw new IllegalArgumentException("Amount must not be null");
        }

        if(amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }

        if(amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Amount must have at most two decimal places");
        }

        if(limits == null) {
            throw new IllegalArgumentException("Limits must not be null");
        }

        if(amount.compareTo(limits.getPerTransactionLimit()) > 0) {
            throw new CardLimitExceededException("Purchase exceeds the card's per-transaction limit");
        }

        BigDecimal newAmountSpent = amountSpent.add(amount);

        if(newAmountSpent.compareTo(limits.getDailyLimit()) > 0) {
            throw new CardLimitExceededException("Purchase exceeds the card's daily limit");
        }

        amountSpent = newAmountSpent.setScale(2, RoundingMode.UNNECESSARY);
    }
}
