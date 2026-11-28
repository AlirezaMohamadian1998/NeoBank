package com.neobank.neobank.card.limit;

import com.neobank.neobank.card.Card;
import com.neobank.neobank.card.support.TestCardLimits;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CardDailySpendServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);

    @Mock
    private CardDailySpendRepository cardDailySpendRepository;

    private CardDailySpendService cardDailySpendService;

    private Card card;

    @BeforeEach
    void setUp() {
        cardDailySpendService = new CardDailySpendService(cardDailySpendRepository);

        card = mock(Card.class);
        lenient().when(card.getSpendingLimits())
                .thenReturn(CardSpendingLimits.createNew(new BigDecimal("100.00"), new BigDecimal("250.00")));
    }

    @Test
    void otherIntegrityViolationsAreRethrown() {
        DataIntegrityViolationException violation = violationOf("chk_card_daily_spend_amount_non_negative");

        given(cardDailySpendRepository.findByCardAndSpendDate(card, TODAY))
                .willReturn(Optional.empty());
        given(cardDailySpendRepository.saveAndFlush(any(CardDailySpend.class)))
                .willThrow(violation);

        assertThatThrownBy(() -> cardDailySpendService.recordSpend(card, TODAY, new BigDecimal("40.00")))
                .isSameAs(violation);
    }

    @Test
    void getAmountSpentPerCardMapsEveryCardAndFillsZeroForCardsWithoutSpend() {
        Card otherCard = mock(Card.class);
        given(card.getId()).willReturn(1L);
        given(otherCard.getId()).willReturn(2L);

        CardDailySpend spend = CardDailySpend.createNew(card, TODAY);
        spend.add(new BigDecimal("75.00"), TestCardLimits.generous());

        given(cardDailySpendRepository.findAllByCardInAndSpendDate(List.of(card, otherCard), TODAY))
                .willReturn(List.of(spend));

        assertThat(cardDailySpendService.getAmountSpentPerCard(List.of(card, otherCard), TODAY))
                .containsOnly(
                        Map.entry(1L, new BigDecimal("75.00")),
                        Map.entry(2L, new BigDecimal("0.00"))
                );
    }

    @Test
    void getAmountSpentPerCardForNoCardsSkipsQuery() {
        assertThat(cardDailySpendService.getAmountSpentPerCard(List.of(), TODAY)).isEmpty();

        verify(cardDailySpendRepository, never()).findAllByCardInAndSpendDate(any(), any());
    }

    private static DataIntegrityViolationException violationOf(String constraintName) {
        return new DataIntegrityViolationException(
                "constraint violated",
                new ConstraintViolationException("constraint violated", new SQLException("duplicate"), constraintName)
        );
    }
}
