package com.neobank.neobank.creditaccount;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CreditStatementRepository extends JpaRepository<CreditStatement, Long> {

    List<CreditStatement> findTop2ByCreditAccountOrderByStatementDateDesc(CreditAccount creditAccount);

    Page<CreditStatement> findByCreditAccountOrderByStatementDateDesc(CreditAccount creditAccount, Pageable pageable);

    Optional<CreditStatement> findByStatementReferenceAndCreditAccount(String statementReference, CreditAccount creditAccount);
}
