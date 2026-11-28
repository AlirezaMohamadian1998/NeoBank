package com.neobank.neobank.card.support;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.card.CardRepository;
import com.neobank.neobank.card.credit.CreditCardRepository;
import com.neobank.neobank.card.credit.dto.CreditCardRepaymentRequest;
import com.neobank.neobank.card.debit.DebitCardRepository;
import com.neobank.neobank.card.limit.CardDailySpendRepository;
import com.neobank.neobank.card.payment.CardPurchaseDetailsRepository;
import com.neobank.neobank.card.payment.CardRefundDetailsRepository;
import com.neobank.neobank.card.payment.dto.CardPurchaseRequest;
import com.neobank.neobank.card.payment.dto.CardPurchaseResponse;
import com.neobank.neobank.creditaccount.CreditAccountRepository;
import com.neobank.neobank.creditaccount.CreditStatementRepository;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.customer.CustomerRepository;
import com.neobank.neobank.idempotency.IdempotencyRecordRepository;
import com.neobank.neobank.internalaccount.InternalAccount;
import com.neobank.neobank.internalaccount.InternalAccountPurpose;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Import(MySqlTestContainerConfiguration.class)
public abstract class CardIntegrationTestSupport {

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
    protected CreditCardRepository creditCardRepository;

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
    protected void setUpCardIntegrationFixture() {
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

    protected ResultActions purchase(
            String cardReference,
            String merchantAccountNumber,
            String amount,
            CurrencyCode currency,
            String idempotencyKey
    ) throws Exception {
        CardPurchaseRequest request = new CardPurchaseRequest(
                merchantAccountNumber,
                currency,
                new BigDecimal(amount),
                null,
                "Test merchant"
        );

        return mockMvc.perform(post("/api/cards/{cardReference}/payments", cardReference)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header("Idempotency-Key", idempotencyKey)
                .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))));
    }

    protected String transactionReferenceOf(ResultActions purchase) throws Exception {
        String body = purchase
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readValue(body, CardPurchaseResponse.class).transactionReference();
    }

    protected ResultActions repay(String cardReference, String amount, String lockId, String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/credit-cards/{cardReference}/repayments", cardReference)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new CreditCardRepaymentRequest(account.getAccountNumber(), new BigDecimal(amount), lockId)
                ))
                .header("Idempotency-Key", idempotencyKey)
                .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))));
    }

    protected void giveCreditBalance(String cardReference, String amount) {
        transactionTemplate.executeWithoutResult(status ->
                creditCardRepository.findByCardReference(cardReference).orElseThrow()
                        .getCreditAccount().getLedgerAccount().credit(new BigDecimal(amount))
        );
    }

    protected InternalAccount createSettlementAccount(String ledgerReference, CurrencyCode currency) {
        LedgerAccount ledgerAccount = LedgerAccount.createNew(ledgerReference, LedgerAccountType.ASSET, currency);
        ledgerAccount.debit(new BigDecimal("10000.00"));

        return InternalAccount.createNew(InternalAccountPurpose.SETTLEMENT, ledgerAccount);
    }
}
