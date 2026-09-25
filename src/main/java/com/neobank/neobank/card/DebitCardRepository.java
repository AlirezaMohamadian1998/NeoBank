package com.neobank.neobank.card;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DebitCardRepository extends JpaRepository<DebitCard, Long> {
    Optional<DebitCard> findByCardReference(String cardReference);

    @EntityGraph(attributePaths = "fundingAccount")
    Optional<DebitCard> findByCardReferenceAndFundingAccount_Customer_EmailIgnoreCase(String cardReference, String email);

    @EntityGraph(attributePaths = "fundingAccount")
    Page<DebitCard> findAllByFundingAccount_Customer_EmailIgnoreCaseOrderByCreatedAtDescIdDesc(String email, Pageable pageable);
}
