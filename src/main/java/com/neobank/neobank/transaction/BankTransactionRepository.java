package com.neobank.neobank.transaction;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BankTransactionRepository extends JpaRepository<BankTransaction, Long> {
    @EntityGraph(attributePaths = {"entries", "entries.ledgerAccount"})
    Optional<BankTransaction> findByReference(String reference);

    /**
     * Loads a transaction with {@code SELECT ... FOR UPDATE}, so concurrent callers queue on this row
     * until the holder commits and then read its committed state.
     * Nothing is fetched by join on purpose: MySQL would otherwise lock the joined rows as well.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select t from BankTransaction t
            where t.reference = :reference
              and t.transactionType = :transactionType
            """)
    Optional<BankTransaction> findForUpdateByReferenceAndTransactionType(
            @Param("reference") String reference,
            @Param("transactionType") TransactionType transactionType
    );
}
