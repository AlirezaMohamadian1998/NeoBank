package com.neobank.neobank.card;

import com.neobank.neobank.card.exception.InvalidCardLimitException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardSpendingLimitsTest {

    @Test
    void validLimitsAreNormalizedToTwoDecimals() {
        CardSpendingLimits limits = CardSpendingLimits.createNew(new BigDecimal("100"), new BigDecimal("250.5"));

        assertThat(limits.getPerTransactionLimit()).isEqualTo(new BigDecimal("100.00"));
        assertThat(limits.getDailyLimit()).isEqualTo(new BigDecimal("250.50"));
    }

    @Test
    void perTransactionLimitMayEqualDailyLimit() {
        assertThatCode(() -> CardSpendingLimits.createNew(new BigDecimal("250.00"), new BigDecimal("250.00")))
                .doesNotThrowAnyException();
    }

    @Test
    void limitsWithSameValuesAreEqual() {
        assertThat(CardSpendingLimits.createNew(new BigDecimal("100"), new BigDecimal("250")))
                .isEqualTo(CardSpendingLimits.createNew(new BigDecimal("100.00"), new BigDecimal("250.00")));
    }

    @ParameterizedTest
    @MethodSource("invalidLimits")
    void invalidLimitsAreRejected(BigDecimal perTransaction, BigDecimal daily, String expectedMessage) {
        assertThatThrownBy(() -> CardSpendingLimits.createNew(perTransaction, daily))
                .isInstanceOf(InvalidCardLimitException.class)
                .hasMessage(expectedMessage);
    }

    @Test
    void limitsAtMaximumAreAccepted() {
        CardSpendingLimits maximum = CardSpendingLimits.createNew(new BigDecimal("1000.00"), new BigDecimal("5000.00"));

        assertThatCode(() -> CardSpendingLimits.createNew(new BigDecimal("1000.00"), new BigDecimal("5000.00")).ensureWithin(maximum))
                .doesNotThrowAnyException();
    }

    @Test
    void perTransactionAboveMaximumIsRejected() {
        CardSpendingLimits maximum = CardSpendingLimits.createNew(new BigDecimal("1000.00"), new BigDecimal("5000.00"));

        assertThatThrownBy(() -> CardSpendingLimits.createNew(new BigDecimal("1000.01"), new BigDecimal("2000.00")).ensureWithin(maximum))
                .isInstanceOf(InvalidCardLimitException.class)
                .hasMessage("Per-transaction limit must not exceed 1000.00");
    }

    @Test
    void dailyAboveMaximumIsRejected() {
        CardSpendingLimits maximum = CardSpendingLimits.createNew(new BigDecimal("1000.00"), new BigDecimal("5000.00"));

        assertThatThrownBy(() -> CardSpendingLimits.createNew(new BigDecimal("100.00"), new BigDecimal("5000.01")).ensureWithin(maximum))
                .isInstanceOf(InvalidCardLimitException.class)
                .hasMessage("Daily limit must not exceed 5000.00");
    }

    @Test
    void ensureWithinRejectsNullMaximum() {
        CardSpendingLimits limits = CardSpendingLimits.createNew(new BigDecimal("100.00"), new BigDecimal("250.00"));

        assertThatThrownBy(() -> limits.ensureWithin(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> invalidLimits() {
        return Stream.of(
                Arguments.of(null, new BigDecimal("250.00"), "Per-transaction limit must not be null"),
                Arguments.of(new BigDecimal("100.00"), null, "Daily limit must not be null"),
                Arguments.of(BigDecimal.ZERO, new BigDecimal("250.00"), "Per-transaction limit must be greater than zero"),
                Arguments.of(new BigDecimal("100.00"), new BigDecimal("-1.00"), "Daily limit must be greater than zero"),
                Arguments.of(new BigDecimal("100.001"), new BigDecimal("250.00"), "Per-transaction limit must have at most two decimal places"),
                Arguments.of(new BigDecimal("100.00"), new BigDecimal("250.005"), "Daily limit must have at most two decimal places"),
                Arguments.of(new BigDecimal("250.01"), new BigDecimal("250.00"), "Per-transaction limit must not exceed the daily limit")
        );
    }
}
