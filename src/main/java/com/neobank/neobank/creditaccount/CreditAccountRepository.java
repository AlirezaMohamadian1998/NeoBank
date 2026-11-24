package com.neobank.neobank.creditaccount;

import com.neobank.neobank.ledger.LedgerAccount;
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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select c from CreditAccount c
            where upper(c.customer.email) = upper(:customerEmail)
              and c.id = (select card.creditAccount.id from CreditCard card where card.cardReference = :cardReference)
            """)
    Optional<CreditAccount> findAndLockByCardReferenceAndCustomerEmail(
            @Param("cardReference") String cardReference,
            @Param("customerEmail") String customerEmail
    );

    Optional<CreditAccount> findByLedgerAccount(LedgerAccount ledgerAccount);

    @Query("""
            select c.id from CreditAccount c
            where c.nextStatementDate <= :today
              and c.ledgerAccount.status = com.neobank.neobank.ledger.LedgerAccountStatus.ACTIVE
            """)
    List<Long> findIdsWithStatementDueBy(@Param("today") LocalDate today);
}
