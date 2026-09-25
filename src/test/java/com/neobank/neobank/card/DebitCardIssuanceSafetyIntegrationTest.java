package com.neobank.neobank.card;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.card.dto.DebitCardIssueResponse;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.customer.CustomerRepository;
import com.neobank.neobank.idempotency.IdempotencyRecord;
import com.neobank.neobank.idempotency.IdempotencyRecordRepository;
import com.neobank.neobank.idempotency.IdempotencyService;
import com.neobank.neobank.internalaccount.InternalAccountRepository;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountRepository;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.MySqlTestContainerConfiguration;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.SimulatedFailureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@ActiveProfiles("integration")
@Import(MySqlTestContainerConfiguration.class)
class DebitCardIssuanceSafetyIntegrationTest {

    private static final long CONCURRENCY_TIMEOUT_SECONDS = 20;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private DebitCardRepository debitCardRepository;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private InternalAccountRepository internalAccountRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DebitCardService debitCardService;

    @MockitoSpyBean
    private IdempotencyService idempotencyService;

    private Customer customer;
    private Account account;

    @BeforeEach
    void setup() {
        idempotencyRecordRepository.deleteAll();
        debitCardRepository.deleteAll();
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

    @Test
    void concurrentIssuanceRequestsWithSameKeyCreateExactlyOneDebitCard() throws ExecutionException, InterruptedException, TimeoutException {
        String idempotencyKey = "11111111111111111111111111111111";
        CountDownLatch bothInitialIdempotencyLookupsFinished = new CountDownLatch(2);
        AtomicInteger attemptCount = new AtomicInteger();

        IdempotencyService idempotencyServiceSpy = AopTestUtils.getUltimateTargetObject(idempotencyService);

        doAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Optional<IdempotencyRecord> result = (Optional<IdempotencyRecord>) invocation.callRealMethod();

                    if(bothInitialIdempotencyLookupsFinished.getCount() > 0) {
                        assertThat(result)
                                .as("Both issuance request find no idempotency record initially")
                                .isEmpty();

                        bothInitialIdempotencyLookupsFinished.countDown();

                        boolean bothIssuanceArrived = bothInitialIdempotencyLookupsFinished.await(
                                CONCURRENCY_TIMEOUT_SECONDS,
                                TimeUnit.SECONDS
                        );

                        assertThat(bothIssuanceArrived)
                                .as("Both issuance request must reach the same point before commencing")
                                .isTrue();
                    }

                    attemptCount.getAndIncrement();
                    return result;
                }
        ).when(idempotencyServiceSpy)
                .findAndValidateRecord(
                        eq(idempotencyKey),
                        eq(customer.getEmail()),
                        anyString()
                );

        ExecutorService executor = Executors.newFixedThreadPool(2);

        DebitCardIssueResponse firstResponse;
        DebitCardIssueResponse secondResponse;

        try {
            Future<DebitCardIssueResponse> firstIssuance = executor.submit(() ->
                    debitCardService.issueDebitCard(
                            customer.getEmail(),
                            account.getAccountNumber(),
                            idempotencyKey
                    )
            );

            Future<DebitCardIssueResponse> secondIssuance = executor.submit(() ->
                    debitCardService.issueDebitCard(
                            customer.getEmail(),
                            account.getAccountNumber(),
                            idempotencyKey
                    )
            );

            firstResponse = firstIssuance.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            secondResponse = secondIssuance.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(attemptCount.get())
                .isEqualTo(3);

        assertThat(firstResponse)
                .isEqualTo(secondResponse);

        assertThat(idempotencyRecordRepository.count())
                .isOne();

        assertThat(debitCardRepository.count())
                .isOne();

        transactionTemplate.executeWithoutResult(status -> {
            DebitCard debitCard = debitCardRepository.findByCardReference(firstResponse.cardReference())
                    .orElseThrow();

            IdempotencyRecord record =
                    idempotencyRecordRepository.findByIdempotencyKeyAndCustomer_EmailIgnoreCase(idempotencyKey, customer.getEmail())
                            .orElseThrow();

            assertThat(debitCard.getFundingAccount().getAccountNumber())
                    .isEqualTo(account.getAccountNumber());

            assertThat(debitCard.getCardReference())
                    .isEqualTo(record.getResultReference());
        });
    }

    @Test
    void failedIssuanceRollsBackDebitCardAndIdempotencyRecord() {
        String idempotencyKey = "11111111111111111111111111111111";

        IdempotencyService idempotencyServiceSpy = AopTestUtils.getUltimateTargetObject(idempotencyService);

        doAnswer(invocation -> {
                    invocation.callRealMethod();
                    throw new SimulatedFailureException("Simulated failure after saving idempotency to check rollback");
                }
        ).when(idempotencyServiceSpy)
                .save(any(IdempotencyRecord.class));

        assertThatThrownBy(() -> debitCardService.issueDebitCard(customer.getEmail(), account.getAccountNumber(), idempotencyKey))
                .isInstanceOf(SimulatedFailureException.class)
                .hasMessage("Simulated failure after saving idempotency to check rollback");

        assertThat(idempotencyRecordRepository.count())
                .isZero();

        assertThat(debitCardRepository.count())
                .isZero();
    }
}
