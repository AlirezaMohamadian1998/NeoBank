package com.neobank.neobank.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {
    boolean existsByAccountNumber(String accountNumber);

    List<Account> findAllByCustomer_EmailIgnoreCaseOrderByCreatedAtDesc(String email);

    Optional<Account> findByAccountNumberAndCustomer_EmailIgnoreCase(String accountNumber, String email);

    Optional<Account> findByAccountNumber(String accountNumber);
}
