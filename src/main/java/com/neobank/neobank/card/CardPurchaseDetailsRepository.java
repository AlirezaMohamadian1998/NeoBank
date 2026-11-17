package com.neobank.neobank.card;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CardPurchaseDetailsRepository extends JpaRepository<CardPurchaseDetails, Long> {

    @EntityGraph(attributePaths = {"bankTransaction", "bankTransaction.entries"})
    Optional<CardPurchaseDetails> findByBankTransaction_Reference(String bankTransactionReference);

    List<CardPurchaseDetails> findAllByBankTransaction_IdIn(Collection<Long> bankTransactionIds);
}
