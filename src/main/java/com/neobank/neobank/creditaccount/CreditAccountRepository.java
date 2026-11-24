package com.neobank.neobank.creditaccount;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface CreditAccountRepository extends JpaRepository<CreditAccount, Long> {
    boolean existsByAccountNumber(String accountNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CreditAccount> findAndLockById(Long id);

    @Query("""
            select c.id from CreditAccount c
            where c.nextStatementDate <= :today
              and c.ledgerAccount.status = com.neobank.neobank.ledger.LedgerAccountStatus.ACTIVE
            """)
    List<Long> findIdsWithStatementDueBy(@Param("today") LocalDate today);
}
