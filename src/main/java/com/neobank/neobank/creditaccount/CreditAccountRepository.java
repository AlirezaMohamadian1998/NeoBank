package com.neobank.neobank.creditaccount;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CreditAccountRepository extends JpaRepository<CreditAccount, Long> {
    boolean existsByAccountNumber(String accountNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CreditAccount> findAndLockById(Long id);
}
