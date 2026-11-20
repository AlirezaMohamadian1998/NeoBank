package com.neobank.neobank.card;

import com.neobank.neobank.card.exception.CardDailySpendRaceException;
import com.neobank.neobank.shared.persistence.ConstraintViolations;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class CardDailySpendService {

    private static final String CARD_DATE_CONSTRAINT = "uk_card_daily_spend_card_date";

    private final CardDailySpendRepository cardDailySpendRepository;

    public CardDailySpend recordSpend(Card card, LocalDate spendDate, BigDecimal amount) {
        CardDailySpend dailySpend = cardDailySpendRepository.findByCardAndSpendDate(card, spendDate)
                .orElseGet(() -> CardDailySpend.createNew(card, spendDate));

        dailySpend.add(amount, card.getSpendingLimits());

        try {
            return cardDailySpendRepository.saveAndFlush(dailySpend);
        } catch (DataIntegrityViolationException e) {
            if(ConstraintViolations.isViolationOf(e, CARD_DATE_CONSTRAINT)) {
                throw new CardDailySpendRaceException(e);
            }

            throw e;
        }
    }
}
