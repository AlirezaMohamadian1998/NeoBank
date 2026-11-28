package com.neobank.neobank.card.limit;

import com.neobank.neobank.card.Card;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CardDailySpendRepository extends JpaRepository<CardDailySpend, Long> {

    Optional<CardDailySpend> findByCardAndSpendDate(Card card, LocalDate spendDate);

    List<CardDailySpend> findAllByCardInAndSpendDate(Collection<? extends Card> cards, LocalDate spendDate);
}
