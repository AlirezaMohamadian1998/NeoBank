package com.neobank.neobank.transaction;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.customer.CustomerRepository;
import com.neobank.neobank.idempotency.IdempotencyRecordRepository;
import com.neobank.neobank.internalaccount.InternalAccount;
import com.neobank.neobank.internalaccount.InternalAccountPurpose;
import com.neobank.neobank.internalaccount.InternalAccountRepository;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountRepository;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

public abstract class TransactionIntegrationTestSupport {

    protected static final String IDEMPOTENCY_KEY = "11111111111111111111111111111111";

    @Autowired
    protected BankTransactionRepository bankTransactionRepository;

    @Autowired
    protected LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    protected CustomerRepository customerRepository;

    @Autowired
    protected AccountRepository accountRepository;

    @Autowired
    protected LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    protected IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    protected InternalAccountRepository internalAccountRepository;

    protected Account account;
    protected Customer customer;
    protected InternalAccount internalAccountTRY;
    protected InternalAccount internalAccountUSD;

    @BeforeEach
    protected void setUpTransactionIntegrationFixture() {
        idempotencyRecordRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        bankTransactionRepository.deleteAll();
        accountRepository.deleteAll();
        internalAccountRepository.deleteAll();
        ledgerAccountRepository.deleteAll();
        customerRepository.deleteAll();

        customer = customerRepository.save(Customer.createNew(
                "customer@example.com",
                "{bcrypt}encoded-password",
                "Ada Lovelace"
                )
        );

        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "7f3c8a21d9e64b5fa2c17e9084bd6a31",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        account = accountRepository.save(Account.createNew(
                "12345678900987",
                "Private Account",
                AccountType.CURRENT,
                customer,
                ledgerAccount
                )
        );

        LedgerAccount internalLedgerAccountTRY = LedgerAccount.createNew(
                "9f3c8a21d9e64b5fa2c17e9084bd6a33",
                LedgerAccountType.ASSET,
                CurrencyCode.TRY
        );

        internalLedgerAccountTRY.debit(new BigDecimal("10000.00"));

        internalAccountTRY = internalAccountRepository.save(InternalAccount.createNew(
                InternalAccountPurpose.SETTLEMENT,
                internalLedgerAccountTRY
        ));

        LedgerAccount internalLedgerAccountUSD = LedgerAccount.createNew(
                "9c3c8a21d9e64b5fa2c17e9084bd6d33",
                LedgerAccountType.ASSET,
                CurrencyCode.USD
        );

        internalLedgerAccountUSD.debit(new BigDecimal("10000.00"));

        internalAccountUSD = internalAccountRepository.save(InternalAccount.createNew(
                InternalAccountPurpose.SETTLEMENT,
                internalLedgerAccountUSD
        ));
    }

    protected void creditAndSave(Account account, BigDecimal amount) {
        account.getLedgerAccount().credit(amount);
        ledgerAccountRepository.save(account.getLedgerAccount());
    }
}
