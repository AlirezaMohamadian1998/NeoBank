package com.neobank.neobank.card;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CardRefundDetailsRepository extends JpaRepository<CardRefundDetails, Long> {

    @EntityGraph(attributePaths = {
            "bankTransaction",
            "bankTransaction.entries",
            "cardPurchaseDetails",
            "cardPurchaseDetails.bankTransaction"
    })
    Optional<CardRefundDetails> findByBankTransaction_Reference(String bankTransactionReference);
}
