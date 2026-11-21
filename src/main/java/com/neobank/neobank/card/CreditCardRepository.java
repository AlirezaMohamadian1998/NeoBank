package com.neobank.neobank.card;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CreditCardRepository extends JpaRepository<CreditCard, Long> {
    @EntityGraph(attributePaths = {"creditAccount", "creditAccount.ledgerAccount"})
    Optional<CreditCard> findByCardReference(String cardReference);

    @EntityGraph(attributePaths = {"creditAccount", "creditAccount.ledgerAccount", "creditAccount.customer"})
    Optional<CreditCard> findByCardReferenceAndCreditAccount_Customer_EmailIgnoreCase(String cardReference, String email);

    @EntityGraph(attributePaths = {"creditAccount", "creditAccount.ledgerAccount"})
    Page<CreditCard> findAllByCreditAccount_Customer_EmailIgnoreCaseOrderByCreatedAtDescIdDesc(String email, Pageable pageable);
}
