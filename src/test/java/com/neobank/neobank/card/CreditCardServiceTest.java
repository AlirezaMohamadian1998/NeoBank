package com.neobank.neobank.card;

import com.neobank.neobank.account.AccountNumberGenerator;
import com.neobank.neobank.card.dto.CardSpendingLimitsUpdateRequest;
import com.neobank.neobank.card.dto.CreditCardIssueRequest;
import com.neobank.neobank.card.dto.CreditCardIssueResponse;
import com.neobank.neobank.card.dto.CreditCardRetrieveResponse;
import com.neobank.neobank.card.exception.CreditCardNotFoundException;
import com.neobank.neobank.card.exception.InvalidCardLimitException;
import com.neobank.neobank.card.issuing.CardIssuer;
import com.neobank.neobank.card.issuing.IssuedCard;
import com.neobank.neobank.card.limit.TestCardLimitProperties;
import com.neobank.neobank.card.support.TestCardLimits;
import com.neobank.neobank.creditaccount.CreditAccount;
import com.neobank.neobank.creditaccount.CreditAccountProperties;
import com.neobank.neobank.creditaccount.CreditAccountRepository;
import com.neobank.neobank.creditaccount.CreditStatement;
import com.neobank.neobank.creditaccount.CreditStatementNotFoundException;
import com.neobank.neobank.creditaccount.CreditStatementRepository;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.customer.CustomerNotFoundException;
import com.neobank.neobank.customer.CustomerRepository;
import com.neobank.neobank.idempotency.IdempotencyRecord;
import com.neobank.neobank.idempotency.IdempotencyService;
import com.neobank.neobank.idempotency.InvalidIdempotencyKeyException;
import com.neobank.neobank.idempotency.RequestHasher;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.LedgerEntry;
import com.neobank.neobank.transaction.LedgerEntryRepository;
import com.neobank.neobank.transaction.TransactionType;
import com.neobank.neobank.transaction.history.TransactionHistoryItemAssembler;
import com.neobank.neobank.transaction.history.dto.TransactionHistoryItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreditCardServiceTest {

    private static final String EMAIL = "customer@example.com";

    private static final String IDEMPOTENCY_KEY = "1".repeat(32);

    @Mock
    private CreditCardRepository creditCardRepository;

    @Mock
    private CreditAccountRepository creditAccountRepository;

    @Mock
    private CreditStatementRepository creditStatementRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private TransactionHistoryItemAssembler transactionHistoryItemAssembler;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private ReferenceGenerator referenceGenerator;

    @Mock
    private AccountNumberGenerator accountNumberGenerator;

    @Mock
    private CardIssuer cardIssuer;

    @Mock
    private CardDailySpendService cardDailySpendService;

    private final RequestHasher requestHasher = new RequestHasher();

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), Clock.systemUTC().getZone());

    private final CreditAccountProperties creditAccountProperties = new CreditAccountProperties(
            Map.of(
                    CurrencyCode.USD, new BigDecimal("5000.00"),
                    CurrencyCode.EUR, new BigDecimal("5000.00"),
                    CurrencyCode.GBP, new BigDecimal("5000.00"),
                    CurrencyCode.TRY, new BigDecimal("150000.00")
            ),
            Map.of(
                    CurrencyCode.USD, new BigDecimal("0.2499"),
                    CurrencyCode.EUR, new BigDecimal("0.2499"),
                    CurrencyCode.GBP, new BigDecimal("0.2499"),
                    CurrencyCode.TRY, new BigDecimal("0.5100")
            ),
            Map.of(
                    CurrencyCode.USD, new BigDecimal("25.00"),
                    CurrencyCode.EUR, new BigDecimal("25.00"),
                    CurrencyCode.GBP, new BigDecimal("25.00"),
                    CurrencyCode.TRY, new BigDecimal("750.00")
            ),
            new BigDecimal("0.03"),
            25
    );

    private final Customer customer = Customer.createNew(EMAIL, "{bcrypt}encoded-password", "Ada Lovelace");

    private CreditCardService creditCardService;

    @BeforeEach
    void setUp() {
        creditCardService = new CreditCardService(
                creditCardRepository,
                creditAccountRepository,
                creditStatementRepository,
                ledgerEntryRepository,
                transactionHistoryItemAssembler,
                customerRepository,
                idempotencyService,
                requestHasher,
                referenceGenerator,
                accountNumberGenerator,
                cardIssuer,
                TestCardLimitProperties.standard(),
                creditAccountProperties,
                cardDailySpendService,
                clock
        );
    }

    @Test
    void issueCreditCardCreatesCreditAccountAndCardWithDefaults() {
        YearMonth now = YearMonth.now(clock);
        ArgumentCaptor<CreditCard> creditCardCaptor = ArgumentCaptor.forClass(CreditCard.class);
        ArgumentCaptor<IdempotencyRecord> idempotencyRecordCaptor = ArgumentCaptor.forClass(IdempotencyRecord.class);

        given(customerRepository.findByEmailIgnoreCase(EMAIL)).willReturn(Optional.of(customer));
        given(idempotencyService.findAndValidateRecord(eq(IDEMPOTENCY_KEY), eq(EMAIL), any())).willReturn(Optional.empty());
        given(accountNumberGenerator.generateUnique()).willReturn("55".repeat(7));
        given(referenceGenerator.generate()).willReturn("5".repeat(32), "2".repeat(32));
        given(cardIssuer.issue(now)).willReturn(new IssuedCard("5678", now.plusYears(5)));
        given(creditAccountRepository.save(any(CreditAccount.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(creditCardRepository.save(any(CreditCard.class))).willAnswer(invocation -> invocation.getArgument(0));

        CreditCardIssueResponse response =
                creditCardService.issueCreditCard(EMAIL, new CreditCardIssueRequest(CurrencyCode.USD), IDEMPOTENCY_KEY);

        verify(creditCardRepository).save(creditCardCaptor.capture());
        verify(idempotencyService).save(idempotencyRecordCaptor.capture());

        CreditCard creditCard = creditCardCaptor.getValue();
        CreditAccount creditAccount = creditCard.getCreditAccount();

        assertThat(creditCard.getCardReference()).isEqualTo("2".repeat(32));
        assertThat(creditCard.getLastFourDigits()).isEqualTo("5678");
        assertThat(creditCard.getExpirationYearMonth()).isEqualTo(now.plusYears(5));
        assertThat(creditCard.getStatus()).isEqualTo(CardStatus.INACTIVE);
        assertThat(creditCard.getSpendingLimits())
                .isEqualTo(TestCardLimitProperties.standard().getDefaultLimits(CurrencyCode.USD));

        assertThat(creditAccount.getAccountNumber()).isEqualTo("55".repeat(7));
        assertThat(creditAccount.getCustomer()).isSameAs(customer);
        assertThat(creditAccount.getCreditLimit()).isEqualByComparingTo("5000.00");
        assertThat(creditAccount.getAnnualInterestRate()).isEqualByComparingTo("0.2499");
        assertThat(creditAccount.getNextStatementDate()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(creditAccount.getLedgerAccount().getLedgerReference()).isEqualTo("5".repeat(32));
        assertThat(creditAccount.getLedgerAccount().getType()).isEqualTo(LedgerAccountType.ASSET);
        assertThat(creditAccount.getCurrency()).isEqualTo(CurrencyCode.USD);

        IdempotencyRecord idempotencyRecord = idempotencyRecordCaptor.getValue();
        assertThat(idempotencyRecord.getIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
        assertThat(idempotencyRecord.getResultReference()).isEqualTo("2".repeat(32));

        assertThat(response.cardReference()).isEqualTo("2".repeat(32));
        assertThat(response.creditAccountNumber()).isEqualTo("55".repeat(7));
        assertThat(response.creditLimit()).isEqualByComparingTo("5000.00");
        assertThat(response.currency()).isEqualTo(CurrencyCode.USD);
        assertThat(response.lastFourDigits()).isEqualTo("5678");
        assertThat(response.expirationYearMonth()).isEqualTo(now.plusYears(5));
    }

    @Test
    void issueCreditCardReplaysStoredCardForSameIdempotencyKey() {
        CreditCard existingCard = createCreditCard();
        IdempotencyRecord existingRecord = IdempotencyRecord.createNew(
                IDEMPOTENCY_KEY,
                "a".repeat(64),
                customer,
                existingCard.getCardReference()
        );

        given(customerRepository.findByEmailIgnoreCase(EMAIL)).willReturn(Optional.of(customer));
        given(idempotencyService.findAndValidateRecord(eq(IDEMPOTENCY_KEY), eq(EMAIL), any())).willReturn(Optional.of(existingRecord));
        given(creditCardRepository.findByCardReference(existingCard.getCardReference())).willReturn(Optional.of(existingCard));

        CreditCardIssueResponse response =
                creditCardService.issueCreditCard(EMAIL, new CreditCardIssueRequest(CurrencyCode.TRY), IDEMPOTENCY_KEY);

        assertThat(response.cardReference()).isEqualTo(existingCard.getCardReference());
        assertThat(response.creditAccountNumber()).isEqualTo("55".repeat(7));

        verify(creditAccountRepository, never()).save(any());
        verify(creditCardRepository, never()).save(any());
        verify(idempotencyService, never()).save(any());
        verifyNoInteractions(accountNumberGenerator, cardIssuer, referenceGenerator);
    }

    @Test
    void issueCreditCardRejectsInvalidIdempotencyKey() {
        assertThatThrownBy(() -> creditCardService.issueCreditCard(EMAIL, new CreditCardIssueRequest(CurrencyCode.TRY), "not-a-key"))
                .isInstanceOf(InvalidIdempotencyKeyException.class);

        verifyNoInteractions(customerRepository, creditAccountRepository, creditCardRepository, idempotencyService);
    }

    @Test
    void issueCreditCardThrowsWhenCustomerDoesNotExist() {
        given(customerRepository.findByEmailIgnoreCase(EMAIL)).willReturn(Optional.empty());

        assertThatThrownBy(() -> creditCardService.issueCreditCard(EMAIL, new CreditCardIssueRequest(CurrencyCode.TRY), IDEMPOTENCY_KEY))
                .isInstanceOf(CustomerNotFoundException.class)
                .hasMessage("Customer not found");

        verifyNoInteractions(creditAccountRepository, creditCardRepository, idempotencyService);
    }

    @Test
    void getCreditCardReturnsBalanceAvailableCreditAndSpentToday() {
        CreditCard creditCard = createCreditCard();
        creditCard.getCreditAccount().getLedgerAccount().debit(new BigDecimal("400.00"));

        given(creditCardRepository.findByCardReferenceAndCreditAccount_Customer_EmailIgnoreCase(creditCard.getCardReference(), EMAIL))
                .willReturn(Optional.of(creditCard));
        given(cardDailySpendService.getAmountSpent(creditCard, LocalDate.now(clock))).willReturn(new BigDecimal("400.00"));

        CreditCardRetrieveResponse response = creditCardService.getCreditCard(creditCard.getCardReference(), EMAIL);

        assertThat(response.creditLimit()).isEqualByComparingTo("1000.00");
        assertThat(response.balance()).isEqualByComparingTo("400.00");
        assertThat(response.availableCredit()).isEqualByComparingTo("600.00");
        assertThat(response.annualInterestRate()).isEqualByComparingTo("0.2499");
        assertThat(response.nextStatementDate()).isEqualTo(LocalDate.of(2026, 2, 15));
        assertThat(response.currency()).isEqualTo(CurrencyCode.TRY);
        assertThat(response.creditAccountNumber()).isEqualTo("55".repeat(7));
        assertThat(response.spendingLimits().spentToday()).isEqualByComparingTo("400.00");
    }

    @Test
    void getCreditCardThrowsWhenCardIsNotFoundForCustomer() {
        given(creditCardRepository.findByCardReferenceAndCreditAccount_Customer_EmailIgnoreCase("2".repeat(32), EMAIL))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> creditCardService.getCreditCard("2".repeat(32), EMAIL))
                .isInstanceOf(CreditCardNotFoundException.class)
                .hasMessage("Credit card not found");
    }

    @Test
    void changeCardStatusActivatesCard() {
        CreditCard creditCard = createCreditCard();

        given(creditCardRepository.findByCardReferenceAndCreditAccount_Customer_EmailIgnoreCase(creditCard.getCardReference(), EMAIL))
                .willReturn(Optional.of(creditCard));
        given(creditCardRepository.save(creditCard)).willReturn(creditCard);
        given(cardDailySpendService.getAmountSpent(creditCard, LocalDate.now(clock))).willReturn(new BigDecimal("0.00"));

        CreditCardRetrieveResponse response =
                creditCardService.changeCardStatus(creditCard.getCardReference(), EMAIL, CardStatus.ACTIVE);

        assertThat(response.status()).isEqualTo(CardStatus.ACTIVE);
        assertThat(creditCard.getStatus()).isEqualTo(CardStatus.ACTIVE);
    }

    @Test
    void updateSpendingLimitsRejectsLimitsAboveTheMaximum() {
        CreditCard creditCard = createCreditCard();

        given(creditCardRepository.findByCardReferenceAndCreditAccount_Customer_EmailIgnoreCase(creditCard.getCardReference(), EMAIL))
                .willReturn(Optional.of(creditCard));

        CardSpendingLimitsUpdateRequest request = new CardSpendingLimitsUpdateRequest(new BigDecimal("250000.01"), new BigDecimal("300000.00"));

        assertThatThrownBy(() -> creditCardService.updateSpendingLimits(creditCard.getCardReference(), EMAIL, request))
                .isInstanceOf(InvalidCardLimitException.class);

        verify(creditCardRepository, never()).save(any());
    }

    @Test
    void updateSpendingLimitsChangesLimitsWithinTheMaximum() {
        CreditCard creditCard = createCreditCard();

        given(creditCardRepository.findByCardReferenceAndCreditAccount_Customer_EmailIgnoreCase(creditCard.getCardReference(), EMAIL))
                .willReturn(Optional.of(creditCard));
        given(creditCardRepository.save(creditCard)).willReturn(creditCard);
        given(cardDailySpendService.getAmountSpent(creditCard, LocalDate.now(clock))).willReturn(new BigDecimal("0.00"));

        CardSpendingLimitsUpdateRequest request = new CardSpendingLimitsUpdateRequest(new BigDecimal("100.00"), new BigDecimal("300.00"));

        CreditCardRetrieveResponse response =
                creditCardService.updateSpendingLimits(creditCard.getCardReference(), EMAIL, request);

        assertThat(response.spendingLimits().perTransactionLimit()).isEqualByComparingTo("100.00");
        assertThat(response.spendingLimits().dailyLimit()).isEqualByComparingTo("300.00");
        assertThat(response.spendingLimits().currency()).isEqualTo(CurrencyCode.TRY);
    }

    @Test
    void getStatementThrowsWhenStatementIsNotOnThisCardsAccount() {
        CreditCard creditCard = createCreditCard();
        String statementReference = "3".repeat(32);

        given(creditCardRepository.findByCardReferenceAndCreditAccount_Customer_EmailIgnoreCase(creditCard.getCardReference(), EMAIL))
                .willReturn(Optional.of(creditCard));
        given(creditStatementRepository.findByStatementReferenceAndCreditAccount(statementReference, creditCard.getCreditAccount()))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> creditCardService.getStatement(creditCard.getCardReference(), statementReference, EMAIL))
                .isInstanceOf(CreditStatementNotFoundException.class)
                .hasMessage("Credit statement not found");
    }

    @Test
    void getStatementLinesQueriesTheStatementWindowAndItsOwnInterest() {
        CreditCard creditCard = createCreditCard();
        String statementReference = "3".repeat(32);
        BankTransaction interestTransaction = BankTransaction.createNew(
                new BigDecimal("10.27"),
                CurrencyCode.TRY,
                TransactionType.CREDIT_CARD_INTEREST,
                "4".repeat(32),
                null
        );
        ReflectionTestUtils.setField(interestTransaction, "id", 42L);
        CreditStatement statement = CreditStatement.createNew(
                statementReference,
                creditCard.getCreditAccount(),
                LocalDate.of(2026, 8, 15),
                LocalDate.of(2026, 9, 15),
                LocalDate.of(2026, 10, 10),
                new BigDecimal("600.00"),
                new BigDecimal("510.27"),
                new BigDecimal("535.71"),
                new BigDecimal("10.27"),
                new BigDecimal("25.00"),
                interestTransaction
        );
        Page<LedgerEntry> entries = Page.empty();
        Page<TransactionHistoryItem> items = Page.empty();

        given(creditCardRepository.findByCardReferenceAndCreditAccount_Customer_EmailIgnoreCase(creditCard.getCardReference(), EMAIL))
                .willReturn(Optional.of(creditCard));
        given(creditStatementRepository.findByStatementReferenceAndCreditAccount(statementReference, creditCard.getCreditAccount()))
                .willReturn(Optional.of(statement));
        given(ledgerEntryRepository.findStatementLines(
                creditCard.getCreditAccount().getLedgerAccount(),
                Instant.parse("2026-08-15T00:00:00Z"),
                Instant.parse("2026-09-15T00:00:00Z"),
                42L,
                PageRequest.of(0, 20, Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")))
        )).willReturn(entries);
        given(transactionHistoryItemAssembler.toItems(eq(entries), any())).willReturn(items);

        Page<TransactionHistoryItem> result = creditCardService.getStatementLines(
                creditCard.getCardReference(),
                statementReference,
                EMAIL,
                PageRequest.of(0, 20, Sort.by(Sort.Order.asc("createdAt")))
        );

        assertThat(result).isSameAs(items);
    }

    private CreditCard createCreditCard() {
        CreditAccount creditAccount = CreditAccount.createNew(
                "55".repeat(7),
                "5".repeat(32),
                customer,
                CurrencyCode.TRY,
                new BigDecimal("1000.00"),
                new BigDecimal("0.2499"),
                LocalDate.of(2026, 1, 15)
        );

        YearMonth now = YearMonth.now(clock);

        return CreditCard.createNew(
                "2".repeat(32),
                "5678",
                now.plusYears(5),
                now,
                creditAccount,
                TestCardLimits.generous()
        );
    }
}
