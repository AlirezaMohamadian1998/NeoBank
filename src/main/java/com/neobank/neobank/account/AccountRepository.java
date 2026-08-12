package com.neobank.neobank.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccountRepository extends JpaRepository<Account, Long> {
    boolean existsByAccountNumber(String accountNumber);

    List<Account> findAllByCustomer_EmailIgnoreCaseOrderByCreatedAtDesc(String email);
}
