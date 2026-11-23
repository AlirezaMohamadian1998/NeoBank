package com.neobank.neobank.transaction;

import com.neobank.neobank.ledger.LedgerAccount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long>, JpaSpecificationExecutor<LedgerEntry> {

    @EntityGraph(attributePaths = "bankTransaction")
    Page<LedgerEntry> findAll(Specification<LedgerEntry> spec, Pageable pageable);

    @Query("""
            select e from LedgerEntry e
            where e.ledgerAccount = :ledgerAccount
              and e.createdAt >= :from
              and e.createdAt < :to
              and e.bankTransaction.transactionType <> com.neobank.neobank.transaction.TransactionType.CREDIT_CARD_INTEREST
            order by e.createdAt, e.id
            """)
    List<LedgerEntry> findNonInterestEntries(
            @Param("ledgerAccount") LedgerAccount ledgerAccount,
            @Param("from") Instant from,
            @Param("to") Instant to
    );

    @Query("""
            select coalesce(sum(e.amount), 0) from LedgerEntry e
            where e.ledgerAccount = :ledgerAccount
              and e.direction = com.neobank.neobank.transaction.EntryDirection.CREDIT
              and e.createdAt >= :from
              and e.createdAt < :to
            """)
    BigDecimal sumRepaymentsAndRefunds(
            @Param("ledgerAccount") LedgerAccount ledgerAccount,
            @Param("from") Instant from,
            @Param("to") Instant to
    );
}
