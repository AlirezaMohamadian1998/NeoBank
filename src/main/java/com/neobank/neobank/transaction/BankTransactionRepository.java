package com.neobank.neobank.transaction;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BankTransactionRepository extends JpaRepository<BankTransaction, Long> {
    @EntityGraph(attributePaths = {"entries", "entries.ledgerAccount"})
    Optional<BankTransaction> findByReference(String reference);
}
