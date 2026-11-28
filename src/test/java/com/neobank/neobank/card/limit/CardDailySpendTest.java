package com.neobank.neobank.card.limit;

import com.neobank.neobank.card.Card;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CardDailySpendTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);

    private static final CardSpendingLimits LIMITS =
            CardSpendingLimits.createNew(new BigDecimal("100.00"), new BigDecimal("250.00"));

    private final Card card = mock(Card.class);

    @Test
    void startOfCreatesRowWithZeroSpent() {
        CardDailySpend spend = CardDailySpend.createNew(card, TODAY);

        assertThat(spend.getCard()).isSameAs(card);
        assertThat(spend.getSpendDate()).isEqualTo(TODAY);
        assertThat(spend.getAmountSpent()).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void startOfRejectsNullCard() {
        assertThatThrownBy(() -> CardDailySpend.createNew(null, TODAY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startOfRejectsNullDate() {
        assertThatThrownBy(() -> CardDailySpend.createNew(card, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addAccumulatesAmounts() {
        CardDailySpend spend = CardDailySpend.createNew(card, TODAY);

        spend.add(new BigDecimal("40.00"), LIMITS);
        spend.add(new BigDecimal("60.5"), LIMITS);

        assertThat(spend.getAmountSpent()).isEqualTo(new BigDecimal("100.50"));
    }

    @Test
    void addAcceptsAmountEqualToPerTransactionLimit() {
        CardDailySpend spend = CardDailySpend.createNew(card, TODAY);

        spend.add(new BigDecimal("100.00"), LIMITS);

        assertThat(spend.getAmountSpent()).isEqualTo(new BigDecimal("100.00"));
    }

    @Test
    void addRejectsAmountAbovePerTransactionLimit() {
        CardDailySpend spend = CardDailySpend.createNew(card, TODAY);

        assertThatThrownBy(() -> spend.add(new BigDecimal("100.01"), LIMITS))
                .isInstanceOf(CardLimitExceededException.class)
                .hasMessage("Purchase exceeds the card's per-transaction limit");

        assertThat(spend.getAmountSpent()).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void addAcceptsReachingDailyLimitExactly() {
        CardDailySpend spend = CardDailySpend.createNew(card, TODAY);
        spend.add(new BigDecimal("100.00"), LIMITS);
        spend.add(new BigDecimal("100.00"), LIMITS);

        spend.add(new BigDecimal("50.00"), LIMITS);

        assertThat(spend.getAmountSpent()).isEqualTo(new BigDecimal("250.00"));
    }

    @Test
    void addRejectsGoingOverDailyLimit() {
        CardDailySpend spend = CardDailySpend.createNew(card, TODAY);
        spend.add(new BigDecimal("100.00"), LIMITS);
        spend.add(new BigDecimal("100.00"), LIMITS);

        assertThatThrownBy(() -> spend.add(new BigDecimal("50.01"), LIMITS))
                .isInstanceOf(CardLimitExceededException.class)
                .hasMessage("Purchase exceeds the card's daily limit");

        assertThat(spend.getAmountSpent()).isEqualTo(new BigDecimal("200.00"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "0.00", "-1.00", "10.001"})
    void addRejectsInvalidAmounts(String amount) {
        CardDailySpend spend = CardDailySpend.createNew(card, TODAY);
        BigDecimal value = amount == null ? null : new BigDecimal(amount);

        assertThatThrownBy(() -> spend.add(value, LIMITS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addRejectsNullLimits() {
        CardDailySpend spend = CardDailySpend.createNew(card, TODAY);

        assertThatThrownBy(() -> spend.add(new BigDecimal("10.00"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
