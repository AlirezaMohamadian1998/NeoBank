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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
