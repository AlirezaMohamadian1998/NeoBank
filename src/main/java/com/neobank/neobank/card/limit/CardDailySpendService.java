package com.neobank.neobank.card.limit;

import com.neobank.neobank.card.Card;
import com.neobank.neobank.shared.persistence.ConstraintViolations;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class CardDailySpendService {

    private static final String CARD_DATE_CONSTRAINT = "uk_card_daily_spend_card_date";

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);

    private final CardDailySpendRepository cardDailySpendRepository;

    public BigDecimal getAmountSpent(Card card, LocalDate spendDate) {
        return cardDailySpendRepository.findByCardAndSpendDate(card, spendDate)
                .map(CardDailySpend::getAmountSpent)
                .orElse(ZERO);
    }

    public Map<Long, BigDecimal> getAmountSpentPerCard(Collection<? extends Card> cards, LocalDate spendDate) {
        if(cards.isEmpty()) {
            return Map.of();
        }

        Map<Long, BigDecimal> recordedAmountsByCardId = cardDailySpendRepository.findAllByCardInAndSpendDate(cards, spendDate).stream()
                .collect(Collectors.toMap(
                        dailySpend -> dailySpend.getCard().getId(),
                        CardDailySpend::getAmountSpent
                ));

        return cards.stream()
                .collect(Collectors.toMap(
                        Card::getId,
                        card -> recordedAmountsByCardId.getOrDefault(card.getId(), ZERO)
                ));
    }

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
