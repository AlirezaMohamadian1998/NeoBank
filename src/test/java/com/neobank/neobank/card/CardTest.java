package com.neobank.neobank.card;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.YearMonth;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardTest {

    @Test
    void validCreationPreservesFieldsAndStartsInactive() {
        String reference = "0123456789abcdef0123456789abcdef";
        String lastFourDigits = "1234";
        YearMonth expirationYearMonth = YearMonth.of(2029, 9);
        YearMonth currentYearMonth = YearMonth.of(2026, 9);

        Card card = new TestCard(
                reference,
                lastFourDigits,
                expirationYearMonth,
                currentYearMonth
        );

        assertThat(card.getCardReference())
                .isEqualTo(reference);

        assertThat(card.getLastFourDigits())
                .isEqualTo(lastFourDigits);

        assertThat(card.getExpirationYearMonth())
                .isEqualTo(expirationYearMonth);

        assertThat(card.getStatus())
                .isEqualTo(CardStatus.INACTIVE);
    }

    @ParameterizedTest
    @MethodSource(value = "invalidFieldsProvider")
    void invalidFieldsAreRejected(
            String reference,
            String lastFourDigits,
            YearMonth expirationYearMonth,
            YearMonth currentYearMonth,
            String exMessage
    ) {
        assertThatThrownBy(() ->
                new TestCard(
                        reference,
                        lastFourDigits,
                        expirationYearMonth,
                        currentYearMonth
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(exMessage);
    }

    @Test
    void expirationBoundaryIsHandledCorrectly() {
        String reference = "0123456789abcdef0123456789abcdef";
        String lastFourDigits = "1234";
        YearMonth expirationYearMonth = YearMonth.of(2026, 9);
        YearMonth currentYearMonth = YearMonth.of(2026, 9);

        Card card = new TestCard(
                reference,
                lastFourDigits,
                expirationYearMonth,
                currentYearMonth
        );

        assertThat(card.isExpired(currentYearMonth))
                .isFalse();

        assertThat(card.isExpired(currentYearMonth.plusMonths(1)))
                .isTrue();
    }

    @Test
    void validLifecycleTransitionsUpdateStatus() {
        YearMonth currentYearMonth = YearMonth.of(2026, 9);
        Card card = new TestCard(
                "0123456789abcdef0123456789abcdef",
                "1234",
                YearMonth.of(2029, 9),
                currentYearMonth
        );

        card.activateCard(currentYearMonth);

        assertThat(card.getStatus())
                .isEqualTo(CardStatus.ACTIVE);

        card.freezeCard(currentYearMonth);

        assertThat(card.getStatus())
                .isEqualTo(CardStatus.FROZEN);

        card.unfreezeCard(currentYearMonth);

        assertThat(card.getStatus())
                .isEqualTo(CardStatus.ACTIVE);

        card.blockCard(currentYearMonth);

        assertThat(card.getStatus())
                .isEqualTo(CardStatus.BLOCKED);

        card.closeCard();

        assertThat(card.getStatus())
                .isEqualTo(CardStatus.CLOSED);
    }

    @Test
    void invalidLifecycleTransitionsAreRejected() {
        YearMonth currentYearMonth = YearMonth.of(2026, 9);
        Card inactiveCard = createValidCard(currentYearMonth);

        assertThatThrownBy(() -> inactiveCard.freezeCard(currentYearMonth))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only active cards can be frozen");

        assertThatThrownBy(() -> inactiveCard.unfreezeCard(currentYearMonth))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only frozen cards can be unfrozen");

        inactiveCard.activateCard(currentYearMonth);

        assertThatThrownBy(() -> inactiveCard.activateCard(currentYearMonth))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only inactive cards can be activated");

        Card blockedCard = createValidCard(currentYearMonth);
        blockedCard.blockCard(currentYearMonth);

        assertThatThrownBy(() -> blockedCard.blockCard(currentYearMonth))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Card is already blocked");

        Card closedCard = createValidCard(currentYearMonth);
        closedCard.closeCard();

        assertThatThrownBy(() -> closedCard.closeCard())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Card is already closed");

        assertThatThrownBy(() -> closedCard.blockCard(currentYearMonth))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Closed cards cannot be blocked");
    }

    @Test
    void expiredCardRejectsTimeSensitiveTransitions() {
        YearMonth expirationYearMonth = YearMonth.of(2026, 9);
        YearMonth expiredYearMonth = expirationYearMonth.plusMonths(1);

        Card inactiveCard = createCard(expirationYearMonth, expirationYearMonth);

        assertThatThrownBy(() -> inactiveCard.activateCard(expiredYearMonth))
                .isInstanceOf(CardExpiredException.class)
                .hasMessage("Expired cards cannot be activated");

        Card activeCard = createCard(expirationYearMonth, expirationYearMonth);
        activeCard.activateCard(expirationYearMonth);

        assertThatThrownBy(() -> activeCard.freezeCard(expiredYearMonth))
                .isInstanceOf(CardExpiredException.class)
                .hasMessage("Expired cards cannot be frozen");

        Card frozenCard = createCard(expirationYearMonth, expirationYearMonth);
        frozenCard.activateCard(expirationYearMonth);
        frozenCard.freezeCard(expirationYearMonth);

        assertThatThrownBy(() -> frozenCard.unfreezeCard(expiredYearMonth))
                .isInstanceOf(CardExpiredException.class)
                .hasMessage("Expired cards cannot be unfrozen");

        Card blockableCard = createCard(expirationYearMonth, expirationYearMonth);

        assertThatThrownBy(() -> blockableCard.blockCard(expiredYearMonth))
                .isInstanceOf(CardExpiredException.class)
                .hasMessage("Expired cards cannot be blocked");
    }

    private static Card createValidCard(YearMonth currentYearMonth) {
        return createCard(currentYearMonth.plusYears(3), currentYearMonth);
    }

    private static Card createCard(
            YearMonth expirationYearMonth,
            YearMonth currentYearMonth
    ) {
        return new TestCard(
                "0123456789abcdef0123456789abcdef",
                "1234",
                expirationYearMonth,
                currentYearMonth
        );
    }

    private static final class TestCard extends Card {
        private TestCard(
                String cardReference,
                String lastFourDigits,
                YearMonth expirationYearMonth,
                YearMonth currentYearMonth
        ) {
            super(cardReference, lastFourDigits, expirationYearMonth, currentYearMonth);
        }
    }

    private static Stream<Arguments> invalidFieldsProvider() {
        return Stream.of(
                Arguments.of(
                        "0123456789abcdef0123456789abcdef".repeat(2),
                        "1234",
                        YearMonth.of(2029, 9),
                        YearMonth.of(2026, 9),
                        "Card reference must be exactly 32 hexadecimal characters"
                ),
                Arguments.of(
                        "1".repeat(31),
                        "1234",
                        YearMonth.of(2029, 9),
                        YearMonth.of(2026, 9),
                        "Card reference must be exactly 32 hexadecimal characters"
                ),
                Arguments.of(
                        "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz",
                        "1234",
                        YearMonth.of(2029, 9),
                        YearMonth.of(2026, 9),
                        "Card reference must be exactly 32 hexadecimal characters"
                ),
                Arguments.of(
                        "0123456789abcdef0123456789abcdef",
                        "abcd",
                        YearMonth.of(2029, 9),
                        YearMonth.of(2026, 9),
                        "Last 4 digits must be numerical"
                ),
                Arguments.of(
                        "0123456789abcdef0123456789abcdef",
                        "12345678",
                        YearMonth.of(2029, 9),
                        YearMonth.of(2026, 9),
                        "Last 4 digits must be numerical"
                ),
                Arguments.of(
                        "0123456789abcdef0123456789abcdef",
                        "12",
                        YearMonth.of(2029, 9),
                        YearMonth.of(2026, 9),
                        "Last 4 digits must be numerical"
                ),
                Arguments.of(
                        "0123456789abcdef0123456789abcdef",
                        "1234",
                        YearMonth.of(2025, 9),
                        YearMonth.of(2026, 9),
                        "Expiration should be at the end of this month or in future"
                ),
                Arguments.of(
                        null,
                        "1234",
                        YearMonth.of(2029, 9),
                        YearMonth.of(2026, 9),
                        "Card reference must not be null"
                ),
                Arguments.of(
                        "0123456789abcdef0123456789abcdef",
                        null,
                        YearMonth.of(2029, 9),
                        YearMonth.of(2026, 9),
                        "Last 4 digits of the card number cannot be null"
                ),
                Arguments.of(
                        "0123456789abcdef0123456789abcdef",
                        "1234",
                        null,
                        YearMonth.of(2026, 9),
                        "Expiration cannot be null"
                ),
                Arguments.of(
                        "0123456789abcdef0123456789abcdef",
                        "1234",
                        YearMonth.of(2029, 9),
                        null,
                        "Current month cannot be null"
                )
        );
    }
}
