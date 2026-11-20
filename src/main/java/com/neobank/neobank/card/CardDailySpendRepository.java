package com.neobank.neobank.card;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface CardDailySpendRepository extends JpaRepository<CardDailySpend, Long> {

    Optional<CardDailySpend> findByCardAndSpendDate(Card card, LocalDate spendDate);
}
