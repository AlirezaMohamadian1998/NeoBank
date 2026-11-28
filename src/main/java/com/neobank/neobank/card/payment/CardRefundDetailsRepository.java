package com.neobank.neobank.card.payment;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CardRefundDetailsRepository extends JpaRepository<CardRefundDetails, Long> {

    @EntityGraph(attributePaths = {
            "bankTransaction",
            "bankTransaction.entries",
            "cardPurchaseDetails",
            "cardPurchaseDetails.bankTransaction"
    })
    Optional<CardRefundDetails> findByBankTransaction_Reference(String bankTransactionReference);

    @EntityGraph(attributePaths = {"cardPurchaseDetails", "cardPurchaseDetails.bankTransaction"})
    List<CardRefundDetails> findAllByBankTransaction_IdIn(Collection<Long> bankTransactionIds);
}
