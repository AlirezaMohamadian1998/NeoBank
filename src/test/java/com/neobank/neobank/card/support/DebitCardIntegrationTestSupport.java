package com.neobank.neobank.card.support;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.card.CardDailySpendRepository;
import com.neobank.neobank.card.CardPurchaseDetailsRepository;
import com.neobank.neobank.card.CardRefundDetailsRepository;
import com.neobank.neobank.card.CardRepository;
import com.neobank.neobank.card.DebitCardRepository;
import com.neobank.neobank.creditaccount.CreditAccountRepository;
import com.neobank.neobank.creditaccount.CreditStatementRepository;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.customer.CustomerRepository;
import com.neobank.neobank.idempotency.IdempotencyRecordRepository;
import com.neobank.neobank.internalaccount.InternalAccountRepository;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountRepository;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.MySqlTestContainerConfiguration;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.BankTransactionRepository;
import com.neobank.neobank.transaction.LedgerEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Import(MySqlTestContainerConfiguration.class)
public abstract class DebitCardIntegrationTestSupport {

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected AccountRepository accountRepository;

    @Autowired
    protected CustomerRepository customerRepository;

    @Autowired
    protected DebitCardRepository debitCardRepository;

    @Autowired
    protected CardRepository cardRepository;

    @Autowired
    protected CreditAccountRepository creditAccountRepository;

    @Autowired
    protected CreditStatementRepository creditStatementRepository;

    @Autowired
    protected LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    protected IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    protected InternalAccountRepository internalAccountRepository;

    @Autowired
    protected CardPurchaseDetailsRepository cardPurchaseDetailsRepository;

    @Autowired
    protected CardRefundDetailsRepository cardRefundDetailsRepository;

    @Autowired
    protected CardDailySpendRepository cardDailySpendRepository;

    @Autowired
    protected LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    protected BankTransactionRepository bankTransactionRepository;

    @Autowired
    protected TransactionTemplate transactionTemplate;

    @Autowired
    protected Clock clock;

    protected Customer customer;
    protected Account account;

    @BeforeEach
    protected void setUpDebitCardIntegrationFixture() {
        idempotencyRecordRepository.deleteAll();
        creditStatementRepository.deleteAll();
        cardRefundDetailsRepository.deleteAll();
        cardPurchaseDetailsRepository.deleteAll();
        cardDailySpendRepository.deleteAll();
        cardRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        bankTransactionRepository.deleteAll();
        creditAccountRepository.deleteAll();
        accountRepository.deleteAll();
        internalAccountRepository.deleteAll();
        ledgerAccountRepository.deleteAll();
        customerRepository.deleteAll();

        customer = customerRepository.save(
                Customer.createNew(
                        "customer@example.com",
                        "{bcrypt}password-hash",
                        "Ada Lovelace"
                )
        );

        account = accountRepository.save(
                Account.createNew(
                        "12345678998745",
                        "test",
                        AccountType.CURRENT,
                        customer,
                        LedgerAccount.createNew(
                                "1".repeat(32),
                                LedgerAccountType.LIABILITY,
                                CurrencyCode.TRY
                        )
                )
        );
    }
}
