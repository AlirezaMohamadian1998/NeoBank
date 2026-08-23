package com.neobank.neobank.internalaccount;

import com.neobank.neobank.shared.money.CurrencyCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InternalAccountRepository extends JpaRepository<InternalAccount, Long> {
    Optional<InternalAccount> findByPurposeAndCurrency(InternalAccountPurpose purpose, CurrencyCode currency);
}
