package com.neobank.neobank.card;

import com.neobank.neobank.card.exception.CardDailySpendRaceException;
import com.neobank.neobank.card.exception.CardLimitExceededException;
import com.neobank.neobank.card.support.TestCardLimits;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    void firstPurchaseOfTheDayCreatesRow() {
        ArgumentCaptor<CardDailySpend> captor = ArgumentCaptor.forClass(CardDailySpend.class);

        given(cardDailySpendRepository.findByCardAndSpendDate(card, TODAY))
                .willReturn(Optional.empty());
        given(cardDailySpendRepository.saveAndFlush(any(CardDailySpend.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        cardDailySpendService.recordSpend(card, TODAY, new BigDecimal("40.00"));

        verify(cardDailySpendRepository).saveAndFlush(captor.capture());

        assertThat(captor.getValue().getCard()).isSameAs(card);
        assertThat(captor.getValue().getSpendDate()).isEqualTo(TODAY);
        assertThat(captor.getValue().getAmountSpent()).isEqualTo(new BigDecimal("40.00"));
    }

    @Test
    void laterPurchaseAddsToExistingRow() {
        CardDailySpend existing = CardDailySpend.createNew(card, TODAY);
        existing.add(new BigDecimal("60.00"), TestCardLimits.generous());

        given(cardDailySpendRepository.findByCardAndSpendDate(card, TODAY))
                .willReturn(Optional.of(existing));
        given(cardDailySpendRepository.saveAndFlush(existing))
                .willReturn(existing);

        CardDailySpend result = cardDailySpendService.recordSpend(card, TODAY, new BigDecimal("40.00"));

        assertThat(result.getAmountSpent()).isEqualTo(new BigDecimal("100.00"));
    }

    @Test
    void limitBreachWritesNothing() {
        CardDailySpend existing = CardDailySpend.createNew(card, TODAY);
        existing.add(new BigDecimal("200.00"), TestCardLimits.generous());

        given(cardDailySpendRepository.findByCardAndSpendDate(card, TODAY))
                .willReturn(Optional.of(existing));

        assertThatThrownBy(() -> cardDailySpendService.recordSpend(card, TODAY, new BigDecimal("50.01")))
                .isInstanceOf(CardLimitExceededException.class);

        verify(cardDailySpendRepository, never()).saveAndFlush(any());
    }

    @Test
    void duplicateFirstInsertBecomesRetryableRaceException() {
        given(cardDailySpendRepository.findByCardAndSpendDate(card, TODAY))
                .willReturn(Optional.empty());
        given(cardDailySpendRepository.saveAndFlush(any(CardDailySpend.class)))
                .willThrow(violationOf("neobank.card_daily_spend.uk_card_daily_spend_card_date"));

        assertThatThrownBy(() -> cardDailySpendService.recordSpend(card, TODAY, new BigDecimal("40.00")))
                .isInstanceOf(CardDailySpendRaceException.class);
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
    void getAmountSpentReturnsAmountOfExistingRow() {
        CardDailySpend existing = CardDailySpend.createNew(card, TODAY);
        existing.add(new BigDecimal("60.00"), TestCardLimits.generous());

        given(cardDailySpendRepository.findByCardAndSpendDate(card, TODAY))
                .willReturn(Optional.of(existing));

        assertThat(cardDailySpendService.getAmountSpent(card, TODAY)).isEqualTo(new BigDecimal("60.00"));
    }

    @Test
    void getAmountSpentReturnsZeroWhenNothingWasSpent() {
        given(cardDailySpendRepository.findByCardAndSpendDate(card, TODAY))
                .willReturn(Optional.empty());

        assertThat(cardDailySpendService.getAmountSpent(card, TODAY)).isEqualTo(new BigDecimal("0.00"));
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
