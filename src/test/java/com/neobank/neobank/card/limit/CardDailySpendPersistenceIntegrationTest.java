package com.neobank.neobank.card.limit;

import com.neobank.neobank.card.debit.DebitCard;
import com.neobank.neobank.card.support.CardIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardDailySpendPersistenceIntegrationTest extends CardIntegrationTestSupport {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);

    private DebitCard debitCard;

    @BeforeEach
    void setUpCard() {
        debitCard = debitCardRepository.save(DebitCard.createNew(
                "2".repeat(32),
                "1234",
                YearMonth.now(clock).plusYears(5),
                YearMonth.now(clock),
                account,
                CardSpendingLimits.createNew(new BigDecimal("100.00"), new BigDecimal("250.00"))
        ));
    }

    @Test
    void dailySpendIsSavedAndFoundByCardAndDate() {
        CardDailySpend spend = CardDailySpend.createNew(debitCard, TODAY);
        spend.add(new BigDecimal("40.00"), debitCard.getSpendingLimits());
        cardDailySpendRepository.save(spend);

        CardDailySpend found = transactionTemplate.execute(status ->
                cardDailySpendRepository.findByCardAndSpendDate(debitCard, TODAY).orElseThrow()
        );

        assertThat(found.getAmountSpent()).isEqualTo(new BigDecimal("40.00"));
        assertThat(found.getVersion()).isZero();
        assertThat(cardDailySpendRepository.findByCardAndSpendDate(debitCard, TODAY.minusDays(1))).isEmpty();
    }

    @Test
    void secondRowForSameCardAndDateIsRejected() {
        cardDailySpendRepository.save(CardDailySpend.createNew(debitCard, TODAY));

        assertThatThrownBy(() -> cardDailySpendRepository.save(CardDailySpend.createNew(debitCard, TODAY)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void staleUpdateIsRejectedByVersion() {
        cardDailySpendRepository.save(CardDailySpend.createNew(debitCard, TODAY));

        CardDailySpend first = cardDailySpendRepository.findByCardAndSpendDate(debitCard, TODAY).orElseThrow();
        CardDailySpend stale = cardDailySpendRepository.findByCardAndSpendDate(debitCard, TODAY).orElseThrow();

        first.add(new BigDecimal("10.00"), debitCard.getSpendingLimits());
        cardDailySpendRepository.save(first);

        stale.add(new BigDecimal("20.00"), debitCard.getSpendingLimits());

        assertThatThrownBy(() -> cardDailySpendRepository.save(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(cardDailySpendRepository.findByCardAndSpendDate(debitCard, TODAY).orElseThrow().getAmountSpent())
                .isEqualTo(new BigDecimal("10.00"));
    }
}
